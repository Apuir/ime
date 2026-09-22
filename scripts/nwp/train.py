#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""NWP 微调：骨干挂 LoRA + 词级输出头全参，判别式学习率 + 逐步解冻。

为什么是这个组合（实施 prompt §5，两条证据交叉）：
- **只训头会欠拟合**：ULMFiT Table 7 里 `Last` 在 TREC-6 上 16.09，比从头训（13.36）还差。
- **全参微调对 <300M 有害**：The Fine-Tuning Trap 明确「sub-300M 全参微调会把准确率打到
  零样本基线以下」。
所以：骨干只挂 LoRA（rank 16–32），**新头全参**，学习率判别式（head > LoRA > 骨干=冻结），
并且先只训头一小段（ULMFiT 的 gradual unfreezing）再打开 LoRA。

`--smoke` 用随机小骨干 + 合成数据在 CPU 上几十秒跑完，验证的是**管线**不是质量。
真实训练命令见 README（fp16 + 8 GB 4060）。
"""

from __future__ import annotations

import argparse
import json
import math
import random
import sys
import time
from pathlib import Path

import torch
import torch.nn.functional as F

sys.path.insert(0, str(Path(__file__).resolve().parent))

from model import CHAR_PAD, build_model, maybe_warmstart_word_head, save_checkpoint  # noqa: E402
from nwp_common import (  # noqa: E402
    add_work_arg,
    read_json,
    layout_from_args,
    load_char2id,
    load_word_vocab,
    log,
    prepare_hf_env,
    write_json,
)

# --------------------------------------------------------------------------- #
# 数据
# --------------------------------------------------------------------------- #


class JsonlSamples(torch.utils.data.Dataset):
    """按行字节偏移随机访问 JSONL：语料级样本集可能几十 GB，不能全读进内存。"""

    def __init__(self, path: Path, limit: int = 0) -> None:
        self.path = Path(path)
        self.offsets: list[int] = []
        with self.path.open("rb") as fh:
            pos = 0
            for line in fh:
                if line.strip():
                    self.offsets.append(pos)
                    if limit and len(self.offsets) >= limit:
                        break
                pos += len(line)
        if not self.offsets:
            raise SystemExit(f"{self.path} 是空的：先跑 build_samples.py")

    def __len__(self) -> int:
        return len(self.offsets)

    def __getitem__(self, index: int) -> dict:
        with self.path.open("rb") as fh:
            fh.seek(self.offsets[index])
            return json.loads(fh.readline().decode("utf-8"))


def collate(batch: list[dict], pad_id: int = CHAR_PAD):
    """左填充。返回 (ids, mask, position_ids, label_positions, label_ids)。

    `label_positions [B,T]` 是监督位置的 ids 下标，`label_ids [B,T]` 是对应词 id；
    T 取批内最大值，不足的补 0（= `<unk>`），loss 里会被 ignore_index 丢掉。
    旧样本（只有单 `label` 字段）自动退化成「最后一个位置一个标签」，
    这样老 jsonl 仍然能用，单标签与多标签的语义也始终一致。
    """
    width = max(len(b["ids"]) for b in batch)
    rows = len(batch)
    labels_list = [b.get("labels") or [[len(b["ids"]) - 1, b["label"]]] for b in batch]
    max_t = max(len(x) for x in labels_list)
    ids = torch.full((rows, width), pad_id, dtype=torch.long)
    mask = torch.zeros((rows, width), dtype=torch.long)
    label_pos = torch.zeros((rows, max_t), dtype=torch.long)
    label_ids = torch.zeros((rows, max_t), dtype=torch.long)
    # 「最后一个位置」的那一个标签，单独给出来：验证/导出走的是只算末位 logits 的路径，
    # 而 [B,T] 矩阵的末**列**在标签条数不足时是补的 0，不能直接拿 `[:, -1]` 用。
    last_label = torch.zeros(rows, dtype=torch.long)
    for row, item in enumerate(batch):
        seq = item["ids"]
        ids[row, width - len(seq) :] = torch.tensor(seq, dtype=torch.long)
        mask[row, width - len(seq) :] = 1
        for j, (pos, wid) in enumerate(labels_list[row]):
            label_pos[row, j] = pos
            label_ids[row, j] = wid
        last_label[row] = labels_list[row][-1][1]
    # 位置从真实 token 起算：左填充不能把位置索引推偏，否则与端侧（无填充）不一致
    position_ids = (mask.cumsum(-1) - 1).clamp(min=0)
    return ids, mask, position_ids, label_pos, label_ids, last_label


class LengthBucketBatchSampler:
    """按长度分桶组批：在长度排序的缓冲窗口内切批，窗口之间再打乱。

    为什么值得做：混合长度的批次必须 padding 到批内最长，实测 micro-batch=8 时
    **22.8% 的骨干算力**花在 padding 上（batch=32 时 33.6%）。窗口内按长度排序后
    批内长度几乎一致，padding 掉到 <5% —— 等于白拿 1.3×，而且完全无损。

    保证：每个样本每轮**恰好出现一次**（窗口是原序列的连续切片，切片之间不重叠）；
    同一 seed + epoch 下批次序列完全可复现。
    """

    def __init__(self, lengths, batch_size: int, buffer_size: int = 4096, seed: int = 0,
                 shuffle: bool = True, shuffle_buffer: int = 256) -> None:
        self.lengths = list(lengths)
        self.batch_size = batch_size
        self.buffer_size = max(buffer_size, batch_size)
        self.seed = seed
        self.shuffle = shuffle
        self.shuffle_buffer = max(shuffle_buffer, 1)
        self.epoch = 0
        # 训练循环**应当每轮只启动一次迭代**。这个计数专门给回归测试用：
        # 一旦有人把 `for batch in loader` 又写回 while 里，它就会等于步数而不是 1。
        self.iter_calls = 0

    def set_epoch(self, epoch: int) -> None:
        self.epoch = epoch

    def __len__(self) -> int:
        n = len(self.lengths)
        total = 0
        for start in range(0, n, self.buffer_size):
            total += -(-min(self.buffer_size, n - start) // self.batch_size)
        return total

    def __iter__(self):
        self.iter_calls += 1
        return self._iter_batches()

    def _iter_batches(self):
        """**惰性**产出批次：内存只占「当前窗口 + 一个有界的批缓冲」。

        早先的实现在 yield 之前先把整轮所有批都排进一个 list —— 真实训练集
        2477 万条约 10–30 秒；而调用方每个梯度累积窗口都重建一次迭代器
        （`for batch in loader` 写在 while 里），于是每 8 个 micro-batch 就重排一次
        全量，实测把步时从 1.05 s 拖到 3.4 s，GPU 基本在空转。

        与 `__iter__` 分开是为了让 `padding_waste()` 这类内部用途不污染 [iter_calls]。
        """
        n = len(self.lengths)
        order = list(range(n))
        rng = random.Random(self.seed + self.epoch)
        if self.shuffle:
            rng.shuffle(order)

        # 比 lambda 快，且每个窗口只排序 buffer_size 个元素
        get_len = self.lengths.__getitem__
        pending: list[list[int]] = []
        for start in range(0, n, self.buffer_size):
            window = order[start:start + self.buffer_size]
            window.sort(key=get_len)
            for cut in range(0, len(window), self.batch_size):
                chunk = window[cut:cut + self.batch_size]
                if not chunk:
                    continue
                if self.shuffle and len(pending) >= self.shuffle_buffer:
                    # 从缓冲里随机换出一个再发：批间乱序照样成立，但内存与时间都有界。
                    # 用「与末尾互换再 pop」而不是 pop(idx) —— 后者是 O(k)，会退化。
                    idx = rng.randrange(len(pending))
                    pending[idx], pending[-1] = pending[-1], pending[idx]
                    yield pending.pop()
                pending.append(chunk)
        if self.shuffle:
            rng.shuffle(pending)
        yield from pending

    def padding_waste(self, max_batches: int = 20000) -> float:
        """按真实长度估 padding 浪费。只抽样前 [max_batches] 批 ——
        流一遍全量要十几秒，而这个数只用于日志和回归断言，抽样足够。"""
        real = padded = 0
        for seen, chunk in enumerate(self._iter_batches()):
            if seen >= max_batches:
                break
            width = max(self.lengths[i] for i in chunk)
            real += sum(self.lengths[i] for i in chunk)
            padded += width * len(chunk)
        return 1.0 - (real / padded) if padded else 0.0


# --------------------------------------------------------------------------- #
# 学习率：每组独立 warmup + 余弦/斜三角
# --------------------------------------------------------------------------- #


def load_lengths(dataset: "JsonlSamples", log_fn=None) -> list[int]:
    """取出每条样本的 `len(ids)`，带磁盘缓存。

    为什么不能直接 `len(dataset[i]["ids"])`：真实 train.jsonl 是 19.5 GB / 1200 万条，
    逐条 `json.loads` 要十几分钟，而分桶组批每次启动都要用长度。
    这里用正则数逗号（约快一个数量级）并把结果缓存成二进制，源文件没变就直接读缓存。
    """
    import re
    from array import array

    src = Path(dataset.path)
    meta_path = Path(str(src) + ".lengths.json")
    bin_path = Path(str(src) + ".lengths.bin")
    st = src.stat()
    meta = {"size": int(st.st_size), "mtime_ns": int(st.st_mtime_ns), "count": len(dataset)}
    if meta_path.exists() and bin_path.exists():
        try:
            if json.loads(meta_path.read_text(encoding="utf-8")) == meta:
                arr = array("l")
                arr.frombytes(bin_path.read_bytes())
                if len(arr) == len(dataset):
                    return list(arr)
        except (OSError, ValueError):
            pass    # 缓存坏了就重算，不值得为此报错

    pattern = re.compile(rb'\{"ids":\s*\[([^\]]*)\]')
    lengths: list[int] = []
    with src.open("rb") as fh:
        for line in fh:
            if not line.strip():
                continue
            m = pattern.match(line)
            if m:
                blob = m.group(1)
                lengths.append(0 if not blob.strip() else blob.count(b",") + 1)
            else:   # 格式意外就退回慢路径，正确性优先
                lengths.append(len(json.loads(line.decode("utf-8"))["ids"]))
    arr = array("l", lengths)
    bin_path.write_bytes(arr.tobytes())
    meta_path.write_text(json.dumps(meta), encoding="utf-8")
    if log_fn:
        log_fn(f"样本长度已缓存到 {bin_path.name}（{len(lengths)} 条）")
    return lengths


def _st_median(values: list[int]) -> float:
    ordered = sorted(values)
    mid = len(ordered) // 2
    return float(ordered[mid]) if len(ordered) % 2 else (ordered[mid - 1] + ordered[mid]) / 2.0


def lr_factor(local_step: int, warmup: int, total: int, schedule: str, min_ratio: float) -> float:
    if warmup > 0 and local_step < warmup:
        return (local_step + 1) / warmup
    progress = (local_step - warmup) / max(1, total - warmup)
    progress = min(1.0, max(0.0, progress))
    if schedule == "cosine":
        return min_ratio + (1 - min_ratio) * 0.5 * (1 + math.cos(math.pi * progress))
    # 斜三角（STLR）：ULMFiT 最优组合 Freez+discr+stlr 里的那个 stlr
    return max(min_ratio, 1.0 - progress)


class GroupSchedule:
    def __init__(self, name: str, base_lr: float, start_step: int, warmup: int) -> None:
        self.name = name
        self.base_lr = base_lr
        self.start_step = start_step
        self.warmup = warmup


def apply_lr(optimizer, schedules: dict[str, GroupSchedule], step: int, total: int, schedule: str, min_ratio: float) -> dict:
    out = {}
    for group in optimizer.param_groups:
        sch = schedules[group["name"]]
        local = step - sch.start_step
        if local < 0:
            continue  # 还没解冻的组不动
        factor = lr_factor(local, sch.warmup, total, schedule, min_ratio)
        group["lr"] = sch.base_lr * factor
        out[group["name"]] = group["lr"]
    return out


# --------------------------------------------------------------------------- #
# 评测（训练内验证只用 top-1/top-5/MRR，不碰 PPL）
# --------------------------------------------------------------------------- #


@torch.no_grad()
def quick_metric(model, dataset, limit: int, batch_size: int, device: str, amp_dtype=None) -> dict:
    """训练内验证。**必须和训练用同一套 autocast**：否则 fp16 骨干在验证时走另一条精度路径，
    量出来的分和训练时的行为对不上（而且 wenzhong 那种 fp16 存储的骨干以前会直接在这里崩）。"""
    model.eval()
    # CPU 的 autocast 只支持 bfloat16：device=cpu 时 fp16 autocast 会抛错，直接关掉
    amp_enabled = amp_dtype is not None and (device == "cuda" or amp_dtype != torch.float16)
    n = min(limit, len(dataset))
    hits1 = hits5 = 0
    rr = 0.0
    total = 0
    for start in range(0, n, batch_size):
        chunk = [dataset[i] for i in range(start, min(start + batch_size, n))]
        ids, mask, pos, _, _, last_label = collate(chunk)
        ids, mask, pos = ids.to(device), mask.to(device), pos.to(device)
        with torch.autocast(device_type="cuda" if device == "cuda" else "cpu",
                            dtype=amp_dtype, enabled=amp_enabled):
            logits, _ = model(ids, mask, pos)
        logits = logits.float()
        top5 = torch.topk(logits, k=min(5, logits.shape[-1]), dim=-1).indices.cpu()
        # 用 `last_label`（每个样本自己的下一个词），不能拿 [B,T] 的末列 ——
        # 标签条数不足的行末列是补 0。曾经这里整段拿 [B,T] 去比，label 成了 list，
        # `label in ranked` 恒为 False，于是 top1/top5/mrr 全是 0，
        # 而训练 loss 正常下降，看起来像「模型没学会」。
        for row, label in enumerate(last_label.tolist()):
            total += 1
            ranked = top5[row].tolist()
            if label in ranked:
                rank = ranked.index(label) + 1
                rr += 1.0 / rank
                if rank == 1:
                    hits1 += 1
                hits5 += 1
    model.train()
    if total == 0:
        return {"n": 0, "top1": 0.0, "top5": 0.0, "mrr": 0.0}
    return {"n": total, "top1": hits1 / total, "top5": hits5 / total, "mrr": rr / total}


# --------------------------------------------------------------------------- #


def build_optimizer(model, lr_head: float, lr_lora: float, weight_decay: float,
                    include_lora: bool = False) -> tuple:
    """头与 LoRA 分两组；骨干参数已由 peft 冻结，这里再断言一次。

    `include_lora=False` 时**先不放 LoRA 组**——逐步解冻要的是「一开始根本不动 LoRA」，
    靠 requires_grad 假装冻结是没用的（AdamW 照样更新它）。
    """
    head_params, lora_params, frozen = [], [], 0
    for name, p in model.named_parameters():
        if not p.requires_grad:
            frozen += 1
            continue
        if name.startswith("word_head."):
            head_params.append(p)
        elif "lora_" in name:
            lora_params.append(p)
        else:
            raise SystemExit(f"意外的可训练参数 {name}：骨干必须冻结，只有 head 与 lora_* 可训")
    groups = [{"params": head_params, "name": "head", "lr": lr_head, "weight_decay": weight_decay}]
    if lora_params and include_lora:
        groups.append({"params": lora_params, "name": "lora", "lr": lr_lora, "weight_decay": weight_decay})
    return torch.optim.AdamW(groups, betas=(0.9, 0.98), eps=1e-8), len(head_params), len(lora_params), frozen


def main() -> int:
    ap = argparse.ArgumentParser(
        description="微调 NWP（LoRA 骨干 + 全参词头，判别式 LR + 逐步解冻）",
        formatter_class=argparse.RawDescriptionHelpFormatter,
    )
    ap.add_argument("--backbone", default=None, help="cluecorpus（默认）| wenzhong | smoke | smoke-b | hf-id")
    ap.add_argument("--smoke", action="store_true", help="随机小骨干 + CPU + 几十步，验证管线")
    ap.add_argument("--samples", type=Path, default=None, help="样本目录（默认 <work>/samples）")
    ap.add_argument("--out", type=Path, default=None, help="checkpoint 目录（默认 <work>/ckpt）")
    ap.add_argument("--lora-rank", type=int, default=16, help="LoRA rank；0=不挂")
    ap.add_argument("--lora-alpha", type=int, default=0, help="默认 2×rank")
    ap.add_argument("--lora-dropout", type=float, default=0.05)
    ap.add_argument("--lora-target", default="c_attn", help="逗号分隔的目标模块名")
    ap.add_argument("--max-steps", type=int, default=20000)
    ap.add_argument("--batch-size", type=int, default=8, help="单卡 micro-batch")
    ap.add_argument("--grad-accum", type=int, default=8, help="累计到 batch 64（S=128 时 8 GB 够）")
    ap.add_argument("--lr-head", type=float, default=1e-3)
    ap.add_argument("--lr-lora", type=float, default=1e-4)
    ap.add_argument("--head-warmup-steps", type=int, default=500,
                    help="只训头多少步后再打开 LoRA（gradual unfreezing）")
    ap.add_argument("--lora-warmup-steps", type=int, default=100, help="解冻后 LoRA 自己的 warmup")
    ap.add_argument("--warmup-steps", type=int, default=200)
    ap.add_argument("--schedule", choices=["cosine", "stlr"], default="cosine")
    ap.add_argument("--min-lr-ratio", type=float, default=0.05)
    ap.add_argument("--weight-decay", type=float, default=0.01)
    ap.add_argument("--grad-clip", type=float, default=1.0)
    ap.add_argument("--dtype", choices=["auto", "fp16", "bf16", "fp32"], default="auto",
                    help="4060 Laptop 上 fp16 比 bf16 快 43%%（实测），默认 auto 会在 cuda 上选 fp16")
    ap.add_argument("--device", default="auto")
    ap.add_argument("--seed", type=int, default=20260921)
    ap.add_argument("--log-every", type=int, default=10)
    ap.add_argument("--eval-every", type=int, default=200)
    ap.add_argument("--eval-limit", type=int, default=512)
    ap.add_argument("--save-every", type=int, default=500)
    ap.add_argument("--train-limit", type=int, default=0, help="只用前 N 条训练样本（调试用）")
    ap.add_argument("--bucket", action=argparse.BooleanOptionalAction, default=True,
                    help="按长度分桶组批（默认开；--no-bucket 走原来的随机批，用于对照）")
    ap.add_argument("--bucket-buffer", type=int, default=4096,
                    help="分桶窗口大小：窗口越大批内长度越齐、浪费越低，数据顺序也越规整（默认 4096）")
    ap.add_argument("--head-warmstart", action=argparse.BooleanOptionalAction, default=True,
                    help="用字向量均值热启动词头（默认开；--resume 时自动跳过）")
    ap.add_argument("--resume", type=Path, default=None)
    ap.add_argument("--no-lora", action="store_true", help="只训头（仅用于对照，不是推荐做法）")
    add_work_arg(ap)

    args = ap.parse_args()
    smoke = args.smoke
    if args.backbone is None:
        args.backbone = "cluecorpus"
    if smoke and args.backbone == "cluecorpus":
        args.backbone = "smoke"
        args.device = "cpu"
        args.dtype = "fp32"
        args.max_steps = min(args.max_steps, 40)
        args.batch_size = min(args.batch_size, 8)
        args.grad_accum = 1
        args.head_warmup_steps = min(args.head_warmup_steps, 10)
        args.warmup_steps = min(args.warmup_steps, 5)
        args.lora_warmup_steps = min(args.lora_warmup_steps, 5)
        args.log_every = min(args.log_every, 5)
        args.eval_every = min(args.eval_every, 20)
        args.eval_limit = min(args.eval_limit, 64)
        args.lora_rank = args.lora_rank if not args.no_lora else 0

    lay = layout_from_args(args)
    prepare_hf_env(lay.root)
    samples_dir = args.samples or lay.samples
    out_dir = args.out or lay.ckpt
    out_dir.mkdir(parents=True, exist_ok=True)

    random.seed(args.seed)
    torch.manual_seed(args.seed)

    words = load_word_vocab(lay.word_vocab)
    char2id = load_char2id(lay.char2id)
    char_vocab_size = max(char2id.values()) + 1
    lora_cfg = None if (args.no_lora or args.lora_rank <= 0) else {
        "rank": args.lora_rank,
        "alpha": args.lora_alpha or args.lora_rank * 2,
        "dropout": args.lora_dropout,
        "targets": [t.strip() for t in args.lora_target.split(",") if t.strip()],
    }

    model = build_model(args.backbone, char_vocab_size, len(words), smoke=smoke, lora=lora_cfg)
    model.output_cache = False  # 训练不需要 KV cache
    device = args.device
    if device == "auto":
        device = "cuda" if torch.cuda.is_available() else "cpu"
    model.to(device)
    dtype = args.dtype
    if dtype == "auto":
        dtype = "fp16" if device == "cuda" else "fp32"
    if dtype == "fp16" and device != "cuda":
        log("CPU 上不支持 fp16 训练，改用 fp32")
        dtype = "fp32"
    amp_dtype = {"fp16": torch.float16, "bf16": torch.bfloat16}.get(dtype)
    use_scaler = dtype == "fp16"

    log(f"backbone={args.backbone} device={device} dtype={dtype} 参数={sum(p.numel() for p in model.parameters())/1e6:.1f}M")

    warm_stats = maybe_warmstart_word_head(
        model, char2id, words, enabled=args.head_warmstart, resume=args.resume is not None)
    if warm_stats:
        log(f"词头热启动：{warm_stats['warmed_words']} 个词用字向量均值初始化"
            f"（跳过 {warm_stats['skipped_no_known_char']} 个无已知字的词，"
            f"平均 {warm_stats['mean_chars_per_word']:.2f} 字/词）")
    elif args.resume is not None:
        log("词头热启动：跳过（--resume，checkpoint 里已是训练过的权重）")

    unfreeze_at = args.head_warmup_steps if lora_cfg else 0
    optimizer, n_head_t, n_lora_t, n_frozen = build_optimizer(
        model, args.lr_head, args.lr_lora, args.weight_decay, include_lora=unfreeze_at <= 0)
    lora_params_all = [p for n, p in model.named_parameters() if "lora_" in n]
    schedules = {"head": GroupSchedule("head", args.lr_head, 0, args.warmup_steps)}
    if lora_cfg:
        schedules["lora"] = GroupSchedule("lora", args.lr_lora, unfreeze_at, args.lora_warmup_steps)
    scaler = torch.amp.GradScaler("cuda", enabled=use_scaler)

    train_set = JsonlSamples(samples_dir / "train.jsonl", limit=args.train_limit)
    valid_set = JsonlSamples(samples_dir / "valid.jsonl")
    log(f"训练样本 {len(train_set)} 条，验证 {len(valid_set)} 条；头参数 {n_head_t} 组，LoRA {n_lora_t} 组，冻结 {n_frozen} 组")

    # 分桶组批：批内长度接近，padding 浪费从 22.8% 降到 <5%（无损的 1.3×）
    lengths = load_lengths(train_set, log)
    if args.bucket:
        batch_sampler = LengthBucketBatchSampler(
            lengths, args.batch_size, buffer_size=args.bucket_buffer, seed=args.seed)
        loader = torch.utils.data.DataLoader(
            train_set, batch_sampler=batch_sampler, collate_fn=collate, num_workers=0)
        waste = batch_sampler.padding_waste()
    else:
        batch_sampler = None
        loader = torch.utils.data.DataLoader(
            train_set, batch_size=args.batch_size, shuffle=True, collate_fn=collate,
            num_workers=0, drop_last=True,
            generator=torch.Generator().manual_seed(args.seed))
        widths = [lengths[i:i + args.batch_size] for i in range(0, len(lengths), args.batch_size)]
        real = sum(sum(w) for w in widths)
        padded = sum(max(w) * len(w) for w in widths if w)
        waste = 1.0 - real / padded if padded else 0.0
    log(f"padding 浪费：{waste * 100:.1f}%（bucket={args.bucket}，"
        f"样本长度 min/median/max = {min(lengths)}/{int(_st_median(lengths))}/{max(lengths)}）")

    start_step = 0
    best = {"top1": -1.0}
    history: list[dict] = []
    if args.resume:
        payload = torch.load(args.resume, map_location="cpu", weights_only=False)
        model.load_state_dict(payload["state_dict"], strict=False)
        if "optimizer" in payload:
            optimizer.load_state_dict(payload["optimizer"])
        start_step = int(payload.get("step", 0))
        best = payload.get("best", best)
        history = payload.get("history", [])
        log(f"从 {args.resume} 恢复：step={start_step}")

    model.train()
    t0 = time.time()
    step = start_step
    micro = 0
    running = 0.0
    running_n = 0
    running_samples = 0
    # 窗口内的真实标签数（loss 的分母）在每个窗口开头重算，见下面的 window_target。
    lora_on = not lora_cfg or unfreeze_at <= 0
    def epochs():
        epoch = 0
        while True:
            if batch_sampler is not None:
                batch_sampler.set_epoch(epoch)
            yield epoch
            epoch += 1

    epoch_iter = epochs()
    next(epoch_iter)
    # **迭代器只建一次**。原先写的是 `for batch in loader` 且在 while 里，
    # 于是每个梯度累积窗口都要重建迭代器、重新 shuffle 一遍全量样本；
    # 配合当时「整轮物化」的采样器，每 8 个 micro-batch 就重排 2477 万条 ——
    # 实测把步时从 1.05 s 拖到 3.4 s，GPU 大部分时间在空转。
    loader_iter = iter(loader)
    pending_s = 0.0

    while step < args.max_steps:
        # 取满一个梯度累积窗口；一轮走完就换轮（重新 shuffle）后接着取。
        t_fill = time.time()
        window: list = []
        while len(window) < args.grad_accum:
            try:
                window.append(next(loader_iter))
            except StopIteration:
                next(epoch_iter)
                loader_iter = iter(loader)
                if not window:
                    break
        pending_s += time.time() - t_fill
        if not window:
            break

        if lora_cfg and not lora_on and step >= unfreeze_at:
            # 逐步解冻：LoRA 参数**加进优化器**才算真的开始训。
            # 不能靠 requires_grad 假装冻结——AdamW 不检查它，照样会更新。
            optimizer.add_param_group({
                "params": lora_params_all,
                "name": "lora", "lr": args.lr_lora, "weight_decay": args.weight_decay,
            })
            lora_on = True
            log(f"step {step}: 打开 LoRA（rank={lora_cfg['rank']}，lr={args.lr_lora}）")

        lrs = apply_lr(optimizer, schedules, step, args.max_steps, args.schedule, args.min_lr_ratio)
        # 整个窗口共用一个分母：各样本标签数不同，按 micro-batch 各自平均会让
        # 等效学习率随数据抖动，梯度累积也就失去意义。
        window_target = max(1, int(sum((b[4] != 0).sum() for b in window)))

        for batch in window:
            ids, mask, pos, label_pos, label_ids, _ = batch
            ids, mask, pos = ids.to(device), mask.to(device), pos.to(device)
            label_pos, label_ids = label_pos.to(device), label_ids.to(device)

            with torch.autocast(device_type="cuda" if device == "cuda" else "cpu", dtype=amp_dtype,
                                enabled=amp_dtype is not None):
                logits, _ = model(ids, mask, pos, label_positions=label_pos)
                # sum / 窗口标签数：每个标签等权，梯度累积不改变等效学习率
                loss = F.cross_entropy(
                    logits.reshape(-1, logits.shape[-1]), label_ids.reshape(-1),
                    ignore_index=0, reduction="sum") / window_target
            if use_scaler:
                scaler.scale(loss).backward()
            else:
                loss.backward()

            # running 累加的是**标签损失之和**（loss 已经除过 window_target），
            # 所以 running/labels 就是「每个标签的平均损失」，与窗口大小无关。
            running += float(loss.detach()) * window_target
            running_n += int((label_ids != 0).sum())
            running_samples += int(ids.shape[0])
            micro += 1

        # **整个窗口跑完才更新一次参数。** 原先这一步写在 for 里面，梯度累积形同虚设：
        # 等效 batch 从 64 掉回 8；而 loss 已经除过窗口标签数、却只有单个 micro-batch
        # 贡献梯度，等于梯度又被缩小约 8 倍；`step` 还按 micro-batch 计数，
        # 于是 --max-steps 只覆盖到预期 1/8 的数据。
        if use_scaler:
            scaler.unscale_(optimizer)
        if args.grad_clip > 0:
            torch.nn.utils.clip_grad_norm_(
                [p for p in model.parameters() if p.requires_grad], args.grad_clip)
        if use_scaler:
            scaler.step(optimizer)
            scaler.update()
        else:
            optimizer.step()
        optimizer.zero_grad(set_to_none=True)
        step += 1

        if step % args.log_every == 0:
            speed = (time.time() - t0) / max(1, step - start_step)
            # data= 是等数据的时间。它一旦和步时同量级，瓶颈就在采样器而不是 GPU ——
            # 之前那个「每窗口重排全量样本」的 bug 正是这样被看出来的。
            log(f"step {step}/{args.max_steps} loss/label={running / max(1, running_n):.4f} "
                f"labels/sample={running_n / max(1, running_samples):.2f} "
                f"lr={','.join(f'{k}:{v:.2e}' for k, v in lrs.items())} "
                f"{speed:.2f}s/step data={pending_s / max(1, args.log_every):.2f}s")
            running, running_n, running_samples = 0.0, 0, 0
            pending_s = 0.0

        if args.eval_every and step % args.eval_every == 0:
            metrics = quick_metric(model, valid_set, args.eval_limit, args.batch_size, device, amp_dtype)
            metrics["step"] = step
            history.append(metrics)
            log(f"  valid@{step}: top1={metrics['top1']:.4f} top5={metrics['top5']:.4f} mrr={metrics['mrr']:.4f}")
            if metrics["top1"] > best.get("top1", -1):
                best = metrics
                save_checkpoint(out_dir / "best.pt", model, {"step": step, "metrics": metrics, "args": vars(args) | {"samples": str(samples_dir)}})
            model.train()

        if args.save_every and step % args.save_every == 0:
            save_checkpoint(out_dir / "last.pt", model, {
                "step": step, "optimizer": optimizer.state_dict(), "best": best,
                "history": history, "args": vars(args) | {"samples": str(samples_dir)},
            })

    # 训练侧监督密度（样本文件里记录的 labels 总量）
    srep = read_json(lay.samples / "report.json") if (lay.samples / "report.json").exists() else {}
    report_labels_per_token = srep.get("labels", {}).get("labels_per_token", 0.0)
    report_labels_per_sample = srep.get("labels", {}).get("labels_per_sample", 0.0)
    final = quick_metric(model, valid_set, args.eval_limit, args.batch_size, device, amp_dtype)
    if final["top1"] >= best.get("top1", -1):
        best = final | {"step": step}
        save_checkpoint(out_dir / "best.pt", model, {"step": step, "metrics": best,
                                                     "args": vars(args) | {"samples": str(samples_dir)}})
    save_checkpoint(out_dir / "last.pt", model, {
        "step": step, "optimizer": optimizer.state_dict(), "best": best, "history": history,
        "args": vars(args) | {"samples": str(samples_dir)},
    })
    elapsed = time.time() - t0
    report = {
        "backbone": args.backbone,
        "padding_waste": waste,
        "labels_per_token": report_labels_per_token,
        "labels_per_sample": report_labels_per_sample,
        "head_warmstart": warm_stats,
        "smoke": smoke,
        "device": device,
        "dtype": dtype,
        "steps": step,
        # 这两个数是不变量，回归测试会断言它们：
        # micro_batches 应当等于 steps×grad_accum（否则梯度累积没生效），
        # epoch_iterations 应当是 1（否则迭代器被每个窗口重建，采样开销要乘上步数）。
        "micro_batches": micro,
        "grad_accum": args.grad_accum,
        "epoch_iterations": batch_sampler.iter_calls if batch_sampler is not None else 0,
        "elapsed_sec": round(elapsed, 1),
        "sec_per_step": round(elapsed / max(1, step - start_step), 3),
        "final_valid": final,
        "best_valid": best,
        "history": history,
        "model_config": model.model_config,
        "args": {k: (str(v) if isinstance(v, Path) else v) for k, v in vars(args).items()},
    }
    write_json(lay.results / f"train_{args.backbone}{'_smoke' if smoke else ''}.json", report)
    print(json.dumps({k: report[k] for k in ("backbone", "steps", "elapsed_sec", "sec_per_step", "final_valid")},
                     ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
