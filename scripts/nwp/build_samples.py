#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""把语料切成滑窗训练样本，并把测试集与训练集按 ≥8 字 n-gram 去重。

样本 = 「前 S=128 个词的**字**」→「第 S+1 个词的 id」，不需要任何标注。
`ctx` 原样保留：评测时基线要用它，人工看错误样例也要它。

去重是硬要求，不是可选项
------------------------
Sloth 吃过基准沾染的亏：对外报 top-1 47.3，去重后实际只有 10.9。
所以测试集里任何与训练/验证文本共享 ≥8 字连续片段的**样本整条丢弃**，
并把丢弃条数写进 report——不报数的去重等于没做。

文档按 hash 分到 train/valid/test，先在 train 上建 n-gram 指纹集合，
valid 对 train 去重、test 对 train+valid 去重，避免任何一路漏掉。
"""

from __future__ import annotations

import argparse
import json
import re
import sys
import time
import zlib
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

from nwp_common import (  # noqa: E402
    DEFAULT_MAX_CONTEXT_IDS,
    Segmenter,
    add_work_arg,
    layout_from_args,
    load_char2id,
    load_word_vocab,
    log,
    ngram_hashes,
    ngram_strings,
    write_json,
)


def iter_docs_round_robin(corpus_files: list[Path]):
    """按行**轮流**读各语料文件，而不是一个读完了再读下一个。

    为什么必须轮流：`--max-samples` 是按遍历顺序填的，而分片名是按数据集排的
    （lccc-* → thucnews-* → wiki-*）。顺序读的话配额会在前面的数据集里用光——
    用户那次 `--max-samples 12000000` 就是这样，**Wikipedia 一条都没进训练集**
    （训练尾部 维基/参考文献 = 27/0，测试尾部 = 100/20）。
    轮流读让配额在所有来源之间摊开；配合 report.sources 的逐来源计数，
    这种「某个语料其实没进去」的事不可能再悄悄发生。
    """
    handles = [(path, path.open("r", encoding="utf-8")) for path in corpus_files]
    try:
        while handles:
            alive = []
            for path, fh in handles:
                line = fh.readline()
                if not line:
                    fh.close()
                    continue
                doc = line.strip()
                if doc:
                    yield path.name, doc
                alive.append((path, fh))
            handles = alive
    finally:
        for _, fh in handles:
            try:
                fh.close()
            except OSError:
                pass


def split_of(doc: str, valid_ratio: float, test_ratio: float) -> str:
    """按文档内容定分片：可复现，且同一文档不会跨分片（否则就是泄漏）。"""
    bucket = zlib.crc32(doc.encode("utf-8")) % 10000
    if bucket < int(test_ratio * 10000):
        return "test"
    if bucket < int((test_ratio + valid_ratio) * 10000):
        return "valid"
    return "train"


def emit_samples(
    words: list[str],
    word2id: dict[str, int],
    char2id: dict[str, int],
    segmenter: Segmenter,
    doc: str,
    ctx_words: int,
    min_ctx_words: int,
    stride: int,
    max_context_ids: int,
    stats: dict,
    label_stride: int = 8,
    max_labels: int = 16,
):
    """滑窗产样本。`max_context_ids` 是**硬上限**，超了保留尾部。

    为什么按 id 数（字数）而不是词数设上限：端侧按字符预算喂输入，
    manifest.context_tokens 也是字符数。上下文按词取 128 个词时，实际字数是 200–550，
    训练一旦超过端侧能喂的长度，多出来的部分推理时永远见不到——那是白训。
    截尾（而不是截头）与端侧「取已上屏文本最后 N 个码点」的语义一致，
    标签不受影响（上下文只是前文，丢掉开头不影响预测下一个词）。
    """
    seg = segmenter.segment(doc)
    if len(seg) <= min_ctx_words:
        return
    unk = char2id.get("<unk>", 0)
    for i in range(min_ctx_words, len(seg), stride):
        word_id = word2id.get(seg[i][0], 0)
        if word_id == 0:
            # 目标是 <unk> 的样本进 loss 也会被 ignore_index 丢掉，不如根本别写入磁盘
            continue
        a = max(0, i - ctx_words)          # 上下文词的第一个下标
        c_lo = seg[a][1]                   # 上下文起点（seg 元组是 (词, 起, 止)）
        ctx = doc[c_lo : seg[i][1]]        # 到 label 词的**起点**为止：上下文不含答案
        if len(ctx) < 8:
            continue
        ids = [char2id.get(ch, unk) for ch in ctx]
        stats["max_ids_seen"] = max(stats.get("max_ids_seen", 0), len(ids))
        dropped_head = 0
        if len(ids) > max_context_ids:
            # ctx 与 ids 必须同步裁，否则评测/人工看错误样例时文本与张量对不上
            dropped_head = len(ids) - max_context_ids
            ids = ids[-max_context_ids:]
            ctx = ctx[-max_context_ids:]
            stats["truncated"] = stats.get("truncated", 0) + 1

        # 多位置监督：上下文里**每个词的最后一个字**都在预测它的下一个词
        # （和单标签同语义——最后那个位置预测的就是 sample 的 label），
        # 于是一次前向的 ~130 个字不再只为 1 个标签买单。
        # 位置是 ids 的下标；截尾后整体左移 dropped_head，移出上下文的直接丢掉。
        pairs: list[list[int]] = []
        for k in range(a, i):
            pos = seg[k][2] - 1 - c_lo - dropped_head   # 词 k 的末字在 ids 里的下标
            if pos < 0:
                continue
            target = word2id.get(seg[k + 1][0], 0)
            if target == 0:
                continue      # 目标 <unk> 会被 ignore_index 丢掉，没必要占一个位置
            pairs.append([pos, target])
        # 从**最近的**位置往前按 stride 取样：远端位置信息价值低，且最近的位置与端侧用法一致
        if label_stride > 1:
            pairs = pairs[::-1][::label_stride][::-1]
        if max_labels and len(pairs) > max_labels:
            pairs = pairs[-max_labels:]
        yield {"ids": ids, "label": word_id, "labels": pairs, "ctx": ctx}


class Deduper:
    """测试集与训练集的 n-gram 重叠检查。

    默认用 64 位指纹（crc32+adler32）：3.7 GB 语料的 8-gram 有十亿量级，
    存原串会爆内存。`--dedup-mode exact` 保留原串，用于验证指纹模式的结果一致。
    """

    def __init__(self, mode: str, cap: int, window: int, n: int, min_match: int = 1) -> None:
        self.mode = mode
        self.cap = cap
        self.window = window
        self.n = n
        self.min_match = min_match
        self.seen: set = set()
        self.truncated = False
        self.candidates = 0      # 生成过的 n-gram 条数（覆盖率的分母）
        self.inserted = 0        # 真正进了索引的条数（分子）
        self.cap_hit_at = None   # 在第几条候选上撞到上限

    def add_text(self, text: str) -> None:
        grams = ngram_strings(text, self.n) if self.mode == "exact" else ngram_hashes(text, self.n)
        for gram in grams:
            # 撞上限之后**继续数分母**（只剩一次 hash，很便宜）：否则报告里的覆盖率
            # 分母会缩水，读者会误以为「索引一直是满的」。
            self.candidates += 1
            if self.truncated:
                continue
            if len(self.seen) >= self.cap:
                self.truncated = True
                self.cap_hit_at = self.candidates
                continue
            self.seen.add(gram)
            self.inserted += 1

    def coverage(self) -> dict:
        """索引覆盖率：命中上限后，后面的 n-gram 根本没进集合。

        不报这个数，`去重覆盖不完整` 就只是一句没法判断风险的话。
        """
        return {
            "mode": self.mode,
            "cap": self.cap,
            "cap_hit": self.truncated,
            "cap_hit_at_occurrence": self.cap_hit_at,
            "ngram_occurrences": self.candidates,
            "ngram_indexed": self.inserted,
            "coverage_rate": (self.inserted / self.candidates) if self.candidates else 1.0,
            "distinct_indexed": len(self.seen),
        }

    def overlap_count(self, text: str) -> int:
        """命中多少个**不同**的 n-gram。"""
        if not self.seen:
            return 0
        tail = text[-self.window :] if self.window else text
        grams = ngram_strings(tail, self.n) if self.mode == "exact" else ngram_hashes(tail, self.n)
        hits = {g for g in grams if g in self.seen}
        return len(hits)

    def overlaps(self, text: str) -> bool:
        return self.overlap_count(text) >= self.min_match


def dedup_selftest() -> int:
    """边界自检：8 字重叠必须命中，7 字重叠必须放过。

    去重是「多删一条没事、漏删一条就是假成绩」的东西，所以边界要能自己证明。
    """
    d = Deduper("exact", cap=10**9, window=0, n=8)
    d.add_text("今天我们一起去公园散步看风景")
    cases = [
        ("完全相同的 8 字片段", "明天今天我们一起去公园散步好吗", True),
        ("跨边界拼接的 8 字片段", "我们一起去公园散步", True),
        # 与训练文本的最长公共子串正好 7 字，第 8 个字不同 → 必须放过
        ("只共享 7 字", "啊们一起去公园散啊", False),
        ("完全无关", "北京大学的图书馆藏书很多", False),
    ]
    ok = True
    for name, text, expect in cases:
        got = d.overlaps(text)
        flag = "PASS" if got == expect else "FAIL"
        if got != expect:
            ok = False
        print(f"  [{flag}] {name}: overlap={got} expect={expect}")
    d2 = Deduper("hash", cap=10**9, window=0, n=8)
    d2.add_text("今天我们一起去公园散步看风景")
    same = d2.overlaps("明天今天我们一起去公园散步好吗")
    print(f"  [{'PASS' if same else 'FAIL'}] hash 模式同判定: {same}")
    return 0 if ok and same else 1


def main() -> int:
    ap = argparse.ArgumentParser(description="滑窗切样本 + 测试集 n-gram 去重")
    ap.add_argument("--context-words", type=int, default=128,
                    help="上下文词数 S（采样规则，仅记录进报告；真正约束是 --max-context-ids）")
    ap.add_argument("--max-context-ids", type=int, default=DEFAULT_MAX_CONTEXT_IDS,
                    help=f"上下文**输入 id（字）数**硬上限，默认 {DEFAULT_MAX_CONTEXT_IDS}，"
                         "超了保留尾部（与端侧取最后 N 个码点一致）。"
                         "manifest.context_tokens 取的就是这个值，必须 ≥ 端侧 prompt 预算 210")
    ap.add_argument("--min-context-words", type=int, default=8)
    ap.add_argument("--stride", type=int, default=1, help="每多少词出一个样本（>1 可线性缩小样本量）")
    ap.add_argument("--label-stride", type=int, default=4,
                    help="多位置监督的取样间隔（按上下文词边界，默认 4 个词）。"
                         "真实语料实测：stride=8 只有 4.6× 标签密度、stride=4 有 7.9×；"
                         "长上下文样本被 --max-labels 截住，所以调小 stride 不增加骨干成本")
    ap.add_argument("--max-labels", type=int, default=16,
                    help="单个样本最多监督多少个位置（默认 16，取最近的若干个）")
    ap.add_argument("--valid-ratio", type=float, default=0.02)
    ap.add_argument("--test-ratio", type=float, default=0.02)
    ap.add_argument("--max-samples", type=int, default=0, help="每个分片最多写多少条（0=不限）")
    ap.add_argument("--max-doc-chars", type=int, default=4000)
    ap.add_argument("--dedup-mode", choices=["hash", "exact"], default="hash")
    ap.add_argument("--dedup-ngram", type=int, default=8)
    ap.add_argument("--dedup-window", type=int, default=64,
                    help="只对样本末尾这么多字符取 n-gram（0=全文）")
    ap.add_argument("--dedup-max-ngrams", type=int, default=20_000_000)
    ap.add_argument("--dedup-min-match", type=int, default=1,
                    help="命中多少个不同的 n-gram 才算沾染。1=严格按字面（任一 ≥N 字片段重合就丢）；"
                         "全量 3.7 GB 语料上 1 会把测试集几乎清空，那时用 2–4 更实际")
    ap.add_argument("--dedup-selftest", action="store_true", help="只跑去重边界自检")
    add_work_arg(ap)
    args = ap.parse_args()

    if args.dedup_selftest:
        return dedup_selftest()

    lay = layout_from_args(args)
    words = load_word_vocab(lay.word_vocab)
    char2id = load_char2id(lay.char2id)
    word2id = {w: i for i, w in enumerate(words)}
    segmenter = Segmenter(words)
    log(f"词表 {len(words)} 词，char2id {len(char2id)} 项，最大词长 {segmenter.max_len}")

    # 只认分片命名 `{dataset}-NNN.txt`。语料目录里还躺着 synthetic_words.txt 这类
    # 辅助文件（词表清单），按 *.txt 全收会把它当文档切出垃圾样本，也把 sources 计数搞花。
    corpus_files = sorted(
        p for p in lay.corpus.glob("*.txt") if re.fullmatch(r".+-\d{3,}\.txt", p.name))
    if not corpus_files:
        raise SystemExit(f"{lay.corpus} 下没有分片（形如 xxx-000.txt）：先跑 fetch_corpus.py")

    docs: dict[str, list[tuple[str, str]]] = {"train": [], "valid": [], "test": []}
    docs_by_source: dict[str, int] = {}
    for source, doc in iter_docs_round_robin(corpus_files):
        docs_by_source[source] = docs_by_source.get(source, 0) + 1
        docs[split_of(doc, args.valid_ratio, args.test_ratio)].append((source, doc[: args.max_doc_chars]))
    log(f"文档分片：train={len(docs['train'])} valid={len(docs['valid'])} test={len(docs['test'])}"
        f"（来源 {len(docs_by_source)} 个，轮流读取）")

    lay.make("samples")
    report: dict = {
        "corpus_files": [p.name for p in corpus_files],
        "docs": {k: len(v) for k, v in docs.items()},
        "context_words": args.context_words,
        "max_context_ids": args.max_context_ids,
        "label_stride": args.label_stride,
        "max_labels": args.max_labels,
        "stride": args.stride,
        "dedup": {"mode": args.dedup_mode, "ngram": args.dedup_ngram,
                  "window": args.dedup_window, "max_ngrams": args.dedup_max_ngrams,
                  "min_match": args.dedup_min_match},
        "splits": {},
    }

    t0 = time.time()
    deduper = Deduper(args.dedup_mode, args.dedup_max_ngrams, args.dedup_window,
                      args.dedup_ngram, args.dedup_min_match)
    max_ids_seen = 0
    source_samples: dict[str, int] = {}
    labels_total = 0
    tokens_total = 0
    samples_total = 0
    for split in ("train", "valid", "test"):
        out = lay.samples / f"{split}.jsonl"
        kept = 0
        dropped = 0
        skipped_short = 0
        stop = False
        stats: dict = {}
        checked_complete = 0   # 在索引仍完整时做过重叠判断的样本数
        checked_partial = 0    # 索引已不完整之后才判断的样本数
        src_samples: dict[str, int] = {}
        with out.open("w", encoding="utf-8") as fh:
            for source, doc in docs[split]:
                if stop:
                    break
                wrote_any = False
                for sample in emit_samples(
                    words, word2id, char2id, segmenter, doc,
                    args.context_words, args.min_context_words, args.stride,
                    args.max_context_ids, stats, args.label_stride, args.max_labels,
                ):
                    wrote_any = True
                    # valid/test 先查重叠再入库；train 只负责建集合
                    if split != "train":
                        complete = not deduper.truncated
                        if deduper.overlaps(sample["ctx"]):
                            dropped += 1
                            if complete:
                                checked_complete += 1
                            else:
                                checked_partial += 1
                            continue
                        if complete:
                            checked_complete += 1
                        else:
                            checked_partial += 1
                    if args.max_samples and kept >= args.max_samples:
                        stop = True
                        break
                    tail = sample["ctx"][-args.dedup_window :] if args.dedup_window else sample["ctx"]
                    deduper.add_text(tail)
                    fh.write(json.dumps(sample, ensure_ascii=False, separators=(",", ":")) + "\n")
                    kept += 1
                    # 监督密度只在**真的写出去**的样本上统计：被去重丢掉的样本不该进分子，
                    # 否则 labels/sample 会算出大于 --max-labels 的不可能值
                    stats["labels_total"] = stats.get("labels_total", 0) + len(sample["labels"])
                    stats["tokens_total"] = stats.get("tokens_total", 0) + len(sample["ids"])
                    src_samples[source] = src_samples.get(source, 0) + 1
                if not wrote_any:
                    skipped_short += 1
        rate = dropped / (kept + dropped) if (kept + dropped) else 0.0
        report["splits"][split] = {
            "file": out.name,
            "samples": kept,
            "dropped_by_dedup": dropped,
            "dropped_rate": rate,
            "docs_too_short": skipped_short,
            "truncated_by_max_context_ids": stats.get("truncated", 0),
            "dedup_checked_with_complete_index": checked_complete,
            "dedup_checked_with_partial_index": checked_partial,
            "sources": src_samples,
        }
        for name, n in src_samples.items():
            source_samples[name] = source_samples.get(name, 0) + n
        max_ids_seen = max(max_ids_seen, stats.get("max_ids_seen", 0))
        labels_total += stats.get("labels_total", 0)
        tokens_total += stats.get("tokens_total", 0)
        samples_total += kept
        log(f"{split}: {kept} 条，去重丢弃 {dropped} 条（{rate * 100:.2f}%），"
            f"因超 {args.max_context_ids} 字截尾 {stats.get('truncated', 0)} 条，"
            f"耗时 {time.time() - t0:.1f}s")

    # 截尾**之前**真实出现过的最大 id 数：export_onnx 用它断言 manifest 不会低报模型输入长度
    report["max_ids_seen"] = max_ids_seen
    # 监督密度：多位置监督是否真的生效，用「标签数 / token 数」这一个数就看得出
    report["labels"] = {
        "label_stride": args.label_stride,
        "max_labels": args.max_labels,
        "labels_total": labels_total,
        "tokens_total": tokens_total,
        "labels_per_sample": (labels_total / samples_total) if samples_total else 0.0,
        "labels_per_token": (labels_total / tokens_total) if tokens_total else 0.0,
    }
    # 逐来源计数：哪个语料实际进了多少条，一眼可见（防止某个来源被配额饿死）
    report["sources"] = {
        name: {"docs": n, "samples": source_samples.get(name, 0)}
        for name, n in sorted(docs_by_source.items())
    }
    report["dedup"]["ngrams_indexed"] = len(deduper.seen)
    report["dedup"]["truncated"] = deduper.truncated
    report["dedup"]["coverage"] = deduper.coverage()
    report["elapsed_sec"] = round(time.time() - t0, 1)
    write_json(lay.samples / "report.json", report)
    print(json.dumps(report["splits"], ensure_ascii=False, indent=2))
    if deduper.truncated:
        cov = deduper.coverage()
        ev = "；".join(
            f"{sp} {v['dedup_checked_with_complete_index']}/{v['dedup_checked_with_complete_index'] + v['dedup_checked_with_partial_index']}"
            f" 条在索引完整时检查"
            for sp, v in report["splits"].items() if sp != "train"
        )
        log(f"⚠ 去重索引撞到 --dedup-max-ngrams={cov['cap']:,} 后不再增长（覆盖不完整）：\n"
            f"    候选 n-gram {cov['ngram_occurrences']:,} 条，入索引 {cov['ngram_indexed']:,} 条 "
            f"→ 覆盖率 {cov['coverage_rate'] * 100:.1f}%（distinct {cov['distinct_indexed']:,}）；"
            f"第 {cov['cap_hit_at_occurrence']:,} 条候选时撞上限。\n"
            f"    {ev}。\n"
            f"    要把覆盖做到 100%，上限至少要到候选条数级别（Python set ≈50 B/条，"
            f"{cov['ngram_occurrences']:,} 条 ≈ {cov['ngram_occurrences'] * 50 / 2**30:.1f} GB 内存，通常不现实）；"
            f"更实际的做法是换异源测试集，或减小 --dedup-window / 提高 --dedup-min-match。")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
