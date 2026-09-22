# phrase-index

造**固定短语索引**（成语 / 歇后语 / 名句 / 诗句「上句→下句」），给输入法的联想多加一条「上下文尾部命中已知长短语就补全」的路。

为什么不用神经网络：这些都是**固定长短语**，候选是确定的——前缀匹配 100% 准确、微秒级、零功耗，让模型去生成只会更差更慢（依据见 `.research/联想升级-方案选型.md` §2、§3 路径 1）。所以这里没有模型，只有一份扁平索引 + 一张权重表。

## 输出格式（已冻结）

`phrase_index.tsv.gz`：gzip 压缩的 UTF-8 文本，每行三列 `key\tvalue\tweight`。

| 列 | 含义 |
|---|---|
| `key` | 触发串，与用户**已上屏文本的尾部**匹配。2..16 个码点，不含 tab / 换行 |
| `value` | 要给出的候选。非空，不含 tab / 换行 |
| `weight` | 整数 ≥1，越大越优先；既给候选排序，也用于按体积剪枝 |

行序固定为 `key` 升序 → `weight` 降序 → `value` 升序，查表与 `git diff` 都可复现。同一个 `key` 允许多行（实测 600,891 个键里有 **24,050** 个是多候选，例如 `一心` → 一心一意 / 一心二用，`不可` → 不可思议 / 不可开交 / 不可多得 / 不可磨灭）。

消费侧的做法（app 侧按此实现）：`len` 从 `min(16, tail.length)` 递减到 2，拿 `tail.takeLast(len)` 查 `Map<String, List<(value, weight)>>`，取第一个击中键的候选，即**最长匹配优先**——这一层由消费侧保证，索引只管把各种长度的键都写出来。

gzip 的 `mtime` 固定为 0，同样的输入产出同样的字节（实测重跑 md5 一致）。

## 用法

```bash
# 正式构建：默认 24 MiB 未压缩预算 + 唐诗/宋词/诗经/论语/蒙学
python3 scripts/phrase-index/build_phrase_index.py --work-dir .nwp-work

# 不剪枝，看全部来源的原始规模
python3 scripts/phrase-index/build_phrase_index.py --work-dir .nwp-work --max-bytes 0

# 并入宋诗（255 个文件，配对量翻 3 倍多，默认关闭）
python3 scripts/phrase-index/build_phrase_index.py --work-dir .nwp-work --include-songshi

# 并入 jichu 基础词库（142 万条 2-4 字词，默认关闭）
python3 scripts/phrase-index/build_phrase_index.py --work-dir .nwp-work --include-jichu

# 只重写索引不联网
python3 scripts/phrase-index/build_phrase_index.py --work-dir .nwp-work --offline

# 顺手复制到打包目录
python3 scripts/phrase-index/build_phrase_index.py --work-dir .nwp-work \
  --out-dir-asset app/src/main/assets/phrase

# 真语料验收 / 单元测试
python3 scripts/phrase-index/verify.py --work-dir .nwp-work
python3 scripts/phrase-index/test_phrase_index.py
```

| 参数 | 默认 | 说明 |
|---|---|---|
| `--work-dir` | `~/.ime-phrase` | 语料缓存在 `<work-dir>/phrase/raw/`，产物在 `<work-dir>/phrase/` |
| `--out` | `<work-dir>/phrase/phrase_index.tsv.gz` | 输出路径 |
| `--include-songshi` | 关 | 并入宋诗 |
| `--include-jichu` | 关 | 并入 jichu |
| `--max-bytes` | `25165824`（24 MiB） | **未压缩** TSV 预算，超出则剪掉最低权重条目；`0` 不限。接受 `24M` / `512K` 写法 |
| `--offline` | 关 | 只用 `raw/` 缓存 |
| `--out-dir-asset` | 无 | 额外把 gz 复制到该目录（目录不存在会创建） |

构建会额外写一份 `<out 同名>.manifest.json`，记录每来源的保留 / 被剪条数与体积——`verify.py` 靠它打印来源构成（索引格式只有三列，装不下来源信息）。

在容器里必须传 `--work-dir <repo>/.nwp-work`：`$HOME` 只读，且 `/tmp` 每次执行都会被清空。

## 语料来源（体积与条数均为本次实测）

| 用途 | 来源 | 许可证 | 实测条数 | 实测下载体积 |
|---|---|---|---|---|
| 成语全集 | <https://github.com/pwxcoo/chinese-xinhua> → `data/idiom.json` | MIT（Copyright 2018 PWXCOO） | **30,895** | 10,319,857 B（9.84 MiB） |
| 成语频次 | <https://github.com/thunlp/THUOCL> → `data/THUOCL_chengyu.txt` | MIT（Copyright 2018 THUNLP） | **8,519** 行 | 166,549 B |
| 名句 + 频次 | 同上 → `data/THUOCL_poem.txt` | MIT | **13,703** 行 | 294,695 B |
| 歇后语 | <https://github.com/pwxcoo/chinese-xinhua> → `data/xiehouyu.json` | MIT | **14,032** | 1,272,390 B（1.21 MiB） |
| 诗句上下句 | <https://github.com/chinese-poetry/chinese-poetry> | MIT（Copyright 2016 JackeyGao） | 见下表 | 默认 sparse checkout 落盘 **35 MiB**；仓库 ≈222 MiB |
| 本地词库 | 项目自带 `app/src/main/assets/resource.zip` | 随仓库 | shici 335,235 / lianxiang 162,566 / jichu 1,425,533 | 68,952,098 B（不下载，直接读 zip） |
| 四字常用语 | 同上 → `shared/lua/data/chengyu.txt` | 随仓库 | 26,048 个去重词，其中 18,489 已在 `idiom.json` 里，**7,559** 是额外条目 | 455,299 B |

诗词语料**没有整仓 clone**：`git clone --depth 1 --filter=blob:none --no-checkout` 只拿元数据，再用 `sparse-checkout` 按需拉需要的文件。默认集合（实测，去重前）：

| 文集 | 文件数 | 单元（首 / 篇） | 相邻句对（去重前 / 去重后） |
|---|---|---|---|
| 全唐诗（唐） | 58 | 57,603 | 454,012 / 438,450 |
| 宋词 | 24 | 21,333 | 272,676 / 265,935 |
| 蒙学（三字经 / 千字文 / 唐诗三百首 / 声律启蒙 …） | 12 | 849 | 38,094 / 37,860 |
| 诗经 | 1 | 305 | 6,903 / 6,731 |
| 论语 | 1 | 20 篇 | 3,619 / 3,545 |
| **合计** | **96** | | **775,304 / 749,876** |

`chinese-poetry` 默认集里**没有**李白《静夜思》的通行本——验收要的 `床前明月光 → 疑是地上霜` 来自 `蒙学/tangshisanbaishou.json`（唐诗三百首），这是把蒙学放进默认集合的直接原因。

## 键的三类规则

1. **前缀补全**（成语 / 名句 / 本地词库 / 四字常用语）：长度为 L 的短语，对 `2..min(L-1,16)` 的每个前缀出一行，**值是整条短语**。不出 L 及更长的键（用户已经上屏了整条短语，再给一遍是噪声），也不出单字键。实测例子：`画蛇添` → 画蛇添足(703820)、`胸有成` → 胸有成竹(704324)。
2. **配对补全**（歇后语）：`key` = 谜面，`value` = 谜底。实测 `竹篮打水` → 「一场空；枉费功」（原样保留多答案写法）。
3. **配对补全**（诗句）：`key` = 上句，`value` = 下句，取**一首诗内部相邻的句**（含跨联：上联末句→下联首句）。

配套的两个清洗：

- **繁简转换**：`chinese-poetry` 大量条目是繁体（`全唐诗` 尤甚：抽样 62,796 句里有 86% 含繁体字），用户上屏的是简体，不转就永远匹配不上。直接用 `resource.zip` 里的 `shared/lua/data/STCharacters.txt` + `STPhrases.txt` 做长词优先转换，省掉 opencc 依赖。
- **配对边界**取「最内层持有字符串列表的对象」：`全唐诗` 的一首诗就是这个对象，所以能跨它的各个 `paragraphs` 配「上联→下联」；而 `蒙学/唐诗三百首` 顶层对象是**整本选集**，以它为边界会把上一首的末句接到下一首的首句。上句超过 16 码点截到 16 码点（消费侧最多只看尾部 16 字，留更长的键等于永远匹配不上）。非汉字段、>32 字的下句丢掉。

## 权重与剪枝

`weight = TIER[来源] + FAME(词频)`，`FAME = 1000 + min(5000, round(1000·log10(freq)))`。分层间隔 1e5 远大于频率加成（≤6000），所以**优先级由来源分层决定、层内由词频决定**，剪枝次序完全可预测：

| 层 | 来源 | 权重基数 |
|---|---|---|
| 1 | 成语 | 700,000 |
| 2 | 四字常用语（chengyu.txt 非成语部分） | 650,000 |
| 3 | 歇后语 | 600,000 |
| 4 | 名句 | 500,000 |
| 5 | 诗句上句→下句 | 400,000 |
| 6 | shici 诗词句前缀 | 300,000 |
| 7 | lianxiang 长词前缀 | 200,000 |
| 8 | jichu 基础词前缀（默认不启用） | 100,000 |

诗句配对没有频次，用**名句表当「知名度」信号**：上句或下句出现在 `THUOCL_poem.txt` 里就按它的词频抬权重（实测 4,174 对命中），这也是 `床前明月光`（频次 181）能在剪枝后活下来的原因。

`--max-bytes` 从**最低层**开始砍，同层内按 `key` 升序砍（与输入顺序无关，可复现）。

**shici / lianxiang 在默认 24 MiB 下会被整个剪掉，这是有意的**：24 MiB 内诗句配对与它们互斥（单 shici 前缀就 39.94 MiB）。而且 `shared/wanxiang.dict.yaml` 本来就 `import_tables` 了 `dicts/shici`——单句补全（`春眠不觉` → 春眠不觉晓）走既有候选链路已经有了，短语索引对它的增量价值远不如「上句→下句」这种引擎做不到的配对。要它们就把预算加上去（见下一节）。

## 实测结果

### 默认（24 MiB 预算，唐诗/宋词/诗经/论语/蒙学）

命令：`python3 scripts/phrase-index/build_phrase_index.py --work-dir .nwp-work`（首次含下载 35 MiB 诗词语料约 1 分钟，`--offline` 重跑约 11 秒）

| 来源 | 记录 | 未压缩 | 被剪 |
|---|---:|---:|---:|
| 成语 | 61,920 | 1.71 MiB | 0 |
| 四字常用语 | 15,124 | 0.41 MiB | 0 |
| 歇后语 | 14,032 | 0.58 MiB | 0 |
| 名句 | 56,065 | 2.11 MiB | 0 |
| 诗句上句→下句 | 495,927 | 19.18 MiB | 253,948 |
| shici | 0 | 0 | 1,140,878 |
| lianxiang | 0 | 0 | 570,814 |
| **合计** | **643,068** | **24.00 MiB** | **1,965,640** |

gz 体积 **10.00 MiB**，键数 600,891。去重后总记录 2,607,216。

### 不限预算（`--max-bytes 0`）

| 来源 | 记录 | 未压缩 |
|---|---:|---:|
| 成语 | 61,920 | 1.71 MiB |
| 四字常用语 | 15,124 | 0.41 MiB |
| 歇后语 | 14,032 | 0.58 MiB |
| 名句 | 56,065 | 2.11 MiB |
| 诗句上句→下句 | 749,875 | 29.01 MiB |
| shici | 1,140,878 | 39.94 MiB |
| lianxiang | 570,814 | 19.59 MiB |
| **合计** | **2,608,708** | **93.36 MiB** |

gz **23.95 MiB**。想把 shici 也带上，预算要放到 ~70 MiB；要连 lianxiang 一起则要 ~90 MiB（全量 93.36 MiB）。

### 并入宋诗（`--include-songshi`，默认预算不变）

宋诗 255 个文件，配对量从 775,304 涨到 **2,663,645**（去重 2,616,748，即宋诗净增约 187 万对）。24 MiB 预算下诗句配对只剩 463,647 条、被剪 2,153,100 条——**宋诗把预算吃光却换不来多少可见收益**，所以默认关闭。

## 验收

`verify.py` 会对真语料跑：全量格式校验（列数 / 键长 / 权重 / 行序 / 空行）、诗句配对、成语前缀补全、歇后语配对，并打印体积与来源构成；任一项不过退出码非 0。`test_phrase_index.py` 用合成数据（不联网、不读 `resource.zip`）跑 29 条用例，覆盖三类规则、解析器、繁简转换、去重、剪枝、最长匹配。

`test_phrase_index.py` 里断言的是**规则**，`verify.py` 里断言的是**真实语料的内容**，两者都要绿。

## 刻意排除的东西

- **谚语 / 俗语**：没有找到可靠的开源中文词表（`.research/中文语料与词表资源.md` §B4：HF 全站按 `proverb / 谚语 / 俗语` 检索无有效中文结果，`funNLP` 目录树里也没有这一类）。**没有伪造，也不拿近似物冒充**——那 14,032 条歇后语结构上就是「上句→下句」，先顶这一格。
- **jichu（1,425,533 条 2–4 字词）**：默认关闭。Rime 引擎本身就在补全常用词，灌进索引只会多出 **1,857,585** 条低价值记录去挤诗句/成语；要的话用 `--include-jichu`。
- **宋诗**：默认关闭，理由见上一节。
- **御定全唐詩（900 文件，繁体，与全唐诗基本重复）、楚辞、元曲、四书五经**：不在默认集合里，按需在 `POETRY_PATTERNS` 加一行即可。
- **`idiom.json` 的释义 / 例句字段**：只用了 `word`。释义索引（「释义→成语」）是另一个功能，不属于前缀补全。

## 文件

| 文件 | 作用 |
|---|---|
| `build_phrase_index.py` | 下载 / 解析 / 生成键 / 剪枝 / 写出（只用标准库 + `curl` + `git`） |
| `test_phrase_index.py` | 合成数据单元测试（29 条，不联网） |
| `verify.py` | 真语料验收 + 来源构成报告 |

`build_phrase_index.py` 里的 `load_index()` / `lookup()` 是给 `verify.py` 与测试共用的**消费侧参考实现**，行为与 app 侧一致（从长到短取尾部子串查表）。
