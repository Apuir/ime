#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""`scripts/nwp` 的共享工具：目录布局、文本清洗、词表/分词、指标。

为什么单独一个模块：语料清洗口径、分词口径、指标口径一旦在多个脚本里各写一份，
训出来的模型和评出来的分数就对不上——「同一段文本在不同脚本里切出不同的词」
是这个项目最容易出的静默 bug。所以这些规则只在这里定义一次。
"""

from __future__ import annotations

import argparse
import hashlib
import json
import math
import re
import sys
import time
from pathlib import Path

# --------------------------------------------------------------------------- #
# 目录布局
# --------------------------------------------------------------------------- #

#: 工作目录一律在仓库内、且被 .gitignore 忽略；语料与权重绝不入库。
WORK_DIRNAME = ".nwp-work/nwp"

#: `manifest.context_tokens` 的默认值。**语义的权威定义在 `model.py` 的「ONNX 契约」一节**：
#: 它是模型能接受的**输入 id（字）上限**，端侧当字符预算用（取已上屏文本最后 N 个码点），
#: **不是词数**。S=128 个词对应的字数是 200–550；把这个字段写成词数，端侧会少喂约 40% 的上下文。
#: 数值放在这个不依赖 torch 的模块里，只是为了让 build_samples 这类纯文本脚本不必 import torch；
#: `model.py` 会 import 它并对外声明，避免出现第二份定义。
DEFAULT_MAX_CONTEXT_IDS = 256

#: 端侧 prompt 预算（设计里的「≈210 汉字 / 3–4 句」）。manifest.context_tokens 低于它，
#: 就等于静默砍掉设计要求的上下文长度。
DEVICE_PROMPT_BUDGET_CHARS = 210


def repo_root() -> Path:
    return Path(__file__).resolve().parents[2]


def default_work() -> Path:
    return repo_root() / WORK_DIRNAME


class Layout:
    """`<work>/` 下的固定布局。所有脚本都从这里取路径，避免各写各的。"""

    def __init__(self, work: Path | str) -> None:
        self.root = Path(work).expanduser().resolve()

    def __fspath__(self) -> str:  # 方便直接塞给需要 str 的库
        return str(self.root)

    @property
    def corpus(self) -> Path:
        return self.root / "corpus"

    @property
    def dicts(self) -> Path:
        return self.root / "dicts"

    @property
    def vocab(self) -> Path:
        return self.root / "vocab"

    @property
    def samples(self) -> Path:
        return self.root / "samples"

    @property
    def ckpt(self) -> Path:
        return self.root / "ckpt"

    @property
    def onnx(self) -> Path:
        return self.root / "onnx"

    @property
    def bin(self) -> Path:
        return self.root / "bin"

    @property
    def results(self) -> Path:
        return self.root / "results"

    @property
    def logs(self) -> Path:
        return self.root / "logs"

    def make(self, *which: str) -> None:
        names = which or ("corpus", "dicts", "vocab", "samples", "ckpt", "onnx", "bin", "results", "logs")
        for name in names:
            getattr(self, name).mkdir(parents=True, exist_ok=True)

    # 常用文件
    @property
    def char2id(self) -> Path:
        return self.vocab / "char2id.json"

    @property
    def word_vocab(self) -> Path:
        return self.vocab / "word_vocab.txt"

    @property
    def word_freq(self) -> Path:
        return self.vocab / "word_freq.json"


def add_work_arg(parser: argparse.ArgumentParser) -> None:
    parser.add_argument(
        "--work",
        type=Path,
        default=default_work(),
        help=f"工作目录（默认 {WORK_DIRNAME}；语料/词表/权重都放这里，不入库）",
    )


def layout_from_args(args: argparse.Namespace) -> Layout:
    lay = Layout(args.work)
    lay.make()
    return lay


def log(msg: str) -> None:
    print(f"[{time.strftime('%H:%M:%S')}] {msg}", file=sys.stderr, flush=True)


def prepare_hf_env(work: Path) -> None:
    """HF 的镜像与缓存必须在 `import transformers` **之前**落地，否则会去连被封的 huggingface.co。

    缓存也放工作目录：容器里 `/tmp` 每次调用都重置，缓存放那儿等于每次都重下。
    """
    import os

    os.environ.setdefault("HF_ENDPOINT", "https://hf-mirror.com")
    os.environ.setdefault("HF_HOME", str(Path(work).expanduser().resolve() / "hf"))
    Path(os.environ["HF_HOME"]).mkdir(parents=True, exist_ok=True)


#: 真实底座候选（Phase 1 探针要各微调一版对比）
BACKBONES = {
    "wenzhong": {
        "hf_id": "IDEA-CCNL/Wenzhong-GPT2-110M",
        "params": "110M",
        "layers": 12,
        "license": "Apache-2.0",
        "note": "悟道 300G 语料，字级",
    },
    "cluecorpus": {
        "hf_id": "uer/gpt2-chinese-cluecorpussmall",
        "params": "102M",
        "layers": 12,
        "license": "Apache-2.0",
        "note": "CLUECorpusSmall，字级；vocab.txt 纯单字，char 映射无损",
    },
    "smoke": {
        "hf_id": "smoke",
        "params": "0.1M",
        "layers": 2,
        "license": "n/a",
        "note": "随机初始化的小 GPT2，仅用于 CPU 冒烟，不代表任何质量结论",
    },
    "smoke-b": {
        "hf_id": "smoke-b",
        "params": "0.5M",
        "layers": 3,
        "license": "n/a",
        "note": "第二个随机小 GPT2，给 probe.py 验证双底座对比流程",
    },
}


def resolve_backbone(name: str) -> str:
    """把 `wenzhong` / `smoke` 这类短名换成 HF id；原样传 HF id 也允许。"""
    return BACKBONES.get(name, {}).get("hf_id", name)


# --------------------------------------------------------------------------- #
# 文本清洗
# --------------------------------------------------------------------------- #

#: 保留的字符：CJK 汉字、常用中文标点、ASCII 可见字符。
#: 不保留表情/制表符/私用区——这些在输入法上屏文本里几乎不出现，留着只会污染词表和 n-gram 去重。
_CJK_RANGES = (
    (0x3400, 0x4DBF),   # 扩展 A
    (0x4E00, 0x9FFF),   # 基本区
    (0xF900, 0xFAFF),   # 兼容汉字
)
_CJK_PUNCT = set("，。！？、；：（）《》〈〉「」『』【】“”‘’…—·～￥％＃＠＆＊＋－／＝｜＼")
_ASCII_OK = set(
    "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
    " .,!?;:'\"()[]{}<>+-*/=_%$#@&|~^`\\"
)

_HTML_TAG = re.compile(r"<[^>]{1,60}>")
_WS_RUN = re.compile(r"[ \t\r\f\v]+")


def is_cjk(ch: str) -> bool:
    cp = ord(ch)
    for lo, hi in _CJK_RANGES:
        if lo <= cp <= hi:
            return True
    return False


def has_cjk(ch: str) -> bool:
    return is_cjk(ch)


def cjk_count(text: str) -> int:
    return sum(1 for ch in text if is_cjk(ch))


def _cjkish(ch: str) -> bool:
    """汉字或中文标点。lccc 的分词空格两侧常常是标点（`别招 、 他`）。"""
    return bool(ch) and (is_cjk(ch) or ch in _CJK_PUNCT)


def dejoin_cjk_spaces(text: str) -> str:
    """删掉夹在中文之间的 ASCII 空格。

    lccc 是**预先分好词**的（`火锅 我 在 重庆`），那些空格是分词痕迹不是原文；
    留在语料里会变成满屏 `<unk>` 字符。英文/数字周围的空格是真实的，保留。
    """
    if " " not in text:
        return text
    out: list[str] = []
    n = len(text)
    for i, ch in enumerate(text):
        if ch == " ":
            prev = out[-1] if out else ""
            nxt = text[i + 1] if i + 1 < n else ""
            if _cjkish(prev) and _cjkish(nxt):
                continue
        out.append(ch)
    return "".join(out)


def clean_doc(
    text: str,
    min_cjk: int = 20,
    max_chars: int = 2000,
    keep_ascii: bool = True,
) -> str:
    """把一条原始样本清成「一行干净的中文」。返回空串表示该条不要。"""
    if not text:
        return ""
    text = _HTML_TAG.sub("", text)
    text = text.replace("\u3000", " ").replace("\xa0", " ")
    text = text.replace("\r\n", "\n").replace("\r", "\n")
    kept: list[str] = []
    for ch in text:
        if ch == "\n":
            kept.append(" ")
        elif is_cjk(ch) or ch in _CJK_PUNCT:
            kept.append(ch)
        elif keep_ascii and ch in _ASCII_OK:
            kept.append(ch)
        # 其余（emoji / 私用区 / 控制符）直接丢
    text = "".join(kept)
    # 先合并连续空格再去分词空格：被过滤掉的字符（如全角引号）会在两侧各留一个空格，
    # 不先合并的话「说 ␣␣ 他」不会被识别成「中文空格中文」。
    text = _WS_RUN.sub(" ", text)
    text = dejoin_cjk_spaces(text).strip()
    if cjk_count(text) < min_cjk:
        return ""
    if len(text) > max_chars:
        # 长文档按标点就近截断，避免把一句话拦腰砍断
        cut = text[:max_chars]
        for mark in ("。", "！", "？", "\n", "，"):
            pos = cut.rfind(mark)
            if pos >= max_chars // 2:
                return cut[: pos + 1]
        return cut
    return text


# --------------------------------------------------------------------------- #
# 词表 / 分词
# --------------------------------------------------------------------------- #


def load_word_vocab(path: Path) -> list[str]:
    words = Path(path).read_text(encoding="utf-8").split("\n")
    while words and words[-1] == "":
        words.pop()
    if not words or words[0] != "<unk>":
        raise SystemExit(f"{path}: 第 0 行必须是 <unk>")
    return words


def load_char2id(path: Path) -> dict[str, int]:
    data = json.loads(Path(path).read_text(encoding="utf-8"))
    if data.get("<unk>") != 0 or data.get("<pad>") != 1:
        raise SystemExit(f"{path}: 必须含 \"<unk>\":0 与 \"<pad>\":1")
    return {k: int(v) for k, v in data.items()}


class Segmenter:
    """前向最大匹配。**只用 word_vocab**，不用 jieba。

    为什么不用 jieba：标签必须是词表里的 id；任何与词表不一致的分词都会让
    「词典里的词」永远拿不到标签。而且端侧没有 jieba，训练/推理口径必须一致。
    """

    def __init__(self, words: list[str]) -> None:
        self.words = words
        self.vocab = set(words)
        self.max_len = max((len(w) for w in words), default=1)

    def segment(self, text: str) -> list[tuple[str, int, int]]:
        """返回 [(词, 起始字符下标, 结束字符下标)]；未登录部分按单字切并标记未登录。

        单字若不在一字词表里，用空串表示「未知词」——调用方据此把标签判为 <unk>。
        """
        out: list[tuple[str, int, int]] = []
        i = 0
        n = len(text)
        while i < n:
            if not is_cjk(text[i]):
                i += 1
                continue
            hit = ""
            end = i + 1
            upper = min(self.max_len, n - i)
            for length in range(upper, 1, -1):
                piece = text[i : i + length]
                if piece in self.vocab:
                    hit, end = piece, i + length
                    break
            if not hit:
                # 单字也查一次：词表里有一字词（“的”“了”）时它们才是合法标签
                if text[i] in self.vocab:
                    hit, end = text[i], i + 1
                else:
                    hit, end = text[i], i + 1  # 保留字面用于统计 OOV
            out.append((hit, i, end))
            i = end
        return out


def word_text_of(seg: list[tuple[str, int, int]], text: str, i: int) -> str:
    return seg[i][0]


# --------------------------------------------------------------------------- #
# JSON / JSONL / 哈希
# --------------------------------------------------------------------------- #


def write_json(path: Path, obj) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(obj, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def read_json(path: Path):
    return json.loads(Path(path).read_text(encoding="utf-8"))


def write_jsonl(path: Path, rows) -> int:
    path.parent.mkdir(parents=True, exist_ok=True)
    n = 0
    with path.open("w", encoding="utf-8") as fh:
        for row in rows:
            fh.write(json.dumps(row, ensure_ascii=False, separators=(",", ":")) + "\n")
            n += 1
    return n


def iter_jsonl(path: Path):
    with Path(path).open("r", encoding="utf-8") as fh:
        for line in fh:
            line = line.strip()
            if line:
                yield json.loads(line)


def sha256_file(path: Path) -> str:
    h = hashlib.sha256()
    with Path(path).open("rb") as fh:
        for chunk in iter(lambda: fh.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def file_bytes(path: Path) -> int:
    return Path(path).stat().st_size


# --------------------------------------------------------------------------- #
# n-gram 去重（评测集必须与训练集去重）
# --------------------------------------------------------------------------- #


def ngram_hashes(text: str, n: int = 8):
    """把文本的全部 n 字组合压成 64 位整数（crc32 + adler32 拼起来）。

    为什么不用原串做 key：3.7 GB 语料的 8-gram 有十亿量级，存字符串会吃光内存。
    64 位指纹在 10^8 量级下期望碰撞 <10^-3，实际不会造成误删。
    """
    import zlib

    if len(text) < n:
        if not text:
            return
        key = (zlib.crc32(text.encode("utf-8")) << 32) | zlib.adler32(text.encode("utf-8"))
        yield key
        return
    for i in range(len(text) - n + 1):
        piece = text[i : i + n].encode("utf-8")
        yield (zlib.crc32(piece) << 32) | zlib.adler32(piece)


def ngram_strings(text: str, n: int = 8):
    if len(text) < n:
        yield text
        return
    for i in range(len(text) - n + 1):
        yield text[i : i + n]


# --------------------------------------------------------------------------- #
# 指标
# --------------------------------------------------------------------------- #


def rank_metrics(ranks: list[int | None], topk: int = 5) -> dict:
    """ranks[i] = 正确词的排名（1 起）或 None（没进候选）。"""
    n = len(ranks)
    if n == 0:
        return {"n": 0, "top1": 0.0, "top5": 0.0, "mrr": 0.0, "covered": 0.0}
    hit1 = sum(1 for r in ranks if r == 1)
    hitk = sum(1 for r in ranks if r is not None and r <= topk)
    rr = sum((1.0 / r) for r in ranks if r is not None)
    return {
        "n": n,
        "top1": hit1 / n,
        "top5": hitk / n,
        "mrr": rr / n,
        "covered": sum(1 for r in ranks if r is not None) / n,
    }


def markdown_table(headers: list[str], rows: list[list[str]]) -> str:
    out = ["| " + " | ".join(headers) + " |", "|" + "|".join(["---"] * len(headers)) + "|"]
    for row in rows:
        out.append("| " + " | ".join(str(c) for c in row) + " |")
    return "\n".join(out) + "\n"


def fmt_pct(x: float | None) -> str:
    if x is None:
        return "-"
    return f"{x * 100:.2f}"


def bpb_from_nll(nll_sum: float, byte_count: int) -> float:
    """bits-per-byte：跨分词器唯一可比的似然指标。"""
    if byte_count <= 0:
        return float("nan")
    return nll_sum / byte_count / math.log(2)


def _cli() -> int:
    """这层只是为了让 `python nwp_common.py --help` 也能用（本目录所有脚本统一有 --help）。"""
    ap = argparse.ArgumentParser(description="scripts/nwp 的公共工具模块（不直接执行）")
    ap.add_argument("--work", type=Path, default=default_work(), help="打印默认工作目录后退出")
    ap.add_argument("--paths", action="store_true", help="打印工作目录布局")
    args = ap.parse_args()
    lay = Layout(args.work)
    print(f"work={lay.root}")
    if args.paths:
        for name in ("corpus", "dicts", "vocab", "samples", "ckpt", "onnx", "bin", "results"):
            print(f"  {name:9s} {getattr(lay, name)}")
    return 0


if __name__ == "__main__":
    raise SystemExit(_cli())
