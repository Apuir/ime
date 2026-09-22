#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""对**已构建**的 ``phrase_index.tsv.gz`` 跑真实数据验收。

``test_phrase_index.py`` 用合成数据保证规则正确；这里用真语料保证**内容**正确：
「床前明月光」能出「疑是地上霜」、成语前缀能补全整个成语、歇后语谜面能出谜底，
外加全量格式校验（列数 / 键长 / 权重 / 行序 / 非法字符）。

用法
----
::

    python3 scripts/phrase-index/verify.py --work-dir .nwp-work
    python3 scripts/phrase-index/verify.py ~/.ime-phrase/phrase/phrase_index.tsv.gz
"""

from __future__ import annotations

import argparse
import gzip
import json
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

import build_phrase_index as bpi  # noqa: E402

# 验收样本：都取自公开语料里的条目本身，不手写答案
POEM_CHECK = ("静夜思里那句床前明月光", "床前明月光", "疑是地上霜")
IDIOM_CHECKS = ["一心一意", "画蛇添足", "守株待兔", "亡羊补牢", "一鼓作气", "胸有成竹", "画龙点睛"]
XIEHOUYU_CHECKS = [
    ("竹篮打水", "一场空"),
    ("哑巴吃黄连", "有苦说不出"),
    ("芝麻开花", "节节高"),
    ("八仙过海", "各显神通"),
    ("老鼠过街", "人人喊打"),
    ("铁公鸡", "一毛不拔"),
]
DEMO_TAILS = ["我们今天要一如既往", "他说话总是拐弯抹", "春眠不觉"]


def check_format(path: Path) -> tuple[int, int, list[str]]:
    """全量扫一遍：列数、键长、权重、非法字符、行序。返回 (行数, 未压缩字节, 问题列表)。"""
    problems: list[str] = []
    count = 0
    raw_bytes = 0
    prev_order: tuple[str, int, str] | None = None
    opener = gzip.open if path.suffix == ".gz" else open
    with opener(path, "rt", encoding="utf-8") as fh:
        for lineno, line in enumerate(fh, 1):
            if not line.endswith("\n"):
                problems.append(f"{lineno}: 最后一行没有换行符")
            raw_bytes += len(line.encode("utf-8"))
            body = line.rstrip("\n")
            if not body:
                problems.append(f"{lineno}: 空行")
                continue
            parts = body.split("\t")
            if len(parts) != 3:
                problems.append(f"{lineno}: 不是三列（{len(parts)} 列）")
                continue
            key, value, weight_text = parts
            count += 1
            if not (bpi.MIN_KEY_CHARS <= len(key) <= bpi.MAX_KEY_CHARS):
                problems.append(f"{lineno}: 键长 {len(key)} 越界: {key!r}")
            if not value:
                problems.append(f"{lineno}: value 为空")
            if not weight_text.lstrip("-").isdigit() or int(weight_text) < 1:
                problems.append(f"{lineno}: 权重非法: {weight_text!r}")
                continue
            order = (key, -int(weight_text), value)
            if prev_order is not None and order < prev_order:
                problems.append(f"{lineno}: 行序错误: {order!r} 在 {prev_order!r} 之后")
            prev_order = order
            if len(problems) > 20:
                problems.append("...（问题过多，已截断）")
                break
    return count, raw_bytes, problems


def show(index, tail: str) -> bool:
    key, hits = bpi.lookup(index, tail)
    if not hits:
        print(f"    {tail!r} → （无命中）")
        return False
    top = "、".join(f"{value}({weight})" for value, weight in hits[:4])
    print(f"    {tail!r} → 命中键 {key!r}: {top}")
    return True


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="phrase_index.tsv.gz 真实验收")
    parser.add_argument("index", nargs="?", type=Path,
                        help="索引路径（默认 <work-dir>/phrase/phrase_index.tsv.gz）")
    parser.add_argument("--work-dir", type=Path, default=bpi.DEFAULT_WORK_DIR,
                        help=f"工作目录，用于定位默认索引与 manifest（默认 {bpi.DEFAULT_WORK_DIR}）")
    args = parser.parse_args(argv)

    path: Path = args.index or args.work_dir.expanduser() / "phrase/phrase_index.tsv.gz"
    if not path.exists():
        print(f"找不到索引: {path}", file=sys.stderr)
        return 2

    index = bpi.load_index(path)
    gz_bytes = path.stat().st_size

    print(f"索引      : {path}")
    print(f"键数      : {len(index):,}")
    print(f"gzip 体积 : {gz_bytes / 1024 / 1024:.2f} MiB")

    failures: list[str] = []

    print("\n[1] 格式校验（全量）")
    count, raw_bytes, problems = check_format(path)
    print(f"    记录 {count:,} 条，未压缩 {raw_bytes / 1024 / 1024:.2f} MiB")
    if problems:
        failures.append("格式校验")
        for line in problems:
            print(f"    ✗ {line}")
    else:
        print("    ✓ 三列 / 键长 2..16 / 权重 ≥1 / 行序 / 无空行，全部通过")

    print("\n[2] 诗句「上句→下句」")
    tail, upper, lower = POEM_CHECK
    _key, hits = bpi.lookup(index, tail)
    values = [v for v, _w in (hits or [])]
    if lower in values:
        print(f"    ✓ {upper} → {lower}（尾部整串 {tail!r} 下取到）")
    else:
        failures.append("诗句配对")
        print(f"    ✗ {upper} 没有给出 {lower}，实际: {values[:5]}")

    print("\n[3] 成语前缀补全（输入前 L-1 字应补出整个成语）")
    for idiom in IDIOM_CHECKS:
        prefix = idiom[:-1]
        _key, hits = bpi.lookup(index, prefix)
        values = [v for v, _w in (hits or [])]
        if idiom in values:
            print(f"    ✓ {prefix} → {idiom}({dict(hits or [])[idiom]})")
        else:
            failures.append(f"成语 {idiom}")
            print(f"    ✗ {prefix} → 没有 {idiom}，实际: {values[:5]}")

    print("\n[4] 歇后语「谜面→谜底」")
    for riddle, answer in XIEHOUYU_CHECKS:
        _key, hits = bpi.lookup(index, riddle)
        values = [v for v, _w in (hits or [])]
        ok = any(answer in v for v in values)
        if ok:
            print(f"    ✓ {riddle} → {values[0]}({dict(hits)[values[0]]})")
        else:
            failures.append(f"歇后语 {riddle}")
            print(f"    ✗ {riddle} → 没有包含 {answer} 的候选，实际: {values[:5]}")

    print("\n[5] 其他抽样")
    for tail in DEMO_TAILS:
        show(index, tail)

    manifest_path = path.with_suffix("").with_suffix(".manifest.json")
    print("\n[6] 来源构成")
    if manifest_path.exists():
        manifest = json.loads(manifest_path.read_text("utf-8"))
        print(f"    {'来源':<34}{'记录':>12}{'未压缩':>15}{'被剪':>12}")
        for source, info in manifest["sources"].items():
            if not info["records"] and not info["dropped"]:
                continue
            print(f"    {bpi.pad(info['label'], 34)}{info['records']:>12,}"
                  f"{info['uncompressed_bytes'] / 1024 / 1024:>9.2f} MiB{info['dropped']:>12,}")
        print(f"    {bpi.pad('合计', 34)}{manifest['entries']:>12,}"
              f"{manifest['uncompressed_bytes'] / 1024 / 1024:>9.2f} MiB"
              f"{sum(i['dropped'] for i in manifest['sources'].values()):>12,}")
        print(f"    诗句配对：原始 {manifest['poem_pairs_raw']:,} → 去重 "
              f"{manifest['poem_pairs_distinct']:,}")
    else:
        print(f"    （没有 {manifest_path.name}，跳过；它由 build 脚本写出）")

    print()
    if failures:
        print(f"验收失败 {len(failures)} 项: {', '.join(failures)}")
        return 1
    print("验收通过。")
    return 0


if __name__ == "__main__":
    sys.exit(main())
