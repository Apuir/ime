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


def main() -> int:
    tests = [
        ("分片文件名唯一 + 磁盘行数 == 报告条数", test_shards_are_distinct),
        ("manifest 条数 == 磁盘行数", test_manifest_count_matches_disk),
        ("fp16 骨干 + fp32 词头的前向", test_fp16_backbone_forward),
        ("ONNX 导出强制 float32 I/O", test_export_forces_float32_io),
        ("去重边界 + 覆盖率可报数", test_dedup_boundary_and_coverage),
        ("抓取失败要响亮且不留半截分片", test_failed_fetch_is_loud_and_leaves_no_partial),
        ("char 覆盖率闸门（拦下字节级 BPE 底座）", test_char_coverage_guard),
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
