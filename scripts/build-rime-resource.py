#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""重建 app/src/main/assets/resource.zip（Rime 方案数据包）。

背景
----
app 把 librime 当纯内核，方案数据（万象拼音）打包在 ``assets/resource.zip``，
运行时解压到 ``files/shared``。这个 zip **不入库**（65 MB+），所以必须有一个
可复现的脚本，否则换机器 / 换版本就没法重建。

本脚本做四件事：

1. 从「干净来源」（默认本机 fcitx5 的 rime 用户目录）按**白名单**取方案数据；
2. **注入 app 专属字段** —— ``schema/{layout,punctuation,kind,candidateKind}``
   与顶层 ``options:`` 块。这些是上游 danjian/ime 加的桥接字段，
   万象官方发布包**没有**，缺了会导致：键盘不随方案切换、简繁 / Emoji / 英文模式
   三个设置不跟 app 走；
3. **注入模糊音规则**（默认 6 组，`--fuzzy` 可换档）到 ``wanxiang.schema.yaml``；
4. 保留上一版 zip 里的 ``model/predict.marisa``（app 专用，来自上游），
   按 librime 要求的结构打包（根目录直接是 ``shared/`` 和 ``model/``）。

三类注入都用 ``# >>> ime:xxx`` / ``# <<< ime:xxx`` 注释成对包裹，
所以脚本**幂等**：重复执行、或把输出目录当来源再跑一次，结果都一致。

另外会做一次**引用校验**：扫描 yaml 的 ``import_tables`` / ``__include`` /
``files`` 与 lua 里的 ``lua/data/...`` 字面量，报告「被引用但没打包」的文件。
校验不过就退出，避免打出一个启动即报错的包。

用法
----
::

    # 只看看会做什么（不写文件）
    python3 scripts/build-rime-resource.py --dry-run

    # 正式重建（覆盖 app/src/main/assets/resource.zip）
    python3 scripts/build-rime-resource.py

    # 换来源 / 换输出 / 换 manifest
    python3 scripts/build-rime-resource.py --source /path/to/rime --out /tmp/r.zip

来源目录可以是：本机 fcitx5 的 rime 用户目录（默认）、万象官方发布包解出的目录，
或上一次的输出目录（解压后）。用户状态（``*.userdb`` / ``user.yaml`` /
``installation.yaml`` / ``build`` / ``sync`` / ``*.gram``）一律不进包。
"""

from __future__ import annotations

import argparse
import hashlib
import json
import re
import sys
import zipfile
from dataclasses import dataclass, field
from pathlib import Path

# --------------------------------------------------------------------------- #
# 常量
# --------------------------------------------------------------------------- #

#: 期望的万象拼音版本。来源版本对不上时会在输出里提示（不致命）。
EXPECTED_WANXIANG_VERSION = "17.9.9"

#: app 要求启用的方案，顺序即方案列表顺序。与升级前保持一致
#: （``wanxiang_t9`` 是九宫格键盘的来源，去掉会让九键用户没有可用方案）。
APP_SCHEMA_LIST = ["wanxiang_t9", "wanxiang", "wanxiang_english"]

#: 每个方案的 app 专属字段。值取自升级前 app 内实际打包的
#: ``schema:`` 块与 ``options:`` 块（见 docs/plans/feat-pinyin-input-quality/DESIGN.md 4.1.1）。
APP_SCHEMA_FIELDS: dict[str, dict] = {
    "wanxiang": dict(
        layout="Qwerty", punctuation="FullWidth", kind="PinYin",
        candidateKind="PinYin", s2t_lock=False, ascii_lock=True,
    ),
    "wanxiang_t9": dict(
        layout="T9", punctuation="FullWidth", kind="PinYin",
        candidateKind="T9PinYin", s2t_lock=False, ascii_lock=True,
    ),
    "wanxiang_t9i": dict(
        layout="T9", punctuation="FullWidth", kind="PinYin",
        candidateKind="T9PinYin", s2t_lock=False, ascii_lock=True,
    ),
    "wanxiang_english": dict(
        layout="Qwerty", punctuation="HalfWidth", kind="English",
        candidateKind=None, s2t_lock=True, ascii_lock=False,
    ),
    "wanxiang_mixedcode": dict(
        layout="Qwerty", punctuation="FullWidth", kind="PinYin",
        candidateKind="PinYin", s2t_lock=False, ascii_lock=True,
    ),
    "wanxiang_reverse": dict(
        layout="Qwerty", punctuation="FullWidth", kind="PinYin",
        candidateKind="PinYin", s2t_lock=False, ascii_lock=True,
    ),
}

#: 只对这些方案注入模糊音。它们的 speller/algebra 走 ``wanxiang_algebra:/base/全拼``，
#: 模糊音规则正是作用在「全拼音节」上的。
#: 九宫格（t9/t9i）的拼写运算把字母即时转成数字键，不适用全拼模糊音；其简拼由
#: ``lua/data/t9_abbrev.txt`` 那套简码实现。英文 / 混合 / 反查方案也不是拼音方案。
FUZZY_SCHEMAS = ["wanxiang"]

#: 万象内置的模糊音规则组（名字必须与 wanxiang_algebra.yaml 的顶层节点一致）。
FUZZY_ALL = [
    "模糊音_nl",      # n - l
    "模糊音_ry",      # r - y
    "模糊音_hf",      # h - f
    "模糊音_rl",      # r - l
    "模糊音_kg",      # k - g
    "模糊音_en_eng",  # en - eng
    "模糊音_in_ing",  # in - ing
    "模糊音_c_ch",    # c - ch
    "模糊音_z_zh",    # z - zh
    "模糊音_s_sh",    # s - sh
]

#: 常用子集（**本项目默认**）：平翘舌 + 前后鼻音 + n/l。主流的「南方口音」组合。
#:
#: 为什么默认不用 [FUZZY_ALL]：r/l、r/y 这两组会让歧义明显变大 —— 实测
#: ``qryt`` 的候选数 2546 → 4011，首选从「杞人忧天」变成「去了一趟」，
#: 而「简拼能出成语」恰恰是本项目要保住的能力之一。h/f、k/g 也不属于
#: 常见的混淆对。换句话说 [FUZZY_SAFE] 拿到了模糊音的全部收益、没有代价。
FUZZY_SAFE = [
    "模糊音_nl",
    "模糊音_en_eng",
    "模糊音_in_ing",
    "模糊音_c_ch",
    "模糊音_z_zh",
    "模糊音_s_sh",
]

FUZZY_PRESETS: dict[str, list[str]] = {
    "safe": FUZZY_SAFE,
    "all": FUZZY_ALL,
    "none": [],
}

#: 默认档位 —— 必须等同于随包发出那一份，否则裸跑脚本复现不出同一个 zip。
DEFAULT_FUZZY = "safe"

#: 顶层要带的文件扩展名（``*.gram`` 之类大文件不在白名单里）。
TOP_LEVEL_EXTS = {".yaml", ".yml", ".md", ".txt"}

#: 顶层要排除的文件。
TOP_LEVEL_EXCLUDE = {
    "installation.yaml",  # 用户安装戳记（含机器 id）
    "user.yaml",          # 用户开关记忆
}

#: dicts/ 里要排除的文件 —— 经引用扫描确认无任何方案引用，排除可为 APK 省下约 6.5 MB。
DICTS_EXCLUDE = {"cn&en.dict.yaml", "top.dict.yaml"}

#: custom/ 里要排除的 —— custom/wanxiang_pure 与 custom/wanxiang_lite 引用了
#: ``dicts/*.pro`` / ``dicts/*.lite``，这些词库不在标准版发行包里，方案本身无法部署。
CUSTOM_EXCLUDE_PREFIXES = ("wanxiang_pure.", "wanxiang_lite.")

#: 用户数据判定（来源是「用过的」rime 目录，混着用户状态）。
USER_STATE_SUFFIXES = (".userdb",)
USER_STATE_DIRS = {"build", "sync"}
USER_STATE_FILES = {"user.yaml", "installation.yaml"}
USER_STATE_FILE_RE = re.compile(r"\.userdb\.txt$")

#: 注入标记 —— 三类注入各自成对包裹，便于识别与**幂等剥离**。
#: 用注释做标记是安全的：YAML 会忽略注释，标记可以落在任意缩进上。
MARK_SCHEMA_BEGIN = "  # >>> ime:schema-fields (由 build-rime-resource.py 注入，勿手改)"
MARK_SCHEMA_END = "  # <<< ime:schema-fields"
MARK_OPTIONS_BEGIN = "# >>> ime:options (由 build-rime-resource.py 注入，勿手改)"
MARK_OPTIONS_END = "# <<< ime:options"
MARK_FUZZY_BEGIN = "# >>> ime:fuzzy (由 build-rime-resource.py 注入，勿手改)"
MARK_FUZZY_END = "# <<< ime:fuzzy"

MARK_PAIRS = [
    (MARK_SCHEMA_BEGIN, MARK_SCHEMA_END),
    (MARK_OPTIONS_BEGIN, MARK_OPTIONS_END),
    (MARK_FUZZY_BEGIN, MARK_FUZZY_END),
]

#: 允许解析不到的引用 —— 这些是「可选 / 用户自己提供」的文件，缺失时方案有默认行为。
#: 加入白名单前必须确认：干净来源里也没有它，且缺失不会让部署或输入报错。
OPTIONAL_REFS = {
    # super_tips.lua 的 DEFAULT_USER：用户自定义提示数据，缺失则只用内置 tips_show.txt。
    # 万象官方包里放的是占位文件 `tips_user.txt预留自定义文件`，真名文件本来就不存在。
    "lua/data/tips_user.txt",
}

DEFAULT_SOURCE = Path.home() / ".local/share/fcitx5/rime"
DEFAULT_OUT = Path("app/src/main/assets/resource.zip")
DEFAULT_MANIFEST = Path("scripts/rime-resource-manifest.json")

# --------------------------------------------------------------------------- #
# 数据模型
# --------------------------------------------------------------------------- #


@dataclass
class BuildPlan:
    """``{zip 内相对路径: 内容字节}``，外加若干报告字段。"""

    files: dict[str, bytes] = field(default_factory=dict)
    from_previous: list[str] = field(default_factory=list)
    transformed: list[str] = field(default_factory=list)
    excluded_unreferenced: list[str] = field(default_factory=list)
    warnings: list[str] = field(default_factory=list)


# --------------------------------------------------------------------------- #
# 来源文件收集
# --------------------------------------------------------------------------- #


def is_user_state(rel: Path) -> bool:
    """判断相对路径是否是用户状态（不该打进包里）。"""
    for part in rel.parts:
        if part in USER_STATE_DIRS or part in USER_STATE_FILES:
            return True
        if part.endswith(USER_STATE_SUFFIXES):
            return True
    return bool(USER_STATE_FILE_RE.search(rel.name))


def collect_source_files(source: Path) -> list[Path]:
    """按白名单收集要打包的相对路径。"""
    picked: list[Path] = []

    # 1) 顶层方案文件（*.yaml / *.md / *.txt），排除用户状态文件
    for child in sorted(source.iterdir()):
        if not child.is_file():
            continue
        if child.suffix not in TOP_LEVEL_EXTS or child.name in TOP_LEVEL_EXCLUDE:
            continue
        picked.append(child.relative_to(source))

    # 2) dicts/
    dicts = source / "dicts"
    if dicts.is_dir():
        for child in sorted(dicts.iterdir()):
            if child.is_file() and child.name not in DICTS_EXCLUDE:
                picked.append(child.relative_to(source))

    # 3) lua/ 递归（跳过 *.userdb 目录）
    lua = source / "lua"
    if lua.is_dir():
        for child in sorted(lua.rglob("*")):
            if child.is_dir() or is_user_state(child.relative_to(source)):
                continue
            picked.append(child.relative_to(source))

    # 4) custom/ 只取 yaml 与 md，且排除不可部署的 pure / lite
    custom = source / "custom"
    if custom.is_dir():
        for child in sorted(custom.iterdir()):
            if not child.is_file():
                continue
            if child.suffix not in {".yaml", ".yml", ".md"}:
                continue
            if child.name.startswith(CUSTOM_EXCLUDE_PREFIXES):
                continue
            picked.append(child.relative_to(source))

    return picked


# --------------------------------------------------------------------------- #
# 注入（幂等）
# --------------------------------------------------------------------------- #


def strip_injection(text: str, begin: str, end: str) -> str:
    """剥离**某一类**注入块（按标记成对匹配）。"""
    pattern = re.compile(
        r"[ \t]*" + re.escape(begin.lstrip()) + r".*?" + re.escape(end.lstrip())
        + r"[^\n]*\n?",
        re.DOTALL,
    )
    return pattern.sub("", text)


def strip_injections(text: str) -> str:
    """剥离所有已注入的块。"""
    for begin, end in MARK_PAIRS:
        text = strip_injection(text, begin, end)
    return text


def _top_level_block_bounds(lines: list[str], key: str) -> tuple[int, int] | None:
    """找顶层 ``key:`` 块的行区间 ``[start, end)``；``lines`` 保留换行符。"""
    start = None
    for idx, line in enumerate(lines):
        if re.match(rf"^{re.escape(key)}:\s*$", line):
            start = idx
            break
    if start is None:
        return None
    end = len(lines)
    for idx in range(start + 1, len(lines)):
        if lines[idx].strip() and not lines[idx][0].isspace():
            end = idx
            break
    return start, end


def inject_schema_fields(text: str, schema_id: str) -> tuple[str, list[str]]:
    """往 ``schema:`` 块注入 layout / punctuation / kind / candidateKind。

    每个注入函数都只剥离**自己那一类**标记块，因此既可以单独重复调用（幂等），
    也可以三个串起来用（互不影响）。
    """
    spec = APP_SCHEMA_FIELDS[schema_id]
    text = strip_injection(text, MARK_SCHEMA_BEGIN, MARK_SCHEMA_END)
    lines = text.splitlines(keepends=True)
    bounds = _top_level_block_bounds(lines, "schema")
    if bounds is None:
        raise ValueError(f"{schema_id}: 找不到顶层 schema: 块")
    start, end = bounds

    fields = [
        ("layout", spec["layout"]),
        ("punctuation", spec["punctuation"]),
        ("kind", spec["kind"]),
    ]
    if spec["candidateKind"]:
        fields.append(("candidateKind", spec["candidateKind"]))

    block = [MARK_SCHEMA_BEGIN + "\n"]
    block += [f'  {key}: "{value}"\n' for key, value in fields]
    block.append(MARK_SCHEMA_END + "\n")

    # 插在 `version:` 之后（没有就插在块尾），让字段顺序稳定、便于 diff
    insert_at = end
    for idx in range(start + 1, end):
        if re.match(r"^\s+version:", lines[idx]):
            insert_at = idx + 1
    lines[insert_at:insert_at] = block
    return "".join(lines), [key for key, _ in fields]


def inject_fuzzy_rules(text: str, schema_id: str, rules: list[str]) -> tuple[str, int]:
    """把模糊音规则追加到 ``speller/algebra/__patch`` 的 ``base/全拼`` 之后。"""
    if not rules:
        return text, 0
    text = strip_injection(text, MARK_FUZZY_BEGIN, MARK_FUZZY_END)
    anchor_re = re.compile(r"^([ \t]*)-[ \t]*wanxiang_algebra:/base/全拼[ \t]*.*$", re.M)
    match = anchor_re.search(text)
    if match is None:
        raise ValueError(
            f"{schema_id}: 找不到 `- wanxiang_algebra:/base/全拼` 锚点，无法追加模糊音"
        )
    block = (
        "\n"
        + MARK_FUZZY_BEGIN
        + "\n"
        + "\n".join(
            f"{match.group(1)}- wanxiang_algebra:/{rule}" for rule in rules
        )
        + "\n"
        + MARK_FUZZY_END
    )
    return text[: match.end()] + block + text[match.end():], len(rules)


def inject_options_block(text: str, schema_id: str) -> str:
    """插入 ``options:`` 块（app 设置 → Rime 运行时选项的桥接）。

    插在 ``switches:`` **之前**，与上游 danjian/ime 的字段顺序一致，
    也避免追加到文件末尾时踩到「上一个键是块标量」的坑。
    """
    spec = APP_SCHEMA_FIELDS[schema_id]
    text = strip_injection(text, MARK_OPTIONS_BEGIN, MARK_OPTIONS_END)
    s2t_lock = "true" if spec["s2t_lock"] else "false"
    ascii_lock = "true" if spec["ascii_lock"] else "false"
    block = f"""{MARK_OPTIONS_BEGIN}
# app 设置 → Rime 运行时选项的桥接。OptionsApplier.kt 按 name 查这张表；
# key 是要 set_option 的开关名，keys 里除 key 之外的名字会被显式置 false。
options:
  - name: traditional_chinese_enabled
    key: s2t
    keys: [s2s, s2t, s2hk, s2tw]
    lock: {s2t_lock}
    value: false
  - name: emoji_enabled
    key: emoji
    keys: [emoji]
    lock: false
    value: false
  - name: ascii_mode_enabled
    key: ascii_mode
    keys: [ascii_mode]
    lock: {ascii_lock}
    value: false
{MARK_OPTIONS_END}
"""

    lines = text.splitlines(keepends=True)
    bounds = _top_level_block_bounds(lines, "switches")
    if bounds is None:
        # 没有 switches 就往文件末尾追加（这些方案文件最后一段都是普通映射，安全）
        return text.rstrip("\n") + "\n\n" + block
    lines.insert(bounds[0], block)
    return "".join(lines)


def enable_fallback_reorder(text: str, schema_id: str) -> tuple[str, bool]:
    """把「同码回删再输首次交换」打开。

    17.9.9 里这个配置在 ``context_reorder:`` 节点下，早期版本在 ``user_predict:`` 下，
    两种都处理。返回 ``(新文本, 是否改动)``。
    """
    changed = False
    out: list[str] = []
    for line in text.splitlines(keepends=True):
        m = re.match(r"^(\s*)enable_fallback_reorder:[ \t]*(\S*)(.*)$", line)
        if m:
            out.append(f"{m.group(1)}enable_fallback_reorder: true{m.group(3)}\n")
            changed = True
        else:
            out.append(line)
    return "".join(out), changed


def force_schema_list(text: str) -> str:
    """把 ``schema_list:`` 固定成 app 要启用的三项。"""
    lines = text.splitlines(keepends=True)
    bounds = _top_level_block_bounds(lines, "schema_list")
    if bounds is None:
        raise ValueError("default.yaml: 找不到 schema_list:")
    start, end = bounds
    lines[start:end] = ["schema_list:\n"] + [
        f"  - schema: {sid}\n" for sid in APP_SCHEMA_LIST
    ]
    return "".join(lines)


# --------------------------------------------------------------------------- #
# 单文件变换
# --------------------------------------------------------------------------- #


def transform(rel: str, data: bytes, fuzzy_rules: list[str]) -> tuple[bytes, list[str], list[str]]:
    """对单个文件做变换。返回 ``(新字节, 做了什么, 警告)``。"""
    notes: list[str] = []
    warnings: list[str] = []

    # 只处理顶层文件；custom/ / dicts/ / lua/ 原样带过去
    if rel.startswith(("custom/", "dicts/", "lua/")):
        return data, notes, warnings

    name = Path(rel).name
    if name == "version.txt":
        return data, notes, warnings

    try:
        text = data.decode("utf-8")
    except UnicodeDecodeError:
        return data, notes, warnings

    if name == "default.yaml":
        text = force_schema_list(strip_injections(text))
        notes.append("schema_list 固定为 " + "/".join(APP_SCHEMA_LIST))
        return text.encode("utf-8"), notes, warnings

    schema_id = name[: -len(".schema.yaml")] if name.endswith(".schema.yaml") else ""
    if not schema_id or schema_id not in APP_SCHEMA_FIELDS:
        return data, notes, warnings

    text = strip_injections(text)          # 先剥离，保证幂等
    text, injected = inject_schema_fields(text, schema_id)
    notes.append("注入 " + ",".join(injected))
    if schema_id in FUZZY_SCHEMAS:
        text, count = inject_fuzzy_rules(text, schema_id, fuzzy_rules)
        notes.append(f"追加模糊音 {count} 组")
    text = inject_options_block(text, schema_id)
    notes.append("注入 options 块")
    text, changed = enable_fallback_reorder(text, schema_id)
    if changed:
        notes.append("enable_fallback_reorder=true")

    return text.encode("utf-8"), notes, warnings


# --------------------------------------------------------------------------- #
# 引用校验
# --------------------------------------------------------------------------- #

#: lua 里读取的数据文件字面量： ``"lua/data/xxx.txt"``
LUA_DATA_REF_RE = re.compile(r"""["']([\w./&-]*lua/data/[\w./&-]+)["']""")
#: yaml 里指向 lua/data 的 files 条目
YAML_DATA_REF_RE = re.compile(r"^\s*-\s*(lua/data/[\w./&-]+)\s*(?:#.*)?$", re.M)
#: import_tables 条目 -> dicts/xxx.dict.yaml
IMPORT_TABLE_RE = re.compile(r"^\s*-\s*(dicts/[\w./&-]+)\s*(?:#.*)?$", re.M)


def validate_references(plan: BuildPlan) -> tuple[list[str], list[str]]:
    """扫描引用，返回 ``(缺失且不允许的, 可选而缺失的)``。"""
    packed_names = {Path(p).name for p in plan.files}
    packed_full = set(plan.files)
    missing: set[str] = set()

    for path, data in plan.files.items():
        if not (path.endswith((".yaml", ".yml", ".lua"))):
            continue
        try:
            text = data.decode("utf-8")
        except UnicodeDecodeError:
            continue

        refs: set[str] = set()
        refs |= set(YAML_DATA_REF_RE.findall(text))
        refs |= set(LUA_DATA_REF_RE.findall(text))
        for table in IMPORT_TABLE_RE.findall(text):
            refs.add(f"{table}.dict.yaml")

        for ref in refs:
            ref = ref.lstrip("./")
            if ref in packed_full or Path(ref).name in packed_names:
                continue
            missing.add(ref)

    fatal = sorted(m for m in missing if m not in OPTIONAL_REFS)
    optional = sorted(m for m in missing if m in OPTIONAL_REFS)
    return fatal, optional


# --------------------------------------------------------------------------- #
# 主流程
# --------------------------------------------------------------------------- #


def sha256(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def build_plan(source: Path, previous: Path | None, fuzzy_rules: list[str]) -> BuildPlan:
    plan = BuildPlan()

    rels = collect_source_files(source)
    if not rels:
        raise SystemExit(f"来源目录里没找到方案文件: {source}")

    for rel in rels:
        data = (source / rel).read_bytes()
        new_data, notes, warns = transform(str(rel), data, fuzzy_rules)
        plan.files[f"shared/{rel}"] = new_data
        plan.warnings.extend(f"{rel}: {w}" for w in warns)
        if notes:
            plan.transformed.append(f"shared/{rel} → " + "; ".join(notes))

    for name in sorted(DICTS_EXCLUDE):
        if (source / "dicts" / name).exists():
            plan.excluded_unreferenced.append(f"shared/dicts/{name}（无方案引用）")

    # 从上一版 zip 里保留 app 专用物料
    if previous and previous.exists():
        with zipfile.ZipFile(previous) as zf:
            for name in sorted(zf.namelist()):
                if name.startswith("model/") and not name.endswith("/"):
                    plan.files[name] = zf.read(name)
                    plan.from_previous.append(name)
    else:
        plan.warnings.append(
            "没有上一版 resource.zip，model/predict.marisa 缺失："
            "打出来的包没有预测模型（app 仍能跑，但候选项预测失效）"
        )

    fatal, optional = validate_references(plan)
    if fatal:
        raise SystemExit(
            "引用校验失败，以下文件被引用但没打包：\n  - " + "\n  - ".join(fatal)
        )
    plan.warnings.extend(f"可选文件缺失（方案已有默认行为）: {p}" for p in optional)
    return plan


def write_zip(plan: BuildPlan, out: Path) -> None:
    out.parent.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED, compresslevel=9) as zf:
        for name in sorted(plan.files):
            info = zipfile.ZipInfo(name, date_time=(2026, 9, 15, 0, 0, 0))
            info.compress_type = zipfile.ZIP_DEFLATED
            info.external_attr = 0o644 << 16
            zf.writestr(info, plan.files[name])


def write_manifest(plan: BuildPlan, source: Path, manifest: Path,
                   fuzzy_preset: str, fuzzy_rules: list[str]) -> None:
    version_file = source / "version.txt"
    src_version = version_file.read_text(encoding="utf-8").strip() if version_file.exists() else ""
    payload = {
        "generated_by": "scripts/build-rime-resource.py",
        "note": "resource.zip 不入库；本 manifest 用于追溯与复现",
        "source_dir": str(source),
        "wanxiang_version": src_version,
        "app_schema_list": APP_SCHEMA_LIST,
        "injected": {
            "schema_fields": APP_SCHEMA_FIELDS,
            "fuzzy_preset": fuzzy_preset,
            "fuzzy_rules": fuzzy_rules,
            "fuzzy_schemas": FUZZY_SCHEMAS,
            "enable_fallback_reorder": True,
        },
        "file_count": len(plan.files),
        "uncompressed_bytes": sum(len(v) for v in plan.files.values()),
        "files": {
            name: {"bytes": len(data), "sha256": sha256(data)}
            for name, data in sorted(plan.files.items())
        },
        "transformed": plan.transformed,
        "kept_from_previous_zip": plan.from_previous,
        "excluded_unreferenced": plan.excluded_unreferenced,
        "warnings": plan.warnings,
    }
    manifest.parent.mkdir(parents=True, exist_ok=True)
    manifest.write_text(
        json.dumps(payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
    )


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(
        description="重建 app/src/main/assets/resource.zip（万象拼音方案数据）",
        formatter_class=argparse.RawDescriptionHelpFormatter,
    )
    parser.add_argument("--source", type=Path, default=DEFAULT_SOURCE,
                        help=f"方案数据来源目录（默认 {DEFAULT_SOURCE}）")
    parser.add_argument("--out", type=Path, default=DEFAULT_OUT,
                        help=f"输出 zip（默认 {DEFAULT_OUT}）")
    parser.add_argument("--previous", type=Path, default=None,
                        help="上一版 zip，用于保留 model/ 下的 app 专用物料"
                             "（默认取 --out 指向的现有文件）")
    parser.add_argument("--manifest", type=Path, default=DEFAULT_MANIFEST,
                        help=f"manifest 输出路径（默认 {DEFAULT_MANIFEST}）")
    parser.add_argument("--fuzzy", choices=sorted(FUZZY_PRESETS), default=DEFAULT_FUZZY,
                        help="模糊音档位：safe=平翘舌+前后鼻音+n/l 共 6 组（默认）; "
                             "all=10 组全开; none=不注入")
    parser.add_argument("--dry-run", action="store_true", help="只打印计划，不写任何文件")
    args = parser.parse_args(argv)

    source: Path = args.source.expanduser()
    if not source.is_dir():
        print(f"来源目录不存在: {source}", file=sys.stderr)
        return 2

    fuzzy_rules = FUZZY_PRESETS[args.fuzzy]
    previous = args.previous if args.previous is not None else args.out
    plan = build_plan(source, previous if previous.exists() else None, fuzzy_rules)

    version_file = source / "version.txt"
    src_version = version_file.read_text(encoding="utf-8").strip() if version_file.exists() else "?"
    total = sum(len(v) for v in plan.files.values())

    print(f"来源      : {source}")
    print(f"万象版本  : {src_version}")
    if src_version != EXPECTED_WANXIANG_VERSION:
        print(f"  ! 期望 {EXPECTED_WANXIANG_VERSION}，来源是 {src_version}，请确认来源无误")
    print(f"模糊音档位: {args.fuzzy}（{len(fuzzy_rules)} 组）")
    print(f"文件数    : {len(plan.files)}")
    print(f"未压缩大小: {total / 1024 / 1024:.1f} MiB")

    if plan.from_previous:
        print("\n保留上一版 zip 里的 app 专用物料：")
        for name in plan.from_previous:
            print(f"  + {name}  {len(plan.files[name]) / 1024 / 1024:.1f} MiB")

    if plan.transformed:
        print("\n做了变换的文件：")
        for item in plan.transformed:
            print(f"  * {item}")

    if plan.excluded_unreferenced:
        print("\n排除（无引用）：")
        for item in plan.excluded_unreferenced:
            print(f"  - {item}")

    if plan.warnings:
        print("\n警告：")
        for item in plan.warnings:
            print(f"  ! {item}")

    schemas = sorted(
        Path(n).name[: -len(".schema.yaml")]
        for n in plan.files
        if n.startswith("shared/") and n.endswith(".schema.yaml")
        and not n.startswith("shared/custom/")
    )
    print(f"\n打包的顶层方案: {', '.join(schemas)}")

    if args.dry_run:
        print("\n--dry-run：没有写任何文件。")
        return 0

    write_zip(plan, args.out)
    write_manifest(plan, source, args.manifest, args.fuzzy, fuzzy_rules)
    print(f"\n已写出 {args.out}  {args.out.stat().st_size / 1024 / 1024:.1f} MiB")
    print(f"已写出 {args.manifest}")
    print(f"zip 的 md5: {hashlib.md5(args.out.read_bytes()).hexdigest()}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
