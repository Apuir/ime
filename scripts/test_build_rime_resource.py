#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""``build-rime-resource.py`` 的单元测试。

跑法（两种都行）::

    python3 scripts/test_build_rime_resource.py
    python3 -m unittest scripts.test_build_rime_resource

为什么值得单独测：这个脚本要往 65 MB 的方案数据里**注入** app 专属字段与模糊音规则，
注入错了不会立刻报错，而是表现为「键盘不随方案切换」「简繁设置失效」这类
上线后才发现的怪问题；而且它必须**幂等**（重复跑不能叠加）。这两点都在这里钉住。

测试只用最小 YAML 片段，不依赖真实的 65 MB 数据，所以能在任何机器上跑。
"""

from __future__ import annotations

import importlib.util
import sys
import unittest
from pathlib import Path

SCRIPTS_DIR = Path(__file__).resolve().parent


def _load_module():
    spec = importlib.util.spec_from_file_location(
        "build_rime_resource", SCRIPTS_DIR / "build-rime-resource.py"
    )
    assert spec and spec.loader
    module = importlib.util.module_from_spec(spec)
    sys.modules[spec.name] = module
    spec.loader.exec_module(module)
    return module


b = _load_module()


MINIMAL_SCHEMA = """# Rime schema
# encoding: utf-8
schema:
  schema_id: wanxiang
  name: 万象拼音
  version: "LTS"
  author:
    - amzxyz
  dependencies:
    - wanxiang_mixedcode

switches:
  - name: ascii_mode
    states: [中文, 英文]

translator:
  dictionary: wanxiang
  enable_completion: true
  max_homophones: 8

speller:
  alphabet: zyxwvutsrqponmlkjihgfedcba
  algebra:
    __patch:
      #- 模糊音
      - wanxiang_algebra:/base/全拼  #拼音转双拼码

context_reorder:
  db_name: context_reorder
  enable_fallback_reorder: false
"""


class InjectSchemaFieldsTest(unittest.TestCase):
    def test_adds_app_bridge_fields(self):
        out, injected = b.inject_schema_fields(MINIMAL_SCHEMA, "wanxiang")
        for key in ("layout", "punctuation", "kind", "candidateKind"):
            self.assertIn(f'  {key}: "', out)
        self.assertEqual(
            ["layout", "punctuation", "kind", "candidateKind"], injected
        )
        # 必须落在 schema 块内部：在 `author:` 之前，且缩进为两格
        self.assertLess(out.index("layout:"), out.index("author:"))

    def test_englishSchemaSkipsCandidateKind(self):
        # wanxiang_english 在升级前就没有 candidateKind，不能凭空造一个
        out, injected = b.inject_schema_fields(MINIMAL_SCHEMA, "wanxiang_english")
        self.assertNotIn("candidateKind:", out)
        self.assertIn("kind: \"English\"", out)
        self.assertEqual(["layout", "punctuation", "kind"], injected)

    def test_layoutFollowsScheme(self):
        t9, _ = b.inject_schema_fields(MINIMAL_SCHEMA, "wanxiang_t9")
        self.assertIn('layout: "T9"', t9)
        self.assertIn('candidateKind: "T9PinYin"', t9)

    def test_isIdempotent(self):
        once, _ = b.inject_schema_fields(MINIMAL_SCHEMA, "wanxiang")
        twice, _ = b.inject_schema_fields(once, "wanxiang")
        # 第二次要先剥离再注入，否则字段会叠两层
        self.assertEqual(once, twice)
        self.assertEqual(1, twice.count("layout:"))


class InjectFuzzyRulesTest(unittest.TestCase):
    def test_appendsAllPresetRules(self):
        out, count = b.inject_fuzzy_rules(MINIMAL_SCHEMA, "wanxiang", b.FUZZY_ALL)
        self.assertEqual(len(b.FUZZY_ALL), count)
        for rule in b.FUZZY_ALL:
            self.assertIn(f"- wanxiang_algebra:/{rule}", out)
        # 必须挂在 `base/全拼` 之后，缩进与它一致（6 空格）
        anchor = out.index("- wanxiang_algebra:/base/全拼")
        self.assertLess(anchor, out.index("- wanxiang_algebra:/模糊音_nl"))
        self.assertIn("\n      - wanxiang_algebra:/模糊音_nl", out)

    def test_emptyPresetLeavesTextUntouched(self):
        out, count = b.inject_fuzzy_rules(MINIMAL_SCHEMA, "wanxiang", [])
        self.assertEqual(0, count)
        self.assertEqual(MINIMAL_SCHEMA, out)

    def test_isIdempotent(self):
        once, _ = b.inject_fuzzy_rules(MINIMAL_SCHEMA, "wanxiang", b.FUZZY_ALL)
        twice, _ = b.inject_fuzzy_rules(once, "wanxiang", b.FUZZY_ALL)
        self.assertEqual(once, twice)
        self.assertEqual(1, twice.count("模糊音_nl"))

    def test_safePresetIsSubsetOfAll(self):
        self.assertTrue(set(b.FUZZY_SAFE).issubset(set(b.FUZZY_ALL)))
        self.assertLess(len(b.FUZZY_SAFE), len(b.FUZZY_ALL))

    def test_missingAnchorRaises(self):
        with self.assertRaises(ValueError):
            b.inject_fuzzy_rules("schema:\n  schema_id: x\n", "wanxiang", b.FUZZY_ALL)


class InjectOptionsBlockTest(unittest.TestCase):
    def test_insertedBeforeSwitches(self):
        out = b.inject_options_block(MINIMAL_SCHEMA, "wanxiang")
        self.assertIn("options:", out)
        self.assertLess(out.index("options:"), out.index("switches:"))
        # 恰好一个 options 顶层键
        self.assertEqual(1, out.count("\noptions:\n"))

    def test_lockFlagsFollowScheme(self):
        default_lock = b.inject_options_block(MINIMAL_SCHEMA, "wanxiang")
        # 拼音方案：简繁不锁（跟 app 设置走），英文模式锁定关闭
        self.assertIn("    key: s2t\n    keys: [s2s, s2t, s2hk, s2tw]\n    lock: false", default_lock)
        english_lock = b.inject_options_block(MINIMAL_SCHEMA, "wanxiang_english")
        self.assertIn("    keys: [s2s, s2t, s2hk, s2tw]\n    lock: true", english_lock)

    def test_abbreviationSwitchBridgesAllChineseSchemas(self):
        for schema_id in ("wanxiang", "wanxiang_t9", "wanxiang_t9i",
                          "wanxiang_mixedcode", "wanxiang_reverse"):
            out = b.inject_options_block(MINIMAL_SCHEMA, schema_id)
            self.assertIn("name: abbreviation_enabled", out, schema_id)
            self.assertIn("key: abbrev_disabled", out, schema_id)
        # 英文方案不注入：简拼是拼音方案的事
        self.assertNotIn("abbreviation_enabled", b.inject_options_block(
            MINIMAL_SCHEMA, "wanxiang_english"))

    def test_isIdempotent(self):
        once = b.inject_options_block(MINIMAL_SCHEMA, "wanxiang")
        twice = b.inject_options_block(once, "wanxiang")
        self.assertEqual(once, twice)


#: 九键方案的最小骨架：注释掉的简拼规则 + lua 简码表规则 + 简码开关。
MINIMAL_T9_SCHEMA = """# Rime schema
schema:
  schema_id: wanxiang_t9
  name: 万象九键
  version: "LTS"

switches:
  - name: ascii_mode
    states: [中文, 英文]
  - name: abbrev
    states: [简码关, 简码开]
    reset: 1

speller:
  alphabet: zyxwvutsrqponmlkjihgfedcba9876543210
  algebra:
    - xform/^n$/en/
    #- abbrev/^([a-z]).*/$1/
    - derive/^(.*)$/\\U$1/
    #- abbrev/^([A-Z]).*/$1/
    - xlit/ABCDEFGHIJKLMNOPQRSTUVWXYZ/22233344455566677778889999/

super_replacer:
  rules:
    - option: emoji
      mode: append
    - option: abbrev
      mode: abbrev
      abbrev_rule: "2,3"
      t9_optimization: true
      files:
        - lua/data/t9_abbrev.txt

translator:
  dictionary: wanxiang
  prism: wanxiang_t9
"""


class T9ShorthandTest(unittest.TestCase):
    def test_enablesBothAbbrevRulesAroundTheUppercaseDerive(self):
        out = b.inject_t9_abbrev(MINIMAL_T9_SCHEMA)
        lower = out.index("- abbrev/^([a-z]).*/$1/")
        upper_derive = out.index("- derive/^(.*)$/\\U$1/")
        upper = out.index("- abbrev/^([A-Z]).*/$1/")
        # 小写那条要在「派生大写」之前，大写那条要在之后（缩过的大写首字母再由
        # xlit 映射成数字键），顺序反了简拼就出不来数字码
        self.assertLess(lower, upper_derive)
        self.assertLess(upper_derive, upper)
        self.assertNotIn("#- abbrev/", out)
        self.assertIn(b.MARK_T9_ABBREV_BEGIN, out)
        self.assertIn(b.MARK_T9_ABBREV_UPPER_BEGIN, out)

    def test_insertsWhenSourceHasNoCommentedRule(self):
        # wanxiang_t9i 上游压根没写这两行注释，要按锚点插进去
        text = MINIMAL_T9_SCHEMA.replace("    #- abbrev/^([a-z]).*/$1/\n", "")
        text = text.replace("    #- abbrev/^([A-Z]).*/$1/\n", "")
        out = b.inject_t9_abbrev(text)
        self.assertIn("- abbrev/^([a-z]).*/$1/", out)
        self.assertIn("- abbrev/^([A-Z]).*/$1/", out)
        self.assertLess(out.index("- abbrev/^([a-z]).*/$1/"),
                        out.index("- derive/^(.*)$/\\U$1/"))

    def test_isIdempotent(self):
        once = b.inject_t9_abbrev(MINIMAL_T9_SCHEMA)
        twice = b.inject_t9_abbrev(once)
        self.assertEqual(once, twice)
        self.assertEqual(1, twice.count("- abbrev/^([a-z]).*/$1/"))

    def test_missingAnchorRaises(self):
        with self.assertRaises(ValueError):
            b.inject_t9_abbrev("schema:\n  schema_id: wanxiang_t9\n")

    def test_maxLengthSitsInSpellerBlock(self):
        out = b.inject_abbrev_max_length(MINIMAL_T9_SCHEMA, "wanxiang_t9")
        self.assertIn(f"  abbrev_max_length: {b.ABBREV_MAX_LENGTH}\n", out)
        lines = out.splitlines()
        start = lines.index("speller:")
        end = next(i for i in range(start + 1, len(lines))
                   if lines[i].strip() and not lines[i][0].isspace())
        self.assertIn("abbrev_max_length", "\n".join(lines[start:end]))

    def test_maxLengthIsIdempotent(self):
        once = b.inject_abbrev_max_length(MINIMAL_T9_SCHEMA, "wanxiang_t9")
        twice = b.inject_abbrev_max_length(once, "wanxiang_t9")
        self.assertEqual(once, twice)
        self.assertEqual(1, twice.count("abbrev_max_length:"))

    def test_retireAbbrevTableDisablesRuleAndDropsSwitch(self):
        out, retired = b.retire_t9_abbrev_table(MINIMAL_T9_SCHEMA, "wanxiang_t9")
        self.assertTrue(retired)
        # 规则改成永不开火：只删开关不够，switcher/save_options 会把旧的
        # abbrev: true 再读回来
        self.assertIn("- option: false", out)
        self.assertIn(b.MARK_T9_ABBREV_TABLE_OFF, out)
        # 其他规则不能跟着遭殃
        self.assertIn("- option: emoji", out)
        # 开关也要撤掉，否则面板上留一个不生效的「简码」
        self.assertNotIn("- name: abbrev", out)
        self.assertIn("- name: ascii_mode", out)

    def test_retireAbbrevTableIsIdempotent(self):
        once, _ = b.retire_t9_abbrev_table(MINIMAL_T9_SCHEMA, "wanxiang_t9")
        twice, retired = b.retire_t9_abbrev_table(once, "wanxiang_t9")
        self.assertFalse(retired)
        self.assertEqual(once, twice)

    def test_fullTransformOfT9SchemaIsStable(self):
        first, notes, warns = b.transform(
            "wanxiang_t9.schema.yaml", MINIMAL_T9_SCHEMA.encode(), b.FUZZY_SAFE
        )
        self.assertEqual([], warns)
        self.assertIn("abbrev_max_length", first.decode())
        self.assertIn("- abbrev/^([a-z]).*/$1/", first.decode())
        second, _, _ = b.transform(
            "wanxiang_t9.schema.yaml", first, b.FUZZY_SAFE
        )
        self.assertEqual(first, second)
        # 26 键方案不注入这两样：它的简拼是全量的，靠自己的 algebra 支撑
        plain, _, _ = b.transform(
            "wanxiang.schema.yaml", MINIMAL_SCHEMA.encode(), b.FUZZY_SAFE
        )
        self.assertNotIn("abbrev_max_length", plain.decode())
        self.assertNotIn("t9-abbrev", plain.decode())


class InjectSentenceOptionsTest(unittest.TestCase):
    def test_injectsIntoSchema(self):
        out, applied = b.inject_sentence_options(
            MINIMAL_SCHEMA, "wanxiang", 8, 0.5
        )
        self.assertTrue(applied)
        self.assertIn("  max_sentences: 8\n", out)
        self.assertIn("  sentence_cutoff_threshold: 0.5\n", out)
        # 必须落在 translator 块内部（缩进两格），且不能跑到别的顶层键下面
        lines = out.splitlines()
        start = lines.index("translator:")
        end = next(
            i for i in range(start + 1, len(lines))
            if lines[i].strip() and not lines[i][0].isspace()
        )
        block = "\n".join(lines[start:end])
        self.assertIn("max_sentences: 8", block)

    def test_defaultsMatchShippedValues(self):
        # 裸跑脚本必须复现出随包那一份，所以默认值要钉住
        self.assertEqual(8, b.DEFAULT_MAX_SENTENCES)
        self.assertEqual(0.5, b.DEFAULT_SENTENCE_CUTOFF)

    def test_disabledWhenMaxSentencesIsOne(self):
        out, applied = b.inject_sentence_options(MINIMAL_SCHEMA, "wanxiang", 1, 0.5)
        self.assertFalse(applied)
        self.assertNotIn("max_sentences:", out)
        self.assertEqual(MINIMAL_SCHEMA, out)

    def test_stripsPreviousInjectionWhenDisabled(self):
        once, _ = b.inject_sentence_options(MINIMAL_SCHEMA, "wanxiang", 8, 0.5)
        back, applied = b.inject_sentence_options(once, "wanxiang", 1, 0.5)
        self.assertFalse(applied)
        self.assertEqual(MINIMAL_SCHEMA, back)

    def test_isIdempotent(self):
        once, _ = b.inject_sentence_options(MINIMAL_SCHEMA, "wanxiang", 8, 0.5)
        twice, _ = b.inject_sentence_options(once, "wanxiang", 8, 0.5)
        self.assertEqual(once, twice)
        self.assertEqual(1, twice.count("max_sentences:"))

    def test_missingTranslatorBlockIsSkipped(self):
        # 不抛异常：没有 translator 的方案由调用方记警告，不能整包构建失败
        out, applied = b.inject_sentence_options("schema:\n  schema_id: x\n", "wanxiang", 8, 0.5)
        self.assertFalse(applied)
        self.assertEqual("schema:\n  schema_id: x\n", out)

    def test_nonPinyinSchemaNotInSentenceSchemas(self):
        self.assertNotIn("wanxiang_english", b.SENTENCE_SCHEMAS)
        self.assertIn("wanxiang", b.SENTENCE_SCHEMAS)


class MiscTransformTest(unittest.TestCase):
    def test_forceSchemaList(self):
        text = "schema_list:\n  - schema: wanxiang\nmenu:\n  page_size: 6\n"
        out = b.force_schema_list(text)
        for schema in b.APP_SCHEMA_LIST:
            self.assertIn(f"  - schema: {schema}", out)
        self.assertIn("menu:", out)
        self.assertEqual(1, out.count("schema_list:"))

    def test_enableFallbackReorder(self):
        out, changed = b.enable_fallback_reorder(MINIMAL_SCHEMA, "wanxiang")
        self.assertTrue(changed)
        self.assertIn("enable_fallback_reorder: true", out)
        self.assertNotIn("enable_fallback_reorder: false", out)
        again, changed_again = b.enable_fallback_reorder(out, "wanxiang")
        self.assertTrue(changed_again)
        self.assertEqual(out, again)

    def test_stripInjectionsRemovesEveryBlock(self):
        text = MINIMAL_SCHEMA
        text, _ = b.inject_schema_fields(text, "wanxiang")
        text, _ = b.inject_fuzzy_rules(text, "wanxiang", b.FUZZY_ALL)
        text = b.inject_options_block(text, "wanxiang")
        text, _ = b.inject_sentence_options(text, "wanxiang", 8, 0.5)
        stripped = b.strip_injections(text)
        for marker in (
            b.MARK_SCHEMA_BEGIN, b.MARK_OPTIONS_BEGIN,
            b.MARK_FUZZY_BEGIN, b.MARK_SENTENCE_BEGIN,
        ):
            self.assertNotIn(marker, stripped)
        self.assertNotIn("模糊音_nl", stripped)
        self.assertNotIn("options:", stripped)
        self.assertNotIn("layout:", stripped)
        self.assertNotIn("max_sentences", stripped)
        self.assertEqual(MINIMAL_SCHEMA, stripped)

    def test_fullTransformIsStableAcrossRuns(self):
        """最强的一条：把输出当输入再跑一次，结果必须逐字节一致。"""
        first, notes, warns = b.transform("wanxiang.schema.yaml", MINIMAL_SCHEMA.encode(), b.FUZZY_ALL)
        self.assertEqual([], warns)
        self.assertTrue(notes)
        self.assertIn("max_sentences: 8", first.decode())
        second, _, _ = b.transform("wanxiang.schema.yaml", first, b.FUZZY_ALL)
        self.assertEqual(first, second)

    def test_fullTransformCanTurnSentencesOff(self):
        out, _, warns = b.transform(
            "wanxiang.schema.yaml", MINIMAL_SCHEMA.encode(), b.FUZZY_ALL, 1, 0.5
        )
        self.assertEqual([], warns)
        self.assertNotIn("max_sentences", out.decode())

    def test_transformLeavesLuaAndDictsUntouched(self):
        payload = b"whatever"
        out, notes, _ = b.transform("lua/wanxiang/wanxiang.lua", payload, b.FUZZY_ALL)
        self.assertEqual(payload, out)
        self.assertEqual([], notes)

    def test_markPairsCoverEveryInjection(self):
        # 少一对标记，重复跑脚本就会把注入块叠两层
        for pair in (
            (b.MARK_T9_ABBREV_BEGIN, b.MARK_T9_ABBREV_END),
            (b.MARK_T9_ABBREV_UPPER_BEGIN, b.MARK_T9_ABBREV_UPPER_END),
            (b.MARK_ABBREV_MAX_LENGTH_BEGIN, b.MARK_ABBREV_MAX_LENGTH_END),
        ):
            self.assertIn(pair, b.MARK_PAIRS)

    def test_topLevelBlockBounds(self):
        lines = MINIMAL_SCHEMA.splitlines(keepends=True)
        bounds = b._top_level_block_bounds(lines, "schema")
        self.assertIsNotNone(bounds)
        start, end = bounds
        self.assertEqual("schema:", lines[start].strip())
        self.assertEqual("switches:", lines[end].strip())

    def test_isUserState(self):
        self.assertTrue(b.is_user_state(Path("predict.userdb/data.mdb")))
        self.assertTrue(b.is_user_state(Path("user.yaml")))
        self.assertTrue(b.is_user_state(Path("build/wanxiang.table.bin")))
        self.assertFalse(b.is_user_state(Path("wanxiang.schema.yaml")))
        self.assertFalse(b.is_user_state(Path("lua/data/abbrev.txt")))


class ValidateReferencesTest(unittest.TestCase):
    def test_missingImportTableIsFatal(self):
        # 只给 dict 文件、不给它 import 的 dicts/zi.dict.yaml
        plan = b.BuildPlan(
            files={"shared/wanxiang.dict.yaml": b"name: w\nimport_tables:\n  - dicts/zi\n"}
        )
        fatal, optional = b.validate_references(plan)
        self.assertIn("dicts/zi.dict.yaml", fatal)
        self.assertEqual([], optional)

    def test_optionalReferenceIsNotFatal(self):
        plan = b.BuildPlan(
            files={"shared/lua/wanxiang/super_tips.lua": b'local X = "lua/data/tips_user.txt"\n'}
        )
        fatal, optional = b.validate_references(plan)
        self.assertEqual([], fatal)
        self.assertIn("lua/data/tips_user.txt", optional)

    def test_presentReferencePasses(self):
        plan = b.BuildPlan(
            files={
                "shared/wanxiang.dict.yaml": b"import_tables:\n  - dicts/zi\n",
                "shared/dicts/zi.dict.yaml": b"name: zi\n",
            }
        )
        fatal, optional = b.validate_references(plan)
        self.assertEqual([], fatal)
        self.assertEqual([], optional)


if __name__ == "__main__":
    unittest.main(verbosity=2)
