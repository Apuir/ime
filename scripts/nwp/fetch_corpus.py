#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""抓取并清洗 NWP 训练语料。

只下「够冒烟的那一点」是刻意设计：三个数据集全量 3.7 GB，而这一层的正确性
（字段名、编码、清洗、分片）用小样本就能验证完；全量下载留给用户在自己的机器上跑。
所以每个数据集都支持 `--limit`（条数）/ `--shards`（源文件数 / 分片数）。

输出：`<work>/corpus/<dataset>-NNN.txt`，**一行一条文档**，UTF-8，清洗后的简体中文。
`<work>/corpus/manifest.json` 记录来源 URL、许可证、实测条数与命令，供 README 引用。

来源（均可免登录下载）：
  wiki      0xDing/wikipedia-cn-20230720-filtered  524 MB  CC-BY-SA-3.0  百科长文
  thucnews  Tongjilibo/THUCNews                   2.24 GB  Apache-2.0   新闻，按分类分文件
  lccc      silver/lccc                            979 MB  MIT          对话语体，最贴输入法
"""

from __future__ import annotations

import argparse
import codecs
import json
import random
import sys
import urllib.error
import urllib.parse
import urllib.request
import zlib
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

from nwp_common import Layout, add_work_arg, clean_doc, layout_from_args, log, write_json  # noqa: E402

HF = "https://hf-mirror.com/datasets"

DATASETS = {
    "wiki": {
        "repo": "0xDing/wikipedia-cn-20230720-filtered",
        "file": "wikipedia-cn-20230720-filtered.json",
        "bytes": 524_000_000,
        "license": "CC-BY-SA-3.0",
        "kind": "json-array",
        "text_key": "completion",
        "note": "百科长文本，主力通顺语料",
    },
    "thucnews": {
        "repo": "Tongjilibo/THUCNews",
        "bytes": 2_241_000_000,
        "license": "Apache-2.0",
        "kind": "jsonl-per-category",
        "text_key": "content",
        "title_key": "title",
        "categories": [
            "体育", "娱乐", "家居", "彩票", "房产", "教育", "时尚",
            "时政", "星座", "游戏", "社会", "科技", "股票", "财经",
        ],
        "note": "新闻；每个分类一个 jsonl（体育单类就 403 MB），所以必须支持按分类/条数截取",
    },
    "lccc": {
        "repo": "silver/lccc",
        "file": "lccc_large.jsonl.gz",
        "bytes": 979_100_000,
        "license": "MIT",
        "kind": "jsonl-gz",
        "text_key": "dialogue",
        "note": "对话语体，最接近输入法；原文已分词，空格要删（见 clean 中的 dejoin）",
    },
}

SYNTHETIC_WORDS = [
    # 词表刻意造出「长词包含短词」的情况（北京大学 / 北京），用来验证前向最大匹配
    # 真的取了最长匹配，而不是退化成单字切。
    ("北京大学", 900), ("北京", 4200), ("大学", 2600), ("今天", 5200), ("天气", 3400),
    ("不错", 3100), ("我们", 8800), ("一起", 4400), ("去", 9600), ("公园", 2100),
    ("散步", 1500), ("明天", 4300), ("可能", 3900), ("会", 9900), ("下雨", 2600),
    ("记得", 3000), ("带伞", 900), ("这个", 8600), ("周末", 2800), ("打算", 2500),
    ("回家", 2700), ("看看", 3300), ("父母", 2400), ("他们", 6800), ("身体", 2900),
    ("还好", 1900), ("工作", 6200), ("最近", 4100), ("很忙", 2300), ("项目", 3600),
    ("终于", 3400), ("上线", 1200), ("大家", 5600), ("辛苦", 1800), ("谢谢", 4700),
    ("支持", 3900), ("继续", 3500), ("努力", 3200), ("加油", 2800), ("问题", 6400),
    ("解决", 3100), ("已经", 7600), ("完成", 3300), ("可以", 9200), ("开始", 5900),
    ("新的", 2600), ("计划", 3000), ("希望", 4800), ("顺利", 1700), ("朋友", 5100),
    ("吃饭", 2900), ("什么", 7400), ("时候", 5500), ("有空", 2000), ("一起", 4400),
    ("学习", 3800), ("中文", 2200), ("输入法", 1300), ("联想", 1100), ("预测", 1400),
    ("模型", 1900), ("训练", 1600), ("数据", 2400), ("效果", 2100), ("提高", 2300),
    ("心想事成", 800), ("万事如意", 700), ("身体健康", 900), ("床前明月光", 600),
    ("疑是地上霜", 560), ("举头望明月", 520), ("低头思故乡", 500),
]

SYNTH_TEMPLATES = [
    ["今天", "天气", "不错", "我们", "一起", "去", "公园", "散步"],
    ["明天", "可能", "会", "下雨", "记得", "带伞"],
    ["这个", "周末", "打算", "回家", "看看", "父母"],
    ["他们", "的", "身体", "还好", "工作", "最近", "很忙"],
    ["项目", "终于", "上线", "大家", "辛苦", "谢谢", "支持"],
    ["继续", "努力", "加油", "问题", "已经", "解决"],
    ["可以", "开始", "新的", "计划", "希望", "顺利"],
    ["朋友", "一起", "吃饭", "什么", "时候", "有空"],
    ["我", "在", "北京大学", "学习", "中文", "输入法"],
    ["祝", "你", "心想事成", "万事如意", "身体健康"],
    ["床前明月光", "疑是地上霜", "举头望明月", "低头思故乡"],
    ["联想", "预测", "模型", "训练", "数据", "效果", "提高"],
]


def http_chunks(url: str, chunk: int = 1 << 20, max_bytes: int | None = None, timeout: float = 120.0):
    req = urllib.request.Request(url, headers={"User-Agent": "ime-nwp/1.0"})
    with urllib.request.urlopen(req, timeout=timeout) as resp:
        got = 0
        while True:
            data = resp.read(chunk)
            if not data:
                return
            got += len(data)
            yield data
            if max_bytes is not None and got >= max_bytes:
                return


def stream_json_array(url: str, limit: int, max_bytes: int | None = None, timeout: float = 120.0):
    """流式读 JSON 数组，读够 limit 条就把连接掐掉。

    这样 `--limit 200` 只需要下几 MB，而不是 524 MB——冒烟验证的关键。
    """
    dec = json.JSONDecoder()
    inc = codecs.getincrementaldecoder("utf-8")("ignore")
    buf = ""
    idx = 0
    started = False
    count = 0
    for raw in http_chunks(url, max_bytes=max_bytes, timeout=timeout):
        buf += inc.decode(raw)
        if not started:
            pos = buf.find("[")
            if pos < 0:
                continue
            buf = buf[pos + 1 :]
            idx = 0
            started = True
        while True:
            while idx < len(buf) and buf[idx] in " \t\r\n,":
                idx += 1
            if idx >= len(buf):
                break
            try:
                obj, idx = dec.raw_decode(buf, idx)
            except ValueError:
                break  # 当前缓冲还不完整，等下一块
            yield obj
            count += 1
            if count >= limit:
                return
        if idx > (1 << 20):
            buf = buf[idx:]
            idx = 0


def stream_lines(url: str, gz: bool = False, max_bytes: int | None = None, chunk: int = 1 << 20,
                 timeout: float = 120.0):
    inc = codecs.getincrementaldecoder("utf-8")("ignore")
    dec = zlib.decompressobj(31) if gz else None
    buf = ""
    for raw in http_chunks(url, chunk=chunk, max_bytes=max_bytes, timeout=timeout):
        data = dec.decompress(raw) if gz else raw
        buf += inc.decode(data)
        while True:
            pos = buf.find("\n")
            if pos < 0:
                break
            line, buf = buf[:pos], buf[pos + 1 :]
            if line.strip():
                yield line


class ShardWriter:
    def __init__(self, lay: Layout, dataset: str, docs_per_shard: int, max_docs: int | None) -> None:
        self.lay = lay
        self.dataset = dataset
        self.docs_per_shard = docs_per_shard
        self.max_docs = max_docs
        self.files: list[str] = []
        self.total_raw = 0
        self.total_kept = 0
        self._fh = None
        self._in_shard = 0
        self._shard_index = 0
        # 重跑前先清掉同名旧分片：否则上一轮多出来的分片会留在地板上，
        # manifest 只记这一轮写的文件，语料目录却混着两轮的内容。
        for stale in sorted(self.lay.corpus.glob(f"{dataset}-*.txt")):
            stale.unlink()

    def _open(self) -> None:
        # 分片号必须自增。曾经漏了这一句：每片都叫 -000.txt 且以 "w" 打开，
        # 于是每开新片就把上一片截断——报告说保留 25 万条、磁盘上只剩 5 万条，
        # 而且全程静默。所以这里既自增，又断言文件名不重复。
        path = self.lay.corpus / f"{self.dataset}-{self._shard_index:03d}.txt"
        self._shard_index += 1
        if path.name in self.files:
            raise RuntimeError(f"分片文件名重复：{path.name}（分片号没有自增，会导致静默覆盖）")
        self.files.append(path.name)
        self._fh = path.open("w", encoding="utf-8")
        self._in_shard = 0

    def add(self, doc: str) -> None:
        self.total_raw += 1
        if self._fh is None or self._in_shard >= self.docs_per_shard:
            if self._fh is not None:
                self._fh.close()
            self._open()
        self._fh.write(doc + "\n")
        self._in_shard += 1
        self.total_kept += 1

    def close(self) -> None:
        if self._fh is not None:
            self._fh.close()
            self._fh = None
        if len(set(self.files)) != len(self.files):
            raise RuntimeError(f"分片列表里有重名：{self.files}")

    @property
    def shard_summary(self) -> str:
        head = ", ".join(self.files[:3]) + ("…" if len(self.files) > 3 else "")
        return f"{len(set(self.files))} 个分片（{head}）"

    @property
    def full(self) -> bool:
        return self.max_docs is not None and self.total_kept >= self.max_docs

    @property
    def room(self) -> int | None:
        if self.max_docs is None:
            return None
        return max(0, self.max_docs - self.total_kept)


def fetch_wiki(args, lay: Layout, writer: ShardWriter) -> None:
    cfg = DATASETS["wiki"]
    url = f"{HF}/{cfg['repo']}/resolve/main/{cfg['file']}"
    # lccc 是 gz，wiki 不是；downloads 有上限，避免误下全量
    cap = args.max_bytes or (args.shards * args.shard_bytes if args.shards else None)
    log(f"wiki: {url} (limit={args.limit} docs, cap={cap} bytes)")
    for obj in stream_json_array(url, limit=args.limit, max_bytes=cap, timeout=args.timeout):
        if writer.full:
            break
        text = obj.get(cfg["text_key"]) or obj.get("text") or ""
        doc = clean_doc(text, min_cjk=args.min_cjk, max_chars=args.max_chars)
        if doc:
            writer.add(doc)
        if args.verbose and writer.total_raw % 2000 == 0:
            log(f"  raw={writer.total_raw} kept={writer.total_kept}")


def fetch_thucnews(args, lay: Layout, writer: ShardWriter) -> None:
    cfg = DATASETS["thucnews"]
    cats = cfg["categories"]
    if args.categories:
        want = [c.strip() for c in args.categories.split(",") if c.strip()]
        unknown = [c for c in want if c not in cats]
        if unknown:
            raise SystemExit(f"未知分类 {unknown}；可选：{cats}")
        cats = want
    if args.shards:
        cats = cats[: args.shards]
    per_cat = args.limit
    log(f"thucnews: categories={cats} per_category={per_cat}")
    for cat in cats:
        if writer.full:
            break
        url = f"{HF}/{cfg['repo']}/resolve/main/{urllib.parse.quote(cat)}.jsonl"
        room = writer.room
        cat_limit = per_cat if per_cat is not None else room
        if room is not None:
            cat_limit = min(cat_limit if cat_limit is not None else room, room)
        got = 0
        try:
            for line in stream_lines(url, gz=False, max_bytes=args.max_bytes, timeout=args.timeout):
                if cat_limit is not None and got >= cat_limit:
                    break
                try:
                    obj = json.loads(line)
                except json.JSONDecodeError:
                    continue
                text = (obj.get(cfg["title_key"]) or "") + "。" + (obj.get(cfg["text_key"]) or "")
                doc = clean_doc(text, min_cjk=args.min_cjk, max_chars=args.max_chars)
                got += 1
                if doc:
                    writer.add(doc)
        except urllib.error.HTTPError as e:
            log(f"  跳过分类 {cat}: HTTP {e.code}")
            continue
        log(f"  {cat}: kept={writer.total_kept} (raw total={writer.total_raw})")


def fetch_lccc(args, lay: Layout, writer: ShardWriter) -> None:
    cfg = DATASETS["lccc"]
    url = f"{HF}/{cfg['repo']}/resolve/main/{cfg['file']}"
    cap = args.max_bytes or (args.shards * args.shard_bytes if args.shards else None)
    log(f"lccc: {url} (limit={args.limit} docs, cap={cap} bytes)")
    got = 0
    for line in stream_lines(url, gz=True, max_bytes=cap, timeout=args.timeout):
        if writer.full or got >= args.limit:
            break
        try:
            obj = json.loads(line)
        except json.JSONDecodeError:
            continue
        # lccc 的行**本身就是**一个 JSON 数组（多轮对话），不是 {"dialogue": [...]}
        if isinstance(obj, list):
            turns = obj
        elif isinstance(obj, dict):
            turns = obj.get(cfg["text_key"]) or obj.get("dialogue") or []
        else:
            continue
        for turn in turns:
            if writer.full or got >= args.limit:
                break
            doc = clean_doc(str(turn), min_cjk=args.min_cjk, max_chars=args.max_chars)
            got += 1
            if doc:
                writer.add(doc)


def fetch_synthetic(args, lay: Layout, writer: ShardWriter) -> None:
    """本地生成一份确定性的小语料。

    冒烟测试不能依赖网络：这一路让整条链路（清洗→词表→滑窗→训练→导出）在离线
    环境下也能跑通，且内容是小学生句式，人工看一眼就知道样本对不对。
    """
    rng = random.Random(args.seed)
    words = [w for w, _ in SYNTHETIC_WORDS]
    noise = "龘靐齉爩龗灪厵"
    pool: list[str] = []
    docs = 0
    while docs < args.limit:
        if pool and rng.random() < 0.15:
            # 刻意注入跨分片泄漏：在旧文档前面加一个字，hash 变了 → 落进另一个分片，
            # 但尾部 64 字与原文档完全一致。去重必须抓住它，否则这条冒烟测试就是假的。
            text = rng.choice("啊哦嗯诶") + rng.choice(pool)
        else:
            # 一段 = 多句拼起来：单句只有十几字，滑窗出不了几个样本，
            # 而且上下文短到看不出「吃一整段」这件事。
            sentences = []
            for _ in range(rng.randint(3, 6)):
                if rng.random() < 0.75:
                    # 大部分句子随机取词：模板只有 12 条，全用模板的话文档之间
                    # 8-gram 重合度接近 100%，测试集会被去重清空。
                    sent = [rng.choice(words) for _ in range(rng.randint(5, 9))]
                else:
                    sent = list(rng.choice(SYNTH_TEMPLATES))
                    if rng.random() < 0.35:
                        sent.insert(rng.randrange(len(sent) + 1), rng.choice(words))
                if rng.random() < 0.2:
                    # 掺一点生僻字：<unk> 标签必须在 loss 里被忽略，这条路径要有样本覆盖
                    sent.append(rng.choice(noise) + rng.choice(noise))
                sentences.append("".join(sent))
            text = "。".join(sentences) + "。"
        doc = clean_doc(text, min_cjk=args.min_cjk, max_chars=args.max_chars)
        if doc:
            writer.add(doc)
            pool.append(doc)
        docs += 1
    # 词表跟着语料一起产出：synthetic 模式下 build_vocab 直接用这份清单，
    # 不依赖 resource.zip，也不依赖网络。
    lines = ["<unk>\t0"]
    seen: set[str] = set()
    for w, f in SYNTHETIC_WORDS:
        if w in seen:
            continue
        seen.add(w)
        lines.append(f"{w}\t{f}")
    (lay.corpus / "synthetic_words.txt").write_text("\n".join(lines) + "\n", encoding="utf-8")


def main() -> int:
    ap = argparse.ArgumentParser(
        description="下载并清洗 NWP 语料（默认只取小样本；全量命令见 README）",
        formatter_class=argparse.RawDescriptionHelpFormatter,
    )
    ap.add_argument("--dataset", default="wiki",
                    help="wiki | thucnews | lccc | synthetic（本地合成，冒烟用）| all（三个真实数据集）")
    ap.add_argument("--limit", type=int, default=500, help="每个数据集最多取多少条文档")
    ap.add_argument("--shards", type=int, default=0,
                    help="thucnews=只取前 N 个分类；单文件数据集=最多下 N×--shard-bytes")
    ap.add_argument("--shard-bytes", type=int, default=32 << 20, help="--shards 的单片字节上限")
    ap.add_argument("--max-bytes", type=int, default=0, help="单个数据集的下载字节硬上限（0=不限）")
    ap.add_argument("--categories", default="", help="thucnews 分类，逗号分隔，如 体育,财经,科技")
    ap.add_argument("--shard-docs", type=int, default=100_000, help="每个输出文件最多多少条文档")
    ap.add_argument("--min-cjk", type=int, default=20, help="少于这么多汉字的文档丢弃")
    ap.add_argument("--max-chars", type=int, default=2000, help="单文档字符上限（按标点就近截断）")
    ap.add_argument("--timeout", type=float, default=120.0,
                    help="单个 HTTP 读的超时秒数；镜像慢时可以调大（默认 120）")
    ap.add_argument("--seed", type=int, default=20260921)
    ap.add_argument("--verbose", action="store_true")
    add_work_arg(ap)
    args = ap.parse_args()
    if args.max_bytes == 0:
        args.max_bytes = None

    lay = layout_from_args(args)
    lay.make("corpus")
    # `all` 只指三个真实数据集；synthetic 是冒烟用的，必须显式点名，免得混进真实语料
    names = list(DATASETS) if args.dataset == "all" else [args.dataset]
    failures: dict[str, str] = {}
    for name in names:
        if name not in list(DATASETS) + ["synthetic"]:
            raise SystemExit(f"未知 --dataset {name}")
        writer = ShardWriter(lay, name, args.shard_docs, args.limit)
        entry: dict = {}
        try:
            if name == "wiki":
                fetch_wiki(args, lay, writer)
            elif name == "thucnews":
                fetch_thucnews(args, lay, writer)
            elif name == "lccc":
                fetch_lccc(args, lay, writer)
            else:
                fetch_synthetic(args, lay, writer)
            writer.close()
        except Exception as e:  # noqa: BLE001 抓取失败必须留下记录，且不能留下一地半截分片
            writer.close()
            failures[name] = f"{type(e).__name__}: {e}"
            # 半截语料比没有语料更危险（会被下游当成完整语料拿去训），所以整片删掉。
            # manifest 仍要写这一条（kept_docs=0 + failed=原因），否则目录里空空如也却查不出为什么。
            for stale in lay.corpus.glob(f"{name}-*.txt"):
                stale.unlink()
            log(f"✗ {name} 抓取失败，已删除半截分片：{failures[name]}")
            entry = {"kept_docs": 0, "raw_docs": writer.total_raw, "shards": [], "failed": failures[name]}
        else:
            log(f"{name}: 原始 {writer.total_raw} 条 → 保留 {writer.total_kept} 条 → {writer.shard_summary}")
            # 报告条数与磁盘行数必须一致：分片覆盖/清空出问题时，这条断言是唯一的护栏
            on_disk = 0
            for shard in writer.files:
                with (lay.corpus / shard).open("r", encoding="utf-8") as fh:
                    on_disk += sum(1 for line in fh if line.strip())
            if on_disk != writer.total_kept:
                raise RuntimeError(f"{name}: manifest 记录 {writer.total_kept} 条，磁盘上只有 {on_disk} 条"
                                   "（分片写入被截断或分片被覆盖）")
            entry = {"kept_docs": writer.total_kept, "raw_docs": writer.total_raw,
                     "shards": writer.files, "failed": failures.get(name)}

        manifest_path = lay.corpus / "manifest.json"
        manifest = json.loads(manifest_path.read_text(encoding="utf-8")) if manifest_path.exists() else {}
        cfg = DATASETS.get(name, {})
        manifest[name] = {
            "source": f"{HF}/{cfg['repo']}" if cfg else "local synthetic generator",
            "file": cfg.get("file", "synthetic.txt"),
            "license": cfg.get("license", "n/a (generated)"),
            "full_bytes": cfg.get("bytes", 0),
            "note": cfg.get("note", "离线合成，冒烟用"),
            "limit": args.limit,
            "categories": args.categories or None,
        } | entry
        write_json(manifest_path, manifest)

    if failures:
        log("以下数据集抓取失败（manifest 里已记 failed，半截分片已删除）：")
        for name, reason in failures.items():
            log(f"  - {name}: {reason}")
        print(f"语料目录：{lay.corpus}（{len(failures)} 个数据集失败，退出码 1）")
        return 1
    print(f"语料目录：{lay.corpus}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
