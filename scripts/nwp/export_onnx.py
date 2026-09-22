#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""导出 ONNX（fp32 + 动态范围 int8）、跑验收对拍、写 manifest.json。

三条不能妥协的事（实施 prompt §4 Phase 3 / §7）：
1. **必须带 KV cache 的 `dynamic_axes`**：档位 ③ 要「只 prefill 新增的几个字」，
   静态 shape 做不到。
2. **只做动态范围 int8，不做静态校准**：实测静态定点 int8 在同类小模型上是抽奖
   （有的掉 1.3 分，有的从 93.20 崩到 30.95）。
3. **top-k 不进图**：图里不出现 TopK/ArgMax，logits 直接返回（约 120 KB），Kotlin 侧取 top-k。
   脚本会扫一遍图来证明这一点。

导完立刻做两个对拍（Phase 3 的验收就是第一条）：
- **top-k 对拍**：ONNX 与 PyTorch 在同一输入上的 top-k 一致率。fp32 必须 100%。
- **KV cache 对拍**：「一次 prefill」与「拆成两步增量、把 present 回灌 past」的最后位置
  logits 必须一致。KV cache 悄悄算错是这条链路上最可能的 bug，所以单独测。

manifest.json 的字段是 Kotlin 下载器解析的，schema 固定，这里不多写一个键。
"""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

import numpy as np
import torch

sys.path.insert(0, str(Path(__file__).resolve().parent))

from eval import eval_checkpoint, eval_onnx, load_rows, pad_batch  # noqa: E402
from model import export_onnx, load_checkpoint  # noqa: E402
from nwp_common import (  # noqa: E402
    DEFAULT_MAX_CONTEXT_IDS,
    DEVICE_PROMPT_BUDGET_CHARS,
    add_onnx_provider_arg,
    add_work_arg,
    file_bytes,
    layout_from_args,
    load_char2id,
    load_word_vocab,
    log,
    onnx_providers,
    prepare_hf_env,
    read_json,
    sha256_file,
    write_json,
)

MANIFEST_VERSION = 1
MANIFEST_NAME = "ime-nwp-zh"


# --------------------------------------------------------------------------- #
# ONNX 运行器（支持 prefill / 增量）
# --------------------------------------------------------------------------- #


class OnnxRunner:
    def __init__(self, path: Path, num_layers: int, num_heads: int, head_dim: int,
                 provider: str | None = None) -> None:
        import onnxruntime as ort

        so = ort.SessionOptions()
        so.log_severity_level = 3
        self.path = Path(path)
        self.session = ort.InferenceSession(str(path), so, providers=onnx_providers(provider))
        self.num_layers = num_layers
        self.num_heads = num_heads
        self.head_dim = head_dim

    def step(self, ids: list[int], past: dict | None = None, batch: int = 1):
        """跑一次前向。`ids` 是**新增**的 token；`past` 是上一次的 present 字典。"""
        past_len = 0
        if past is not None:
            past_len = int(past["present.0.key"].shape[2])
        seq = len(ids)
        feeds = {
            "input_ids": np.tile(np.array(ids, dtype=np.int64), (batch, 1)),
            "attention_mask": np.ones((batch, past_len + seq), dtype=np.int64),
            "position_ids": np.tile(np.arange(past_len, past_len + seq, dtype=np.int64), (batch, 1)),
        }
        for i in range(self.num_layers):
            for kind in ("key", "value"):
                name = f"past_key_values.{i}.{kind}"
                if past is None:
                    feeds[name] = np.zeros((batch, self.num_heads, 0, self.head_dim), dtype=np.float32)
                else:
                    feeds[name] = past[f"present.{i}.{kind}"]
        out_names = ["logits"] + [f"present.{i}.{k}" for i in range(self.num_layers) for k in ("key", "value")]
        values = self.session.run(out_names, feeds)
        return values[0], dict(zip(out_names[1:], values[1:]))

    def prefill(self, ids: list[int], batch: int = 1):
        return self.step(ids, None, batch)


def torch_kv_check(model, ids: list[int], cut1: int, cut2: int) -> dict:
    """PyTorch 侧：全量一次 vs 一次 prefill + 两步增量。"""
    t = torch.tensor([ids], dtype=torch.long)
    s = len(ids)
    with torch.no_grad():
        full, _ = model(t, torch.ones(1, s, dtype=torch.long), torch.arange(s).unsqueeze(0))
        outs = []
        past = None
        start = 0
        for cut in (cut1, cut2, s):
            seg = t[:, start:cut]
            mask = torch.ones(1, cut, dtype=torch.long)
            pos = torch.arange(start, cut).unsqueeze(0)
            lg, past = model(seg, mask, pos, past)
            outs.append(lg)
            start = cut
    inc = outs[-1]
    diff = float((full - inc).abs().max())
    return {
        "max_abs_diff": diff,
        "top1_equal": bool((full.argmax(-1) == inc.argmax(-1)).all()),
        "top5_equal": bool(
            set(full.topk(5, -1).indices[0].tolist()) == set(inc.topk(5, -1).indices[0].tolist())
        ),
        "steps": [cut1, cut2 - cut1, s - cut2],
        "full_logits": full,
    }


def onnx_kv_check(runner: OnnxRunner, ids: list[int], cut1: int, cut2: int, ref_logits) -> dict:
    one, _ = runner.prefill(ids)
    _, past1 = runner.step(ids[:cut1])
    _, past2 = runner.step(ids[cut1:cut2], past1)
    inc, present = runner.step(ids[cut2:], past2)
    ref = np.asarray(ref_logits)
    diff = float(np.abs(inc - ref).max())
    return {
        "steps": [cut1, cut2 - cut1, len(ids) - cut2],
        "max_abs_diff_vs_torch_full": diff,
        "max_abs_diff_vs_onnx_prefill": float(np.abs(inc - one).max()),
        "top1_equal_vs_torch": bool(int(inc.argmax(-1)[0]) == int(ref.argmax(-1)[0])),
        "top5_equal_vs_torch": bool(
            set(np.argsort(-inc[0])[:5].tolist()) == set(np.argsort(-ref[0])[:5].tolist())
        ),
        # present 长度必须随增量累加，否则说明 cache 没真的回灌
        "present_len_after_step": int(present["present.0.key"].shape[2]),
        "context_len": len(ids),
    }


def parity_check(model, runner: OnnxRunner, batches: list[list[list[int]]]) -> dict:
    """同一输入下 ONNX 与 PyTorch 的 top-k 一致率。"""
    agree1 = agree5 = 0
    total = 0
    max_diff = 0.0
    logit_scale = 0.0
    for batch in batches:
        rows = [{"ids": ids, "label": 0, "ctx": ""} for ids in batch]
        ids_t, mask_t, pos_t = pad_batch(rows)
        with torch.no_grad():
            logits, _ = model(ids_t, mask_t, pos_t)
        logits = logits.numpy()
        # ONNX 逐条跑（prefill，无 cache），与 PyTorch 的 padding 结果对齐
        onnx_logits = []
        for ids in batch:
            out, _ = runner.prefill(ids)
            onnx_logits.append(out[0])
        onnx_logits = np.stack(onnx_logits)
        max_diff = max(max_diff, float(np.abs(onnx_logits - logits).max()))
        # 记录 logits 自身的尺度：int8 的 max|Δ| 只有在**相对**于这个尺度看才有意义。
        # 未训练的模型 logits 几乎是平的，任何微小扰动都会翻转 argmax，
        # 那时 int8 的 top-1 一致率低并不代表量化有问题。
        logit_scale = max(logit_scale, float(np.abs(logits).max()), float(np.abs(onnx_logits).max()))
        for i in range(len(batch)):
            a = set(np.argsort(-logits[i])[:5].tolist())
            b = set(np.argsort(-onnx_logits[i])[:5].tolist())
            agree5 += len(a & b)
            agree1 += int(int(logits[i].argmax()) == int(onnx_logits[i].argmax()))
            total += 1
    return {
        "rows": total,
        "top1_agreement": agree1 / max(1, total),
        "top5_overlap": agree5 / max(1, total * 5),
        "max_abs_diff": max_diff,
        "logit_absmax": logit_scale,
        # 相对误差 > 5% 时 argmax 才可能真的被量化改变；<1% 说明量化几乎无损
        "relative_diff": (max_diff / logit_scale) if logit_scale else 0.0,
    }


def convert_to_fp16(src: Path, dst: Path) -> tuple[bool, str]:
    """把 fp32 图整体降到 fp16，但**对外 I/O 保持 float32**（`keep_io_types` 的语义）。

    为什么必须有这条路：文档写的退化方案是 fp16（约 260 MB），
    但之前 `--no-int8` 实际退化到 **fp32 的 500.9 MB**，直接顶爆 300 MB 预算——
    也就是说「int8 不行就退 fp16」这句承诺在代码里根本不存在。
    端侧契约（past/present/logits 全 float32）不能动，所以在图的两端补 Cast：
    进来的 float32 转 fp16 参与计算，出去的 fp16 再转回 float32。

    没有用 onnxconverter_common（要额外装包、离线机器上不一定有）：
    这里只需要「初始化器降精度 + 两端补 Cast」两件事，用 onnx 原生 API 就能做完。
    """
    try:
        import numpy as np
        import onnx
        from onnx import TensorProto, helper, numpy_helper
    except ImportError as e:
        return False, f"缺依赖：{e}"
    try:
        model = onnx.load(str(src))
    except Exception as e:  # noqa: BLE001
        return False, f"读不了 {src}：{e}"

    graph = model.graph
    FLOAT, HALF = TensorProto.FLOAT, TensorProto.FLOAT16
    # fp16 的有限范围是 ±65504。GPT2 的 causal mask 用 finfo.min（-3.4e38）填被屏蔽的位置，
    # 直接 astype 会变 -inf：整行都被屏蔽时 softmax 会出 NaN。夹到有限最小值既保留语义
    # （加一个足够负的数 = 概率 0），又不会产生 inf/NaN，顺带消掉 numpy 的溢出告警。
    fp16_max = 65504.0

    def _to_fp16(array):
        return np.clip(array, -fp16_max, fp16_max).astype(np.float16)

    # 1) 初始化器（权重）降到 fp16
    converted = 0
    for init in graph.initializer:
        if init.data_type == FLOAT:
            init.CopyFrom(numpy_helper.from_array(_to_fp16(numpy_helper.to_array(init)), init.name))
            converted += 1

    # 1b) 常量算子也要降精度。torch 导出的 causal mask 里有 `torch.tensor(0.0)` 这种
    #     Constant（float32），不转就会在图中间出现 fp16 × fp32 的 Add/Where 类型冲突。
    for node in graph.node:
        if node.op_type not in ("Constant", "ConstantOfShape"):
            continue
        for attr in node.attribute:
            if attr.name == "value" and attr.t.data_type == FLOAT:
                attr.t.CopyFrom(numpy_helper.from_array(_to_fp16(numpy_helper.to_array(attr.t))))

    # 2) 入图 float32 → fp16；出图 fp16 → float32。名字按「原名 + __fp16」派生。
    #    注意 `present.{i}.key` 既是图的输出、**也被下一层当输入用**，
    #    所以重命名必须同时覆盖节点的输入和输出，只改输出会让消费者指向一个不存在的张量
    #    （onnx.checker 会报「不是任何前驱节点的输出」）。
    in_map = {i.name: i.name + "__fp16" for i in graph.input if i.type.tensor_type.elem_type == FLOAT}
    out_map = {o.name: o.name + "__fp16" for o in graph.output if o.type.tensor_type.elem_type == FLOAT}
    rename = {**in_map, **out_map}
    for node in graph.node:
        for k, name in enumerate(node.input):
            if name in rename:
                node.input[k] = rename[name]
        for k, name in enumerate(node.output):
            if name in rename:
                node.output[k] = rename[name]

    # 2b) 图内已有的 `Cast(to=float32)`：torch 在 fp32 下 trace，会把「遮罩转浮点」
    #     「隐状态转词头 dtype」这些写死成 float32。它们的消费者全是浮点算子
    #     （Add/MatMul/Gemm），不改成 fp16 就会出现 fp16×fp32 混合 → ORT 直接拒绝加载。
    #     而 `Cast(to=int64/bool)` 的输出喂的是 Range/Shape/And 这类**索引运算**，
    #     fp16 不是它们的合法类型，必须原样保留——所以按消费者算子白名单来决定改不改。
    float_ops = {"Add", "Sub", "Mul", "Div", "MatMul", "Gemm", "Where", "Equal", "Greater",
                 "Less", "GreaterOrEqual", "LessOrEqual", "Concat", "Unsqueeze", "Squeeze",
                 "Softmax", "Tanh", "Erf", "Sqrt", "Pow", "ReduceMean", "Neg", "Abs",
                 "Transpose", "Reshape", "Expand", "Slice", "Split", "Pad", "LayerNormalization",
                 "Identity", "Cast"}
    consumers: dict[str, list] = {}
    for node in graph.node:
        for name in node.input:
            consumers.setdefault(name, []).append(node)
    recast = 0
    for node in graph.node:
        if node.op_type != "Cast":
            continue
        targets = [a for a in node.attribute if a.name == "to"]
        if not targets or targets[0].i != FLOAT:
            continue
        users = consumers.get(node.output[0], [])
        if not users or all(u.op_type in float_ops for u in users):
            targets[0].i = HALF
            recast += 1

    head_nodes, tail_nodes = [], []
    for name, renamed in in_map.items():
        head_nodes.append(helper.make_node("Cast", [name], [renamed], to=HALF, name=f"Cast_in_{name}"))
    for name, renamed in out_map.items():
        tail_nodes.append(helper.make_node("Cast", [renamed], [name], to=FLOAT, name=f"Cast_out_{name}"))
    originals = list(graph.node)
    del graph.node[:]
    graph.node.extend(head_nodes + originals + tail_nodes)

    # value_info 里记的还是 float32，改名/降精度后不再成立，直接清掉让 ORT 自己推
    del graph.value_info[:]
    try:
        onnx.checker.check_model(model)
    except Exception as e:  # noqa: BLE001
        onnx.save(model, str(dst))
        return False, f"onnx.checker 未通过：{type(e).__name__}: {e}"
    onnx.save(model, str(dst))
    # 用 ORT 真正加载一次：类型不匹配、拓扑错序都会在这里被拒。
    # 这个文件是要给端侧用的，宁可在导出阶段失败也不能落一个加载不了的文件。
    try:
        import onnxruntime as ort

        options = ort.SessionOptions()
        options.log_severity_level = 3
        ort.InferenceSession(str(dst), options, providers=["CPUExecutionProvider"])
    except Exception as e:  # noqa: BLE001
        dst.unlink(missing_ok=True)
        return False, f"ORT 无法加载转换后的图：{type(e).__name__}: {e}"
    return True, (f"降精度初始化器 {converted} 个、图内 Cast 改写 {recast} 个、"
                  f"入图 Cast {len(head_nodes)}、出图 Cast {len(tail_nodes)}")


def graph_op_check(path: Path) -> dict:
    import onnx

    model = onnx.load(str(path))
    ops = [n.op_type for n in model.graph.node]
    banned = sorted({o for o in ops if o in ("TopK", "ArgMax", "ArgMin", "ArgPartition")})
    return {
        "nodes": len(ops),
        "distinct_ops": len(set(ops)),
        "banned_ops": banned,
        "has_topk": bool(banned),
        "graph_inputs": [i.name for i in model.graph.input],
        "graph_outputs": [o.name for o in model.graph.output],
    }


def quantize_int8(src: Path, dst: Path, per_channel: bool = False) -> tuple[bool, str]:
    """动态范围 int8。`per_channel=True` 时每个输出通道一组量化参数（默认每张量一组）。

    为什么要开 per-channel：整份权重共用一组 scale/zero-point 时，动态范围被最大的那些通道
    决定，小权重容易被压成 0。词头（768→30000）与骨干的权重分布差得远，实测 per-tensor
    的 int8 在 20 万条上掉 1.01 pp、相对 logits 失真 12.9%。粒度变细不改变算子选择，
    所以速度应当不变——但**这一点必须实测**，见 `bench_quant_speed.sh`。
    """
    try:
        from onnxruntime.quantization import QuantType, quantize_dynamic
    except ImportError as e:  # 量化工具缺 onnx 之类的依赖时降级为只出 fp32
        return False, f"onnxruntime.quantization 不可用：{e}"
    try:
        quantize_dynamic(str(src), str(dst), weight_type=QuantType.QInt8,
                         op_types_to_quantize=None, per_channel=per_channel)
    except Exception as e:  # noqa: BLE001 量化失败不该让整条导出挂掉，交 fp32 并报明原因
        return False, f"quantize_dynamic 失败：{type(e).__name__}: {e}"
    return True, ""


def main() -> int:
    ap = argparse.ArgumentParser(description="导出 ONNX（fp32 + int8）+ 对拍 + manifest")
    ap.add_argument("--checkpoint", type=Path, default=None, help="默认 <work>/ckpt/best.pt，退回 last.pt")
    ap.add_argument("--out-dir", type=Path, default=None, help="默认 <work>/onnx")
    ap.add_argument("--opset", type=int, default=17)
    ap.add_argument("--context-tokens", type=int, default=0,
                    help="manifest.context_tokens = 模型接受的**输入 id（字）上限**；"
                         "0=读 samples/report.json 的 max_context_ids（默认与 build_samples 一致）")
    ap.add_argument("--eval-samples", type=Path, default=None)
    ap.add_argument("--eval-limit", type=int, default=512)
    ap.add_argument("--skip-eval", action="store_true")
    ap.add_argument("--no-int8", action="store_true",
                    help="不量化 int8，改为交付 fp16（约 260 MB）；这是文档写的退化方案")
    ap.add_argument("--fp16", action="store_true",
                    help="与 --no-int8 等价：**只**交付 nwp.fp16.onnx（I/O 仍是 float32，端侧契约不变）")
    ap.add_argument("--also-fp16", action="store_true",
                    help="主交付 int8，同时产出 nwp.fp16.onnx 供对比；manifest 仍指向 int8。"
                         "单跑一次 --fp16 与一次 int8 导出写的是同一个 manifest.json，"
                         "后跑的那次会覆盖前一次的，拿两个文件必须用这个开关")
    ap.add_argument("--int8-per-tensor", action="store_true",
                    help="int8 权重退回到「每张量一组」量化参数（旧的默认）。实测这样会把小权重压没："
                         "20 万条 top-1 掉 1.01 pp、2 千条上与 per-channel 差 0.95 pp，"
                         "体积只省 0.45 MB、速度一样——除非要复现旧数字，否则别开")
    ap.add_argument("--fp32", action="store_true",
                    help="只出 fp32 的 nwp.onnx（真正的保底逃生口，体积约 500 MB）")
    ap.add_argument("--parity", action="store_true", default=True)
    ap.add_argument("--no-parity", dest="parity", action="store_false")
    ap.add_argument("--kv-check", action="store_true", default=True)
    ap.add_argument("--no-kv-check", dest="kv_check", action="store_false")
    ap.add_argument("--parity-rows", type=int, default=8)
    add_work_arg(ap)
    add_onnx_provider_arg(ap)
    args = ap.parse_args()

    lay = layout_from_args(args)
    # 必须在加载 checkpoint 之前落地：HF_ENDPOINT/HF_HOME 要在 hf_hub 被 import 前设好，
    # 否则会去连被封的 huggingface.co，或去读只读的 ~/.cache。
    prepare_hf_env(lay.root)

    # context_tokens 取「造样本时的输入 id 上限」，不是词数：端侧拿它当字符预算用。
    report_path = lay.samples / "report.json"
    samples_report = read_json(report_path) if report_path.exists() else {}
    resolved_context_tokens = args.context_tokens or int(
        samples_report.get("max_context_ids", DEFAULT_MAX_CONTEXT_IDS))
    if resolved_context_tokens < DEVICE_PROMPT_BUDGET_CHARS:
        log(f"⚠ context_tokens={resolved_context_tokens} < 端侧 prompt 预算 {DEVICE_PROMPT_BUDGET_CHARS}："
            "manifest 会把设计要求的上下文长度砍掉")
    # 断言：manifest 声称的上限不能**低于**造样本时的上限，否则又会低报模型输入长度
    # （这正是之前把「128 个词」写进 context_tokens 的那个 bug）。放在导出**之前**，
    # 免得白跑几十秒导出才报错。
    built_cap = int(samples_report.get("max_context_ids", 0))
    if built_cap and resolved_context_tokens < built_cap:
        raise SystemExit(
            f"samples 是按 --max-context-ids {built_cap} 造的，但 context_tokens="
            f"{resolved_context_tokens} 更小；manifest 会低报模型的输入长度。"
            f"要么用 --context-tokens {built_cap}，要么用 build_samples.py --max-context-ids "
            f"{resolved_context_tokens} 重造样本")
    out_dir = args.out_dir or lay.onnx
    out_dir.mkdir(parents=True, exist_ok=True)
    ckpt = args.checkpoint
    if ckpt is None:
        for cand in (lay.ckpt / "best.pt", lay.ckpt / "last.pt"):
            if cand.exists():
                ckpt = cand
                break
    if ckpt is None or not Path(ckpt).exists():
        raise SystemExit("找不到 checkpoint：先跑 train.py，或用 --checkpoint 指定")

    log(f"加载 {ckpt}")
    model = load_checkpoint(ckpt, merge=True)
    model.eval()
    model.output_cache = True
    report: dict = {
        "checkpoint": str(ckpt),
        "model_config": model.model_config,
        "context_tokens": resolved_context_tokens,
        "context_tokens_source": ("--context-tokens" if args.context_tokens
                                  else ("samples/report.json:max_context_ids" if samples_report
                                        else "DEFAULT_MAX_CONTEXT_IDS")),
        "max_ids_seen_before_truncation": samples_report.get("max_ids_seen"),
        "samples_max_context_ids": samples_report.get("max_context_ids"),
        "samples_truncated_by_max_context_ids": {
            k: v.get("truncated_by_max_context_ids", 0)
            for k, v in (samples_report.get("splits") or {}).items()
        },
    }

    fp32 = out_dir / "nwp.onnx"
    log(f"导出 fp32 → {fp32}")
    export_onnx(model, fp32, opset=args.opset)
    report["fp32"] = {"file": fp32.name, "bytes": file_bytes(fp32)}
    report["graph"] = graph_op_check(fp32)
    log(f"  图：{report['graph']['nodes']} 个节点，禁止算子 {report['graph']['banned_ops'] or '无'}")

    int8_ok, int8_reason = (False, "未启用量化")
    int8 = out_dir / "nwp.int8.onnx"
    fp16_ok, fp16_reason = (False, "未启用")
    fp16 = out_dir / "nwp.fp16.onnx"

    if args.fp16 and args.also_fp16:
        raise SystemExit("--fp16 与 --also-fp16 互斥：前者把 fp16 当作交付格式，后者是 int8 之外的补充")

    if args.fp32:
        log("按 --fp32 只交付 fp32（500 MB 级，仅作保底）")
    elif args.fp16 or args.no_int8:
        fp16_ok, fp16_reason = convert_to_fp16(fp32, fp16)
        if fp16_ok:
            report["fp16"] = {"file": fp16.name, "bytes": file_bytes(fp16), "detail": fp16_reason}
            log(f"fp16 → {fp16}（{file_bytes(fp16) / 1e6:.1f} MB；I/O 仍是 float32）{fp16_reason}")
        else:
            log(f"⚠ fp16 转换失败，退回 int8 尝试：{fp16_reason}")
    # `--fp16` 是「不要 int8，改交 fp16」的意思：fp16 转换成功时它就是交付格式，
    # 不能再回头做 int8 —— 两个文件写同一份 manifest.json，主交付只能有一个，
    # 顺手把 int8 也生成出来只会让 manifest 指向一个用户没要的格式。
    # `--also-fp16` 相反：主交付 int8，另外落一份 fp16 供对比量掉点。
    int8_wanted = args.also_fp16 or (not fp16_ok and not args.fp32)
    if int8_wanted:
        per_channel = not args.int8_per_tensor
        int8_ok, int8_reason = quantize_int8(fp32, int8, per_channel=per_channel)
        if int8_ok:
            report["int8"] = {"file": int8.name, "bytes": file_bytes(int8),
                              "per_channel": per_channel}
            log(f"动态范围 int8（{'per-channel' if per_channel else 'per-tensor'}）"
                f" → {int8}（{file_bytes(int8) / 1e6:.1f} MB）")
        else:
            log(f"⚠ int8 失败，只交 fp32：{int8_reason}")
    if args.also_fp16 and int8_ok and not fp16_ok:
        fp16_ok, fp16_reason = convert_to_fp16(fp32, fp16)
        if fp16_ok:
            report["fp16"] = {"file": fp16.name, "bytes": file_bytes(fp16), "detail": fp16_reason}
            log(f"fp16（对比用，不进 manifest）→ {fp16}（{file_bytes(fp16) / 1e6:.1f} MB）{fp16_reason}")
        else:
            log(f"⚠ fp16 转换失败，只剩 int8：{fp16_reason}")
    report["int8_available"] = int8_ok
    report["int8_reason"] = int8_reason
    report["fp16_available"] = fp16_ok
    report["fp16_reason"] = fp16_reason

    # 真实样本：优先用评测集，没有就自己造
    samples_path = args.eval_samples or (lay.samples / "test.jsonl")
    rows = load_rows(samples_path, args.eval_limit) if samples_path.exists() else []
    # 参与对拍/评测的样本逐条核对（不扫全量 train.jsonl：真实语料几十 GB，导出阶段不值得全表扫描）
    if rows:
        worst = max(len(r["ids"]) for r in rows)
        if worst > resolved_context_tokens:
            raise SystemExit(
                f"{samples_path} 里有 {worst} 个输入 id 的样本，超过 context_tokens={resolved_context_tokens}")
    if not rows:
        log(f"⚠ {samples_path} 不存在，对拍改用随机 id")
        rows = [{"ids": [2 + (i * 7 + j) % 40 for j in range(1, 33)], "label": 1, "ctx": ""} for i in range(16)]

    # ------------------------------------------------------------------ 对拍
    H, D = model.num_heads, model.head_dim
    prov = args.onnx_provider
    runner = OnnxRunner(fp32, model.num_layers, H, D, prov)
    runner8 = OnnxRunner(int8, model.num_layers, H, D, prov) if int8_ok else None
    runner16 = OnnxRunner(fp16, model.num_layers, H, D, prov) if fp16_ok else None
    if args.parity:
        # 对齐到同一长度：对拍批次内左填充后按行比较，ONNX 逐条跑（不填充）
        fixed = [[r["ids"] for r in rows[i : i + 4]] for i in range(0, min(len(rows), args.parity_rows), 4)]
        report["parity_fp32"] = parity_check(model, runner, fixed)
        log(f"  fp32 top-1 一致率 {report['parity_fp32']['top1_agreement'] * 100:.2f}%，"
            f"top-5 重合率 {report['parity_fp32']['top5_overlap'] * 100:.2f}%，"
            f"max|Δ|={report['parity_fp32']['max_abs_diff']:.3e}")
        # 注意别用 runner 当循环变量：那会把外层的 fp32 runner 覆盖掉，
        # 后面的 KV 对拍就会拿到 None。
        for tag, sub_runner in (("int8", runner8), ("fp16", runner16)):
            if sub_runner is None:
                continue
            report[f"parity_{tag}"] = parity_check(model, sub_runner, fixed)
            p = report[f"parity_{tag}"]
            log(f"  {tag} top-1 一致率 {p['top1_agreement'] * 100:.2f}%，"
                f"top-5 重合率 {p['top5_overlap'] * 100:.2f}%，max|Δ|={p['max_abs_diff']:.3e}"
                f"（相对 logits 尺度 {p['relative_diff'] * 100:.2f}%）")

    if args.kv_check:
        ids = rows[0]["ids"]
        cut1 = max(1, len(ids) // 3)
        cut2 = max(cut1 + 1, 2 * len(ids) // 3)
        torch_res = torch_kv_check(model, ids, cut1, cut2)
        ref = torch_res.pop("full_logits")
        report["kv_torch"] = torch_res
        log(f"  PyTorch KV：max|Δ|={torch_res['max_abs_diff']:.3e} "
            f"top1={'一致' if torch_res['top1_equal'] else '不一致'} "
            f"top5={'一致' if torch_res['top5_equal'] else '不一致'}")
        report["kv_onnx"] = onnx_kv_check(runner, ids, cut1, cut2, ref.numpy())
        log(f"  ONNX KV：max|Δ|={report['kv_onnx']['max_abs_diff_vs_torch_full']:.3e} "
            f"present_len={report['kv_onnx']['present_len_after_step']}/{report['kv_onnx']['context_len']} "
            f"top5={'一致' if report['kv_onnx']['top5_equal_vs_torch'] else '不一致'}")
        if runner16:
            # fp16 的 I/O 仍是 float32，所以同一套 KV 增量流程必须照样成立
            report["kv_onnx_fp16"] = onnx_kv_check(runner16, ids, cut1, cut2, ref.numpy())
            k = report["kv_onnx_fp16"]
            log(f"  ONNX fp16 KV：max|Δ|={k['max_abs_diff_vs_torch_full']:.3e} "
                f"present_len={k['present_len_after_step']}/{k['context_len']} "
                f"top5={'一致' if k['top5_equal_vs_torch'] else '不一致'}")

    # ------------------------------------------------------------------ manifest
    words = load_word_vocab(lay.word_vocab)
    char2id = load_char2id(lay.char2id)
    import shutil

    shutil.copyfile(lay.word_vocab, out_dir / "word_vocab.txt")
    shutil.copyfile(lay.char2id, out_dir / "char2id.json")

    metrics = {"top1": 0.0, "top5": 0.0, "mrr": 0.0}
    if not args.skip_eval and rows:
        ns = argparse.Namespace(checkpoint=ckpt, batch_size=16, topk=5, bpb=False, cpu=True)
        try:
            m = eval_checkpoint(ns, rows, {i: len(w.encode("utf-8")) for i, w in enumerate(words)})
            metrics = {k: float(m.get(k, 0.0)) for k in ("top1", "top5", "mrr")}
            report["eval_checkpoint"] = m
            if int8_ok:
                ns2 = argparse.Namespace(onnx=int8, batch_size=16, topk=5, bpb=False)
                m8 = eval_onnx(ns2, rows, {i: len(w.encode("utf-8")) for i, w in enumerate(words)})
                report["eval_int8"] = m8
                log(f"  int8 top-1={m8['top1'] * 100:.2f}%（fp32/PyTorch {m['top1'] * 100:.2f}%）")
        except Exception as e:  # noqa: BLE001 评测失败不该毁掉已导出的产物
            report["eval_error"] = f"{type(e).__name__}: {e}"
            log(f"⚠ 导出后评测失败：{report['eval_error']}")

    if int8_ok:
        model_file, model_format = "nwp.int8.onnx", "onnx-int8"
    elif fp16_ok:
        model_file, model_format = "nwp.fp16.onnx", "onnx-fp16"
    else:
        model_file, model_format = "nwp.onnx", "onnx-fp32"
    manifest = {
        "version": MANIFEST_VERSION,
        "name": MANIFEST_NAME,
        "format": model_format,
        "context_tokens": int(resolved_context_tokens),
        "vocab_size": len(words),
        "char_vocab_size": max(char2id.values()) + 1,
        "num_layers": model.num_layers,
        "num_heads": model.num_heads,
        "head_dim": model.head_dim,
        "model": {"file": model_file, "bytes": file_bytes(out_dir / model_file),
                  "sha256": sha256_file(out_dir / model_file)},
        "word_vocab": {"file": "word_vocab.txt", "bytes": file_bytes(out_dir / "word_vocab.txt"),
                       "sha256": sha256_file(out_dir / "word_vocab.txt")},
        "char_vocab": {"file": "char2id.json", "bytes": file_bytes(out_dir / "char2id.json"),
                       "sha256": sha256_file(out_dir / "char2id.json")},
        "eval": metrics,
    }
    write_json(out_dir / "manifest.json", manifest)
    report["manifest"] = manifest
    # 报告跟在 out-dir 后面：否则跑第二遍（比如 --fp16 到另一个目录）会把第一遍的报告覆盖掉，
    # 「int8 的报告里怎么没有 KV 结果」这种问题就是这么做出来的。
    report_name = "export_report.json" if out_dir == lay.onnx else f"export_report_{out_dir.name}.json"
    write_json(lay.results / report_name, report)

    print(json.dumps(manifest, ensure_ascii=False, indent=2))
    print(f"\n对拍：{json.dumps({k: v for k, v in report.items() if k.startswith('parity')}, ensure_ascii=False)}")
    if report.get("parity_int8", {}).get("relative_diff", 0) > 0.05:
        print("注意：int8 的相对误差偏大。若模型本身几乎没训练（logits 平坦），"
              "argmax 本来就在噪声里；请在**训练好的**模型上重测掉点。", file=sys.stderr)
    # 验收：fp32 的 top-1 与 top-5 必须完全一致，KV cache 必须与全量一致
    problems = []
    if args.parity and report.get("parity_fp32", {}).get("top1_agreement", 1.0) < 1.0:
        problems.append("fp32 top-1 一致率不是 100%")
    if args.kv_check:
        if not report.get("kv_torch", {}).get("top5_equal", True):
            problems.append("PyTorch KV cache 与全量前向 top-5 不一致")
        if not report.get("kv_onnx", {}).get("top5_equal_vs_torch", True):
            problems.append("ONNX KV cache 与全量前向 top-5 不一致")
        if report.get("kv_onnx", {}).get("present_len_after_step") != report.get("kv_onnx", {}).get("context_len"):
            problems.append("present 长度没有随增量累加")
    if report["graph"]["has_topk"]:
        problems.append(f"图里出现了 top-k 类算子 {report['graph']['banned_ops']}")
    if problems:
        print("\n验收未通过：" + "；".join(problems), file=sys.stderr)
        return 1
    print("\n验收通过：fp32 对拍 100%，KV cache 路径一致，图中无 TopK/ArgMax")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
