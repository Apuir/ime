#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""构建端侧要用的两份词表：`char2id.json`（输入）与 `word_vocab.txt`（输出）。

两件事都是「训练与端侧必须逐字节一致」的，所以这里的产物就是最终产物，
训练脚本只允许读它、不允许自己再调 tokenizer（见 model.py 的 CharEncoder）。

char2id.json
------------
输入是**字级**的：骨干是字级 GPT2，端侧也只能维护「字 → id」。为了让训练和推理
用同一套 id，我们把每个汉字映射到**骨干 tokenizer 里恰好占一个 token 的那个 id**：
骨干的 `wte` 是预训练好的，只有走上它的真实 id，预训练权重才有意义。
`<unk>`=0、`<pad>`=1；一个字符若被 BPE 切成了多个 token（或不在词表里），
一律走 `<unk>`——端侧没有 BPE 合并表，多 token 的字符在设备上无法还原。
`--assert-agreement` 会在真实文本上逐 token 断言「char2id 编码 == tokenizer 编码」，
不一致就报出比例（uer 那套纯单字表下应为 0）。

word_vocab.txt
--------------
V=30000，第 index 行就是 word id，第 0 行固定 `<unk>`。
词频来自 resource.zip 的 `jichu`（以及 `lianxiang`），**但成语与名句是显式强制入表的**：
BPE/词典切不出「心想事成」时，模型就没法「一次前向给出一个整词」，
而端侧联想最想要的就是这种整词。强制入表可能挤掉几个最低频的普通词，脚本会报出数量。
"""

from __future__ import annotations

import argparse
import json
import re
import sys
import urllib.error
import urllib.request
import zipfile
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

from nwp_common import (  # noqa: E402
    Layout,
    add_work_arg,
    cjk_count,
    layout_from_args,
    log,
    prepare_hf_env,
    resolve_backbone,
    write_json,
)

DICT_MEMBERS = {
    "jichu": "shared/dicts/jichu.dict.yaml",
    "shici": "shared/dicts/shici.dict.yaml",
    "lianxiang": "shared/dicts/lianxiang.dict.yaml",
    "zi": "shared/dicts/zi.dict.yaml",
}

THUOCL_BASE = "https://raw.githubusercontent.com/thunlp/THUOCL/master/data"
IDIOM_URL = f"{THUOCL_BASE}/THUOCL_chengyu.txt"
POEM_URL = f"{THUOCL_BASE}/THUOCL_poem.txt"


# --------------------------------------------------------------------------- #
# 词典来源
# --------------------------------------------------------------------------- #


def extract_dicts(lay: Layout, names: list[str], resource_zip: Path) -> dict[str, Path]:
    """从 resource.zip 取词库到工作目录并缓存。

    为什么落盘缓存：jichu 解出来 45 MB、跑一次要几秒，而调试时这些脚本会反复跑。
    """
    lay.make("dicts")
    out: dict[str, Path] = {}
    with zipfile.ZipFile(resource_zip) as zf:
        for name in names:
            member = DICT_MEMBERS[name]
            dest = lay.dicts / f"{name}.dict.yaml"
            if not dest.exists() or dest.stat().st_mtime < resource_zip.stat().st_mtime:
                dest.write_bytes(zf.read(member))
            out[name] = dest
    return out


_FREQ_RE = re.compile(r"^(-?\d+)$")


def parse_dict_yaml(path: Path) -> dict[str, int]:
    """`词语\t拼音\t词频` → {词: 频次}。头部 yaml 段与注释跳过。"""
    freq: dict[str, int] = {}
    in_body = False
    with path.open("r", encoding="utf-8") as fh:
        for line in fh:
            line = line.rstrip("\n")
            if not in_body:
                if line.strip() in ("...", "---"):
                    in_body = True
                continue
            if not line or line[0] == "#":
                continue
            parts = line.split("\t")
            word = parts[0].strip()
            if not word:
                continue
            value = 1
            if len(parts) >= 3 and _FREQ_RE.match(parts[-1].strip()):
                value = max(1, int(parts[-1].strip()))
            freq[word] = freq.get(word, 0) + value
    return freq


def char_coverage_failure(oov_char_rate: float, min_coverage: float) -> str | None:
    """字符覆盖率不够时返回一句可执行的错误说明，够则返回 None。

    为什么要有这道闸：`IDEA-CCNL/Wenzhong-GPT2-110M` 的 tokenizer 是**字节级 BPE**
    （`今` → `[20015, 232]`，两个 token 才拼出一个字），于是「一个字恰好一个 token」的
    映射只剩 137 个字，真实语料上 **77%–86% 的字变成 `<unk>`**。
    不拦的话，词表照样生成、训练照样跑、loss 照样降——只是模型的输入几乎全是 `<unk>`，
    4000 步全白跑。这条只能靠覆盖率断言挡，肉眼看不出来。
    抽成纯函数是为了能单测（不依赖网络与 tokenizer）。
    """
    if oov_char_rate <= 1.0 - min_coverage:
        return None
    return (
        f"字符覆盖率不足：真实语料里有 {oov_char_rate * 100:.1f}% 的字无法映射到单个 token"
        f"（要求 ≤ {(1 - min_coverage) * 100:.1f}%）。\n"
        "    常见原因是底座 tokenizer 是**字节级 BPE**（例如 IDEA-CCNL/Wenzhong-GPT2-110M，"
        "`今` 要两个 token），而端侧是纯字级输入。\n"
        "    选择：①换字表型底座（uer/gpt2-chinese-cluecorpussmall 的 vocab.txt 每行一个字）；"
        "②沿用另一个底座的 char2id（`--char-source file:<path/to/char2id.json>`，"
        "但那样 id 只表示嵌入行号，不是该底座的真实字表）；"
        "③确实要用这个底座就得改成 BPE 输入，那是换架构，不在本脚本范围内。\n"
        "    （确认要这么做就加 --allow-low-char-coverage）"
    )


def download_cached(url: str, dest: Path, min_bytes: int = 1024) -> Path | None:
    """先写 `<dest>.part`，成功后再原子改名。

    为什么不直接 `write_bytes(dest)`：网络中断会抛 `http.client.IncompleteRead`——
    它既不是 `URLError` 也不是 `OSError`，不在原来的 except 里；于是**半截文件留在地板上**，
    下次运行看到「存在且非空」就当缓存用，词表会静默少掉一批成语/名句。
    原子改名 + 宽 except 保证「要么完整，要么什么都没有」；下载失败只降级走本地退路
    （不能因为一个 160 KB 的词表把整次构建弄崩）。
    """
    if dest.exists() and dest.stat().st_size >= min_bytes:
        return dest
    tmp = dest.with_name(dest.name + ".part")
    try:
        req = urllib.request.Request(url, headers={"User-Agent": "ime-nwp/1.0"})
        with urllib.request.urlopen(req, timeout=60) as resp:
            data = resp.read()
        if len(data) < min_bytes:
            raise ValueError(f"只拿到 {len(data)} 字节，疑似被截断")
        tmp.write_bytes(data)
        tmp.replace(dest)
        return dest
    except Exception as e:  # noqa: BLE001 任何下载问题都只降级，不拖垮整次构建
        log(f"下载失败（改用本地退路）{url}: {type(e).__name__}: {e}")
        tmp.unlink(missing_ok=True)
        dest.unlink(missing_ok=True)
        return None


def parse_freq_file(path: Path, sep: str | None = None) -> dict[str, int]:
    out: dict[str, int] = {}
    for line in path.read_text(encoding="utf-8", errors="ignore").split("\n"):
        line = line.strip()
        if not line:
            continue
        parts = line.split(sep) if sep else line.split("\t")
        word = parts[0].strip()
        if not word:
            continue
        value = 1
        if len(parts) >= 2 and _FREQ_RE.match(parts[1].strip()):
            value = max(1, int(parts[1].strip()))
        out[word] = out.get(word, 0) + value
    return out


def load_phrase_index(repo_root: Path) -> tuple[dict[str, int], dict[str, int], list[str]]:
    """若兄弟工作流 `scripts/phrase-index/` 已经产出成语/诗句清单就顺手用上。

    刻意做成「可选」：这条线不由我们负责，没产出也必须能跑。
    """
    idioms: dict[str, int] = {}
    poems: dict[str, int] = {}
    used: list[str] = []
    root = repo_root / "scripts" / "phrase-index"
    if not root.is_dir():
        return idioms, poems, used
    for path in sorted(root.rglob("*")):
        if not path.is_file() or path.suffix.lower() not in (".txt", ".json", ".jsonl", ".tsv"):
            continue
        name = path.name.lower()
        target = None
        if any(k in name for k in ("idiom", "chengyu", "成语")):
            target = idioms
        elif any(k in name for k in ("poem", "shici", "诗句", "mingju", "名句")):
            target = poems
        if target is None:
            continue
        try:
            if path.suffix.lower() in (".json", ".jsonl"):
                rows = []
                text = path.read_text(encoding="utf-8", errors="ignore")
                if path.suffix.lower() == ".jsonl":
                    rows = [json.loads(l) for l in text.split("\n") if l.strip()]
                else:
                    data = json.loads(text)
                    rows = data if isinstance(data, list) else data.get("data", [])
                for row in rows:
                    word = ""
                    freq = 1
                    if isinstance(row, str):
                        word = row
                    elif isinstance(row, dict):
                        for key in ("word", "text", "phrase", "成语", "content"):
                            if isinstance(row.get(key), str):
                                word = row[key]
                                break
                        for key in ("freq", "count", "frequency", "weight"):
                            if isinstance(row.get(key), (int, float)):
                                freq = max(1, int(row[key]))
                                break
                    if word:
                        target[word] = target.get(word, 0) + freq
            else:
                target.update(parse_freq_file(path))
            used.append(str(path.relative_to(repo_root)))
        except (json.JSONDecodeError, OSError) as e:
            log(f"  跳过 {path}: {e}")
    return idioms, poems, used


# --------------------------------------------------------------------------- #
# char2id
# --------------------------------------------------------------------------- #


def corpus_chars(corpus_dir: Path, limit_files: int = 0) -> dict[str, int]:
    freq: dict[str, int] = {}
    files = sorted(corpus_dir.glob("*.txt"))
    if limit_files:
        files = files[:limit_files]
    for path in files:
        with path.open("r", encoding="utf-8") as fh:
            for line in fh:
                for ch in line:
                    if ch.strip():
                        freq[ch] = freq.get(ch, 0) + 1
    return freq


def build_char2id_backbone(backbone: str, candidates: set[str], work: Path) -> tuple[dict[str, int], dict]:
    from transformers import AutoTokenizer

    tok = AutoTokenizer.from_pretrained(backbone)
    vocab_size = int(tok.vocab_size)
    char2id: dict[str, int] = {"<unk>": 0, "<pad>": 1}
    multi = 0
    for ch in sorted(candidates):
        ids = tok.encode(ch, add_special_tokens=False)
        if len(ids) == 1 and ids[0] not in (0, 1):
            char2id[ch] = int(ids[0])
        else:
            multi += 1
    # 反向校验：每个映射都必须真的能解回同一个字符，否则是 BPE 的部分字节，不能用
    bad = 0
    for ch, cid in list(char2id.items()):
        if cid <= 1:
            continue
        if tok.decode([cid]) != ch:
            del char2id[ch]
            bad += 1
    meta = {
        "source": f"backbone:{backbone}",
        "backbone_vocab_size": vocab_size,
        "chars_not_single_token": multi,
        "chars_dropped_by_decode_check": bad,
    }
    return char2id, meta


def build_char2id_corpus(freq: dict[str, int]) -> tuple[dict[str, int], dict]:
    """离线模式：按字频给 id。

    仅用于冒烟（随机初始化的骨干本来就没有预训练字表可用），
    真实训练必须走 `--char-source backbone:*`。
    """
    ordered = sorted(freq.items(), key=lambda kv: (-kv[1], kv[0]))
    char2id: dict[str, int] = {"<unk>": 0, "<pad>": 1}
    for ch, _ in ordered:
        if ch in char2id:
            continue
        char2id[ch] = len(char2id)
    return char2id, {"source": "corpus", "distinct_chars": len(ordered)}


def verify_agreement(char2id: dict[str, int], backbone: str, text: str) -> dict:
    """在真实文本上验证 char2id 与 tokenizer 的关系。

    这里的判据必须写清楚，否则会得出错误结论：
    - **逐字 id**：文本里每个字，`char2id[字]` 必须等于 tokenizer 单独编码该字得到的那个 id。
      这才是「端侧 char2id 与骨干 wte 对齐」的实质；不等就是映射错了。
    - **合并率**：tokenizer 是 BPE，会把常用字对合成**一个** token（400 字 → 391 token），
      所以「整段编码的 token 数 == 字数」在有合并的底座上**本就不成立**。
      合并 token 在字级模型里无法表示（端侧没有合并表），这就是设计上「输入按字」的代价，
      统计出来是为了留证据，不是错误。
    """
    ids_ours = [char2id.get(ch, 0) for ch in text]
    unk = char2id.get("<unk>", 0)
    if backbone == "smoke":
        return {"checked_chars": len(text), "unknown_rate": sum(1 for i in ids_ours if i == unk) / max(1, len(text)),
                "note": "corpus 模式无 tokenizer 可比"}
    from transformers import AutoTokenizer

    tok = AutoTokenizer.from_pretrained(backbone)
    ids_tok = tok.encode(text, add_special_tokens=False)
    pieces = [tok.decode([i]) for i in ids_tok]
    merged = sum(1 for p in pieces if len(p) > 1)
    per_char_checked = per_char_bad = 0
    for ch in set(text):
        cid = char2id.get(ch, unk)
        if cid == unk:
            continue
        per_char_checked += 1
        if tok.encode(ch, add_special_tokens=False) != [cid]:
            per_char_bad += 1
    return {
        "checked_chars": len(text),
        "token_count": len(ids_tok),
        "merged_tokens": merged,
        "merge_rate": merged / max(1, len(ids_tok)),
        "per_char_checked": per_char_checked,
        "per_char_mismatch": per_char_bad,
        "unknown_rate": sum(1 for i in ids_ours if i == unk) / max(1, len(text)),
    }


# --------------------------------------------------------------------------- #
# word_vocab
# --------------------------------------------------------------------------- #


def collect_words(args, lay: Layout, repo_root: Path) -> tuple[dict[str, int], dict]:
    stats: dict = {"sources": {}, "forced": {}, "used_phrase_index": []}
    freq: dict[str, int] = {}

    if args.vocab_source == "resource":
        paths = extract_dicts(lay, ["jichu", "lianxiang", "shici"], args.resource_zip)
        jichu = parse_dict_yaml(paths["jichu"])
        lianxiang = parse_dict_yaml(paths["lianxiang"])
        shici = parse_dict_yaml(paths["shici"])
        stats["sources"] = {
            "jichu": len(jichu),
            "lianxiang": len(lianxiang),
            "shici": len(shici),
        }
        for word, f in jichu.items():
            freq[word] = freq.get(word, 0) + f
        # lianxiang 是「长词/联想词」，权重刻意压低：它的词频口径与 jichu 不同，
        # 直接相加会把一批专名顶到普通常用词前面。
        for word, f in lianxiang.items():
            freq[word] = freq.get(word, 0) + max(1, f // 10)

        idioms, poems, used = load_phrase_index(repo_root)
        stats["used_phrase_index"] = used
        if not idioms:
            cache = lay.dicts / "THUOCL_chengyu.txt"
            if args.offline is False and download_cached(IDIOM_URL, cache):
                idioms = parse_freq_file(cache)
        if not poems:
            cache = lay.dicts / "THUOCL_poem.txt"
            if args.offline is False and download_cached(POEM_URL, cache):
                poems = parse_freq_file(cache)
        if not poems:
            # 退路：resource.zip 的 shici 本身就是名句库
            poems = shici
        if not idioms:
            # 退路：jichu 里长度为 4 且高频的多半是成语。精度差一些，但必须有东西顶上，
            # 否则「成语必须是单个输出 token」这条设计就落空了。
            idioms = {w: f for w, f in jichu.items() if len(w) == 4 and f >= args.idiom_fallback_freq}
            stats["idiom_fallback"] = "jichu 四字高频词"
        idioms = dict(sorted(idioms.items(), key=lambda kv: (-kv[1], kv[0]))[: args.max_forced_idioms])
        poems = dict(sorted(poems.items(), key=lambda kv: (-kv[1], kv[0]))[: args.max_forced_poems])
        stats["forced"] = {"idioms": len(idioms), "poems": len(poems)}
        for word, f in idioms.items():
            freq[word] = max(freq.get(word, 0), f)
        for word, f in poems.items():
            freq[word] = max(freq.get(word, 0), f)
        forced = list(idioms) + list(poems)
    else:
        path = lay.corpus / "synthetic_words.txt"
        if not path.exists():
            raise SystemExit(f"{path} 不存在：先跑 fetch_corpus.py --dataset synthetic")
        freq = parse_freq_file(path)
        forced = []
        stats["sources"] = {"synthetic_words": len(freq)}

    stats["forced_words"] = len(set(forced))
    stats["unique_after_merge"] = len(freq)
    return freq, (forced, stats)


def word_allowed(word: str, max_len: int, char2id: dict[str, int]) -> bool:
    if not word or len(word) > max_len:
        return False
    if cjk_count(word) == 0:
        return False
    if any(ch.isspace() or not ch.isprintable() for ch in word):
        return False
    # 词表里出现 char2id 覆盖不到的字，训练时上下文里就永远是 <unk>；仍允许，
    # 但要求至少一半的字是可编码的，否则这个词在端侧根本打不出来。
    known = sum(1 for ch in word if char2id.get(ch, 0) > 1)
    return known * 2 >= len(word)


def build_word_vocab(
    args, freq: dict[str, int], forced: list[str], char2id: dict[str, int]
) -> tuple[list[str], dict]:
    size = args.size
    ordered = sorted(freq.items(), key=lambda kv: (-kv[1], kv[0]))
    ordered = [(w, f) for w, f in ordered if word_allowed(w, args.max_word_len, char2id)]
    base = ordered[: size - 1]
    base_words = {w for w, _ in base}

    forced_ok: list[str] = []
    seen: set[str] = set()
    for word in forced:
        if word in seen:
            continue
        seen.add(word)
        if word_allowed(word, args.max_phrase_len, char2id):
            forced_ok.append(word)
    missing = [w for w in forced_ok if w not in base_words]

    displaced = 0
    if missing:
        # 只从尾部（最低频、且不是强制项）腾位置，避免动到高频区
        keep: list[tuple[str, int]] = []
        drop_budget = len(missing)
        for item in reversed(base):
            if drop_budget > 0 and item[0] not in seen:
                drop_budget -= 1
                displaced += 1
                continue
            keep.append(item)
        base = list(reversed(keep))
        for word in missing:
            base.append((word, freq.get(word, 1)))
    base.sort(key=lambda kv: (-kv[1], kv[0]))
    words = ["<unk>"] + [w for w, _ in base][: size - 1]
    final_freq = {w: f for w, f in base}
    stats = {
        "size_requested": size,
        "size_actual": len(words),
        "forced_displaced_base_words": displaced,
        "forced_missing_after_filter": len(forced_ok) - len(missing) + 0,
        "max_word_len": args.max_word_len,
        "max_phrase_len": args.max_phrase_len,
    }
    return words, {"freq": final_freq, "stats": stats}


# --------------------------------------------------------------------------- #


def main() -> int:
    ap = argparse.ArgumentParser(
        description="构建 char2id.json（输入）与 word_vocab.txt（输出词表）",
        formatter_class=argparse.RawDescriptionHelpFormatter,
    )
    ap.add_argument("--char-source", default="backbone:cluecorpus",
                    help="backbone:<名|hf-id>（真实）| corpus（冒烟）| file:<path>")
    ap.add_argument("--vocab-source", default="resource", choices=["resource", "synthetic"],
                    help="resource=jichu+lianxiang+成语/名句；synthetic=离线合成小词表")
    ap.add_argument("--size", type=int, default=30000, help="word_vocab 行数上限（含 <unk>）")
    ap.add_argument("--max-word-len", type=int, default=8, help="普通词最大字数")
    ap.add_argument("--max-phrase-len", type=int, default=24, help="强制入表的成语/名句最大字数")
    ap.add_argument("--max-forced-idioms", type=int, default=3000,
                    help="强制入表的成语上限（按频次取前 N）。成语表有 8519 条、名句 13703 条，"
                         "全塞进 3 万词表会把词频头部挤掉 2 万条，词表就不再是「高频词表」了")
    ap.add_argument("--max-forced-poems", type=int, default=1500,
                    help="强制入表的名句上限（按频次取前 N）")
    ap.add_argument("--idiom-fallback-freq", type=int, default=500,
                    help="拿不到成语表时，jichu 里四字词频次 ≥ 该值者视为成语")
    ap.add_argument("--resource-zip", type=Path,
                    default=Path(__file__).resolve().parents[2] / "app/src/main/assets/resource.zip")
    ap.add_argument("--assert-agreement", action="store_true", default=True,
                    help="在真实语料上断言 char2id 与 tokenizer 编码一致（默认开）")
    ap.add_argument("--min-char-coverage", type=float, default=0.80,
                    help="语料字符必须能被 char2id 表示的最低比例；默认 0.8。"
                         "字节级 BPE 底座（Wenzhong）在这里只有 ~0.23，必须拦下来")
    ap.add_argument("--allow-low-char-coverage", action="store_true",
                    help="明知道覆盖率低还要继续（训练会几乎全程喂 <unk>，慎用）")
    ap.add_argument("--strict-agreement", action="store_true",
                    help="不一致就退出非零（纯单字表的底座应能过）")
    ap.add_argument("--offline", action="store_true", help="不下载成语/名句表，只用本地退路")
    add_work_arg(ap)
    args = ap.parse_args()

    lay = layout_from_args(args)
    prepare_hf_env(lay.root)
    repo_root = Path(__file__).resolve().parents[2]

    freq, (forced, vocab_stats) = collect_words(args, lay, repo_root)
    log(f"词频合并完成：{vocab_stats['sources']}，强制入表 {vocab_stats['forced']}")

    char_freq = corpus_chars(lay.corpus, limit_files=args.char_limit_files if hasattr(args, "char_limit_files") else 0)
    candidates = set(char_freq) | {ch for w in list(freq)[:200000] for ch in w}
    candidates = {ch for ch in candidates if ch.isprintable() and not ch.isspace()}
    log(f"候选字符 {len(candidates)}（来自语料 {len(char_freq)} 种）")

    if args.char_source.startswith("backbone:"):
        backbone = resolve_backbone(args.char_source.split(":", 1)[1])
        char2id, char_meta = build_char2id_backbone(backbone, candidates, lay.root)
    elif args.char_source == "corpus":
        char2id, char_meta = build_char2id_corpus(char_freq)
        backbone = "smoke"
    elif args.char_source.startswith("file:"):
        data = json.loads(Path(args.char_source.split(":", 1)[1]).read_text(encoding="utf-8"))
        char2id = {k: int(v) for k, v in data.items()}
        char2id.setdefault("<unk>", 0)
        char2id.setdefault("<pad>", 1)
        char_meta = {"source": args.char_source}
        backbone = "smoke"
    else:
        raise SystemExit(f"未知 --char-source {args.char_source}")

    log(f"char2id：{len(char2id)} 项（{char_meta['source']}）")

    total_chars = max(1, sum(char_freq.values()))
    oov_char_rate = sum(v for ch, v in char_freq.items() if char2id.get(ch, 0) <= 1) / total_chars
    failure = char_coverage_failure(oov_char_rate, args.min_char_coverage)
    if failure:
        if args.allow_low_char_coverage:
            log(f"⚠ {failure}\n    （已用 --allow-low-char-coverage 放行）")
        else:
            raise SystemExit(failure)

    words, extra = build_word_vocab(args, freq, forced, char2id)
    freq_out, word_stats = extra["freq"], extra["stats"]
    log(f"word_vocab：{word_stats['size_actual']} 行，强制入表挤掉 {word_stats['forced_displaced_base_words']} 个普通词")

    # 一致性断言：在真实语料里抽一段
    sample_text = ""
    for path in sorted(lay.corpus.glob("*.txt")):
        with path.open("r", encoding="utf-8") as fh:
            for line in fh:
                if len(line) >= 200:
                    sample_text = line[:400]
                    break
        if sample_text:
            break
    agreement = verify_agreement(char2id, backbone, sample_text) if sample_text else {"checked_chars": 0}
    if sample_text:
        log(f"char2id 校验：{agreement}")
        if agreement.get("per_char_mismatch"):
            msg = f"逐字 id 与 tokenizer 不一致 {agreement['per_char_mismatch']} 个"
            if args.strict_agreement:
                raise SystemExit(msg)
            log(f"⚠ {msg}")

    lay.make("vocab")
    ordered = {"<unk>": 0, "<pad>": 1}
    for ch, cid in sorted(char2id.items(), key=lambda kv: kv[1]):
        if cid > 1:
            ordered[ch] = cid
    write_json(lay.char2id, ordered)
    lay.word_vocab.write_text("\n".join(words) + "\n", encoding="utf-8")
    write_json(lay.word_freq, freq_out)

    char_vocab_size = max(ordered.values()) + 1
    manifest = {
        "backbone": backbone,
        "char_source": char_meta,
        "char_vocab_size": char_vocab_size,
        "char_count": len(ordered) - 2,
        "word_vocab_size": len(words),
        "vocab_source": args.vocab_source,
        "vocab_stats": vocab_stats,
        "word_stats": word_stats,
        "agreement": agreement,
        "corpus_oov_char_rate": oov_char_rate,
        "min_char_coverage": args.min_char_coverage,
    }
    write_json(lay.vocab / "vocab_manifest.json", manifest)
    print(json.dumps({k: manifest[k] for k in ("char_vocab_size", "word_vocab_size", "agreement")},
                     ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
