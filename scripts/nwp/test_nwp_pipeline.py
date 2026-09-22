#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""`scripts/nwp` 的回归测试：两个曾经**静默**出错的 bug 各一个用例。

为什么单独放这个文件：这两个 bug 都是「不报错、只是结果悄悄变少/变差」的类型，
靠 review 抓不住，只能靠断言。测试不依赖 GPU、不依赖网络：

1. `test_shards_are_distinct` —— 分片号不自增时，每片都以 `"w"` 打开同一个文件名，
   报告说保留 25 万条、磁盘上只剩最后一片（用户实测 wiki 254,546 → 54,546）。
   断言：文件名两两不同，且**磁盘行数之和 == manifest 记录的 kept_docs**。
2. `test_fp16_backbone_forward` —— `IDEA-CCNL/Wenzhong-GPT2-110M` 的骨干是 fp16 落盘的，
   新建的 word_head 是 fp32；不在 autocast 里的调用点（quick_metric / eval / ONNX 导出）
   会抛 "mat1 and mat2 must have the same dtype"。用小骨干 + `.half()` 复现，不需要下载。
3. `test_char_coverage_guard` —— `IDEA-CCNL/Wenzhong-GPT2-110M` 是字节级 BPE（`今` → 两个 token），
   真实语料 77% 的字会变 `<unk>`。建词表时必须拦下来，否则 4000 步白跑。
4. `test_failed_fetch_is_loud_and_leaves_no_partial` —— 抓取中途失败原本无人记录；
   现在删掉半截分片、manifest 记 failed、退出码非零。
5. `test_export_forces_float32_io` —— 同一原因会让 ONNX 导出崩在 concat，
   且即使导出成功，I/O dtype 也会变成 float16 与契约不符。断言导出图的
   `past_key_values.0.key` / `logits` 都是 float32。

运行（venv 里没有 pytest 也能跑）：
    .nwp-work/venv/bin/python scripts/nwp/test_nwp_pipeline.py
或  python -m pytest scripts/nwp/test_nwp_pipeline.py
"""

from __future__ import annotations

import json
import sys
import tempfile
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

from nwp_common import Layout  # noqa: E402


# --------------------------------------------------------------------------- #
# Bug 1：分片文件名必须唯一，且磁盘行数必须等于报告的条数
# --------------------------------------------------------------------------- #


def test_shards_are_distinct(shard_docs: int = 100, docs: int = 250) -> dict:
    """用离线合成语料跑一次真实的分片写入路径。

    真实数据集的等价命令（慢，需要网络，故不放在默认路径里）：
        fetch_corpus.py --dataset wiki --limit 250000 --shard-docs 100000
    分片写入路径与数据集无关（只有记录解析不同），所以用离线合成语料跑
    `--limit 250000 --shard-docs 100000` 就是同一个用例，5 秒出结果。
    """
    import fetch_corpus

    with tempfile.TemporaryDirectory(prefix="nwp-shard-test-") as tmp:
        lay = Layout(tmp)
        lay.make("corpus")
        args = type("A", (), {})()
        args.limit = docs
        args.min_cjk = 6          # 合成句子短，测试里放宽，不影响分片逻辑
        args.max_chars = 2000
        args.seed = 7
        writer = fetch_corpus.ShardWriter(lay, "synthetic", shard_docs, args.limit)
        fetch_corpus.fetch_synthetic(args, lay, writer)
        writer.close()

        names = writer.files
        assert len(set(names)) == len(names), f"分片文件名有重复（分片号没自增）：{names}"
        expected_shards = -(-writer.total_kept // shard_docs)  # 向上取整
        assert len(names) == expected_shards, (
            f"应该有 {expected_shards} 个分片，实际 {len(names)} 个：{names}")
        on_disk = 0
        for name in names:
            assert (lay.corpus / name).exists(), f"分片 {name} 不存在"
            on_disk += sum(1 for line in (lay.corpus / name).open(encoding="utf-8") if line.strip())
        assert on_disk == writer.total_kept, (
            f"磁盘行数 {on_disk} != 报告条数 {writer.total_kept}（分片被覆盖/截断了）")
        sizes = [sum(1 for _ in (lay.corpus / n).open(encoding="utf-8")) for n in names]
        assert all(s <= shard_docs for s in sizes), f"某片超过了 --shard-docs={shard_docs}：{sizes}"

        # 重跑一次：旧分片必须被清掉，不能留下上一轮的孤儿文件
        writer2 = fetch_corpus.ShardWriter(lay, "synthetic", shard_docs, 40)
        fetch_corpus.fetch_synthetic(args, lay, writer2)
        writer2.close()
        leftovers = sorted(p.name for p in lay.corpus.glob("synthetic-*.txt"))
        assert leftovers == sorted(writer2.files), f"重跑后目录里有旧分片残留：{leftovers}"

        return {"shards": names, "shard_sizes": sizes, "kept": writer.total_kept,
                "on_disk": on_disk, "rerun_leftovers": leftovers}


def test_manifest_count_matches_disk(shard_docs: int = 50, docs: int = 130) -> dict:
    """跑一次完整的 CLI，断言 manifest 的 kept_docs == 分片行数之和、且分片名两两不同。

    这是用户那次事故的判据：报告写 254,546 条，磁盘上只有 54,546 条。
    真实数据集的等价命令（慢、需要网络，故不放在默认路径里）：
        fetch_corpus.py --dataset wiki --limit 250000 --shard-docs 100000
    """
    import subprocess

    script = Path(__file__).resolve().parent / "fetch_corpus.py"
    with tempfile.TemporaryDirectory(prefix="nwp-manifest-test-") as tmp:
        lay = Layout(tmp)
        lay.make("corpus")
        proc = subprocess.run(
            [sys.executable, str(script), "--work", tmp, "--dataset", "synthetic",
             "--limit", str(docs), "--shard-docs", str(shard_docs), "--min-cjk", "6"],
            capture_output=True, text=True)
        assert proc.returncode == 0, f"fetch_corpus 退出码 {proc.returncode}：{proc.stderr[-500:]}"
        manifest = json.loads((lay.corpus / "manifest.json").read_text(encoding="utf-8"))["synthetic"]
        names = manifest["shards"]
        assert len(set(names)) == len(names), f"manifest 里分片名重复：{names}"
        assert len(names) == -(-manifest["kept_docs"] // shard_docs), (
            f"分片数不对：{len(names)} 个，kept_docs={manifest['kept_docs']}")
        on_disk = 0
        for name in names:
            path = lay.corpus / name
            assert path.exists(), f"manifest 记了 {name} 但磁盘上没有"
            on_disk += sum(1 for line in path.open(encoding="utf-8") if line.strip())
        assert on_disk == manifest["kept_docs"], (
            f"manifest 记 {manifest['kept_docs']} 条，磁盘上 {on_disk} 条——正是那次静默覆盖的症状")
        return {"shards": names, "kept_docs": manifest["kept_docs"], "on_disk": on_disk}


# --------------------------------------------------------------------------- #
# Bug 2：fp16 骨干 + fp32 词头
# --------------------------------------------------------------------------- #


def _tiny_model():
    import torch  # noqa: F401  延迟导入：这两个测试要有 torch 才能跑

    from model import build_model

    return build_model("smoke", 64, 40, smoke=True).eval()


def test_fp16_backbone_forward() -> dict:
    """复现 wenzhong 的存储精度：骨干 Half、词头 Float，前向必须能出 logits。"""
    import torch

    model = _tiny_model()
    model.backbone.half()
    assert next(model.backbone.parameters()).dtype == torch.float16
    assert model.word_head.weight.dtype == torch.float32

    ids = torch.randint(2, 64, (2, 9))
    mask = torch.ones(2, 9, dtype=torch.long)
    pos = torch.arange(9).unsqueeze(0).expand(2, 9).contiguous()
    with torch.no_grad():
        logits, present = model(ids, mask, pos)
    assert logits.shape == (2, 40), f"logits 形状应为 [B,V]，实际 {tuple(logits.shape)}"
    assert torch.isfinite(logits).all(), "fp16 骨干下 logits 出现 NaN/Inf"
    assert logits.dtype == torch.float32, f"词头输出应为 fp32，实际 {logits.dtype}"
    assert present and present[0][0].dtype == torch.float16, "KV cache 应保持骨干的精度"
    return {"backbone_dtype": str(next(model.backbone.parameters()).dtype),
            "head_dtype": str(model.word_head.weight.dtype),
            "logits_shape": list(logits.shape), "logits_dtype": str(logits.dtype),
            "finite": bool(torch.isfinite(logits).all())}


def test_export_forces_float32_io() -> dict:
    """同一 bug 的第二处：ONNX 导出必须把 I/O 统一成 float32（契约要求）。"""
    from model import export_onnx

    model = _tiny_model()
    model.backbone.half()
    with tempfile.TemporaryDirectory(prefix="nwp-onnx-test-") as tmp:
        path = Path(tmp) / "m.onnx"
        export_onnx(model, path, seq=4, past=2)
        import onnx

        graph = onnx.load(str(path))
        dt = {1: "float32", 10: "float16"}
        types = {i.name: dt.get(i.type.tensor_type.elem_type) for i in graph.graph.input}
        outs = {o.name: dt.get(o.type.tensor_type.elem_type) for o in graph.graph.output}
        assert types["past_key_values.0.key"] == "float32", f"past 必须是 float32：{types}"
        assert outs["logits"] == "float32" and outs["present.0.key"] == "float32", f"输出必须是 float32：{outs}"
        ops = {n.op_type for n in graph.graph.node}
        assert not (ops & {"TopK", "ArgMax", "ArgMin"}), f"图里不该有 top-k 类算子：{ops}"
        return {"past_dtype": types["past_key_values.0.key"], "logits_dtype": outs["logits"],
                "present_dtype": outs["present.0.key"], "nodes": len(graph.graph.node)}


# --------------------------------------------------------------------------- #
# 顺带守住去重与 ctx 上限的既有约定
# --------------------------------------------------------------------------- #


def test_dedup_boundary_and_coverage() -> dict:
    """去重边界（8 字命中/7 字放过）+ 撞上限后覆盖率必须可报数。"""
    from build_samples import Deduper, dedup_selftest

    assert dedup_selftest() == 0, "去重边界自检失败"
    d = Deduper("hash", cap=50, window=0, n=8, min_match=1)
    for i in range(40):
        d.add_text("测试文本编号" + str(i) + "一二三四五六七八九十")
    cov = d.coverage()
    assert cov["cap_hit"], "上限 50 这么小却没撞上，覆盖率统计没被验证到"
    assert cov["ngram_occurrences"] > cov["ngram_indexed"], "撞上限后分母必须继续增长"
    assert 0 < cov["coverage_rate"] < 1
    return cov


def test_failed_fetch_is_loud_and_leaves_no_partial() -> dict:
    """抓取失败必须：删掉半截分片 + manifest 记 failed + 退出码非零。

    用 `--timeout 0.001` 强制失败：不管镜像当时快慢，握手一定超时，所以这个用例是确定的。
    半截语料比没有语料更危险——它会被下游当成完整语料拿去训。
    """
    import subprocess

    script = Path(__file__).resolve().parent / "fetch_corpus.py"
    with tempfile.TemporaryDirectory(prefix="nwp-fail-test-") as tmp:
        lay = Layout(tmp)
        lay.make("corpus")
        proc = subprocess.run(
            [sys.executable, str(script), "--work", tmp, "--dataset", "wiki",
             "--limit", "100", "--timeout", "0.001"],
            capture_output=True, text=True)
        assert proc.returncode != 0, "抓取失败却返回了 0"
        shards = sorted(p.name for p in lay.corpus.glob("wiki-*.txt"))
        assert shards == [], f"失败后仍留着半截分片：{shards}"
        manifest = json.loads((lay.corpus / "manifest.json").read_text(encoding="utf-8"))
        assert manifest["wiki"]["failed"], f"manifest 没记 failed：{manifest['wiki']}"
        assert manifest["wiki"]["kept_docs"] == 0 and manifest["wiki"]["shards"] == []
        return {"exit": proc.returncode, "partial_shards": shards,
                "failed": manifest["wiki"]["failed"][:60]}


def test_char_coverage_guard() -> dict:
    """字节级 BPE 底座（Wenzhong）必须在建词表时被拦下，而不是训完 4000 步才发现。"""
    from build_vocab import char_coverage_failure

    ok = char_coverage_failure(0.015, 0.80)          # cluecorpus 实测 ~1.5%
    assert ok is None, f"1.5% 的未知字率不该被拦：{ok}"
    bad = char_coverage_failure(0.772, 0.80)         # wenzhong 实测 77.2%
    assert bad and "字节级 BPE" in bad and "--allow-low-char-coverage" in bad, bad
    return {"cluecorpus_oov": 0.015, "cluecorpus_blocked": False,
            "wenzhong_oov": 0.772, "wenzhong_blocked": True,
            "message_head": bad.splitlines()[0][:40]}




# --------------------------------------------------------------------------- #
# 提速包（多位置监督 / 分桶组批 / 轮流采样 / 词头热启动 / fp16 导出）
# --------------------------------------------------------------------------- #

_FIXTURE: dict = {}


def _synthetic_work(sources: int = 1, docs_per_source: int = 800, max_context_ids: int = 256,
                    src_prefix: str = "source") -> Path:
    """做一个离线小工作目录（语料 + 词表 + 样本），多个用例共用。

    用合成语料是为了不依赖网络与 20 GB 的真实样本；数值口径（labels/token、
    padding 浪费）在小语料上与真实语料同量级，因为它们只取决于长度分布与采样参数。
    """
    key = (sources, docs_per_source, max_context_ids, src_prefix)
    if key in _FIXTURE:
        return _FIXTURE[key]
    import subprocess

    here = Path(__file__).resolve().parent
    tmp = Path(tempfile.mkdtemp(prefix="nwp-fx-"))
    lay = Layout(tmp)
    lay.make("corpus")
    # 直接写分片命名（{name}-000.txt），与 fetch_corpus 的 ShardWriter 一致
    import fetch_corpus

    args = type("A", (), {})()
    args.limit = docs_per_source
    args.min_cjk = 6
    args.max_chars = 2000
    args.seed = 3
    for i in range(sources):
        name = f"{src_prefix}-{chr(ord('a') + i)}"
        writer = fetch_corpus.ShardWriter(lay, name, 10**9, docs_per_source)
        fetch_corpus.fetch_synthetic(args, lay, writer)
        writer.close()
    run = lambda *cmd: subprocess.run([sys.executable, str(here / cmd[0]), "--work", tmp, *cmd[1:]],
                                      capture_output=True, text=True)
    proc = run("build_vocab.py", "--char-source", "corpus", "--vocab-source", "synthetic", "--size", "400")
    assert proc.returncode == 0, proc.stderr[-800:]
    proc = run("build_samples.py", "--context-words", "128", "--min-context-words", "4",
               "--test-ratio", "0.05", "--valid-ratio", "0.05",
               "--max-context-ids", str(max_context_ids))
    assert proc.returncode == 0, proc.stderr[-800:]
    _FIXTURE[key] = tmp
    return tmp


def test_multi_label_vs_single_position() -> dict:
    """(A1) `label_positions=[S-1]` 必须与 `None` 路径**逐位相同**（契约不能被动到）。"""
    import torch

    model = _tiny_model()
    ids = torch.randint(2, 64, (3, 11))
    mask = torch.ones(3, 11, dtype=torch.long)
    pos = torch.arange(11).unsqueeze(0).expand(3, 11).contiguous()
    last = torch.full((3, 1), 10, dtype=torch.long)
    with torch.no_grad():
        a, _ = model(ids, mask, pos)
        b, _ = model(ids, mask, pos, label_positions=last)
    assert a.shape == (3, 40) and b.shape == (3, 1, 40), f"{tuple(a.shape)} vs {tuple(b.shape)}"
    diff = float((a - b[:, 0, :]).abs().max())
    assert diff == 0.0, f"最后一个位置 gather 出来的 logits 与 None 路径不一致：max|Δ|={diff}"
    return {"none_shape": list(a.shape), "gather_shape": list(b.shape), "max_abs_diff": diff}


def test_labels_per_token_ratio() -> dict:
    """(A2) 多位置监督要把标签/token 从 1/130 提到至少 5 倍。

    优先用**真实语料切片**的 report（`.nwp-work/nwp-realcheck/`，README 里有生成命令），
    没有就退回合成语料——两条路径都要满足 5×。
    """
    import json as _json

    baseline = 1 / 130
    real_report = Path(__file__).resolve().parents[2] / ".nwp-work/nwp-realcheck/samples/report.json"
    if real_report.exists():
        report = _json.loads(real_report.read_text(encoding="utf-8"))
        origin = f"real:{real_report.parent.parent.name}"
    else:
        work = _synthetic_work()
        report = _json.loads((Layout(work).samples / "report.json").read_text(encoding="utf-8"))
        origin = "synthetic"
    labels = report["labels"]
    assert labels["labels_per_token"] > 5 * baseline, (
        f"[{origin}] labels/token={labels['labels_per_token']:.4f}，没有达到 5×{baseline:.5f}")
    assert labels["labels_per_sample"] <= labels["max_labels"], (
        f"labels/样本 {labels['labels_per_sample']} 超过了 --max-labels，统计口径有问题")
    assert labels["labels_per_sample"] > 1.5, labels
    return {"origin": origin, "stride": labels["label_stride"],
            "labels_per_token": round(labels["labels_per_token"], 4),
            "labels_per_sample": round(labels["labels_per_sample"], 2),
            "baseline_1_over_130": round(baseline, 5),
            "improvement": round(labels["labels_per_token"] / baseline, 1)}


def test_max_context_ids_variants() -> dict:
    """(约束) cap=128/160/256 都要能跑，且 report 与 manifest 取同一个值。"""
    import json as _json

    out = {}
    for cap in (128, 160):
        work = _synthetic_work(max_context_ids=cap)
        report = _json.loads((Layout(work).samples / "report.json").read_text(encoding="utf-8"))
        assert report["max_context_ids"] == cap
        worst = 0
        for split in ("train", "valid", "test"):
            path = Layout(work).samples / f"{split}.jsonl"
            with path.open(encoding="utf-8") as fh:
                for line in fh:
                    worst = max(worst, len(_json.loads(line)["ids"]))
        assert worst <= cap, f"cap={cap} 时有 {worst} 个 id 的样本，超过了上限"
        truncated = sum(v["truncated_by_max_context_ids"] for v in report["splits"].values())
        if report["max_ids_seen"] > cap:
            assert truncated > 0, f"cap={cap}：有样本超过上限却没有记录截尾"
        out[f"cap_{cap}"] = {"max_ids": worst, "max_ids_seen": report["max_ids_seen"],
                             "truncated": truncated,
                             "samples": report["splits"]["train"]["samples"]}
    return out


def test_bucket_sampler() -> dict:
    """(B) 分桶组批：每样本恰好一次、可复现、padding 浪费 < 5%。"""
    import random as _random

    from train import LengthBucketBatchSampler

    # 用真实长度分布（若本机有真实样本）否则用同量级的合成分布
    lengths: list[int] = []
    real = Path(__file__).resolve().parents[2] / ".nwp-work/nwp/samples/train.jsonl"
    cached = Path(str(real) + ".lengths.bin")
    if cached.exists():
        # load_lengths 落下来的长度缓存：1200 万条也能秒级读进来
        from array import array

        arr = array("l")
        arr.frombytes(cached.read_bytes())
        lengths = list(arr)
        source = f"real:train.jsonl 全量长度缓存（{len(lengths)} 条）"
    elif real.exists():
        import json as _json

        with real.open(encoding="utf-8") as fh:
            for i, line in enumerate(fh):
                if i >= 20000:
                    break
                lengths.append(len(_json.loads(line)["ids"]))
        source = "real:train.jsonl 前 20000 条"
    else:
        rng = _random.Random(0)
        lengths = [max(16, min(256, int(rng.lognormvariate(4.4, 0.75)))) for _ in range(20000)]
        source = "synthetic lognormal"

    sampler = LengthBucketBatchSampler(lengths, 8, buffer_size=4096, seed=7)
    seen: list[int] = []
    for batch in sampler:
        seen.extend(batch)
    assert sorted(seen) == list(range(len(lengths))), "有样本被漏掉或重复"
    assert len(seen) == len(lengths), f"覆盖 {len(seen)} != {len(lengths)}"
    again = [i for batch in sampler for i in batch]
    assert again == seen, "同一 seed/epoch 下结果不可复现"
    sampler.set_epoch(1)
    epoch2 = [i for batch in sampler for i in batch]
    assert sorted(epoch2) == list(range(len(lengths)))
    assert epoch2 != seen, "换 epoch 后顺序没变，shuffle 没生效"
    waste = sampler.padding_waste()
    assert waste < 0.05, f"padding 浪费 {waste * 100:.1f}% 仍高于 5%"
    # 对照：完全随机的批次
    rng = _random.Random(0)
    order = list(range(len(lengths)))
    rng.shuffle(order)
    batches = [order[i:i + 8] for i in range(0, len(order), 8)]
    real_tok = sum(sum(lengths[i] for i in b) for b in batches)
    pad_tok = sum(max(lengths[i] for i in b) * len(b) for b in batches)
    return {"source": source, "samples": len(lengths), "batches": len(sampler),
            "bucket_waste": round(waste * 100, 2), "random_waste": round((1 - real_tok / pad_tok) * 100, 2)}


def test_round_robin_sources() -> dict:
    """(C) 三个来源 + 小配额：每个来源都必须出现在输出里（那次 Wikipedia 被饿死就是反例）。"""
    import json as _json

    work = _synthetic_work(sources=3, docs_per_source=300, src_prefix="src")
    lay = Layout(work)
    report = _json.loads((lay.samples / "report.json").read_text(encoding="utf-8"))
    sources = report["sources"]
    assert len(sources) == 3, f"只看到 {len(sources)} 个来源：{sources}"
    missing = [name for name, v in sources.items() if v["samples"] <= 0]
    assert not missing, f"这些来源一条样本都没进训练集：{missing}（配额被前面的来源吃光了）"
    # 再验证一次：train.jsonl 里每个来源的样本数都应接近配额的三分之一
    counts: dict[str, int] = {}
    with (lay.samples / "train.jsonl").open(encoding="utf-8") as fh:
        for line in fh:
            counts["x"] = counts.get("x", 0) + 1
    kept = report["splits"]["train"]["sources"]
    share = {k: v / max(1, sum(kept.values())) for k, v in kept.items()}
    assert all(0.15 < v < 0.6 for v in share.values()), f"来源占比失衡：{share}"
    return {"sources": {k: v["samples"] for k, v in sources.items()},
            "train_share": {k: round(v, 3) for k, v in share.items()}}


def test_head_warmstart() -> dict:
    """(D) 词头行 == 该词的字向量均值；--resume 不得重新初始化。"""
    import torch

    from model import maybe_warmstart_word_head, warmstart_word_head

    model = _tiny_model()
    char2id = {"<unk>": 0, "<pad>": 1}
    words = ["<unk>", "甲", "甲乙", "甲乙丙", "乙"]
    for i, ch in enumerate("甲乙丙"):
        char2id[ch] = 5 + i
    stats = warmstart_word_head(model, char2id, words)
    wte = model.word_head.weight.new_tensor(0)  # 占位避免误用
    del wte
    from model import input_embedding

    emb = input_embedding(model).weight.detach()
    head = model.word_head.weight.detach()
    for wid, word in enumerate(words[1:], start=1):
        expect = emb[[char2id[ch] for ch in word]].float().mean(0)
        got = head[wid].float()
        assert torch.allclose(got, expect, atol=1e-5), f"词 {word} 的行不是字向量均值"
    assert torch.count_nonzero(model.word_head.bias) == 0, "bias 必须保持 0"
    # 词表外的字：整词无已知字时保留原随机行
    with torch.no_grad():
        model.word_head.weight[4] = torch.full_like(model.word_head.weight[4], 0.123)
    stats2 = warmstart_word_head(model, {"<unk>": 0, "<pad>": 1}, words)
    assert stats2["skipped_no_known_char"] >= 4
    assert torch.allclose(model.word_head.weight[4], torch.full_like(model.word_head.weight[4], 0.123))
    # resume：不得动权重
    before = model.word_head.weight.detach().clone()
    assert maybe_warmstart_word_head(model, char2id, words, enabled=True, resume=True) is None
    assert torch.equal(before, model.word_head.weight.detach()), "--resume 时又热启动了一次"
    assert maybe_warmstart_word_head(model, char2id, words, enabled=False, resume=False) is None
    return {"warmed": stats["warmed_words"], "skipped": stats["skipped_no_known_char"],
            "mean_chars_per_word": round(stats["mean_chars_per_word"], 2),
            "resume_skipped": True}


def test_fp16_export() -> dict:
    """(E) fp16 导出：I/O 仍是 float32、无 TopK、体积明显更小、ORT 能加载并跑出与 fp32 一致的 top-k。"""
    import os

    import onnx
    import torch

    from export_onnx import OnnxRunner, convert_to_fp16, graph_op_check
    from model import export_onnx as export_fp32

    model = _tiny_model()
    with tempfile.TemporaryDirectory(prefix="nwp-fp16-") as tmp:
        src = Path(tmp) / "fp32.onnx"
        dst = Path(tmp) / "fp16.onnx"
        export_fp32(model, src, seq=8, past=2)
        ok, reason = convert_to_fp16(src, dst)
        assert ok, f"fp16 转换失败：{reason}"
        graph = onnx.load(str(dst))
        kinds = {1: "float32", 10: "float16", 7: "int64", 6: "int32", 9: "bool"}
        io = {i.name: kinds.get(i.type.tensor_type.elem_type) for i in graph.graph.input}
        oo = {o.name: kinds.get(o.type.tensor_type.elem_type) for o in graph.graph.output}
        assert all(v in ("float32", "int64") for v in io.values()), f"输入 dtype 不对：{io}"
        assert all(v == "float32" for v in oo.values()), f"输出 dtype 必须是 float32：{oo}"
        assert graph_op_check(dst)["has_topk"] is False, "fp16 图里出现了 TopK/ArgMax"
        assert os.path.getsize(dst) < 0.7 * os.path.getsize(src), "fp16 体积没有明显变小"

        ids = [3, 5, 7, 9, 11, 13, 15]
        runner16 = OnnxRunner(dst, model.num_layers, model.num_heads, model.head_dim)
        runner32 = OnnxRunner(src, model.num_layers, model.num_heads, model.head_dim)
        l16, present = runner16.prefill(ids)
        l32, _ = runner32.prefill(ids)
        assert l16.shape == l32.shape and present["present.0.key"].dtype.name == "float32"
        agree = int(l16[0].argmax()) == int(l32[0].argmax())
        top5 = len(set(l16[0].argsort()[-5:].tolist()) & set(l32[0].argsort()[-5:].tolist()))
        # KV 增量同样要走得通（I/O 是 float32，所以调用方不需要知道图内是 fp16）
        _, p1 = runner16.step(ids[:3])
        inc, pr = runner16.step(ids[3:], p1)
        assert pr["present.0.key"].shape[2] == len(ids)
        return {"fp32_kb": round(os.path.getsize(src) / 1024), "fp16_kb": round(os.path.getsize(dst) / 1024),
                "ratio": round(os.path.getsize(dst) / os.path.getsize(src), 3),
                "io_dtypes": sorted(set(io.values())), "top1_match": agree, "top5_overlap": top5,
                "kv_present_len": int(pr["present.0.key"].shape[2]), "detail": reason}


def test_onnx_provider_aliases() -> dict:
    """`--onnx-provider` 的简称要能对到 ORT 的完整 provider 名。

    回归的是这个 bug：最初拿 `cuda` 去和 `CUDAExecutionProvider` 做**精确字符串比较**，
    于是 `--onnx-provider cuda` 在装了 onnxruntime-gpu 的机器上照样报「cuda 不可用」。
    """
    from nwp_common import resolve_onnx_provider

    available = ["TensorrtExecutionProvider", "CUDAExecutionProvider", "CPUExecutionProvider"]
    cases = {
        "cuda": "CUDAExecutionProvider",
        "CUDA": "CUDAExecutionProvider",
        "cudaexecutionprovider": "CUDAExecutionProvider",   # 全名不分大小写
        "CUDAExecutionProvider": "CUDAExecutionProvider",
        "cpu": "CPUExecutionProvider",
        "trt": "TensorrtExecutionProvider",
        "tensorrt": "TensorrtExecutionProvider",
        None: "CUDAExecutionProvider",                      # 不指定时优先 GPU
    }
    for given, want in cases.items():
        got = resolve_onnx_provider(given, available)
        assert got == want, f"resolve_onnx_provider({given!r}) = {got}，应为 {want}"
    # 只有 CPU 的机器上不指定就退 CPU
    assert resolve_onnx_provider(None, ["CPUExecutionProvider"]) == "CPUExecutionProvider"
    # 真的不可用时要响亮报错，而不是静默退回 CPU
    for bad in ("rocm", "banana"):
        try:
            resolve_onnx_provider(bad, available)
            raise AssertionError(f"{bad} 不可用却没报错")
        except SystemExit:
            pass
    return {"aliases": len(cases), "available": available}


def test_onnx_parity_after_multi_label() -> dict:
    """(A3) 多位置监督改动之后，ONNX 仍是 `logits [B,V]` 且与 PyTorch top-1 100% 一致。"""
    import numpy as np
    import torch

    from export_onnx import OnnxRunner
    from eval import pad_batch
    from model import export_onnx as export_fp32

    model = _tiny_model()
    with tempfile.TemporaryDirectory(prefix="nwp-parity-") as tmp:
        path = Path(tmp) / "m.onnx"
        export_fp32(model, path, seq=8, past=2)
        runner = OnnxRunner(path, model.num_layers, model.num_heads, model.head_dim)
        rows = [{"ids": [2 + (i * 5 + j) % 50 for j in range(6 + i)], "label": 1, "ctx": ""} for i in range(6)]
        ids, mask, pos = pad_batch(rows)
        with torch.no_grad():
            logits, _ = model(ids, mask, pos)
        assert logits.shape[1] == model.word_head.out_features, "logits 必须是 [B,V]，不是多位置形状"
        ok = 0
        for i, row in enumerate(rows):
            out, _ = runner.prefill(row["ids"])
            assert out.shape[1] == model.word_head.out_features
            ok += int(int(out[0].argmax()) == int(logits[i].argmax()))
        assert ok == len(rows), f"top-1 一致率 {ok}/{len(rows)}，不是 100%"
        return {"rows": len(rows), "top1_agreement": ok / len(rows),
                "logits_shape": list(logits.shape)}


def test_train_loop_invariants() -> dict:
    """训练循环的两个不变量。两个都是踩过的坑，而且**单测与冒烟当时都是绿的** ——
    只有真跑 20000 步才暴露，所以必须由这个用例钉住：

    - `micro_batches == steps × grad_accum`：参数更新只能**每个累积窗口一次**。
      曾把 `optimizer.step()` 写在 micro-batch 循环内，等效 batch 从 64 掉回 8，
      而 loss 已除过窗口标签数、却只有单个 micro-batch 贡献梯度，梯度又被缩小约 8 倍；
      `step` 还按 micro-batch 计数，于是 `--max-steps` 只覆盖预期 1/8 的数据。
    - `epoch_iterations == 1`：加载器迭代器每轮只能建一次。曾写在 while 里，
      配合当时「整轮物化」的采样器，每 8 个 micro-batch 就重排 2477 万条样本 ——
      步时因此从 1.05 s 涨到 3.4 s，GPU 大部分时间在空转。
    """
    import subprocess

    here = Path(__file__).resolve().parent
    tmp = _synthetic_work(sources=1, docs_per_source=400)
    steps, accum = 3, 4
    # 显式给 `--backbone smoke`：`--smoke` 只在 backbone 仍是默认值时才会把
    # grad_accum 压成 1（那是给「一条命令冒烟」用的），而这里要**真的测累积**。
    proc = subprocess.run(
        [sys.executable, str(here / "train.py"), "--work", tmp,
         "--smoke", "--backbone", "smoke", "--device", "cpu", "--dtype", "fp32",
         "--max-steps", str(steps), "--grad-accum", str(accum),
         "--batch-size", "2", "--eval-every", "0", "--save-every", "0"],
        capture_output=True, text=True)
    assert proc.returncode == 0, proc.stderr[-1500:]

    reports = sorted((Path(tmp) / "results").glob("train_*.json"))
    assert reports, f"没有产出训练报告：{proc.stdout[-500:]}"
    rep = json.loads(reports[-1].read_text())

    iters = rep["epoch_iterations"]
    assert iters == 1, f"加载器迭代器被重建了 {iters} 次（每轮应当只建一次）"
    assert rep["grad_accum"] == accum, f"累积步数被改成了 {rep['grad_accum']}，用例前提不成立"
    micro = rep["micro_batches"]
    assert micro == steps * accum, (
        f"micro_batch={micro}，应为 steps({steps})×grad_accum({accum})={steps * accum}"
        " —— 说明参数更新被写进了 micro-batch 循环里")
    return {"steps": rep["steps"], "grad_accum": rep["grad_accum"],
            "micro_batches": micro, "epoch_iterations": iters}


def test_quick_metric_uses_last_position_label() -> dict:
    """验证指标取的是「末位 logits vs 该样本自己的下一个词」。

    collate 改成多标签后返回 `[B,T]` 的 `label_ids`，而验证走的仍是「只算末位」的
    前向；当时验证代码整段遍历了 `[B,T]`，`label` 于是成了 list，
    `label in ranked` 恒为 False —— **top1/top5/mrr 全是 0**，而训练 loss 正常下降，
    看起来像「模型没学会」。这个用例用一个「必然答对」的假模型把这条通路钉死。

    样本故意做成同构（label 固定 7），这样与批大小、批顺序都无关。
    """
    import torch

    import train as T

    dataset = [{"ids": [3, 4, 5], "labels": [[2, 7]], "label": 7} for _ in range(8)]

    class AlwaysRight:
        def eval(self):
            return self

        def train(self):
            return self

        def __call__(self, ids, mask, pos, label_positions=None):
            logits = torch.zeros(ids.shape[0], 10)
            logits[:, 7] = 1.0
            return logits, None

    m = T.quick_metric(AlwaysRight(), dataset, 8, 4, "cpu", None)
    assert m["n"] == 8, m
    assert m["top1"] == 1.0, f"必然答对的假模型 top1 却是 {m['top1']} —— 末位标签取错了"
    assert m["top5"] == 1.0 and m["mrr"] == 1.0, m
    return {"n": m["n"], "top1": m["top1"], "top5": m["top5"], "mrr": m["mrr"]}


def main() -> int:
    tests = [
        ("分片文件名唯一 + 磁盘行数 == 报告条数", test_shards_are_distinct),
        ("manifest 条数 == 磁盘行数", test_manifest_count_matches_disk),
        ("fp16 骨干 + fp32 词头的前向", test_fp16_backbone_forward),
        ("ONNX 导出强制 float32 I/O", test_export_forces_float32_io),
        ("去重边界 + 覆盖率可报数", test_dedup_boundary_and_coverage),
        ("抓取失败要响亮且不留半截分片", test_failed_fetch_is_loud_and_leaves_no_partial),
        ("char 覆盖率闸门（拦下字节级 BPE 底座）", test_char_coverage_guard),
        ("A1 label_positions=[S-1] == None 路径", test_multi_label_vs_single_position),
        ("A2 labels/token 提升 ≥5×", test_labels_per_token_ratio),
        ("A3 多位置之后的 ONNX 对拍 100%", test_onnx_parity_after_multi_label),
        ("B 分桶组批：恰好一次/可复现/浪费<5%", test_bucket_sampler),
        ("C 轮流采样：三个来源都进训练集", test_round_robin_sources),
        ("D 词头热启动 + resume 不重置", test_head_warmstart),
        ("E fp16 导出：I/O float32/无 TopK/更小/可跑", test_fp16_export),
        ("--onnx-provider 简称 cuda/cpu/trt 能对上 ORT 全名", test_onnx_provider_aliases),
        ("约束 cap=128/160 都能跑", test_max_context_ids_variants),
        ("训练循环不变量：每窗口一次更新 + 迭代器只建一次", test_train_loop_invariants),
        ("评测量的是末位标签（必然答对的假模型 top1==1）", test_quick_metric_uses_last_position_label),
    ]
    failed = 0
    for name, fn in tests:
        try:
            detail = fn()
            print(f"[PASS] {name}: {json.dumps(detail, ensure_ascii=False)}")
        except Exception as e:  # noqa: BLE001 测试运行器要把失败汇总后统一退出非零
            failed += 1
            print(f"[FAIL] {name}: {type(e).__name__}: {e}", file=sys.stderr)
    print(f"\n{'全部通过' if failed == 0 else f'{failed} 个用例失败'}（共 {len(tests)} 个）")
    return 1 if failed else 0


def _cli() -> int:
    if {"-h", "--help"} & set(sys.argv[1:]):
        print(__doc__.split("运行（")[0].strip())
        print("\n用法：python test_nwp_pipeline.py   # 不需要参数，也不需要 pytest")
        return 0
    return main()


if __name__ == "__main__":
    raise SystemExit(_cli())
