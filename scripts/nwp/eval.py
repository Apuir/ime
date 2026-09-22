#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""NWP 评测：top-1 / top-5 / MRR（+ 可选 BPB）。

**不比 PPL**。跨分词器比 PPL 是错的（实施 prompt §4 Phase 1 明确要求），
要似然指标就用 BPB（bits-per-byte，与分词无关）。

可以评三种东西，互相之间可比：
- `--checkpoint`：训练产出的 checkpoint（自动合并 LoRA）
- `--onnx`：导出后的 fp32 / int8 ONNX
- `--baseline`：unigram / n-gram（实现在 baselines.py，探针阶段要一起跑）

产出 `results/eval_*.json` + 一张 markdown 表；`report` 子命令把多个 json 合成一张对比表。
"""

from __future__ import annotations

import argparse
import json
import math
import sys
import time
from pathlib import Path

import torch

sys.path.insert(0, str(Path(__file__).resolve().parent))

from baselines import NgramBaseline, UnigramBaseline, ranks_from_ids, to_ids  # noqa: E402
from model import CHAR_PAD, load_checkpoint  # noqa: E402
from nwp_common import (  # noqa: E402
    Segmenter,
    add_work_arg,
    iter_jsonl,
    layout_from_args,
    load_word_vocab,
    log,
    markdown_table,
    prepare_hf_env,
    rank_metrics,
    write_json,
)

def load_rows(path: Path, limit: int) -> list[dict]:
    rows: list[dict] = []
    for row in iter_jsonl(path):
        rows.append(row)
        if limit and len(rows) >= limit:
            break
    if not rows:
        raise SystemExit(f"{path} 是空的")
    return rows


def pad_batch(rows: list[dict], pad_id: int = CHAR_PAD):
    width = max(len(r["ids"]) for r in rows)
    ids = torch.full((len(rows), width), pad_id, dtype=torch.long)
    mask = torch.zeros((len(rows), width), dtype=torch.long)
    for i, row in enumerate(rows):
        seq = row["ids"]
        ids[i, width - len(seq) :] = torch.tensor(seq, dtype=torch.long)
        mask[i, width - len(seq) :] = 1
    position_ids = (mask.cumsum(-1) - 1).clamp(min=0)
    return ids, mask, position_ids


def topk_and_nll(logits: torch.Tensor, labels: torch.Tensor, topk: int):
    k = min(topk, logits.shape[-1])
    top = torch.topk(logits, k=k, dim=-1).indices
    logp = torch.log_softmax(logits.float(), dim=-1)
    nll = -logp.gather(1, labels.unsqueeze(1)).squeeze(1)
    return top, nll


def eval_checkpoint(args, rows: list[dict], word_bytes: dict[int, int]) -> dict:
    model = load_checkpoint(args.checkpoint, merge=True)
    device = "cuda" if torch.cuda.is_available() and not args.cpu else "cpu"
    model.to(device).eval()
    model.output_cache = False
    ranks: list[int | None] = []
    nll_sum = 0.0
    bytes_sum = 0
    for start in range(0, len(rows), args.batch_size):
        chunk = rows[start : start + args.batch_size]
        ids, mask, pos = pad_batch(chunk)
        labels = torch.tensor([r["label"] for r in chunk], dtype=torch.long)
        with torch.no_grad():
            logits, _ = model(ids.to(device), mask.to(device), pos.to(device))
        logits = logits.cpu()
        top, nll = topk_and_nll(logits, labels, args.topk)
        for row_i, label in enumerate(labels.tolist()):
            ranked = top[row_i].tolist()
            ranks.append(ranked.index(label) + 1 if label in ranked else None)
            nll_sum += float(nll[row_i])
            bytes_sum += word_bytes.get(label, 1)
    metrics = rank_metrics(ranks, args.topk)
    if args.bpb:
        metrics["bpb"] = nll_sum / max(1, bytes_sum) / math.log(2)
    return metrics


def onnx_feeds(session, ids: torch.Tensor, mask: torch.Tensor, pos: torch.Tensor) -> dict:
    """prefill：past 全给 0 长度（契约里 past 是必填输入，长度可动态）。"""
    import numpy as np

    feeds = {
        "input_ids": ids.numpy().astype("int64"),
        "attention_mask": mask.numpy().astype("int64"),
        "position_ids": pos.numpy().astype("int64"),
    }
    for inp in session.get_inputs():
        if inp.name.startswith("past_key_values."):
            shape = [d if isinstance(d, int) else 1 for d in inp.shape]
            for i, d in enumerate(inp.shape):
                if i == 0:
                    shape[i] = ids.shape[0]
                elif i == 2:
                    shape[i] = 0
            feeds[inp.name] = np.zeros(shape, dtype=np.float32)
    return feeds


def eval_onnx(args, rows: list[dict], word_bytes: dict[int, int]) -> dict:
    import onnxruntime as ort

    so = ort.SessionOptions()
    so.log_severity_level = 3
    sess = ort.InferenceSession(str(args.onnx), so, providers=["CPUExecutionProvider"])
    names = [o.name for o in sess.get_outputs()]
    ranks: list[int | None] = []
    nll_sum = 0.0
    bytes_sum = 0
    for start in range(0, len(rows), args.batch_size):
        chunk = rows[start : start + args.batch_size]
        ids, mask, pos = pad_batch(chunk)
        labels = torch.tensor([r["label"] for r in chunk], dtype=torch.long)
        out = sess.run(["logits"], onnx_feeds(sess, ids, mask, pos))[0]
        logits = torch.from_numpy(out)
        top, nll = topk_and_nll(logits, labels, args.topk)
        for row_i, label in enumerate(labels.tolist()):
            ranked = top[row_i].tolist()
            ranks.append(ranked.index(label) + 1 if label in ranked else None)
            nll_sum += float(nll[row_i])
            bytes_sum += word_bytes.get(label, 1)
    metrics = rank_metrics(ranks, args.topk)
    if args.bpb:
        metrics["bpb"] = nll_sum / max(1, bytes_sum) / math.log(2)
    return metrics


def eval_baselines(args, lay, rows: list[dict]) -> dict:
    words = load_word_vocab(lay.word_vocab)
    word2id = {w: i for i, w in enumerate(words)}
    segmenter = Segmenter(words)
    labels = [r["label"] for r in rows]
    contexts = [r["ctx"] for r in rows]
    results: dict = {}
    uni = UnigramBaseline(lay.word_vocab, lay.word_freq)
    if args.baseline in ("unigram", "all"):
        preds = uni.predict(contexts, topk=args.topk)
        results["unigram"] = rank_metrics(ranks_from_ids(labels, preds), args.topk)
    if args.baseline in ("ngram", "all"):
        ng = NgramBaseline(lay, topk=max(args.topk, 5), mode=args.ngram_mode)
        if ng.prepare():
            raw = ng.predict(contexts, segmenter)
            preds, oov = to_ids(raw, contexts, word2id, unigram_head=uni.ranked[: args.topk])
            results["ngram"] = rank_metrics(ranks_from_ids(labels, preds), args.topk) | oov | {
                "mode": args.ngram_mode}
        else:
            results["ngram"] = {"available": False, "reason": ng.reason}
            log(f"⚠ n-gram 基线不可用：{ng.reason}")
    return results


def main() -> int:
    ap = argparse.ArgumentParser(description="NWP 评测（top-1/top-5/MRR，可比 BPB）")
    sub = ap.add_subparsers(dest="cmd", required=True)

    p = sub.add_parser("run", help="评一个模型或基线")
    p.add_argument("--checkpoint", type=Path, default=None)
    p.add_argument("--onnx", type=Path, default=None)
    p.add_argument("--baseline", choices=["none", "unigram", "ngram", "all"], default="none")
    p.add_argument("--samples-file", type=Path, default=None)
    p.add_argument("--split", default="test")
    p.add_argument("--limit", type=int, default=2000)
    p.add_argument("--topk", type=int, default=5)
    p.add_argument("--batch-size", type=int, default=16)
    p.add_argument("--bpb", action="store_true", default=True)
    p.add_argument("--no-bpb", dest="bpb", action="store_false")
    p.add_argument("--ngram-mode", choices=["spaced", "chars"], default="spaced")
    p.add_argument("--cpu", action="store_true", help="强制 CPU")
    p.add_argument("--name", default=None, help="结果里的模型名（默认按文件推断）")
    p.add_argument("--out", type=Path, default=None)
    p.add_argument("--md", type=Path, default=None)
    add_work_arg(p)
    p.set_defaults(func=cmd_run)

    p_report = sub.add_parser("report", help="把多个 eval json 合成一张对比表")
    p_report.add_argument("--inputs", type=Path, nargs="+", required=True)
    p_report.add_argument("--out", type=Path, default=None)
    p_report.add_argument("--title", default="NWP 评测对比")
    add_work_arg(p_report)
    p_report.set_defaults(func=cmd_report)

    args = ap.parse_args()
    return args.func(args)


def cmd_run(args) -> int:
    lay = layout_from_args(args)
    prepare_hf_env(lay.root)
    samples = args.samples_file or (lay.samples / f"{args.split}.jsonl")
    rows = load_rows(samples, args.limit)
    words = load_word_vocab(lay.word_vocab)
    word_bytes = {i: len(w.encode("utf-8")) for i, w in enumerate(words)}
    name = args.name
    t0 = time.time()
    results: dict = {}
    if args.checkpoint:
        name = name or f"ckpt:{Path(args.checkpoint).stem}"
        results["model"] = eval_checkpoint(args, rows, word_bytes)
    if args.onnx:
        name = name or f"onnx:{Path(args.onnx).name}"
        results["model"] = eval_onnx(args, rows, word_bytes)
    if args.baseline != "none":
        results |= eval_baselines(args, lay, rows)
        if name is None:
            name = "baselines"
    if not results:
        raise SystemExit("至少要给 --checkpoint / --onnx / --baseline 之一")
    out = {"name": name, "samples": str(samples), "n": len(rows), "topk": args.topk,
           "elapsed_sec": round(time.time() - t0, 1), "results": results}
    out_json = args.out or (lay.results / f"eval_{str(name).replace(':', '_').replace('/', '_')}.json")
    write_json(out_json, out)
    md = markdown_table(
        ["model", "top-1", "top-5", "MRR", "BPB", "n"],
        [[k, f"{v.get('top1', 0) * 100:.2f}", f"{v.get('top5', 0) * 100:.2f}",
          f"{v.get('mrr', 0):.4f}",
          (f"{v['bpb']:.4f}" if isinstance(v.get("bpb"), float) else "-"),
          v.get("n", len(rows))]
         for k, v in results.items() if v.get("available", True)],
    )
    if args.md:
        Path(args.md).write_text(md, encoding="utf-8")
    print(md)
    print(f"→ {out_json}")
    return 0


def cmd_report(args) -> int:
    rows = []
    for path in args.inputs:
        data = json.loads(Path(path).read_text(encoding="utf-8"))
        for k, v in data.get("results", {}).items():
            if not v.get("available", True):
                continue
            rows.append([f"{data.get('name', Path(path).stem)}/{k}",
                         f"{v.get('top1', 0) * 100:.2f}", f"{v.get('top5', 0) * 100:.2f}",
                         f"{v.get('mrr', 0):.4f}",
                         (f"{v['bpb']:.4f}" if isinstance(v.get("bpb"), float) else "-"),
                         v.get("n", data.get("n", ""))])
    md = f"# {args.title}\n\n" + markdown_table(["run", "top-1", "top-5", "MRR", "BPB", "n"], rows)
    if args.out:
        Path(args.out).parent.mkdir(parents=True, exist_ok=True)
        Path(args.out).write_text(md, encoding="utf-8")
    print(md)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
