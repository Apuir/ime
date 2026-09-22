#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""两个基线：unigram（词频）与现有 n-gram（`model/predict.marisa`）。

为什么要基线：实施 prompt §4 Phase 1 写得很直白——**神经模型打不过词频基线就是早期告警**。
所以基线不是可选项，探针阶段必须一起跑。

n-gram 基线走 C++ 助手（`ngram_baseline.cc`）：`predict.marisa` 是 marisa-trie 二进制，
Python 侧没有能读它的绑定。助手**按需编译**到 `<work>/bin/`，任何一步不可用
（没有 resource.zip / 没有 g++ / 没有 libmarisa / 模型打不开）都在这里降级为
「unavailable + 原因」，而不是让整条评测挂掉。

查询口径有两种，都实现了：
- `spaced`（默认）：用我们自己的分词把上下文拼成 `"词 词 词"`，与 marisa 里的键格式
  （词间带空格）一致，从 5 词回退到 1 词。这是这份模型**真正**能给到的上限。
- `chars`：直接拿字符后缀查（12/8/6/4/3/2/1 字），**复现 app 现在的行为**。
  研究文档实测这条路径只能命中「1 个词」的上下文，正是要被替换掉的那条。
"""

from __future__ import annotations

import argparse
import json
import shutil
import subprocess
import sys
import tempfile
import zipfile
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

from nwp_common import (  # noqa: E402
    Segmenter,
    add_work_arg,
    iter_jsonl,
    layout_from_args,
    load_word_vocab,
    log,
    markdown_table,
    rank_metrics,
    read_json,
    write_json,
)

REPO = Path(__file__).resolve().parents[2]
CC = Path(__file__).resolve().parent / "ngram_baseline.cc"


# --------------------------------------------------------------------------- #
# unigram
# --------------------------------------------------------------------------- #


class UnigramBaseline:
    """词频排序。`word_vocab.txt` 本身就是按词频降序排的（第 0 行是 `<unk>`），
    有 `word_freq.json` 时用它精确排序。"""

    def __init__(self, word_vocab: Path, word_freq: Path | None = None) -> None:
        words = load_word_vocab(word_vocab)
        self.freq = read_json(word_freq) if word_freq and Path(word_freq).exists() else {}
        if self.freq:
            ranked = sorted(range(1, len(words)), key=lambda i: (-self.freq.get(words[i], 0), words[i]))
        else:
            ranked = list(range(1, len(words)))  # 行序即词频序
        self.ranked = ranked
        self.words = words

    def predict(self, contexts: list[str], topk: int = 5) -> list[list[int | None]]:
        head = self.ranked[:topk]
        return [list(head) for _ in contexts]


# --------------------------------------------------------------------------- #
# n-gram（marisa + C++ 助手）
# --------------------------------------------------------------------------- #


class NgramBaseline:
    def __init__(self, lay, topk: int = 5, mode: str = "spaced", resource_zip: Path | None = None,
                 max_scan: int = 200000) -> None:
        self.lay = lay
        self.topk = topk
        self.mode = mode
        self.resource_zip = resource_zip or (REPO / "app/src/main/assets/resource.zip")
        self.max_scan = max_scan
        self.binary: Path | None = None
        self.model_path: Path | None = None
        self.reason = ""

    # -- 准备 -------------------------------------------------------------
    def prepare(self) -> bool:
        try:
            self.model_path = self._ensure_model()
            self.binary = self._ensure_binary()
        except Degraded as e:
            self.reason = str(e)
            return False
        if not self.model_path or not self.binary:
            return False
        probe = self._run([str(self.binary), "--model", str(self.model_path), "--dump", "1"])
        if probe.returncode != 0:
            self.reason = f"助手无法读取 {self.model_path.name}: {probe.stderr.strip()[:200]}"
            return False
        return True

    def _ensure_model(self) -> Path:
        dest = self.lay.bin / "predict.marisa"
        if dest.exists() and dest.stat().st_size > 0:
            return dest
        if not self.resource_zip.exists():
            raise Degraded(f"缺少 {self.resource_zip}（该文件不入库，需先跑 scripts/build-rime-resource.py）")
        self.lay.make("bin")
        with zipfile.ZipFile(self.resource_zip) as zf:
            dest.write_bytes(zf.read("model/predict.marisa"))
        return dest

    def _ensure_binary(self) -> Path:
        dest = self.lay.bin / "ngram_baseline"
        if dest.exists() and dest.stat().st_mtime >= CC.stat().st_mtime:
            return dest
        gxx = shutil.which("g++")
        if not gxx:
            raise Degraded("没有 g++，无法编译 ngram_baseline.cc")
        self.lay.make("bin")
        cmd = [gxx, "-O2", "-std=c++17", "-o", str(dest), str(CC), "-lmarisa"]
        proc = subprocess.run(cmd, capture_output=True, text=True)
        if proc.returncode != 0:
            raise Degraded(f"编译失败（libmarisa 未安装？）：{proc.stderr.strip()[:300]}")
        return dest

    def _run(self, cmd: list[str]) -> subprocess.CompletedProcess:
        return subprocess.run(cmd, capture_output=True, text=True)

    # -- 查询 -------------------------------------------------------------
    def build_queries(self, contexts: list[str], segmenter: Segmenter) -> list[tuple[int, str]]:
        queries: list[tuple[int, str]] = []
        for i, ctx in enumerate(contexts):
            if self.mode == "chars":
                for k in (12, 8, 6, 4, 3, 2, 1):
                    if len(ctx) >= k:
                        queries.append((i, ctx[-k:]))
            else:
                words = [w for w, _, _ in segmenter.segment(ctx)]
                for k in range(5, 0, -1):
                    if len(words) >= k:
                        queries.append((i, " ".join(words[-k:])))
        return queries

    def predict(self, contexts: list[str], segmenter: Segmenter) -> list[list[str]]:
        queries = self.build_queries(contexts, segmenter)
        with tempfile.NamedTemporaryFile("w", suffix=".tsv", delete=False, encoding="utf-8") as fh:
            for i, ctx in queries:
                fh.write(f"{i}\t{ctx}\n")
            query_path = Path(fh.name)
        try:
            proc = self._run([str(self.binary), "--model", str(self.model_path),
                              "--input", str(query_path), "--topk", str(self.topk)])
            if proc.returncode != 0:
                self.reason = f"助手退出码 {proc.returncode}: {proc.stderr.strip()[:200]}"
                return [[] for _ in contexts]
            out: dict[int, list[str]] = {}
            for line in proc.stdout.split("\n"):
                if not line:
                    continue
                parts = line.split("\t")
                idx = int(parts[0])
                words: list[str] = []
                rest = parts[1:]
                for j in range(0, len(rest) - 1, 2):
                    words.append(rest[j])
                out[idx] = words
            return [out.get(i, []) for i in range(len(contexts))]
        finally:
            query_path.unlink(missing_ok=True)

    def stats(self) -> dict:
        if not self.binary or not self.model_path:
            return {"available": False, "reason": self.reason}
        proc = self._run([str(self.binary), "--model", str(self.model_path), "--stats"])
        out = {"available": proc.returncode == 0}
        for line in proc.stdout.split("\n"):
            if "=" in line:
                k, v = line.split("=", 1)
                out[k] = int(v) if v.strip().isdigit() else v.strip()
        if proc.returncode != 0:
            out["reason"] = proc.stderr.strip()[:200]
        return out


class Degraded(Exception):
    """基线不可用时抛这个：调用方降级而不是崩。"""


def to_ids(preds: list[list[str]], contexts: list[str], word2id: dict[str, int],
           unigram_head: list[int] | None = None) -> tuple[list[list[int | None]], dict]:
    """把预测出的**词串**映射成词 id；词表外的词给 None（占位但永远不命中，不能悄悄跳过）。

    一个细节：词表外的候选不该被「跳过」腾位置——那样会虚高基线成绩。
    所以先按顺序放（None 占位），只有候选不足 topk 时才用 unigram 补。
    """
    out: list[list[int | None]] = []
    oov = 0
    total = 0
    for words in preds:
        ids: list[int | None] = []
        for w in words:
            total += 1
            wid = word2id.get(w)
            if wid is None:
                oov += 1
            ids.append(wid)
        if unigram_head:
            for extra in unigram_head:
                if len(ids) >= 5:
                    break
                if extra not in ids:
                    ids.append(extra)
        out.append(ids)
    return out, {"oov_predictions": oov, "predictions": total,
                 "oov_rate": (oov / total) if total else 0.0}


def ranks_from_ids(labels: list[int], preds: list[list[int | None]]) -> list[int | None]:
    ranks: list[int | None] = []
    for label, ids in zip(labels, preds):
        try:
            ranks.append(ids.index(label) + 1)
        except ValueError:
            ranks.append(None)
    return ranks


def evaluate_baselines(args) -> int:
    lay = layout_from_args(args)
    samples = Path(args.samples_file) if args.samples_file else lay.samples / (args.split + ".jsonl")
    if not samples.exists():
        raise SystemExit(f"{samples} 不存在：先跑 build_samples.py")
    rows = []
    for row in iter_jsonl(samples):
        rows.append(row)
        if args.limit and len(rows) >= args.limit:
            break
    labels = [r["label"] for r in rows]
    contexts = [r["ctx"] for r in rows]
    words = load_word_vocab(lay.word_vocab)
    word2id = {w: i for i, w in enumerate(words)}
    segmenter = Segmenter(words)
    log(f"评测 {len(rows)} 条（{samples.name}）")

    results: dict = {}
    if args.baseline in ("unigram", "all"):
        uni = UnigramBaseline(lay.word_vocab, lay.word_freq)
        preds = uni.predict(contexts, topk=args.topk)
        ranks = ranks_from_ids(labels, preds)
        results["unigram"] = rank_metrics(ranks, args.topk) | {"source": "word_freq"}
        log(f"unigram: top1={results['unigram']['top1']:.4f} top5={results['unigram']['top5']:.4f}")

    if args.baseline in ("ngram", "all"):
        ng = NgramBaseline(lay, topk=max(args.topk, 5), mode=args.ngram_mode)
        if ng.prepare():
            raw = ng.predict(contexts, segmenter)
            uni_head = UnigramBaseline(lay.word_vocab, lay.word_freq).ranked[: args.topk]
            preds, oov_stats = to_ids(raw, contexts, word2id, unigram_head=uni_head)
            ranks = ranks_from_ids(labels, preds)
            results["ngram"] = rank_metrics(ranks, args.topk) | oov_stats | {
                "mode": args.ngram_mode, "source": "model/predict.marisa",
            }
            log(f"ngram({args.ngram_mode}): top1={results['ngram']['top1']:.4f} "
                f"top5={results['ngram']['top5']:.4f} oov={results['ngram']['oov_rate']:.3f}")
        else:
            results["ngram"] = {"available": False, "reason": ng.reason}
            log(f"⚠ n-gram 基线不可用：{ng.reason}")

    out_json = Path(args.out) if args.out else lay.results / f"baselines_{args.split}.json"
    write_json(out_json, {"samples": str(samples), "n": len(rows), "results": results})
    md = markdown_table(
        ["baseline", "top-1", "top-5", "MRR", "覆盖"],
        [[name, f"{v.get('top1', 0)*100:.2f}", f"{v.get('top5', 0)*100:.2f}",
          f"{v.get('mrr', 0):.4f}", f"{v.get('covered', 0)*100:.2f}"]
         for name, v in results.items() if v.get("available", True)],
    )
    if args.md:
        Path(args.md).write_text(md, encoding="utf-8")
    print(md)
    return 0


def ngram_stats(args) -> int:
    lay = layout_from_args(args)
    ng = NgramBaseline(lay, mode=args.ngram_mode)
    if not ng.prepare():
        print(json.dumps({"available": False, "reason": ng.reason}, ensure_ascii=False, indent=2))
        return 0 if not args.strict else 1
    print(json.dumps(ng.stats(), ensure_ascii=False, indent=2))
    return 0


def main() -> int:
    ap = argparse.ArgumentParser(description="unigram / n-gram 基线评测")
    sub = ap.add_subparsers(dest="cmd", required=True)

    p_eval = sub.add_parser("eval", help="在样本集上评基线")
    p_eval.add_argument("--baseline", choices=["unigram", "ngram", "all"], default="all")
    p_eval.add_argument("--samples-file", type=Path, default=None)
    p_eval.add_argument("--split", default="test")
    p_eval.add_argument("--limit", type=int, default=2000)
    p_eval.add_argument("--topk", type=int, default=5)
    p_eval.add_argument("--ngram-mode", choices=["spaced", "chars"], default="spaced")
    p_eval.add_argument("--out", type=Path, default=None)
    p_eval.add_argument("--md", type=Path, default=None)
    add_work_arg(p_eval)
    p_eval.set_defaults(func=evaluate_baselines)

    p_stats = sub.add_parser("ngram-stats", help="枚举 predict.marisa 的规模（验证助手与模型可用）")
    p_stats.add_argument("--ngram-mode", choices=["spaced", "chars"], default="spaced")
    p_stats.add_argument("--strict", action="store_true")
    add_work_arg(p_stats)
    p_stats.set_defaults(func=ngram_stats)

    args = ap.parse_args()
    return args.func(args)


if __name__ == "__main__":
    raise SystemExit(main())
