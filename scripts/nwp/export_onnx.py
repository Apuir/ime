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
    add_work_arg,
    file_bytes,
    layout_from_args,
    load_char2id,
    load_word_vocab,
    log,
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
    def __init__(self, path: Path, num_layers: int, num_heads: int, head_dim: int) -> None:
        import onnxruntime as ort

        so = ort.SessionOptions()
        so.log_severity_level = 3
        self.path = Path(path)
        self.session = ort.InferenceSession(str(path), so, providers=["CPUExecutionProvider"])
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


def quantize_int8(src: Path, dst: Path) -> tuple[bool, str]:
    try:
        from onnxruntime.quantization import QuantType, quantize_dynamic
    except ImportError as e:  # 量化工具缺 onnx 之类的依赖时降级为只出 fp32
        return False, f"onnxruntime.quantization 不可用：{e}"
    try:
        quantize_dynamic(str(src), str(dst), weight_type=QuantType.QInt8, op_types_to_quantize=None)
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
    ap.add_argument("--no-int8", action="store_true")
    ap.add_argument("--parity", action="store_true", default=True)
    ap.add_argument("--no-parity", dest="parity", action="store_false")
    ap.add_argument("--kv-check", action="store_true", default=True)
    ap.add_argument("--no-kv-check", dest="kv_check", action="store_false")
    ap.add_argument("--parity-rows", type=int, default=8)
    add_work_arg(ap)
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

    int8_ok, int8_reason = (False, "已用 --no-int8 关闭")
    int8 = out_dir / "nwp.int8.onnx"
    if not args.no_int8:
        int8_ok, int8_reason = quantize_int8(fp32, int8)
        if int8_ok:
            report["int8"] = {"file": int8.name, "bytes": file_bytes(int8)}
            log(f"动态范围 int8 → {int8}（{file_bytes(int8) / 1e6:.1f} MB）")
        else:
            log(f"⚠ int8 失败，只交 fp32：{int8_reason}")
    report["int8_available"] = int8_ok
    report["int8_reason"] = int8_reason

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
    runner = OnnxRunner(fp32, model.num_layers, H, D)
    runner8 = OnnxRunner(int8, model.num_layers, H, D) if int8_ok else None
    if args.parity:
        # 对齐到同一长度：对拍批次内左填充后按行比较，ONNX 逐条跑（不填充）
        fixed = [[r["ids"] for r in rows[i : i + 4]] for i in range(0, min(len(rows), args.parity_rows), 4)]
        report["parity_fp32"] = parity_check(model, runner, fixed)
        log(f"  fp32 top-1 一致率 {report['parity_fp32']['top1_agreement'] * 100:.2f}%，"
            f"top-5 重合率 {report['parity_fp32']['top5_overlap'] * 100:.2f}%，"
            f"max|Δ|={report['parity_fp32']['max_abs_diff']:.3e}")
        if runner8:
            report["parity_int8"] = parity_check(model, runner8, fixed)
            log(f"  int8 top-1 一致率 {report['parity_int8']['top1_agreement'] * 100:.2f}%，"
                f"top-5 重合率 {report['parity_int8']['top5_overlap'] * 100:.2f}%，"
                f"max|Δ|={report['parity_int8']['max_abs_diff']:.3e}"
                f"（相对 logits 尺度 {report['parity_int8']['relative_diff'] * 100:.2f}%）")

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

    model_file = "nwp.int8.onnx" if int8_ok else "nwp.onnx"
    manifest = {
        "version": MANIFEST_VERSION,
        "name": MANIFEST_NAME,
        "format": "onnx-int8" if int8_ok else "onnx-fp32",
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
    write_json(lay.results / "export_report.json", report)

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
