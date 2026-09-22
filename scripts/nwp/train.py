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

from model import CHAR_PAD, build_model, save_checkpoint  # noqa: E402
from nwp_common import (  # noqa: E402
    add_work_arg,
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
    """左填充：最后一个位置永远是真实 token，模型只取最后一个位置的 logits。"""
    width = max(len(b["ids"]) for b in batch)
    ids = torch.full((len(batch), width), pad_id, dtype=torch.long)
    mask = torch.zeros((len(batch), width), dtype=torch.long)
    for row, item in enumerate(batch):
        seq = item["ids"]
        ids[row, width - len(seq) :] = torch.tensor(seq, dtype=torch.long)
        mask[row, width - len(seq) :] = 1
    # 位置从真实 token 起算：左填充不能把位置索引推偏，否则与端侧（无填充）不一致
    position_ids = (mask.cumsum(-1) - 1).clamp(min=0)
    labels = torch.tensor([b["label"] for b in batch], dtype=torch.long)
    return ids, mask, position_ids, labels


# --------------------------------------------------------------------------- #
# 学习率：每组独立 warmup + 余弦/斜三角
# --------------------------------------------------------------------------- #


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
def quick_metric(model, dataset, limit: int, batch_size: int, device: str) -> dict:
    model.eval()
    n = min(limit, len(dataset))
    hits1 = hits5 = 0
    rr = 0.0
    total = 0
    for start in range(0, n, batch_size):
        chunk = [dataset[i] for i in range(start, min(start + batch_size, n))]
        ids, mask, pos, labels = collate(chunk)
        ids, mask, pos = ids.to(device), mask.to(device), pos.to(device)
        logits, _ = model(ids, mask, pos)
        top5 = torch.topk(logits, k=min(5, logits.shape[-1]), dim=-1).indices.cpu()
        for row, label in enumerate(labels.tolist()):
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

    loader = torch.utils.data.DataLoader(
        train_set,
        batch_size=args.batch_size,
        shuffle=True,
        collate_fn=collate,
        num_workers=0,
        drop_last=True,
        generator=torch.Generator().manual_seed(args.seed),
    )

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
    lora_on = not lora_cfg or unfreeze_at <= 0
    while step < args.max_steps:
        for batch in loader:
            if step >= args.max_steps:
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
            ids, mask, pos, labels = batch
            ids, mask, pos, labels = ids.to(device), mask.to(device), pos.to(device), labels.to(device)

            with torch.autocast(device_type="cuda" if device == "cuda" else "cpu", dtype=amp_dtype,
                                enabled=amp_dtype is not None):
                logits, _ = model(ids, mask, pos)
                loss = F.cross_entropy(logits, labels, ignore_index=0) / args.grad_accum
            if use_scaler:
                scaler.scale(loss).backward()
            else:
                loss.backward()

            running += float(loss.detach()) * args.grad_accum
            running_n += 1
            micro += 1
            if micro % args.grad_accum != 0:
                continue

            if use_scaler:
                scaler.unscale_(optimizer)
            if args.grad_clip > 0:
                torch.nn.utils.clip_grad_norm_([p for p in model.parameters() if p.requires_grad], args.grad_clip)
            if use_scaler:
                scaler.step(optimizer)
                scaler.update()
            else:
                optimizer.step()
            optimizer.zero_grad(set_to_none=True)
            step += 1

            if step % args.log_every == 0:
                speed = (time.time() - t0) / max(1, step - start_step)
                log(f"step {step}/{args.max_steps} loss={running / max(1, running_n):.4f} "
                    f"lr={','.join(f'{k}:{v:.2e}' for k, v in lrs.items())} {speed:.2f}s/step")
                running, running_n = 0.0, 0

            if args.eval_every and step % args.eval_every == 0:
                metrics = quick_metric(model, valid_set, args.eval_limit, args.batch_size, device)
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

    final = quick_metric(model, valid_set, args.eval_limit, args.batch_size, device)
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
        "smoke": smoke,
        "device": device,
        "dtype": dtype,
        "steps": step,
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
