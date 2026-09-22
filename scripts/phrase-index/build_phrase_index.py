#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""构建固定短语前缀/配对索引（成语 / 歇后语 / 名句 / 诗句「上句→下句」）。

背景
----
这几类都是**固定长短语**：给定上下文尾部，候选是确定的，不需要也不该用模型猜。
前缀 trie 匹配 100% 准确、微秒级、零功耗（见 ``.research/联想升级-方案选型.md`` §2 路径 1）。
本脚本产出**一份扁平索引**，app 侧只需 ``Map<String, List<(value, weight)>>`` 即可消费，
所以这里不建真正的 trie —— 匹配交给消费侧的「从长到短取尾部子串查表」。

输出格式（已冻结，app 侧按此实现）
----------------------------------
``phrase_index.tsv.gz``：gzip 压缩的 UTF-8 文本，每行 ``key\\tvalue\\tweight``。

- ``key``   触发串，与用户**已上屏文本的尾部**匹配；2..16 个码点，不含 tab/换行；
- ``value`` 要给出的候选；非空，不含 tab/换行；
- ``weight`` 整数 ≥1，越大越优先；既用于排序候选，也用于按体积剪枝。

行序固定为 ``key`` 升序 → ``weight`` 降序 → ``value`` 升序，保证查表与 git diff 都可复现。
同一个 ``key`` 允许多行（例如「一心」既是「一心一意」也是「一心二用」的前缀）。

三类规则
--------
1. **前缀补全**（成语 / 名句 / 四字常用语 / 本地词库）：长度为 L 的短语，对 2..min(L-1,16) 的每个前缀
   出一行，值是**整条短语**（输入成语前缀补全整个成语）。不产 L 及更长的键，也不产单字键。
2. **配对补全**（歇后语）：key = 前半（谜面），value = 后半（谜底）。
3. **配对补全**（诗句）：key = 上句，value = 下句。

权重分层
--------
``weight = TIER[来源] + FAME(词频)``，分层间隔 1e5 远大于频率加成（≤6000），
所以**优先级由 tier 决定、层内由词频决定**：``--max-bytes`` 剪枝从最低层尾部砍起，
砍掉哪些、留下哪些完全可预测、可解释（脚本会按来源报告砍掉了多少）。

用法
----
::

    # 正式构建（默认 24 MiB 未压缩预算，含唐诗/宋词/诗经/论语/蒙学）
    python3 scripts/phrase-index/build_phrase_index.py --work-dir .nwp-work

    # 连宋诗一起（255 个文件 / 约 25 万首，相邻句对从 77.5 万涨到 266 万，默认关闭）
    python3 scripts/phrase-index/build_phrase_index.py --work-dir .nwp-work --include-songshi

    # 不剪枝，看全部来源的原始规模
    python3 scripts/phrase-index/build_phrase_index.py --work-dir .nwp-work --max-bytes 0

    # 只重写索引，不联网
    python3 scripts/phrase-index/build_phrase_index.py --work-dir .nwp-work --offline

原始语料缓存在 ``<work-dir>/phrase/raw/``，**不入库**（``.nwp-work/`` 已 gitignore）。
"""

from __future__ import annotations

import argparse
import gzip
import json
import math
import re
import shutil
import subprocess
import sys
import unicodedata
import zipfile
from collections import Counter
from pathlib import Path

# ---------------------------------------------------------------- 常量

MAX_KEY_CHARS = 16
MIN_KEY_CHARS = 2
# 下句超过这个长度基本是序文/散文（宋词长调里偶有），保留只会白占体积
MAX_VALUE_CHARS = 32

REPO_ROOT = Path(__file__).resolve().parents[2]
RESOURCE_ZIP = REPO_ROOT / "app/src/main/assets/resource.zip"
DEFAULT_WORK_DIR = Path("~/.ime-phrase")
DEFAULT_MAX_BYTES = 24 * 1024 * 1024

RAW_FILES = {
    # 文件名: 下载地址
    "idiom.json": "https://raw.githubusercontent.com/pwxcoo/chinese-xinhua/master/data/idiom.json",
    "xiehouyu.json": "https://raw.githubusercontent.com/pwxcoo/chinese-xinhua/master/data/xiehouyu.json",
    "THUOCL_chengyu.txt": "https://raw.githubusercontent.com/thunlp/THUOCL/master/data/THUOCL_chengyu.txt",
    "THUOCL_poem.txt": "https://raw.githubusercontent.com/thunlp/THUOCL/master/data/THUOCL_poem.txt",
}

POETRY_REPO = "https://github.com/chinese-poetry/chinese-poetry.git"
POETRY_DIRNAME = "cpoetry"
# 用 sparse-checkout 只取需要的目录：整仓 ≈222 MB，其中 255 个 poet.song.* 就是宋诗大头
POETRY_PATTERNS = [
    "/LICENSE",
    "/全唐诗/poet.tang.*.json",
    "/宋词/ci.song.*.json",
    "/宋词/宋词三百首.json",
    "/诗经/shijing.json",
    "/论语/lunyu.json",
    "/蒙学/*.json",
]
POETRY_PATTERNS_SONGSHI = ["/全唐诗/poet.song.*.json"]

# 繁简转换：语料（chinese-poetry）大量条目是繁体（`全唐诗` 尤甚），
# 而用户上屏的是简体，不转换的话键永远匹配不上。这两张表随 resource.zip 一起来，
# 直接复用，省掉 opencc 依赖。
ST_TABLES = ("shared/lua/data/STCharacters.txt", "shared/lua/data/STPhrases.txt")

# 权重分层。顺序即 `--max-bytes` 的取舍顺序：先丢 jichu，最后丢 idiom。
TIER = {
    "idiom": 700_000,
    "phrase4": 650_000,
    "xiehouyu": 600_000,
    "poemline": 500_000,
    "poem_pair": 400_000,
    "shici": 300_000,
    "lianxiang": 200_000,
    "jichu": 100_000,
}
SOURCE_LABEL = {
    "idiom": "成语（chinese-xinhua/idiom.json）",
    "phrase4": "四字常用语（resource.zip chengyu.txt 中非成语部分）",
    "xiehouyu": "歇后语（chinese-xinhua/xiehouyu.json）",
    "poemline": "名句（THUOCL_poem.txt）",
    "poem_pair": "诗句上句→下句（chinese-poetry）",
    "shici": "诗词句前缀（resource.zip shici.dict.yaml）",
    "lianxiang": "长词/专名前缀（resource.zip lianxiang.dict.yaml）",
    "jichu": "基础词前缀（resource.zip jichu.dict.yaml）",
}

_SENTENCE_SPLIT = re.compile(r"[，。！？；：、,.!?;:]+")
_CJK_ONLY = re.compile(r"^[\u3400-\u9fff]+$")


# ---------------------------------------------------------------- 基础工具


def parse_size(text: str) -> int:
    """``24M`` / ``512K`` / ``0`` → 字节数；``0`` 表示不限制。"""
    s = text.strip().upper().replace("IB", "B")
    mult = 1
    for suffix, factor in (("G", 1 << 30), ("M", 1 << 20), ("K", 1 << 10)):
        if s.endswith(suffix):
            s, mult = s[: -len(suffix)], factor
            break
    return int(float(s) * mult)


def sanitize(text: str) -> str:
    """去掉会破坏 TSV 行结构的字符；返回空串表示这条记录不可用。"""
    if not text:
        return ""
    return text.replace("\t", "").replace("\n", "").replace("\r", "").strip()


def fame(freq: int) -> int:
    """词频 → 层内加成（1000..6000）。取对数是因为频次跨 5 个数量级。"""
    if freq <= 0:
        return 0
    return 1000 + min(5000, round(1000 * math.log10(freq)))


def is_usable_phrase(text: str) -> bool:
    return bool(text) and len(text) <= MAX_VALUE_CHARS and bool(_CJK_ONLY.match(text))


def pad(text: str, width: int) -> str:
    """按终端显示宽度补空格：中文列在报告里对不齐会很难读。"""
    shown = sum(2 if unicodedata.east_asian_width(c) in "WF" else 1 for c in text)
    return text + " " * max(0, width - shown)


# ---------------------------------------------------------------- 键生成


def prefix_records(source: str, phrase: str, weight: int):
    """前缀补全：值为整条短语。长度 L 只出 2..min(L-1,16) 的键。

    不出 L 及更长的键 —— 用户已经把整条短语上屏了，再给一遍是噪声；
    也不出单字键 —— 一个字的前缀会命中海量候选，只会淹掉别的候选。
    """
    limit = min(len(phrase) - 1, MAX_KEY_CHARS)
    for length in range(MIN_KEY_CHARS, limit + 1):
        yield (source, phrase[:length], phrase, weight)


def pair_records(source: str, upper: str, lower: str, weight: int):
    """配对补全：key = 上句，value = 下句。

    上句超过 16 码点时截到 16：消费侧最多只看尾部 16 个字，
    留更长的键等于永远匹配不上；截断后用户在打到第 16 个字时仍能命中。
    """
    key = upper[:MAX_KEY_CHARS]
    if len(key) < MIN_KEY_CHARS or not lower:
        return
    yield (source, key, lower, weight)


# ---------------------------------------------------------------- 解析


def parse_thuocl(text: str):
    """``词<TAB>词频``（THUOCL 的行首行尾带空格，必须 strip）。"""
    for line in text.splitlines():
        parts = line.split("\t")
        if len(parts) != 2:
            continue
        word, freq = parts[0].strip(), parts[1].strip()
        if word and freq.isdigit():
            yield word, int(freq)


def parse_code_word(text: str):
    """``简拼<TAB>词[<TAB>异形词…]``（resource.zip ``lua/data/chengyu.txt``）。

    第 3 列起是同一条的异形写法（``暗度陈仓`` / ``暗渡陈仓``），也都是可用的词。
    """
    for line in text.splitlines():
        parts = line.split("\t")
        for word in parts[1:]:
            if word.strip():
                yield word.strip()


def parse_rime_dict(text: str):
    """Rime 词库 ``词语<TAB>拼音<TAB>词频``，正文在 ``...`` 之后。"""
    started = False
    for line in text.splitlines():
        if line.strip() == "...":
            started = True
            continue
        if not started or not line or line.startswith("#"):
            continue
        parts = line.split("\t")
        if len(parts) >= 3 and parts[0].strip() and parts[2].strip().isdigit():
            yield parts[0].strip(), int(parts[2])


def iter_paragraph_groups(node):
    """产出「一首诗 / 一段语录」的段落列表。

    各文集结构不统一（``paragraphs`` / ``content`` / ``content[].content[].paragraphs``），
    所以递归找「元素全是字符串的列表」，而不是按固定路径取。

    关键是**以最内层持有该列表的对象为配对边界**：``蒙学/唐诗三百首`` 顶层对象是整本选集，
    以它为边界会把上一首的末句接到下一首的首句；而 `全唐诗` 的一首诗本身就是那个对象，
    所以必须跨它的各个 ``paragraphs`` 配「上联→下联」。
    """
    if isinstance(node, dict):
        for key in ("paragraphs", "content"):
            value = node.get(key)
            if isinstance(value, list) and value and all(isinstance(x, str) for x in value):
                yield value
                return
        for key in ("paragraphs", "content"):
            if key in node:
                yield from iter_paragraph_groups(node[key])
    elif isinstance(node, list):
        for item in node:
            yield from iter_paragraph_groups(item)


def split_sentences(paragraph: str):
    """按中英标点切句，并剥掉包裹的引号书名号。"""
    for raw in _SENTENCE_SPLIT.split(paragraph):
        s = raw.strip().strip("“”\"‘’「」『』（）()《》〈〉[]【】")
        if s:
            yield s


# ---------------------------------------------------------------- 繁简转换


class Simplifier:
    """繁体 → 简体，优先长词匹配（opencc 的 ST 表就是同一个数据源）。"""

    def __init__(self, maps: list[dict[str, str]]):
        self.table: dict[str, str] = {}
        for m in maps:
            self.table.update(m)
        self.max_len = max((len(k) for k in self.table), default=0)

    @classmethod
    def load(cls, resource_zip: Path) -> "Simplifier":
        maps = []
        with zipfile.ZipFile(resource_zip) as z:
            for name in ST_TABLES:
                m: dict[str, str] = {}
                for line in z.read(name).decode("utf-8").splitlines():
                    parts = line.split("\t")
                    if len(parts) >= 2 and parts[0]:
                        for variant in parts[1:]:
                            if variant:
                                m[variant] = parts[0]
                maps.append(m)
        return cls(maps)

    def __call__(self, text: str) -> str:
        out = []
        i, n = 0, len(text)
        while i < n:
            hit = None
            for length in range(min(self.max_len, n - i), 0, -1):
                seg = text[i : i + length]
                if seg in self.table:
                    hit = self.table[seg]
                    break
            if hit is None:
                out.append(text[i])
                i += 1
            else:
                out.append(hit)
                i += length
        return "".join(out)


# ---------------------------------------------------------------- 下载 / 缓存


def download(url: str, dest: Path) -> None:
    print(f"  下载 {url}")
    dest.parent.mkdir(parents=True, exist_ok=True)
    tmp = dest.with_suffix(dest.suffix + ".part")
    subprocess.run(
        ["curl", "-fsSL", "--retry", "3", "-o", str(tmp), url],
        check=True,
    )
    tmp.replace(dest)


def ensure_raw_files(raw_dir: Path, offline: bool) -> None:
    missing = []
    for name, url in RAW_FILES.items():
        path = raw_dir / name
        if path.exists() and path.stat().st_size > 0:
            continue
        if offline:
            missing.append(name)
            continue
        download(url, path)
    if missing:
        raise SystemExit(
            f"--offline 模式下缺少缓存文件：{', '.join(missing)}（先联网跑一次）"
        )


def ensure_poetry(raw_dir: Path, include_songshi: bool, offline: bool) -> Path:
    repo = raw_dir / POETRY_DIRNAME
    patterns = POETRY_PATTERNS + (POETRY_PATTERNS_SONGSHI if include_songshi else [])
    if not repo.exists():
        if offline:
            raise SystemExit(f"--offline 模式下缺少诗词缓存：{repo}")
        print(f"  克隆 {POETRY_REPO}（只取元数据，blob 按需拉取）")
        subprocess.run(
            ["git", "clone", "--depth", "1", "--filter=blob:none", "--no-checkout",
             POETRY_REPO, str(repo)],
            check=True,
        )
    elif offline:
        return repo

    subprocess.run(["git", "sparse-checkout", "init", "--no-cone"], cwd=repo, check=True)
    subprocess.run(["git", "sparse-checkout", "set", *patterns], cwd=repo, check=True)
    subprocess.run(["git", "checkout"], cwd=repo, check=True,
                   stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    return repo


def iter_poetry_files(repo: Path, include_songshi: bool) -> list[Path]:
    files: list[Path] = []
    files += sorted(repo.glob("全唐诗/poet.tang.*.json"))
    if include_songshi:
        files += sorted(repo.glob("全唐诗/poet.song.*.json"))
    files += sorted(repo.glob("宋词/ci.song.*.json"))
    files += sorted(repo.glob("宋词/宋词三百首.json"))
    files += sorted(repo.glob("诗经/shijing.json"))
    files += sorted(repo.glob("论语/lunyu.json"))
    # 蒙学里有一份繁体三字经，和简体版重复；统一走繁简转换后留简体那份即可
    files += [p for p in sorted(repo.glob("蒙学/*.json")) if "traditional" not in p.name]
    missing = [p for p in files if not p.exists()]
    if missing:
        raise SystemExit(f"诗词缓存不完整（--offline？）：缺少 {missing[0]}")
    return files


# ---------------------------------------------------------------- 各来源


def poem_pair_records(source: str, group, poem_freq: dict[str, int], simplifier):
    """一首诗内部的相邻句配对（含跨联：上联末句→下联首句）。

    ``poem_freq`` 是名句表：某句出现在里面说明它流传广，用它抬权重 ——
    诗词语料本身没有频次，这是唯一可用的「知名度」信号。
    """
    sentences: list[str] = []
    for paragraph in group:
        sentences += [simplifier(s) for s in split_sentences(paragraph)]
    sentences = [s for s in sentences if is_usable_phrase(s)]
    for upper, lower in zip(sentences, sentences[1:]):
        if len(upper) < MIN_KEY_CHARS:
            continue
        boost = max(poem_freq.get(upper, 0), poem_freq.get(lower, 0))
        yield from pair_records(source, upper, lower, TIER["poem_pair"] + fame(boost))


def prefix_source_records(source: str, phrases_with_freq, tier: int):
    """前缀补全来源的公共部分：短语 → 记录。"""
    for phrase, freq in phrases_with_freq:
        phrase = sanitize(phrase)
        if len(phrase) < MIN_KEY_CHARS + 1 or not is_usable_phrase(phrase):
            continue
        yield from prefix_records(source, phrase, tier + fame(freq))


def collect_records(raw_dir: Path, repo: Path, include_songshi: bool, include_jichu: bool,
                    simplifier: Simplifier):
    records: list[tuple[str, str, str, int]] = []
    counts: Counter[str] = Counter()

    def add(records_iter, source: str):
        n = 0
        for rec in records_iter:
            records.append(rec)
            n += 1
        counts[source] += n

    # --- 成语：idiom.json 给全集，THUOCL_chengyu 给频次
    chengyu_freq: dict[str, int] = {}
    for word, freq in parse_thuocl((raw_dir / "THUOCL_chengyu.txt").read_text("utf-8")):
        chengyu_freq[word] = freq
    idioms = json.loads((raw_dir / "idiom.json").read_text("utf-8"))
    idiom_set = {r["word"].strip() for r in idioms if r.get("word")}
    add(prefix_source_records("idiom", ((w, chengyu_freq.get(w, 0)) for w in sorted(idiom_set)),
                              TIER["idiom"]), "idiom")

    # --- chengyu.txt：26,048 个词里 18,489 已在 idiom.json 里，剩下的是普通四字
    #     常用语（一不小心 / 一两个月）；不是成语，但「输入两字补全四字」同样有用
    chengyu_txt = raw_dir / "chengyu_lua.txt"
    if chengyu_txt.exists():
        extra = sorted(set(parse_code_word(chengyu_txt.read_text("utf-8"))) - idiom_set)
        add(prefix_source_records("phrase4", ((w, 0) for w in extra), TIER["phrase4"]),
            "phrase4")

    # --- 歇后语：谜面 → 谜底
    xiehouyu = json.loads((raw_dir / "xiehouyu.json").read_text("utf-8"))
    add((rec for r in xiehouyu
         for rec in pair_records("xiehouyu", sanitize(r.get("riddle", "")),
                                 sanitize(r.get("answer", "")), TIER["xiehouyu"])),
        "xiehouyu")

    # --- 名句（THUOCL_poem，带频次）：整句做前缀补全
    poem_freq = dict(parse_thuocl((raw_dir / "THUOCL_poem.txt").read_text("utf-8")))
    add(prefix_source_records("poemline", sorted(poem_freq.items()), TIER["poemline"]),
        "poemline")

    # --- 诗句「上句→下句」
    pair_keys: set[tuple[str, str]] = set()
    pair_raw = 0
    for path in iter_poetry_files(repo, include_songshi):
        data = json.loads(path.read_text("utf-8"))
        for group in iter_paragraph_groups(data):
            for rec in poem_pair_records("poem_pair", group, poem_freq, simplifier):
                records.append(rec)
                pair_keys.add((rec[1], rec[2]))
                pair_raw += 1
    counts["poem_pair"] += len(pair_keys)

    # --- 本地词库（resource.zip）
    if RESOURCE_ZIP.exists():
        with zipfile.ZipFile(RESOURCE_ZIP) as z:
            def dict_text(name: str) -> str:
                return z.read(f"shared/dicts/{name}.dict.yaml").decode("utf-8")

            add(prefix_source_records("shici", parse_rime_dict(dict_text("shici")),
                                      TIER["shici"]), "shici")
            add(prefix_source_records("lianxiang", parse_rime_dict(dict_text("lianxiang")),
                                      TIER["lianxiang"]), "lianxiang")
            if include_jichu:
                add(prefix_source_records("jichu", parse_rime_dict(dict_text("jichu")),
                                          TIER["jichu"]), "jichu")
    else:
        print(f"  ! 找不到 {RESOURCE_ZIP}，跳过本地词库来源")

    stats = {
        "emitted": dict(counts),
        "poem_pairs_raw": pair_raw,
        "poem_pairs_distinct": len(pair_keys),
    }
    return records, stats


# ---------------------------------------------------------------- 剪枝 / 写出


def dedupe(records):
    """同一 ``(key, value)`` 只留权重最高的一条；权重相同则保留先出现的来源。"""
    best: dict[tuple[str, str], tuple[int, str]] = {}
    for source, key, value, weight in records:
        prev = best.get((key, value))
        if prev is None or weight > prev[0]:
            best[(key, value)] = (weight, source)
    return [(source, key, value, weight)
            for (key, value), (weight, source) in best.items()]


def prune(records, max_bytes: int):
    """按体积剪掉**权重最低**的记录。

    权重相同的记录之间先砍 key/value 字典序靠前的，这样剪枝结果与输入顺序无关、
    可复现。返回 (保留的记录, 每来源计数, 每来源字节, 每来源被砍条数)。
    """
    sizes = [len(key.encode()) + len(value.encode()) + len(str(weight)) + 3
             for _, key, value, weight in records]
    total = sum(sizes)
    dropped: Counter[str] = Counter()
    if max_bytes <= 0 or total <= max_bytes:
        kept = records
    else:
        # 从价值最低的一端（层最低、层内 key 最小）往前砍，直到放得下
        order = sorted(range(len(records)),
                       key=lambda i: (records[i][3], records[i][1], records[i][2]))
        kept_flags = [True] * len(records)
        running = total
        for i in order:
            if running <= max_bytes:
                break
            kept_flags[i] = False
            running -= sizes[i]
            dropped[records[i][0]] += 1
        kept = [r for i, r in enumerate(records) if kept_flags[i]]
    kept_bytes: Counter[str] = Counter()
    kept_counts: Counter[str] = Counter()
    for source, key, value, weight in kept:
        kept_bytes[source] += len(key.encode()) + len(value.encode()) + len(str(weight)) + 3
        kept_counts[source] += 1
    return kept, kept_counts, kept_bytes, dropped


def write_index(records, out: Path) -> tuple[int, int]:
    """写出 gz；mtime 固定为 0，保证同样的输入产出同样的字节，便于比对。"""
    out.parent.mkdir(parents=True, exist_ok=True)
    raw_bytes = 0
    with open(out, "wb") as fh:
        with gzip.GzipFile(filename="", mode="wb", fileobj=fh, mtime=0, compresslevel=9) as gz:
            for _source, key, value, weight in records:
                line = f"{key}\t{value}\t{weight}\n".encode("utf-8")
                raw_bytes += len(line)
                gz.write(line)
    return raw_bytes, out.stat().st_size


# ---------------------------------------------------------------- 消费侧（verify/测试共用）


def load_index(path: Path) -> dict[str, list[tuple[str, int]]]:
    index: dict[str, list[tuple[str, int]]] = {}
    opener = gzip.open if path.suffix == ".gz" else open
    with opener(path, "rt", encoding="utf-8") as fh:
        for lineno, line in enumerate(fh, 1):
            line = line.rstrip("\n")
            if not line:
                continue
            parts = line.split("\t")
            if len(parts) != 3:
                raise ValueError(f"{path}:{lineno} 不是三列: {line!r}")
            key, value, weight = parts
            index.setdefault(key, []).append((value, int(weight)))
    return index


def lookup(index: dict[str, list[tuple[str, int]]], tail: str):
    """模拟 app 侧：从长到短取尾部子串查表，返回第一个命中的候选列表。"""
    for length in range(min(MAX_KEY_CHARS, len(tail)), MIN_KEY_CHARS - 1, -1):
        hit = index.get(tail[-length:])
        if hit:
            return tail[-length:], hit
    return None, None


# ---------------------------------------------------------------- 主流程


def build(args) -> int:
    work_dir: Path = args.work_dir.expanduser()
    raw_dir = work_dir / "phrase/raw"
    out: Path = args.out.expanduser() if args.out else work_dir / "phrase/phrase_index.tsv.gz"
    max_bytes = parse_size(args.max_bytes)

    print(f"工作目录  : {work_dir}")
    print(f"输出      : {out}")
    print(f"预算      : {'不限' if max_bytes <= 0 else f'{max_bytes / 1024 / 1024:.1f} MiB（未压缩）'}")

    print("\n[1/4] 取语料")
    raw_dir.mkdir(parents=True, exist_ok=True)
    ensure_raw_files(raw_dir, args.offline)
    repo = ensure_poetry(raw_dir, args.include_songshi, args.offline)
    # 繁简表与 chengyu.txt 都来自 resource.zip，解到 raw/ 供解析复用；
    # 没有 resource.zip 时退化为「不转换」而不是失败 —— 成语/歇后语本身是简体
    if RESOURCE_ZIP.exists():
        simplifier = Simplifier.load(RESOURCE_ZIP)
        with zipfile.ZipFile(RESOURCE_ZIP) as z:
            (raw_dir / "chengyu_lua.txt").write_bytes(z.read("shared/lua/data/chengyu.txt"))
    else:
        print(f"  ! 找不到 {RESOURCE_ZIP}，繁简转换与本地词库来源跳过")
        simplifier = Simplifier([])

    print("\n[2/4] 生成记录")
    records, stats = collect_records(
        raw_dir, repo, args.include_songshi, args.include_jichu, simplifier)
    records = dedupe(records)
    print(f"  去重后记录: {len(records):,}")
    print(f"  诗句配对（原始/去重）: {stats['poem_pairs_raw']:,} / {stats['poem_pairs_distinct']:,}")

    print("\n[3/4] 剪枝")
    kept, kept_counts, kept_bytes, dropped = prune(records, max_bytes)
    kept.sort(key=lambda r: (r[1], -r[3], r[2]))

    print("\n[4/4] 写出")
    raw_bytes, gz_bytes = write_index(kept, out)
    print(f"  {out}  未压缩 {raw_bytes / 1024 / 1024:.2f} MiB  gzip {gz_bytes / 1024 / 1024:.2f} MiB")

    print("\n按来源：")
    print(f"  {'来源':<30}{'记录':>12}{'未压缩':>15}{'被剪':>12}")
    for source in TIER:
        if source not in kept_counts and source not in dropped:
            continue
        label = SOURCE_LABEL.get(source, source)
        print(f"  {pad(label, 30)}{kept_counts[source]:>12,}"
              f"{kept_bytes[source] / 1024 / 1024:>9.2f} MiB{dropped[source]:>12,}")
    print(f"  {pad('合计', 30)}{len(kept):>12,}"
          f"{raw_bytes / 1024 / 1024:>9.2f} MiB{sum(dropped.values()):>12,}")

    manifest = {
        "out": str(out),
        "entries": len(kept),
        "uncompressed_bytes": raw_bytes,
        "gzip_bytes": gz_bytes,
        "max_bytes": max_bytes,
        "include_songshi": args.include_songshi,
        "include_jichu": args.include_jichu,
        "poem_pairs_raw": stats["poem_pairs_raw"],
        "poem_pairs_distinct": stats["poem_pairs_distinct"],
        "sources": {
            source: {
                "label": SOURCE_LABEL.get(source, source),
                "records": kept_counts.get(source, 0),
                "uncompressed_bytes": kept_bytes.get(source, 0),
                "dropped": dropped.get(source, 0),
            }
            for source in TIER
        },
    }
    if args.out_dir_asset:
        asset_dir: Path = args.out_dir_asset.expanduser()
        asset_dir.mkdir(parents=True, exist_ok=True)
        shutil.copy2(out, asset_dir / out.name)
        print(f"  已复制到 {asset_dir / out.name}")
    manifest_path = out.with_suffix("").with_suffix(".manifest.json")
    manifest_path.write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n",
                             encoding="utf-8")
    print(f"\nmanifest: {manifest_path}")
    return 0


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(
        description="构建固定短语前缀/配对索引（成语 / 歇后语 / 名句 / 诗句上句→下句）",
        formatter_class=argparse.RawDescriptionHelpFormatter,
    )
    parser.add_argument("--work-dir", type=Path, default=DEFAULT_WORK_DIR,
                        help=f"工作目录（默认 {DEFAULT_WORK_DIR}）；语料缓存与产物都在它下面")
    parser.add_argument("--out", type=Path, default=None,
                        help="输出 tsv.gz（默认 <work-dir>/phrase/phrase_index.tsv.gz）")
    parser.add_argument("--include-songshi", action="store_true",
                        help="并入宋诗（约 25 万首，体积显著增大，默认关闭）")
    parser.add_argument("--include-jichu", action="store_true",
                        help="并入 jichu 基础词库（142 万条 2-4 字词，默认关闭）")
    parser.add_argument("--max-bytes", default=str(DEFAULT_MAX_BYTES),
                        help=f"未压缩 TSV 预算，超出则剪掉最低权重条目；0 表示不限"
                             f"（默认 {DEFAULT_MAX_BYTES // 1024 // 1024} MiB）")
    parser.add_argument("--offline", action="store_true", help="不联网，只用 raw/ 缓存")
    parser.add_argument("--out-dir-asset", type=Path, default=None,
                        help="额外把产物复制到该目录（如 app/src/main/assets/phrase/）")
    args = parser.parse_args(argv)
    return build(args)


if __name__ == "__main__":
    sys.exit(main())
