# scripts/nwp —— 神经下一词联想（NWP）的可复现训练管线

> 对应设计与证据：`.research/联想升级-方案选型.md`（路径 2）、
> `.research/小模型训练与端侧部署.md`、`.research/中文语料与词表资源.md`、
> `.research/实施prompt-神经联想.md` §4/§5/§7。
> 本目录只是**脚本**；语料、词表、权重全部落在 `<repo>/.nwp-work/`（已 gitignore）。

## 一句话

微调一个中文**字级**预训练底座（`uer/gpt2-chinese-cluecorpussmall` 或
`IDEA-CCNL/Wenzhong-GPT2-110M`），在最后位置接一个**词级输出头** `Linear(d → 30000)`，
导出成 **int8 ONNX + KV cache**；输入是最近 128 个词的**字**，输出是下一个**词**。

## 在本容器里验证到什么程度（重要）

这台机器**没有 GPU**（无 `/dev/nvidia*`，torch 是 `+cpu`），所以：

| 环节 | 本机状态 | 说明 |
|---|---|---|
| 语料下载（wiki / THUCNews / lccc） | ✅ **实跑过小样本** | 分别取了 150 / 60 / 51 条文档，见下表实测耗时 |
| char2id 构建（真实底座 tokenizer） | ✅ **实跑过** | 5351 个字，逐字 id 与 tokenizer 完全一致（0 处不符） |
| word_vocab 30k（resource.zip 抽取） | ✅ **实跑过** | jichu 142.5 万 + lianxiang 16.3 万 + shici 33.5 万词 + 强制成语/名句 |
| 滑窗样本 + ≥8 字 n-gram 去重 | ✅ **实跑过** | 含边界自检（8 字命中 / 7 字放过）与 hash≈exact 一致性 |
| 训练管线（LoRA + 判别式 LR + 逐步解冻 + 断点续训） | ✅ **实跑过** | 随机小骨干 CPU 40 步；**真实底座 125.7M 也实跑过 3 步**（LoRA 解冻生效，1.9–2.4 s/步 @batch 2×2） |
| **Phase 1 质量探针（用户 4060 主机）** | ✅ **跑过** | cluecorpus 4000 步：top-1 **13.51%** vs unigram 0.06% / n-gram 0.57%（见「Phase 1 探针实测」） |
| 正式训练（全量语料 + 完整步数） | ✅ **跑过（用户 4060）** | 20000 步 / 5.8 h / 1.048 s 每步；512 条验证集 top-1 **5.27%**、top-5 **11.72%** |
| 提速包：多位置监督 / 分桶组批 / 轮流采样 / 词头热启动 / fp16 导出 | ✅ **实跑过** | 见「提速包实测」；全部有回归测试 |
| ONNX 导出 + int8 + manifest（**真实 125.7M**） | ✅ **实跑过** | fp32 图 2353 节点、对拍 100%；int8 **126.2 MB**；KV cache 对拍通过 |
| C++ n-gram 基线（marisa） | ✅ **实跑过** | 795 万条键全量枚举 1.2 s |
| **110M 正式微调的质量数字** | ❌ **没做** | 需要用户的 4060。本目录给出的 4060 时间/显存都是**估算**，不是实测 |
| **训练好之后的 int8 掉点** | ❌ **没测** | 见「未完成项」第 4 条：未训练模型的 int8 对拍说明不了问题 |
| 真机（K90 ProMax）延迟/内存 | ❌ 不在本目录范围 | Phase 4/5 |

一键自检：

```bash
bash scripts/nwp/smoke_test.sh          # CPU、离线、约 1 分钟，退出码 0 才算通过
```

## Phase 1 质量探针实测（用户 4060 主机）

两个底座、同数据、同步数、同评测集（`probe.py` 的产出格式）：

| 底座 | top-1 | top-5 | MRR | 说明 |
|---|---|---|---|---|
| `uer/gpt2-chinese-cluecorpussmall` | **13.51 %** | **25.03 %** | **0.1771** | 4000 步，评测 `--limit 200000` |
| 基线 unigram（词频） | 0.06 % | — | — | 神经模型的 **225×** |
| 基线 n-gram（`predict.marisa`） | 0.57 % | — | — | 神经模型的 **~24×** |
| `IDEA-CCNL/Wenzhong-GPT2-110M` | — | — | — | **没能评出来**：先撞上 fp16 骨干的 dtype 崩溃（Bug 2，已修），随后又发现它的 tokenizer 根本不适合字级输入（见下） |

**结论：用 `uer/gpt2-chinese-cluecorpussmall`。** 它明显打得过两个基线——
实施 prompt §4 说的「打不过词频基线就是早期告警」，这里没有触发。

两条必须一起看的前提：

1. **这个 13.51 % 是下限，不是最终数字。** 它是在**被 `ShardWriter` bug 截断过的语料**上训的
   （见「修过的真实 bug」）：磁盘上只有 187 MB / 11.9 万文档，而报告说保住了 152 万文档。
   修好之后重训应当更高。
2. **Wenzhong 不能用字级输入**，这不是调参问题：它的 tokenizer 是**字节级 BPE**
   （`今` → `[20015, 232]`，两个 token 才拼出一个汉字），能「一个字 = 一个 token」的只有
   **137 个字**，真实语料里 **77 %–86 % 的字会变成 `<unk>`**。
   而端侧是纯字级输入，`bert`/字表型 tokenizer 才匹配：
   `uer/gpt2-chinese-cluecorpussmall` 的 `vocab.txt` 每行正好一个字，实测未知字率 **1.5 %**。
   `build_vocab.py` 现在会在覆盖率 < 80 % 时**直接报错退出**（`--min-char-coverage`，
   要用 Wenzhong 得显式 `--allow-low-char-coverage`，或改用
   `--char-source file:<cluecorpus 的 char2id.json>` 明确表示「只借 id 行号」）。

## 提速包实测（本机 CPU 量的数，逻辑与 GPU 无关）

| 手段 | 改动 | 实测效果 |
|---|---|---|
| **A 多位置监督** | 一个样本监督多个位置（`labels: [[位置, 词id], ...]`），一次前向的 200+ 个字不再只为 1 个标签买单 | 标签/token：**0.0077 → 0.0606**（真实语料切片，**7.9×**；合成语料 0.0612 / 8.0×） |
| **B 长度分桶组批** | 缓冲窗口内按长度排序再切批（`--bucket`，默认开） | 真实 1200 万条样本的全量长度：随机批 padding 浪费 **17.93% → 0.10%**（batch=8、窗口 4096），batch=32 时 0.42% |
| **C 轮流采样** | 语料文件按行轮流读（`iter_docs_round_robin`），`--max-samples` 不再被第一个语料吃光；`report.sources` 逐个来源报数 | 三个来源各占 **33.3%**（此前 Wikipedia 一条都进不去） |
| **D 词头热启动** | `word_head[w]` = 该词字向量的均值（`--head-warmstart`，默认开；`--resume` 自动跳过） | 词头 23 M 参数不再从纯随机起步；跳过无已知字的词并报数 |
| **E fp16 导出** | `--fp16`（= `--no-int8`）产出 `nwp.fp16.onnx`，**I/O 保持 float32**；`--fp32` 才是真·保底 | 真实 125.7M：**500.9 MB → 250.7 MB**，与 fp32 的 top-1/top-5 一致率 **100%**（相对误差 0.20%），KV 增量一致 |

### A 的标签/token 为什么在真实语料上低于合成语料

一个样本的标签数 = `min(--max-labels, 上下文词数 / --label-stride)`，而 token 数 = `len(ids)`。
真实语料里**短上下文样本占比不小**（文档开头那一段、以及短文档），它们的分母小、分子更小，
于是整体比值被拉低。实测（3 个真实分片：lccc 2.9 MB + thucnews 95 MB + wiki 90 MB）：

| `--label-stride` | labels/样本 | labels/token | 相对 1/130 |
|---|---|---|---|
| 8 | 10.51 | 0.0351 | 4.6×（**不达标**） |
| **4（现默认）** | **12.93** | **0.0606** | **7.9×** |
| 2 | 20.88 | 0.0698 | 9.1× |

上表的复现命令（用真实语料里最小的三个分片，约 3 分钟）：

```bash
W=.nwp-work/nwp-realcheck; mkdir -p $W/corpus $W/vocab
for pref in lccc thucnews wiki; do cp "$(ls -S .nwp-work/nwp/corpus/$pref-*.txt | tail -1)" $W/corpus/; done
cp .nwp-work/nwp/vocab/{char2id.json,word_vocab.txt} $W/vocab/
$PY scripts/nwp/build_samples.py --work $W --context-words 128 --min-context-words 16 \
  --max-context-ids 256 --max-samples 1500000        # 报告在 $W/samples/report.json 的 labels 段
```

默认取 4 而不是 8 就是这张表定的：stride=8 在真实语料上只有 4.6×，达不到 5× 的目标；
而调小 stride **不增加骨干成本**——长上下文样本的标签数被 `--max-labels` 截住，
只有本来就更便宜的短上下文样本多拿几个标签。想再密一点可以 `--label-stride 2`，
或提高 `--max-labels`（长上下文线性增加，但每个标签的边际信息在变小）。

> 收益的正确读法：**不是**「步数减少 8 倍」。标签数变多只说明同样一次前向提供了更多监督信号，
> 实际能省多少步取决于任务与数据；保守地按 **2–4×** 估算，并且必须用真实训练曲线验证。

## 磁盘布局

```
<repo>/scripts/nwp/            脚本（入库）
<repo>/.nwp-work/              工作目录（gitignore，200+ GB 可用）
  venv/                        Python 3.12 环境（见下）
  hf/                          HF 缓存（HF_HOME，避免重复下载）
  nwp/
    corpus/  *.txt             一行一条文档的清洗后语料 + manifest.json
    dicts/                    从 resource.zip 解出的 jichu/lianxiang/shici + 成语/名句表
    vocab/   char2id.json     端侧输入表（字 → 骨干 token id）
             word_vocab.txt   端侧输出表（第 0 行 <unk>，行号即 word id）
             word_freq.json   词频（unigram 基线用）
             vocab_manifest.json
    samples/ train|valid|test.jsonl + report.json
    ckpt/    best.pt last.pt
    onnx/    nwp.onnx  nwp.int8.onnx  manifest.json  word_vocab.txt  char2id.json
    bin/     ngram_baseline  predict.marisa
    results/ eval_*.json  train_*.json  export_report.json
    probe/   两个底座的对比报告
```

**语料与权重绝不入库**：`.gitignore` 里已有 `/.nwp-work/`。

## 环境

```bash
cd <repo>
export UV_PYTHON_INSTALL_DIR=$PWD/.nwp-work/uv-python
export UV_CACHE_DIR=$PWD/.nwp-work/uv-cache
export UV_DATA_DIR=$PWD/.nwp-work/uv-data
export UV_LINK_MODE=copy
uv venv --python 3.12 .nwp-work/venv
uv pip install --python .nwp-work/venv/bin/python --index-url https://download.pytorch.org/whl/cpu torch
uv pip install --python .nwp-work/venv/bin/python --index-url https://pypi.tuna.tsinghua.edu.cn/simple \
  transformers peft onnx onnxruntime accelerate datasets numpy tqdm
# 4060 上把 torch 换成 CUDA 版即可（cu124/cu126 视驱动而定）
```

本机实测版本：`torch 2.14.0+cpu`、`transformers 5.17.0`、`onnxruntime 1.30.0`、
`onnx 1.23.0`、`peft 0.21.0`、`numpy 2.5.3`。
`transformers` **必须 ≥5**：v5 起 `GPT2Model` 只接受 `DynamicCache`，
legacy tuple 会直接抛 `AttributeError: 'tuple' object has no attribute 'get_query_offset'`
（`model.py` 里已做转换，见 `past_to_cache`）。

网络：

```bash
export HF_ENDPOINT=https://hf-mirror.com   # huggingface.co 在本机被封，镜像可用
export HF_HOME=$PWD/.nwp-work/hf           # 缓存放工作目录（/tmp 每次调用都会重置）
```

n-gram 基线需要 `libmarisa` 与 `g++`（Debian 包 `libmarisa-dev`）：

```bash
ls /usr/include/marisa.h /usr/lib/libmarisa.so.0.3.1 && g++ --version
```

## 数据来源（许可证与实测体积）

| 数据集 | 用途 | 全量体积 | 许可证 | 单条命令 |
|---|---|---|---|---|
| `0xDing/wikipedia-cn-20230720-filtered` | 百科长文本（主力通顺语料） | 524 MB（单 JSON 数组） | CC-BY-SA-3.0 | `--dataset wiki` |
| `Tongjilibo/THUCNews` | 新闻，按 14 个分类各一个 jsonl | 2.24 GB | Apache-2.0 | `--dataset thucnews --categories ...` |
| `silver/lccc` | 对话语体，**最贴输入法** | 979 MB（jsonl.gz） | MIT | `--dataset lccc` |
| `thunlp/THUOCL` → `THUOCL_chengyu.txt` / `THUOCL_poem.txt` | 高频成语 8519 / 名句 13703（强制入词表） | 163 KB / 288 KB | MIT | 自动下载 |
| 项目自带 `resource.zip` → `jichu` / `lianxiang` / `shici` | 词频来源 | 45 MB / 7.8 MB / 16 MB | 随项目 | 自动抽取 |

抓取小样本（**不要一上来下 3.7 GB**，每一条都是先验证字段与清洗）：

```bash
PY=.nwp-work/venv/bin/python
$PY scripts/nwp/fetch_corpus.py --dataset wiki     --limit 150
$PY scripts/nwp/fetch_corpus.py --dataset thucnews --limit 60 --categories 体育,科技
$PY scripts/nwp/fetch_corpus.py --dataset lccc     --limit 400
```

实测（本机）：wiki 150 条 **1.3 s**；thucnews 两条分类各 60 条 **2 s**；
lccc 400 句 → 51 条文档 **4.8 s**。三者都是**边下边解析、够了就断开**，
所以 `--limit` 不会把 524 MB / 979 MB 全拖下来。

全量（用户的机器上）：

```bash
$PY scripts/nwp/fetch_corpus.py --dataset all --limit 4000000   # 三个数据集一起，各取上限
$PY scripts/nwp/fetch_corpus.py --dataset thucnews --categories 体育,娱乐,社会,科技,财经
```

## 步骤

### 0. 环境自检

```bash
$PY -c "import torch, transformers, onnx, onnxruntime, peft; print('READY')"
```

### 1. 语料 → `<work>/corpus/*.txt`

见上一节。清洗规则（`nwp_common.clean_doc`）：去 HTML 标签、丢 emoji/控制符、
**删掉汉字之间的分词空格**（lccc 是预分词的）、少于 20 个汉字的文档丢弃、
超长文档按标点就近截断。输出一行一条文档。

### 2. 词表 → `vocab/char2id.json` + `vocab/word_vocab.txt`

```bash
# 真实：char2id 取自底座 tokenizer，word_vocab 取自 resource.zip + 成语/名句
$PY scripts/nwp/build_vocab.py --char-source backbone:cluecorpus --vocab-source resource --size 30000

# 另一个底座（Phase 1 探针用）
$PY scripts/nwp/build_vocab.py --char-source backbone:wenzhong --vocab-source resource
```

本机实测（16 s）：

```json
{"char_vocab_size": 8081, "char_count": 5349, "word_vocab_size": 30000,
 "agreement": {"token_count": 391, "merged_tokens": 7, "merge_rate": 0.018,
               "per_char_mismatch": 0, "unknown_rate": 0.015}}
```

三件事值得说清楚：

- **逐字 id 必须等于该字单独编码的 id**（`per_char_mismatch=0`）。这是端侧 char2id
  与骨干 `wte` 对齐的实质。
- **合并率不是错误**：BPE 会把常见字对合成一个 token（400 字 → 391 token，1.8%）。
  端侧没有合并表，所以字级模型按字喂；这个比例是这条设计的固有代价，脚本会报出来。
- **1017 个字的 token 解不回原字**（字节级 BPE 的半截 token），一律降级为 `<unk>`；
  实测真实文本上的未知字率 **1.5%**。
- word_vocab 强制入表 **3000 条成语 + 1500 条名句**（按频次取前 N），
  挤掉了 3365 个最低频普通词。**上限是必需的**：成语表 8519 条、名句 13703 条，
  全塞进 3 万词表会挤掉 2 万条高频词，词表就不再是「高频词表」了。

### 3. 样本 → `samples/{train,valid,test}.jsonl`

```bash
$PY scripts/nwp/build_samples.py --work $W --context-words 128 --min-context-words 16 \
  --max-context-ids 256
```

样本形如 `{"ids":[字id...],"label":词id,"ctx":"上下文原文"}`：上下文是**前 128 个词的字**，
标签是第 129 个词的 id。文档按内容 hash 分到 train/valid/test（同一文档绝不跨分片）。

**`--max-context-ids`（默认 256）是硬上限，按输入 id 数（= 字数）算，超了保留尾部。**
为什么按字数而不是词数设限：端侧按**字符预算**喂输入，`manifest.context_tokens` 也是字符数。
128 个词实际对应 200–550 个字，训练若超过端侧能喂的长度，多出来的部分推理时永远见不到，
等于白训。截尾（而不是截头）与端侧「取已上屏文本最后 N 个码点」的语义一致；
标签不受影响——上下文只是前文，丢掉开头不影响预测下一个词。

本机实测（真实语料 370 KB，默认 256）：`max_ids_seen`（截尾前最长）**546**，
train 47398 条里截尾 **8620** 条、valid 49 条里截尾 231 条、test 67 条里截尾 79 条；
截尾后所有分片的最大 id 数都正好是 256。`report.json` 会记下
`max_context_ids`、`max_ids_seen`、每个分片的 `truncated_by_max_context_ids`。

**去重**（不是可选项，Sloth 的教训是报 47.3 实际 10.9）：先建 train 的 ≥8 字 n-gram
指纹集合，valid 对 train 去重、test 对 train+valid 去重，命中就整条丢弃并报数。

```bash
$PY scripts/nwp/build_samples.py --dedup-selftest     # 8 字命中 / 7 字放过的边界自检
```

实测丢弃率：

| 语料 | valid | test |
|---|---|---|
| 本机 370 KB 小样本 | 92.5% | 92.6% |
| **用户主机 1.5 M 文档（约 1.9 GB）** | **52.21%** | **50.82%** |

**这不是小语料的假象**——1.5 M 文档的语料上仍然丢掉一半。这就是同源评测集在真实规模上的样子：
只要测试样本与训练文本同源，约一半会带 ≥8 字连续重合。所以**这是去重在正常工作**，
不是需要调参调掉的噪声；它同时说明：同源测试集的分数天然被高估，
要一个可信的数字就得**让测试集换语体**（例如训练用 wiki+新闻、测试用 lccc），
或把 `--dedup-min-match` 提到 2–4（要求多条 n-gram 同时命中，容忍常见套话）。
脚本两者都支持，并把丢弃率写进 `samples/report.json`。

**索引上限与覆盖率**：`--dedup-max-ngrams`（默认 2000 万）是内存护栏，撞上之后索引不再增长、
后面的样本只能在**不完整**的索引上检查。脚本会把这件事报成可判断的数字，而不是一句
「覆盖不完整」：

```
⚠ 去重索引撞到 --dedup-max-ngrams=20,000,000 后不再增长（覆盖不完整）：
    候选 n-gram 320,000,000 条，入索引 20,000,000 条 → 覆盖率 6.2%（distinct 20,000,000）；
    第 20,000,001 条候选时撞上限。
    valid 12,345/100,000 条在索引完整时检查；test 20,000/120,000 条在索引完整时检查。
    要把覆盖做到 100%，上限至少要到候选条数级别（Python set ≈50 B/条，320,000,000 条 ≈ 14.9 GB 内存，通常不现实）；
    更实际的做法是换异源测试集，或减小 --dedup-window / 提高 --dedup-min-match。
```

`samples/report.json` 的 `dedup.coverage` 里有同一份数字（`coverage_rate` /
`cap_hit_at_occurrence` / `distinct_indexed`），每个分片还记了
`dedup_checked_with_complete_index` 与 `dedup_checked_with_partial_index`。

### 4. 训练

```bash
# CPU 冒烟（随机小骨干，验证管线；结论无意义）
$PY scripts/nwp/train.py --smoke --max-steps 40
# 提速包的开关（默认全开，--no-* 可做对照实验）
$PY scripts/nwp/train.py --bucket --bucket-buffer 4096 --head-warmstart

# 4060 Laptop 8 GB 上的正式微调（用户机器执行）
$PY scripts/nwp/train.py --backbone cluecorpus --max-steps 20000 \
  --batch-size 8 --grad-accum 8 --dtype fp16 \
  --lora-rank 16 --lr-head 1e-3 --lr-lora 1e-4 \
  --head-warmup-steps 500 --warmup-steps 200 --schedule cosine
```

方法（实施 prompt §5，两条证据交叉，照做即可）：

| 部件 | 做法 | 为什么 |
|---|---|---|
| 骨干 | **冻结 + LoRA（rank 16 / 32 各试一次）** | *The Fine-Tuning Trap*：<300M 全参微调会把效果压到零样本基线以下 |
| 词级输出头 | **全参训练** | ULMFiT Table 7：只训最后一层（`Last`）比从头训还差 |
| 学习率 | **判别式**：head 1e-3 > LoRA 1e-4 > 骨干 0 | ULMFiT 最优组合是 `Freez + discr + stlr` |
| 解冻 | **先只训头 500 步，再加 LoRA 组** | ULMFiT 的 gradual unfreezing；注意是**把参数组加进优化器**，不是改 `requires_grad`（AdamW 不看它） |
| 调度 | warmup + cosine（或 `--schedule stlr`） | 每组各自 warmup，LoRA 从解冻那一步重新 warmup |
| 精度 | **fp16**（`--dtype fp16`） | 4060 Laptop 上 fp16 比 bf16 快 43%（spec 实测） |
| 显存 | micro-batch 8 × grad-accum 8 = **等效 batch 64**，S=128 | spec 的显存预算：25M 级约 1.9 GB；133M 挂 LoRA 比全参（5–6 GB）宽裕 |
| 损失 | 词表交叉熵，`ignore_index=0` | `<unk>` 标签学不了，直接忽略 |
| 断点 | `--resume ckpt/last.pt` | 每 `--save-every` 步存 last/best |

4060 上的时间/显存**估算**（不是实测，按 spec 的 FLOPs 模型外推）：
133M、S=128、等效 batch 64 时每步约 0.2–0.4 s，2 万步约 **1.5–2.5 小时**；
LoRA 训练显存 **< 4 GB**（8 GB 有余量，可把 batch 提到 16）。
这些数字请以自己机器上的 `sec_per_step` 为准——训练脚本每 `--log-every` 步会打印。

### 5. 基线（探针阶段必跑）

```bash
$PY scripts/nwp/baselines.py eval --baseline all --limit 2000
$PY scripts/nwp/baselines.py ngram-stats        # 枚举 predict.marisa 的规模
```

- **unigram**：`word_vocab.txt` 的词频排序（有 `word_freq.json` 时用它精确排）。
- **n-gram**：编译 `ngram_baseline.cc`（`g++ -O2 -std=c++17 ... -lmarisa`）到 `<work>/bin/`，
  从 `resource.zip` 抽出 `model/predict.marisa`，一次进程批量查询。
  两种查询口径：`spaced`（用我们自己的分词拼 `"词 词 词"`，5→1 词回退，**这份模型的真实上限**）
  和 `chars`（字符后缀 12→1 字，**复现 app 现在的行为**）。

实测这份 marisa 模型（全量枚举，1.2 s）：

```
keys=7955783   contexts_multiword=6057569 (76.14%)   max_context_words=5   keys_without_count_sep=0
```

与调研文档的 7,955,783 / 76.14% 完全一致。键格式实测为
`<词1> <词2> ...\t<下一个词>\xFF<次数>`。
顺带实测到一件与文档不同的事：这份 trie 有 **3 个子 trie**，
`reverse_lookup(id)` 取不回各自不同的键（只能从头 `predictive_search` 顺序枚举），
脚本里已经改用后者——如果你要复现文档里的枚举统计，注意这一点。

任何一步不可用（没有 `resource.zip`、没有 `g++`、没有 `libmarisa`、模型打不开）
都会降级成 `{"available": false, "reason": ...}` 并打印原因，**不让整条评测挂掉**。

### 6. 评测

```bash
$PY scripts/nwp/eval.py run --checkpoint ckpt/best.pt --baseline all --limit 2000
$PY scripts/nwp/eval.py run --onnx onnx/nwp.int8.onnx --limit 2000
$PY scripts/nwp/eval.py report --inputs results/eval_*.json --out results/compare.md
```

指标只有 **top-1 / top-5 / MRR**（+ 可选 **BPB**）。**不比 PPL**：跨分词器比 PPL 是错的。

### 7. 导出与量化

```bash
$PY scripts/nwp/export_onnx.py --eval-limit 512
```

产出 `onnx/nwp.onnx`（fp32）、`onnx/nwp.int8.onnx`（**动态范围** int8）、
`onnx/word_vocab.txt`、`onnx/char2id.json`、`onnx/manifest.json`。

三种交付格式，`manifest.format` / `model.file` 跟着变，**schema 一个键都不变**：

| 命令 | 交付文件 | `format` | 真实 125.7M 体积 |
|---|---|---|---|
| 默认 | `nwp.int8.onnx` | `onnx-int8` | 126.2 MB |
| `--no-int8` / `--fp16` | `nwp.fp16.onnx` | `onnx-fp16` | **250.7 MB** |
| `--fp32` | `nwp.onnx` | `onnx-fp32` | 500.9 MB |

`--also-fp16` 在默认 int8 之外**再落一份 fp16**（`manifest` 仍指向 int8），用于在同一个
manifest 语境下对两种量化的掉点：int8 与 fp16 写的是同一个 `manifest.json`，
所以**分别跑 `--fp16` 和默认两次会互相覆盖清单**，要同时拿到两个文件只能用这个开关。

fp16 那条路是**文档承诺的退化方案**，之前代码里并不存在（`--no-int8` 实际只留 fp32 的 500 MB，
顶爆 300 MB 预算）。实现是「初始化器降精度 + 图两端补 Cast」，
用 `onnx` 原生 API 完成，**不依赖 onnxconverter_common**（离线机器上不一定装得上）；
转换后会**用 ORT 真加载一次**，加载不了就删文件并让上层退到 int8。
数值上还不够的地方还有两处硬处理：GPT2 的 causal mask 用 `finfo.min`（-3.4e38）填屏蔽位，
直接转 fp16 会变 -inf、整行屏蔽时 softmax 出 NaN，所以降精度时**夹到 fp16 有限范围**；
图内 trace 时写死的 `Cast(to=float32)`（遮罩转浮点、隐状态转词头 dtype）按消费者算子白名单
改写成 fp16，而喂 `Range/Shape` 的 `Cast(to=int64/bool)` 原样保留。

- 量化只用 `quantize_dynamic`，**不做静态校准**（spec：静态定点 int8 在这些小模型上是抽奖，
  93.20 → 30.95 的崩法出现过）。
- **top-k 不进图**：脚本会扫图断言没有 `TopK/ArgMax/ArgMin`，Kotlin 侧取 top-k。
- 导出前自动 `merge_and_unload()` 合并 LoRA，图里就是干净的骨干 + 词头。

`manifest.json` 的 schema 是 Kotlin 下载器解析的，**一个键不多不少**：

```json
{"version":1,"name":"ime-nwp-zh","format":"onnx-int8","context_tokens":256,
 "vocab_size":30000,"char_vocab_size":8081,"num_layers":12,"num_heads":12,"head_dim":64,
 "model":{"file":"nwp.int8.onnx","bytes":126211758,"sha256":"..."},
 "word_vocab":{"file":"word_vocab.txt","bytes":286208,"sha256":"..."},
 "char_vocab":{"file":"char2id.json","bytes":79809,"sha256":"..."},
 "eval":{"top1":0.0,"top5":0.0,"mrr":0.0}}
```

字段含义（`model.py` 的「ONNX 契约」一节是权威定义处）：

- **`context_tokens` = 模型能接受的输入 id 个数上限，也就是字数上限**（端侧当字符预算用：
  取已上屏文本最后 `min(context_tokens, 210)` 个码点喂 `input_ids`，并据此预分配 KV cache）。
  **它不是词数**——S=128 个词对应 200–550 个字，写 128 会让端侧少喂约 40% 上下文且不报错。
  取值来自 `samples/report.json` 的 `max_context_ids`（即 `build_samples.py --max-context-ids`），
  可用 `--context-tokens` 覆盖；**默认 256 ≥ 端侧 prompt 预算 210**，不得低于样本上限——
  低于它 `export_onnx.py` 会直接报错退出（提前到导出之前，不会白跑）。导出前还会逐条核对
  参与评测的样本没有超过这个值。
- `vocab_size`=词表行数（30000），`char_vocab_size`=char2id 最大值+1（8081）。
- int8 失败时 `format` 退化为 `onnx-fp32`、`model.file` 指向 `nwp.onnx`，
  原因写进 `results/export_report.json`。

### 8. 端侧 ONNX I/O 契约（固定，不要改）

`model.py::onnx_io_contract` 是唯一来源；训练与导出共用同一个前向。

| 方向 | 名字 | dtype | shape |
|---|---|---|---|
| 输入 | `input_ids` | int64 | `[B, S]`（**字** id，来自 `char2id.json`） |
| 输入 | `attention_mask` | int64 | `[B, P+S]`（1=真实，0=左填充/丢弃） |
| 输入 | `position_ids` | int64 | `[B, S]`（**必须显式给**，否则增量步位置会算错） |
| 输入 | `past_key_values.{i}.key` / `.value` | float32 | `[B, H, P, D]`，prefill 时 P=0 |
| 输出 | `logits` | float32 | `[B, V_word]`，**只有最后一个位置**（约 120 KB） |
| 输出 | `present.{i}.key` / `.value` | float32 | `[B, H, P+S, D]` |

动态轴：`batch` / `sequence_length` / `total_sequence_length` / `past_sequence_length`。
没有 cache 时把 past 传成长度 0 的张量即可（实测 ORT 1.30 正常，prefill 与全量前向一致）。

## 真实 125.7M 模型上的导出实测（`uer/gpt2-chinese-cluecorpussmall`）

本机没有 GPU，所以在 CPU 上训了 3 步（batch 2 × 累计 2，1.9–2.4 s/步）直接走完导出，
为的是拿到**真实规模**的图、体积、KV cache 与对拍数据：

```bash
$PY scripts/nwp/train.py --work .nwp-work/nwp --backbone cluecorpus --max-steps 3 \
  --batch-size 2 --grad-accum 2 --head-warmup-steps 2 --warmup-steps 10 \
  --eval-every 0 --save-every 0 --out .nwp-work/nwp/ckpt-real
$PY scripts/nwp/export_onnx.py --work .nwp-work/nwp \
  --checkpoint .nwp-work/nwp/ckpt-real/best.pt --eval-limit 64 --parity-rows 4
```

| 项 | 实测值 |
|---|---|
| 骨干 / 词头 / 合计 | 102M + 23.4M（768→30000）= **125.7M** |
| ONNX 图 | **2353 个节点**，无 `TopK/ArgMax` |
| `nwp.onnx`（fp32） | **500.9 MB** |
| `nwp.int8.onnx`（动态范围） | **126.2 MB** ← 目标 130 MB 达成 |
| fp32 对拍（ONNX vs PyTorch） | top-1 **100.00%**，top-5 **100.00%**，max\|Δ\|=2.62e-06（相对 9.8e-07） |
| KV cache（PyTorch） | max\|Δ\|=1.07e-06，top-1/top-5 一致 |
| KV cache（ONNX 增量 27 字，2 步回灌） | max\|Δ\|=1.60e-06，top-1/top-5 一致，`present_len=27` |
| `manifest.context_tokens` | **256**（= `build_samples --max-context-ids 256`；端侧字符预算 210 得到满足） |
| int8 对拍 | top-1 **0.00%**，top-5 重合 50%，max\|Δ\|=0.67（相对 25%）——**见下面的说明，这组数字不可用作结论** |
| 评测（67 条真实测试样本） | 模型 top-1 0.00% / BPB 2.4140；unigram top-1 0.00% / top-5 1.49%；n-gram top-1 0.00% / top-5 1.49% |

**关于 int8 那行数字**：这个模型只训了 3 步，词头基本是随机的，logits 几乎是平的
（std 0.51、max 2.67）。量化后的 Pearson 相关仍有 **0.968**，但「幅值 0.5 的 logits 上
有 0.12 的绝对误差」足以让 argmax 在近似并列的候选之间乱跳。所以：

- fp32 对拍 100% 是有效结论（**Phase 3 的验收条件**，量化误差在 1e-6 量级）；
- int8 的 top-1 一致率在**未训练模型上没有意义**，必须在用户机器上训完之后重测。
  脚本会在 `relative_diff > 5%` 时把这个提醒打到 stderr，并把
  `logit_absmax` / `relative_diff` 一起写进 `results/export_report.json`。
  如果真实 int8 掉点明显，退路是 **fp16（约 260 MB，仍在 300 MB 内）**，
  这也是 spec §8 的检查点 3。

## 训好的模型（step 8000）上的实测

`ckpt/last.pt` = step 8000（LoRA 合并后 125.7M），测试集 `.nwp-work/nwp/samples/test.jsonl`
**顺序取前 N 条**（这个测试集与训练集同源，去重覆盖只有 3.1%，绝对值偏乐观；相对比较仍公平）。

| 被测对象 | n | top-1 | top-5 | MRR | BPB |
|---|---|---|---|---|---|
| PyTorch checkpoint（基准） | 200000 | **19.42 %** | 35.93 % | 0.2552 | 1.1961 |
| `nwp.fp16.onnx`（GPU，batch 16，613 s） | 200000 | **19.42 %** | 35.93 % | 0.2552 | 1.1961 |
| `nwp.int8.onnx`（**per-channel**，默认；8 行对拍 top-1 100 %） | 2000 | **17.10 %** | 32.20 % | 0.2252 | 1.2721 |
| `nwp.int8.onnx`（per-tensor，旧默认） | 200000 | 18.41 % | 34.50 % | 0.2434 | 1.2226 |
| `nwp.int8.onnx`（per-tensor，同 2000 条） | 2000 | 16.15 % | — | — | — |
| `nwp.fp16.onnx`（同 2000 条） | 2000 | 17.05 % | — | — | — |
| n-gram 基线 | 200000 | 0.66 % | 2.21 % | 0.012 | — |
| unigram 基线 | 200000 | 0.11 % | 0.66 % | 0.003 | — |

- **per-channel 是默认，且是严格更优**：同一 2000 条上它 17.10 %，fp16 17.05 %（差 1 个样本），
  而 per-tensor 只有 16.15 %（−0.90 pp，与它在 20 万条上的 −1.01 pp 吻合）。
  8 行对拍也从 top-1 87.5 % / 相对误差 12.9 % 变成 **100 % / 3.6 %**。
  体积只多 0.45 MB（126.8 vs 126.2 MB）、速度不变（同一批 kernel）。
  **`--int8-per-tensor` 只用于复现旧数字，别用来交付。**
- ⚠️ per-channel 的 **20 万条复核还没跑**（上面那行是 2000 条）。要写进对外结论前补一次：
  `eval.py run --onnx onnx/nwp.int8.onnx --limit 200000 --batch-size 16 --onnx-provider cuda`。

**速度：同一个模型在 GPU 与 CPU 上快慢是反的**（同一份测试集、2000 条、batch 16，
`bench_quant_speed.sh` 一次跑完）：

| 量化 | GPU（CUDA EP） | CPU | 单条（GPU） |
|---|---|---|---|
| fp16 | **7.7 s** | 339.2 s | 3.86 ms |
| int8 | 108.3 s | **105.7 s** | 54.14 ms |

- **int8 在 GPU 与 CPU 上耗时几乎相同（108.3 vs 105.7 s）**：说明 CUDA EP 上那些动态量化算子
  （`DynamicQuantizeLinear` / `MatMulInteger`）**根本没被 GPU 加速，实际还在 CPU 上算**。
  GPU 上 fp16 比 int8 快 **14×**，CPU 上 int8 比 fp16 快 **3.2×**。
- ⇒ **不要拿桌面 GPU 的耗时去推端侧**：端侧走的是 XNNPACK CPU，方向与桌面 GPU 相反。
  真机实测（同一段输入、`NeuralPredictor` 的 `ms=`）：**int8 10–17 ms、fp16 35–53 ms**，
  即**手机上是 int8 快 3–4 倍**，与桌面 GPU 的 14× 正好反过来。两者都远在 450 ms 预算内。

**结论：交付 `nwp.int8.onnx`（per-channel）**。它在手机上快 3–4 倍、体积省一半、精度与 fp16 等价；
`nwp.fp16.onnx` 作为「精度优先」的备选保留（零掉点，250.7 MB）。真机换模型的验证方式见
仓库根 `.research/交接-神经联想.md` §5.2（两份模型都推上去，换 `manifest.json` 后重载）。

```bash
# 宿主机 GPU 上（需要 onnxruntime-gpu；容器里只有 CPU 版）
$PY scripts/nwp/eval.py run --onnx .nwp-work/nwp/onnx/nwp.int8.onnx --limit 200000 \
  --batch-size 16 --onnx-provider cuda
$PY scripts/nwp/eval.py run --onnx .nwp-work/nwp/onnx-fp16-new/nwp.fp16.onnx --limit 200000 \
  --batch-size 16 --onnx-provider cuda
```

**没有 onnxruntime-gpu 就用 `setup-gpu-venv.sh` 建一个只跑评测的 venv**（不动训练用的
`.nwp-work/venv`）。脚本会看驱动版本选路线：>= 580 装 CUDA 13 版（ORT 1.30 + `nvidia-cudnn-cu13`），
否则装 CUDA 12 版（ORT 1.22 + `nvidia-*-cu12`，并生成 `ld_library_path.env`）：

```bash
bash scripts/nwp/setup-gpu-venv.sh --dry-run   # 先看要装什么
bash scripts/nwp/setup-gpu-venv.sh             # 真装（onnxruntime-gpu 约 280 MB + CUDA 运行库约 1.5 GB）
.nwp-work/venv-gpu/bin/python -c "import onnxruntime as ort; print(ort.get_available_providers())"
```

只看到 `CPUExecutionProvider` 说明 GPU 路线没成：先 `nvidia-smi` 看驱动，再看 pip 装
`onnxruntime-gpu` 时是否提示缺 `.so`。光看 provider 列表还不够——缺 `.so` 是在**建会话**那一步
才炸（[onnxruntime#25609](https://github.com/microsoft/onnxruntime/issues/25609)），所以脚本里
用真实模型建了一次 session 来验。

ONNX 评测是**流式**的：边跑边写 `<out>.json.partial`（默认每 `--progress-every 20000` 条刷新），
长时间任务中途被 kill 也能看出跑到第几条，不会「跑完才落盘、一 kill 全白跑」。

## 验收证据（本机实跑，CPU）

`bash scripts/nwp/smoke_test.sh`（退出码 0）：

```
=== 3/7 滑窗样本 + ≥8 字 n-gram 去重（含边界自检） ===
  [PASS] 完全相同的 8 字片段: overlap=True expect=True
  [PASS] 跨边界拼接的 8 字片段: overlap=True expect=True
  [PASS] 只共享 7 字: overlap=False expect=False
  [PASS] 完全无关: overlap=False expect=False
  [PASS] hash 模式同判定: True
[PASS] 测试集 177 条，被 ≥8 字 n-gram 去重丢掉 7892 条（97.81%）

=== 6/7 导出 ONNX（fp32 + 动态范围 int8）+ manifest + top-k / KV cache 对拍 ===
[18:54:xx]   图：507 个节点，禁止算子 无
[18:54:xx] 动态范围 int8 → .../nwp.int8.onnx（0.2 MB）
[18:54:xx]   fp32 top-1 一致率 100.00%，top-5 重合率 100.00%，max|Δ|=1.192e-07
[18:54:xx]   int8 top-1 一致率 100.00%，top-5 重合率 100.00%，max|Δ|=9.113e-03
[18:54:xx]   PyTorch KV：max|Δ|=5.960e-08 top1=一致 top5=一致
[18:54:xx]   ONNX KV：max|Δ|=8.941e-08 present_len=11/11 top5=一致
验收通过：fp32 对拍 100%，KV cache 路径一致，图中无 TopK/ArgMax
```

> 注意：以上是**小骨干**上的数字（验证的是管线与契约，不是质量）。
> 真实 110M 的 int8 掉点必须在用户的机器上重测——**int8 是否掉分只能用真实模型说话**
> （见 `results/export_report.json` 的 `parity_int8` 与 `eval_int8`）。

## 修过的真实 bug（都在用户首次全量跑的时候暴露，都已修 + 加了回归测试）

| # | 症状 | 根因 | 现在怎么防 |
|---|---|---|---|
| 1 | 语料 **静默缩水**：manifest 说 wiki 保留 254,546 条，磁盘上只有 54,546 条；三个数据集合计 187 MB / 11.9 万文档，而不是约 1.9 GB / 152 万文档 | `ShardWriter._open()` 用了 `_shard_index` **却从不自增**，于是每一片都叫 `-000.txt` 并以 `"w"` 打开，**每一片都把上一片截断** | 分片号自增 + 「分片名不得重复」断言 + 每次 fetch 前清掉同名旧分片 + 收尾时断言 `磁盘行数 == kept_docs`。回归测试 `test_shards_are_distinct` / `test_manifest_count_matches_disk` |
| 2 | Wenzhong 探针在**第 200 步的第一次验证**崩：`mat1 and mat2 must have the same dtype, but got Half and Float`（导出阶段同样会崩在 concat） | Wenzhong 的权重是 **fp16 落盘**的（骨干 Half），而新建的词头是 Float；训练循环里有 autocast 所以没事，`quick_metric` / `eval` / ONNX 导出都没有 | `NWPModel.forward` 在词头前把 hidden 对齐到**词头**的 dtype（头保持 fp32）；`load_checkpoint` 与 `export_onnx` 统一转 fp32（契约里 past/present/logits 都是 float32）；`quick_metric` 与训练用同一个 autocast。回归测试 `test_fp16_backbone_forward` / `test_export_forces_float32_io` |
| 3 | 词表构建中途崩在 `http.client.IncompleteRead`，并可能把**半截成语表**当缓存留下（下次运行看到「存在且非空」就直接用） | `download_cached` 直接 `write_bytes(dest)`，且 `except` 只抓 `URLError/OSError`——`IncompleteRead` 不在里面 | 先写 `.part` 成功后再原子改名；任何下载异常都只降级到本地退路（jichu 四字高频词 / shici 名句），不再让 160 KB 的词表拖垮整次构建 |

回归测试在 `scripts/nwp/test_nwp_pipeline.py`，已接进 `smoke_test.sh` 的第 0.5 步：

```bash
.nwp-work/venv/bin/python scripts/nwp/test_nwp_pipeline.py   # 不需要 pytest、不需要 GPU/网络
```

## 目录里每个文件的职责

| 文件 | 作用 |
|---|---|
| `nwp_common.py` | 工作目录布局、文本清洗、词表/分词（前向最大匹配）、n-gram 指纹、指标 |
| `fetch_corpus.py` | 抓取/清洗语料，`--limit`/`--shards`/`--categories`；`--dataset synthetic` 生成离线小语料 |
| `build_vocab.py` | `char2id.json`（对齐底座 tokenizer）+ `word_vocab.txt`（词频 + 强制成语/名句） |
| `build_samples.py` | 滑窗样本 + ≥8 字 n-gram 去重（含边界自检 `--dedup-selftest`） |
| `model.py` | 模型定义、LoRA 挂载/合并、checkpoint、**ONNX I/O 契约**、`verify-chars` 自检 |
| `train.py` | LoRA + 全参词头、判别式 LR、逐步解冻、fp16/梯度累积/续训、`--smoke` |
| `baselines.py` | unigram 与 n-gram 基线（含 C++ 助手编译与降级） |
| `ngram_baseline.cc` | marisa-trie 批量查询（prefix 枚举 + 回退），`g++ ... -lmarisa` 按需编译 |
| `eval.py` | top-1/top-5/MRR/BPB，评 checkpoint / ONNX / 基线，出 JSON + markdown |
| `probe.py` | Phase 1：两个底座同步数对比 + 基线 + 结论 |
| `export_onnx.py` | 导出 fp32/int8、扫图断言无 TopK、top-k 与 KV cache 对拍、写 manifest |
| `test_nwp_pipeline.py` | 回归测试：分片名唯一/磁盘行数==报告条数、fp16 骨干前向、ONNX 强制 float32 I/O、去重覆盖率 |
| `smoke_test.sh` | 一条命令跑通全链路并在 CPU 上做完全部断言（含上面的回归测试） |

## 已知边界与未完成项

1. **110M 正式微调的质量数字没有**（本机无 GPU）。`probe.py` 已就绪，命令见上；
   跑完把 `probe/report.md` 填进 `.research/`。
2. **4060 的时间/显存是估算**（来自 spec 的 FLOPs 模型），不是实测。
3. **同源测试集会有一半样本被去重丢掉**（用户 1.5 M 文档实测 valid 52.21 % / test 50.82 %）——
   这是去重在正常工作，不是小语料假象。要一个可信的评测数字请让测试集换语体，
   或提高 `--dedup-min-match`。去重索引撞到 2000 万上限时，脚本会把**覆盖率**和
   「多少条样本是在索引完整时检查的」一起报出来（`samples/report.json` 的 `dedup.coverage`）。
4. **int8 掉分未在真实模型上验证**；小骨干上 top-1 一致率 100%，但那只说明量化链路是通的。
5. **`scripts/phrase-index/`（路径 1，成语/诗句前缀补全）不由本目录负责**，
   `build_vocab.py` 只在它存在时顺手吸收其清单，不依赖。
6. 断言/文案都以**简体中文注释解释「为什么」**为准（AGENTS.md）。
