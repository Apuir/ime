#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""``build_phrase_index.py`` 的单元测试。

只用合成数据 + 标准库：测试不该联网、也不该依赖仓里的 65 MB ``resource.zip``。
真实数据的验收交给 ``verify.py``。

运行：``python3 scripts/phrase-index/test_phrase_index.py``
"""

from __future__ import annotations

import gzip
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

import build_phrase_index as bpi  # noqa: E402


def build_tiny_index(path: Path, max_bytes: int = 0) -> dict[str, list[tuple[str, int]]]:
    """用合成的「成语 + 歇后语 + 诗句」造一份小索引，走真实的生产函数。"""
    records = [
        *bpi.prefix_records("idiom", "一心一意", bpi.TIER["idiom"] + bpi.fame(500)),
        *bpi.prefix_records("idiom", "一心二用", bpi.TIER["idiom"] + bpi.fame(20)),
        *bpi.pair_records("xiehouyu", "泥菩萨过江", "自身难保", bpi.TIER["xiehouyu"]),
        *bpi.pair_records("poem_pair", "床前明月光", "疑是地上霜",
                          bpi.TIER["poem_pair"] + bpi.fame(181)),
        # 同一句同时也是「单句前缀补全」的素材，用来验证最长匹配优先
        *bpi.prefix_records("shici", "床前明月光", bpi.TIER["shici"]),
        # 超长短语：键必须被截到 16 码点，且不能出现长度 ≥ 短语本身的键
        *bpi.prefix_records("poemline", "一二三四五六七八九十一二三四五六七八", 123),
    ]
    kept, _counts, _bytes, _dropped = bpi.prune(bpi.dedupe(records), max_bytes)
    kept.sort(key=lambda r: (r[1], -r[3], r[2]))
    bpi.write_index(kept, path)
    return bpi.load_index(path)


class TestKeyGeneration(unittest.TestCase):
    def test_prefix_rule(self):
        recs = list(bpi.prefix_records("idiom", "一心一意", 700_000))
        self.assertEqual([r[1] for r in recs], ["一心", "一心一"])
        self.assertTrue(all(r[2] == "一心一意" for r in recs))

    def test_prefix_rule_needs_three_chars(self):
        # 两字短语没有 2..L-1 的前缀，不该产出任何键
        self.assertEqual(list(bpi.prefix_records("idiom", "一心", 700_000)), [])

    def test_prefix_rule_caps_key_length(self):
        phrase = "一二三四五六七八九十一二三四五六七八"  # 20 字
        recs = list(bpi.prefix_records("poemline", phrase, 123))
        lengths = [len(r[1]) for r in recs]
        self.assertEqual(lengths, list(range(2, 17)))
        self.assertNotIn(len(phrase), lengths)

    def test_pair_rule(self):
        recs = list(bpi.pair_records("xiehouyu", "泥菩萨过江", "自身难保", 600_000))
        self.assertEqual(recs, [("xiehouyu", "泥菩萨过江", "自身难保", 600_000)])

    def test_pair_rule_truncates_long_upper(self):
        upper = "一二三四五六七八九十一二三四五六七八"  # 20 字
        recs = list(bpi.pair_records("poem_pair", upper, "下句", 400_000))
        self.assertEqual(recs[0][1], upper[:16])
        self.assertEqual(len(recs[0][1]), 16)

    def test_pair_rule_skips_one_char_key(self):
        self.assertEqual(list(bpi.pair_records("poem_pair", "光", "下句", 1)), [])
        self.assertEqual(list(bpi.pair_records("poem_pair", "上句", "", 1)), [])

    def test_sanitize_removes_tab_and_newline(self):
        self.assertEqual(bpi.sanitize("上\t句\n"), "上句")
        self.assertEqual(bpi.sanitize("  "), "")


class TestParsers(unittest.TestCase):
    def test_parse_thuocl(self):
        text = "坚定不移 \t 54113\n存亡绝续\t21\n坏行\n只有一列\n"
        self.assertEqual(list(bpi.parse_thuocl(text)), [("坚定不移", 54113), ("存亡绝续", 21)])

    def test_parse_code_word(self):
        self.assertEqual(list(bpi.parse_code_word("aabq\t傲岸不群\n")), ["傲岸不群"])

    def test_parse_rime_dict(self):
        text = "---\nname: shici\n...\n床前明月光\tchuáng qián\t10\n# 注释\n"
        self.assertEqual(list(bpi.parse_rime_dict(text)), [("床前明月光", 10)])

    def test_iter_paragraph_groups_handles_nested_content(self):
        node = [{"type": "五言绝句", "content": [
            {"chapter": "行宫", "paragraphs": ["寥落古行宫，宫花寂寞红。"]}]}]
        self.assertEqual(list(list(bpi.iter_paragraph_groups(node))[0]), ["寥落古行宫，宫花寂寞红。"])

    def test_iter_paragraph_groups_keeps_anthology_poems_apart(self):
        # 选集顶层对象不能当配对边界，否则会把上一首的末句接到下一首的首句
        node = {"title": "唐诗三百首", "content": [
            {"chapter": "行宫", "paragraphs": ["寥落古行宫，宫花寂寞红。"]},
            {"chapter": "登鹳雀楼", "paragraphs": ["白日依山尽，黄河入海流。"]}]}
        groups = list(bpi.iter_paragraph_groups(node))
        self.assertEqual(len(groups), 2)
        self.assertEqual(groups[1], ["白日依山尽，黄河入海流。"])

    def test_poem_pair_records_pairs_across_couplets(self):
        # 一首诗内部跨联也要配：上联末句 → 下联首句
        group = ["床前明月光，疑是地上霜。", "举头望明月，低头思故乡。"]
        recs = list(bpi.poem_pair_records("poem_pair", group, {"疑是地上霜": 81},
                                          bpi.Simplifier([])))
        self.assertEqual([(r[1], r[2]) for r in recs],
                         [("床前明月光", "疑是地上霜"),
                          ("疑是地上霜", "举头望明月"),
                          ("举头望明月", "低头思故乡")])
        # 命中名句表的那句，它前后的两条边都拿到同一个加成
        self.assertEqual({r[3] for r in recs},
                         {bpi.TIER["poem_pair"] + bpi.fame(81), bpi.TIER["poem_pair"]})

    def test_split_sentences(self):
        self.assertEqual(list(bpi.split_sentences("床前明月光，疑是地上霜。")),
                         ["床前明月光", "疑是地上霜"])
        self.assertEqual(list(bpi.split_sentences("「学而时习之，不亦说乎？」")),
                         ["学而时习之", "不亦说乎"])

    def test_simplifier(self):
        simp = bpi.Simplifier([{"覺": "觉", "曉": "晓"}, {"春眠不覺曉": "春眠不觉晓"}])
        self.assertEqual(simp("春眠不覺曉"), "春眠不觉晓")  # 长词优先
        self.assertEqual(simp("不覺"), "不觉")
        self.assertEqual(simp("已经简体"), "已经简体")

    def test_parse_size(self):
        self.assertEqual(bpi.parse_size("24M"), 24 << 20)
        self.assertEqual(bpi.parse_size("512K"), 512 << 10)
        self.assertEqual(bpi.parse_size("0"), 0)


class TestIndexFile(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.path = Path(self.tmp.name) / "phrase_index.tsv.gz"
        self.index = build_tiny_index(self.path)
        with gzip.open(self.path, "rt", encoding="utf-8") as fh:
            self.lines = [l.rstrip("\n") for l in fh if l.strip()]
        self.records = [tuple(l.split("\t")) for l in self.lines]

    def tearDown(self):
        self.tmp.cleanup()

    def test_format_is_three_columns(self):
        for record in self.records:
            self.assertEqual(len(record), 3)
            key, value, weight = record
            self.assertTrue(key)
            self.assertTrue(value)
            self.assertGreaterEqual(int(weight), 1)

    def test_no_tab_or_newline_inside_fields(self):
        for key, value, _weight in self.records:
            for field in (key, value):
                self.assertNotIn("\t", field)
                self.assertNotIn("\n", field)
                self.assertNotIn("\r", field)

    def test_key_length_within_bounds(self):
        for key, _value, _weight in self.records:
            self.assertGreaterEqual(len(key), bpi.MIN_KEY_CHARS)
            self.assertLessEqual(len(key), bpi.MAX_KEY_CHARS)

    def test_sort_order(self):
        order = [(k, -int(w), v) for k, v, w in self.records]
        self.assertEqual(order, sorted(order))

    def test_prefix_completion(self):
        self.assertIn("一心", self.index)
        self.assertIn(("一心一意", bpi.TIER["idiom"] + bpi.fame(500)), self.index["一心"])
        self.assertIn(("一心二用", bpi.TIER["idiom"] + bpi.fame(20)), self.index["一心"])

    def test_pair_completion(self):
        self.assertEqual(self.index["泥菩萨过江"], [("自身难保", bpi.TIER["xiehouyu"])])
        _key, hits = bpi.lookup(self.index, "泥菩萨过江")
        self.assertEqual(hits, [("自身难保", bpi.TIER["xiehouyu"])])

    def test_longest_match_wins(self):
        # 「床前」和「床前明月光」都在表里；尾部是完整上句时必须命中配对而不是前缀补全
        self.assertIn("床前", self.index)
        key, hits = bpi.lookup(self.index, "他念道床前明月光")
        self.assertEqual(key, "床前明月光")
        self.assertEqual(hits, [("疑是地上霜", bpi.TIER["poem_pair"] + bpi.fame(181))])

    def test_lookup_returns_none_when_no_hit(self):
        self.assertEqual(bpi.lookup(self.index, "完全无关的一句话"), (None, None))

    def test_lookup_respects_short_tail(self):
        # 尾部不足 2 字时不该越界
        self.assertEqual(bpi.lookup(self.index, "一"), (None, None))


class TestPruneAndDedupe(unittest.TestCase):
    def test_dedupe_keeps_highest_weight(self):
        records = [("a", "k", "v", 10), ("b", "k", "v", 99), ("c", "k", "v", 50)]
        self.assertEqual(bpi.dedupe(records), [("b", "k", "v", 99)])

    def test_prune_drops_lowest_weight_first(self):
        records = [(f"s{i}", "键", f"值{i}", 100 + i) for i in range(10)]
        kept, _counts, _bytes, dropped = bpi.prune(records, 65)
        # 每条 13 字节，65 字节刚好放 5 条，且必须是权重最高的 5 条
        self.assertEqual([r[3] for r in kept], [105, 106, 107, 108, 109])
        self.assertEqual(dropped, {"s0": 1, "s1": 1, "s2": 1, "s3": 1, "s4": 1})

    def test_prune_zero_means_unlimited(self):
        records = [("s", "键", "值", 1)]
        kept, *_ = bpi.prune(records, 0)
        self.assertEqual(kept, records)

    def test_prune_is_deterministic(self):
        records = [(f"s{i}", f"键{i:02d}", "值", 5) for i in range(20)]
        first, *_ = bpi.prune(records, 300)
        shuffled = list(reversed(records))
        second, *_ = bpi.prune(shuffled, 300)
        self.assertEqual(sorted(r[1] for r in first), sorted(r[1] for r in second))


if __name__ == "__main__":
    unittest.main(verbosity=2)
