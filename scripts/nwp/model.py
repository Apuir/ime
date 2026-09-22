#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""NWP 模型：字级因果骨干 + 词级输出头。训练与导出**共用这一个定义**。

为什么必须共用：ONNX 的 I/O 契约（输入名字、dtype、动态轴、KV cache 的表示）是
Android 侧写死的。如果导出脚本另写一份前向，两边迟早会漂移；所以契约就在
`onnx_io_contract()` 里，训练、评测、导出都从这里拿。

设计要点
--------
- **输入是字**：字级骨干（110M）不动它的 `wte`，输入 id 必须是它自己的 token id，
  所以只能是 `build_vocab.py` 产出的 `char2id.json`。**训练时绝不调 HF tokenizer**，
  否则训练/端侧口径分叉（端侧只有 char2id）。
- **输出是词**：`Linear(d_model → V_word)` 全参训练，**不与字表 tie**——
  3 万词 × 768 维的随机初始化头不在预训练流形上，必须全量学（见实施 prompt §5）。
- **KV cache 只回最后一个位置**：`logits_last` 是 `[B, V_word]`（≈120 KB），
  top-k 由 Kotlin 侧做，图里不出现 TopK/ArgMax。

只做**前向**的 ONNX 图（prefill 与增量共用一张）：`input_ids [B,S]`、
`attention_mask [B,P+S]`、`position_ids [B,S]`、`past_key_values.{i}.key/value [B,H,P,D]`
→ `logits [B,V]`、`present.{i}.key/value [B,H,P+S,D]`。
`position_ids` 必须是显式输入：有 cache 时若让骨干自己推位置，增量步会算错。
"""

from __future__ import annotations

import argparse
import json
import math
import sys
from pathlib import Path

import torch
import torch.nn as nn

sys.path.insert(0, str(Path(__file__).resolve().parent))

import nwp_common as _nwp_common  # noqa: E402
from nwp_common import (  # noqa: E402
    BACKBONES,
    add_work_arg,
    load_char2id,
    log,
    prepare_hf_env,
    resolve_backbone,
)

CHAR_UNK = 0
CHAR_PAD = 1

#: 冒烟骨干的规模。只求「能在 CPU 上几十秒跑完」，不追求任何效果。
#: 刻意给两份不同规模：probe.py 要能证明「两个底座分别训一遍再对比」这条流程是通的。
SMOKE_CONFIGS = {
    "smoke": dict(n_embd=64, n_layer=2, n_head=2, n_positions=256, n_inner=128),
    "smoke-b": dict(n_embd=96, n_layer=3, n_head=4, n_positions=256, n_inner=192),
}


def _round_up(value: int, multiple: int) -> int:
    return int(math.ceil(value / multiple) * multiple)


class CharEncoder:
    """只认 char2id.json 的编码器。训练、评测、导出都走它。"""

    def __init__(self, char2id: dict[str, int]) -> None:
        self.char2id = char2id
        self.unk = int(char2id.get("<unk>", CHAR_UNK))
        self.pad = int(char2id.get("<pad>", CHAR_PAD))
        self.vocab_size = max(int(v) for v in char2id.values()) + 1

    def encode(self, text: str, limit: int | None = None) -> list[int]:
        ids = [self.char2id.get(ch, self.unk) for ch in text]
        if limit is not None and len(ids) > limit:
            ids = ids[-limit:]
        return ids

    def oov_rate(self, text: str) -> float:
        if not text:
            return 0.0
        return sum(1 for ch in text if self.char2id.get(ch, self.unk) == self.unk) / len(text)


def past_to_cache(past):
    """把 HF 风格的嵌套 tuple 变成 v5 要求的 `DynamicCache`。

    transformers 5 起 `GPT2Model` 不再接受 legacy tuple（会拿不到 `get_query_offset`），
    而 ONNX 契约要的正是「一层一对张量」的扁平形式，所以转换放在这一层。
    """
    if past is None:
        return None
    from transformers.cache_utils import Cache, DynamicCache

    if isinstance(past, Cache):
        return past
    pairs = tuple(past)
    if not pairs:
        return None
    return DynamicCache(pairs)


def cache_to_legacy(cache):
    """`DynamicCache` → `((k, v), ...)`；已经是 tuple 就原样返回。"""
    if cache is None:
        return None
    if isinstance(cache, (tuple, list)):
        return tuple(tuple(pair) for pair in cache)
    layers = getattr(cache, "layers", None)
    if layers is None:
        raise TypeError(f"不认识的 cache 类型 {type(cache)}")
    return tuple((layer.keys, layer.values) for layer in layers)


class NWPModel(nn.Module):
    def __init__(self, backbone: nn.Module, word_vocab_size: int) -> None:
        super().__init__()
        self.backbone = backbone
        self.word_head = nn.Linear(int(backbone.config.n_embd), word_vocab_size)
        # 头是随机初始化的：正态初始化比默认的 U(-1/√d, 1/√d) 更稳，且与骨干量级一致
        nn.init.normal_(self.word_head.weight, mean=0.0, std=0.02)
        nn.init.zeros_(self.word_head.bias)
        # 训练时不需要 present，关掉能省掉每层的 cat 与显存；导出/推理必须开
        self.output_cache = True

    @property
    def num_layers(self) -> int:
        return int(self.backbone.config.n_layer)

    @property
    def num_heads(self) -> int:
        return int(self.backbone.config.n_head)

    @property
    def hidden_size(self) -> int:
        return int(self.backbone.config.n_embd)

    @property
    def head_dim(self) -> int:
        cfg = self.backbone.config
        return int(getattr(cfg, "head_dim", cfg.n_embd // cfg.n_head))

    def forward(self, input_ids, attention_mask, position_ids, past_key_values=None,
                label_positions=None):
        """`label_positions=None` 时**只回最后一个位置** —— ONNX 契约与端侧都依赖这个行为。

        给了 `label_positions`（`[B, T]` 的 ids 下标）时，改为在那些位置取隐状态 →
        `[B, T, D]` → 词头 → `[B, T, V]`。这是训练侧的多位置监督：因果 LM 一次前向本来
        就同时算出了每个位置的「下一个词」，只用最后一个位置等于把 99% 的算力丢掉。
        导出路径不传这个参数，所以图、I/O 名字/形状/dtype 全部不变。
        """
        out = self.backbone(
            input_ids=input_ids,
            attention_mask=attention_mask,
            position_ids=position_ids,
            past_key_values=past_to_cache(past_key_values),
            use_cache=self.output_cache,
            return_dict=True,
        )
        if label_positions is not None:
            hidden_states = out.last_hidden_state
            index = label_positions.unsqueeze(-1).expand(-1, -1, hidden_states.size(-1))
            hidden = hidden_states.gather(1, index)
            logits = self.word_head(hidden.to(self.word_head.weight.dtype))
            return logits, cache_to_legacy(out.past_key_values)
        hidden = out.last_hidden_state[:, -1, :]
        # 骨干的存储精度由底座决定，不归我们管：`IDEA-CCNL/Wenzhong-GPT2-110M` 的权重是
        # **fp16 落盘**的，加载出来骨干参数是 Half，而新建的 word_head 是 Float。
        # 在 autocast 之外（quick_metric / eval / ONNX 导出 / KV cache 路径）做
        # `Float × Half` 会直接抛 "mat1 and mat2 must have the same dtype"，
        # 而且只在第一次 eval 时才炸——200 步训练白跑。
        # 在头前面按**头的** dtype 对齐：头必须留在 fp32（它是要用 AdamW 学的部分，
        # 也是 fp16 主权重不稳的地方），autocast 打开时 Linear 自己会降精度。
        logits = self.word_head(hidden.to(self.word_head.weight.dtype))
        return logits, cache_to_legacy(out.past_key_values)

    @torch.no_grad()
    def predict_ids(self, input_ids, attention_mask=None, position_ids=None, topk: int = 5):
        """给 [B,S] 的字 id，返回 [B,topk] 的词 id。评测与基线对拍用。"""
        b, s = input_ids.shape
        if attention_mask is None:
            attention_mask = torch.ones_like(input_ids)
        if position_ids is None:
            position_ids = torch.arange(s, device=input_ids.device).unsqueeze(0).expand(b, s)
        logits, _ = self.forward(input_ids, attention_mask, position_ids)
        return torch.topk(logits, k=min(topk, logits.shape[-1]), dim=-1).indices

    @torch.no_grad()
    def logits_with_cache(self, input_ids, attention_mask, position_ids, past_key_values=None):
        return self.forward(input_ids, attention_mask, position_ids, past_key_values)


def build_backbone(name: str, char_vocab_size: int, smoke: bool = False):
    """按名字造骨干。`smoke=True` 时造随机小 GPT2（离线、CPU、秒级）。"""
    from transformers import GPT2Config, GPT2Model

    if smoke or name in SMOKE_CONFIGS:
        cfg = GPT2Config(
            vocab_size=_round_up(max(char_vocab_size, 64), 8),
            bos_token_id=0,
            eos_token_id=0,
            attn_implementation="eager",
            **SMOKE_CONFIGS.get(name, SMOKE_CONFIGS["smoke"]),
        )
        model = GPT2Model(cfg)
        model.init_weights()
        return model

    hf_id = resolve_backbone(name)
    # 强制 eager attention：SDPA 导出的 ONNX 会带 fused op，端侧 ORT 的可移植性差得多，
    # 而这条链路要的是「一次前向 + KV cache」的显式 matmul/softmax。
    model = GPT2Model.from_pretrained(hf_id, attn_implementation="eager")
    return model


def apply_lora(backbone, rank: int = 16, alpha: int = 32, dropout: float = 0.05, targets=("c_attn",)):
    """给骨干挂 LoRA；返回 (包好的骨干, 可训练参数数)。

    只训头不行、全参微调对 <300M 有害，LoRA 是这两者之间的那条路（实施 prompt §5）。
    """
    from peft import LoraConfig, get_peft_model

    cfg = LoraConfig(
        r=rank,
        lora_alpha=alpha,
        lora_dropout=dropout,
        bias="none",
        target_modules=list(targets),
        task_type=None,
    )
    try:
        wrapped = get_peft_model(backbone, cfg)
    except Exception:  # peft 对非 CausalLM 的 GPT2Model 可能拒绝派发，退回底层 LoraModel
        from peft import LoraModel

        wrapped = LoraModel(backbone, cfg, "default")
    trainable = sum(p.numel() for p in wrapped.parameters() if p.requires_grad)
    return wrapped, trainable


def merge_lora(model: NWPModel) -> NWPModel:
    """把 LoRA 权重并回骨干，导出前调用：ONNX 图里就不需要额外的 MatMul。"""
    backbone = model.backbone
    if hasattr(backbone, "merge_and_unload"):
        model.backbone = backbone.merge_and_unload()
    return model


def build_model(
    backbone_name: str,
    char_vocab_size: int,
    word_vocab_size: int,
    smoke: bool = False,
    lora: dict | None = None,
) -> NWPModel:
    backbone = build_backbone(backbone_name, char_vocab_size, smoke=smoke)
    lora_params = 0
    if lora and lora.get("rank", 0) > 0:
        backbone, lora_params = apply_lora(
            backbone,
            rank=int(lora["rank"]),
            alpha=int(lora.get("alpha", lora["rank"] * 2)),
            dropout=float(lora.get("dropout", 0.05)),
            targets=tuple(lora.get("targets", ["c_attn"])),
        )
    model = NWPModel(backbone, word_vocab_size)
    model.model_config = model_config_of(model, backbone_name, char_vocab_size, word_vocab_size, smoke, lora, lora_params)
    return model


def model_config_of(model, backbone_name, char_vocab_size, word_vocab_size, smoke, lora, lora_params) -> dict:
    cfg = model.backbone.config
    return {
        "backbone": backbone_name,
        "hf_id": resolve_backbone(backbone_name),
        "smoke": bool(smoke),
        "char_vocab_size": int(char_vocab_size),
        "word_vocab_size": int(word_vocab_size),
        "hidden_size": model.hidden_size,
        "num_layers": model.num_layers,
        "num_heads": model.num_heads,
        "head_dim": model.head_dim,
        "n_positions": int(getattr(cfg, "n_positions", 1024)),
        "lora": lora or {},
        "lora_trainable_params": lora_params,
    }


def input_embedding(model: "NWPModel") -> nn.Embedding:
    """拿骨干的字嵌入表（GPT2 的 `wte`）。

    按能力找而不是按属性名找：挂了 peft 之后属性路径会变（`base_model.model...`），
    写死名字在换底座/换 peft 版本时会静默找错表。
    """
    backbone = model.backbone
    getter = getattr(backbone, "get_input_embeddings", None)
    if callable(getter):
        emb = getter()
        if isinstance(emb, nn.Embedding):
            return emb
    for name in ("wte", "word_embeddings", "embeddings"):
        mod = getattr(backbone, name, None)
        if isinstance(mod, nn.Embedding):
            return mod
    for mod in backbone.modules():
        if isinstance(mod, nn.Embedding):
            return mod
    raise RuntimeError("在骨干里找不到字嵌入表，无法做词头热启动")


@torch.no_grad()
def warmstart_word_head(model: "NWPModel", char2id: dict[str, int], words: list[str]) -> dict:
    """用「词 = 它的字的字向量均值」初始化词头。

    为什么值得做：词头 23 M 参数（768×30000）是随机初始化的，而骨干本来就带字义。
    让词头从「字向量的均值」起步，等于省掉一批原本要用来学「词的向量大概长什么样」的步数。

    缺字策略：**跳过**不在 char2id 里、或映射到 `<unk>`/`<pad>` 的字，不参与平均
    （拿 `<unk>` 行去平均等于把噪声掺进词向量）；一个词一个字都不认识时，
    保留它原来的随机初始化行，并把数量报出来。
    """
    wte = input_embedding(model).weight
    head = model.word_head
    n_words, dim = head.weight.shape
    ids_flat: list[int] = []
    word_flat: list[int] = []
    skipped: list[int] = []
    for wid, word in enumerate(words):
        if wid == 0:
            continue                      # <unk> 行保持随机（它只该被 ignore_index 用到）
        known = [char2id[ch] for ch in word if char2id.get(ch, 0) > 1 and char2id.get(ch, 0) < wte.shape[0]]
        if not known:
            skipped.append(wid)
            continue
        ids_flat.extend(known)
        word_flat.extend([wid] * len(known))
    if not ids_flat:
        return {"warmed_words": 0, "skipped_no_known_char": len(skipped), "mean_chars_per_word": 0.0}
    idx = torch.tensor(ids_flat, device=wte.device, dtype=torch.long)
    wid_t = torch.tensor(word_flat, device=wte.device, dtype=torch.long)
    sums = torch.zeros(n_words, dim, device=wte.device, dtype=torch.float32)
    sums.index_add_(0, wid_t, wte.index_select(0, idx).float())
    counts = torch.zeros(n_words, device=wte.device, dtype=torch.float32)
    counts.index_add_(0, wid_t, torch.ones_like(wid_t, dtype=torch.float32))
    targets = wid_t.unique()
    mean = sums.index_select(0, targets) / counts.index_select(0, targets).clamp(min=1).unsqueeze(1)
    head.weight.data[targets] = mean.to(head.weight.dtype)
    head.bias.data.zero_()
    return {
        "warmed_words": int(targets.numel()),
        "skipped_no_known_char": len(skipped),
        "mean_chars_per_word": len(ids_flat) / max(1, int(targets.numel())),
        "embedding_dtype": str(wte.dtype),
        "head_dtype": str(head.weight.dtype),
    }


def maybe_warmstart_word_head(model: "NWPModel", char2id: dict[str, int], words: list[str],
                              enabled: bool = True, resume: bool = False) -> dict | None:
    """`--resume` 时必须跳过：checkpoint 里已经有训好的词头，再热启动一次就把训练成果抹了。"""
    if not enabled or resume:
        return None
    return warmstart_word_head(model, char2id, words)


# --------------------------------------------------------------------------- #
# checkpoint
# --------------------------------------------------------------------------- #


def save_checkpoint(path: Path, model: NWPModel, extra: dict | None = None) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    payload = {
        "model_config": model.model_config,
        "state_dict": {k: v.detach().cpu() for k, v in model.state_dict().items()},
    }
    if extra:
        payload.update(extra)
    torch.save(payload, path)


def load_checkpoint(path: Path, map_location="cpu", merge: bool = False) -> NWPModel:
    payload = torch.load(path, map_location=map_location, weights_only=False)
    cfg = payload["model_config"]
    model = build_model(
        backbone_name=cfg.get("backbone", "smoke"),
        char_vocab_size=cfg["char_vocab_size"],
        word_vocab_size=cfg["word_vocab_size"],
        smoke=bool(cfg.get("smoke")),
        lora=cfg.get("lora") or None,
    )
    missing, unexpected = model.load_state_dict(payload["state_dict"], strict=False)
    # LoRA 已合并过的 checkpoint 不含 lora_* 权重，这种「少」是预期的；
    # 但头缺权重就是真的坏了，必须报出来。
    fatal = [k for k in missing if "word_head" in k]
    if fatal:
        raise SystemExit(f"{path}: 缺 word_head 权重 {fatal}")
    if unexpected:
        log(f"⚠ checkpoint 有未使用权重 {unexpected[:4]}…")
    model.eval()
    if merge:
        merge_lora(model)
    # 推理与导出统一到 fp32：契约里 past/present/logits 都是 float32，且 CPU 上 fp16
    # 的 matmul 既慢又可能没有内核。fp16 只属于训练循环里的 autocast。
    model.float()
    return model


# --------------------------------------------------------------------------- #
# ONNX 契约（唯一来源）
# --------------------------------------------------------------------------- #
#
# `manifest.context_tokens` 的语义在这里定义一次，端侧按这个定义实现：
#
#     context_tokens = 模型能接受的**输入 id 个数上限**，也就是**字符数**上限。
#
# 端侧把它当**字符预算**：取已上屏文本最后的 `min(context_tokens, 210)` 个码点，
# 逐个查 char2id 后作为 `input_ids` 喂进来，并用它预分配 KV cache。
# **它绝对不是「上下文词数」**：S=128 个词实际对应 200–550 个字，
# 把 128（词数）写进这个字段会让端侧只喂 128 个字、KV cache 也只按 128 分配，
# 模型白训 40% 的上下文，而且不会报错——只会安静地变差。
#
# 因此：`build_samples.py --max-context-ids` 与 `export_onnx.py` 的 manifest 必须用**同一个值**，
# 且该值 ≥ 端侧 prompt 预算（210）。导出前会断言没有样本超过它，避免 manifest 再次低报。
DEFAULT_MAX_CONTEXT_IDS = _nwp_common.DEFAULT_MAX_CONTEXT_IDS
DEVICE_PROMPT_BUDGET_CHARS = _nwp_common.DEVICE_PROMPT_BUDGET_CHARS


class OnnxWrapper(nn.Module):
    """把扁平输入拼回嵌套 cache、再把嵌套 cache 摊平输出。

    ONNX 的输入输出只能是张量列表，而骨干要的是「一层一对」。这层包装是契约的
    落点，训练/评测都不经过它。
    """

    def __init__(self, model: NWPModel) -> None:
        super().__init__()
        self.model = model
        self.num_layers = model.num_layers

    def forward(self, input_ids, attention_mask, position_ids, *past_flat):
        past = None
        if past_flat:
            past = tuple((past_flat[2 * i], past_flat[2 * i + 1]) for i in range(self.num_layers))
        logits, present = self.model(input_ids, attention_mask, position_ids, past)
        return (logits,) + tuple(t for kv in present for t in kv)


def onnx_io_contract(model: NWPModel, batch: int = 1, seq: int = 8, past: int = 2, dtype=torch.float32):
    """返回 (wrapper, dummy_inputs, input_names, output_names, dynamic_axes)。

    `past` 默认给 2 而不是 0：导出时走一遍真正的 concat 路径，图里才会有 cache 分支；
    运行时（含 prefill）再由调用方给 0 长度的 past。
    """
    wrapper = OnnxWrapper(model).eval()
    b, s, p = batch, seq, past
    h, d = model.num_heads, model.head_dim
    dummy = [
        torch.ones(b, s, dtype=torch.long),
        torch.ones(b, p + s, dtype=torch.long),
        torch.arange(p, p + s, dtype=torch.long).unsqueeze(0).expand(b, s).contiguous(),
    ]
    input_names = ["input_ids", "attention_mask", "position_ids"]
    dynamic = {
        "input_ids": {0: "batch", 1: "sequence_length"},
        "attention_mask": {0: "batch", 1: "total_sequence_length"},
        "position_ids": {0: "batch", 1: "sequence_length"},
    }
    for i in range(model.num_layers):
        dummy.append(torch.zeros(b, h, p, d, dtype=dtype))
        dummy.append(torch.zeros(b, h, p, d, dtype=dtype))
        input_names += [f"past_key_values.{i}.key", f"past_key_values.{i}.value"]
        dynamic[f"past_key_values.{i}.key"] = {0: "batch", 2: "past_sequence_length"}
        dynamic[f"past_key_values.{i}.value"] = {0: "batch", 2: "past_sequence_length"}
    output_names = ["logits"]
    for i in range(model.num_layers):
        output_names += [f"present.{i}.key", f"present.{i}.value"]
    dyn_out = {"logits": {0: "batch"}}
    for i in range(model.num_layers):
        for kind in ("key", "value"):
            dyn_out[f"present.{i}.{kind}"] = {0: "batch", 2: "total_sequence_length"}
    return wrapper, dummy, input_names, output_names, {"inputs": dynamic, "outputs": dyn_out}


def export_onnx(model: NWPModel, path: Path, opset: int = 17, seq: int = 8, past: int = 2) -> Path:
    """按契约导出 fp32 ONNX。

    刻意用**旧版（TorchScript）导出器 + 扁平 `dynamic_axes`**：
    torch 2.14 的旧版导出器不接受嵌套的 `{"inputs":…,"outputs":…}` 形式
    （内部 `_jit_pass_onnx_set_dynamic_input_shape` 直接抛 TypeError），
    而 `dynamo=True` 需要额外装 onnxscript、且输出名会被重排成 `output_0…`，
    与契约要求的 `present.{i}.key` 对不上。扁平形式两个坑都没有。
    """
    # 契约声明 past/present 是 float32，而 `IDEA-CCNL/Wenzhong-GPT2-110M` 的骨干权重是
    # **fp16 落盘**的：不先统一到 fp32，dummy 的 float32 past 与 fp16 权重在 concat 处会抛
    # "expected scalar type Float but found Half"（导出阶段就崩）；就算能导出，
    # I/O 也会变成 float16，端侧按 float32 建 session 直接不匹配。
    model.float()
    wrapper, dummy, in_names, out_names, dyn = onnx_io_contract(model, seq=seq, past=past)
    flat = dict(dyn["inputs"])
    flat.update(dyn["outputs"])
    path.parent.mkdir(parents=True, exist_ok=True)
    torch.onnx.export(
        wrapper,
        tuple(dummy),
        str(path),
        input_names=in_names,
        output_names=out_names,
        dynamic_axes=flat,
        opset_version=opset,
        dynamo=False,
        do_constant_folding=True,
    )
    return path


# --------------------------------------------------------------------------- #
# CLI
# --------------------------------------------------------------------------- #


def cmd_info(args) -> int:
    if args.checkpoint:
        payload = torch.load(args.checkpoint, map_location="cpu", weights_only=False)
        print(json.dumps(payload["model_config"], ensure_ascii=False, indent=2))
        if "metrics" in payload:
            print(json.dumps(payload["metrics"], ensure_ascii=False, indent=2))
        return 0
    name = args.backbone
    cfg = BACKBONES.get(name)
    print(json.dumps(cfg or {"hf_id": resolve_backbone(name)}, ensure_ascii=False, indent=2))
    return 0


def cmd_verify_chars(args) -> int:
    """训练前的硬校验：char2id 的编码必须等于骨干 tokenizer 的编码。"""
    lay = args
    prepare_hf_env(Path(args.work))
    char2id = load_char2id(Path(args.char2id))
    enc = CharEncoder(char2id)
    text = Path(args.text).read_text(encoding="utf-8").replace("\n", "") if args.text else "今天的天气不错我们一起去公园散步"
    ids_ours = enc.encode(text)
    hf_id = resolve_backbone(args.backbone)
    if hf_id == "smoke":
        print(json.dumps({"chars": len(text), "ids": len(ids_ours), "note": "smoke 骨干无 tokenizer"}))
        return 0
    from transformers import AutoTokenizer

    tok = AutoTokenizer.from_pretrained(hf_id)
    ids_tok = tok.encode(text, add_special_tokens=False)
    equal = ids_ours == ids_tok
    report = {
        "backbone": hf_id,
        "chars": len(text),
        "ours_len": len(ids_ours),
        "tokenizer_len": len(ids_tok),
        "exact_agreement": equal,
        "mismatch": sum(1 for a, b in zip(ids_ours, ids_tok) if a != b),
    }
    print(json.dumps(report, ensure_ascii=False, indent=2))
    return 0 if equal else 1


def main() -> int:
    ap = argparse.ArgumentParser(description="NWP 模型定义、checkpoint 与 ONNX 契约")
    sub = ap.add_subparsers(dest="cmd", required=True)

    p_info = sub.add_parser("info", help="打印底座或 checkpoint 的模型配置")
    p_info.add_argument("--backbone", default="cluecorpus")
    p_info.add_argument("--checkpoint", type=Path, default=None)
    p_info.set_defaults(func=cmd_info)

    p_verify = sub.add_parser("verify-chars", help="断言 char2id 编码 == 骨干 tokenizer 编码")
    p_verify.add_argument("--char2id", type=Path, required=True)
    p_verify.add_argument("--backbone", default="cluecorpus")
    p_verify.add_argument("--text", type=Path, default=None, help="用于校验的真实文本文件")
    add_work_arg(p_verify)
    p_verify.set_defaults(func=cmd_verify_chars)

    args = ap.parse_args()
    return args.func(args)


if __name__ == "__main__":
    raise SystemExit(main())
