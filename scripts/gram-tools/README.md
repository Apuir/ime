# gram-tools

造 / 改 Rime **语法模型**（`.gram`）的小工具。目前只有一个：`mini_gram_builder`。

## 为什么要有它

`resource.zip` 里不带语法模型（万象官方那份 420 MB，随包会让 APK 到 ~530 MB），
所以「模型到底能做什么、不能做什么」这类问题如果只能靠装模型刷机试，一轮十几分钟
且看不到内部行为。这个工具让你能用手写的 n-gram **几秒钟造出一个定向模型**，
在 `scripts/rime-probe/` 里直接观察它对整句解码的影响。

它也是格式的唯一可执行文档 —— 那个格式没有官方说明，是从
`plugins/librime-octagram/src/gram_db.cc` + `src/rime/dict/mapped_file.cc`
读出来、再用真实的 420 MB 模型交叉验证的。

## 格式（逆向所得，已验证）

```
offset 0   grammar::Metadata                     共 44 字节
             char     format[32]        "Rime::Grammar/1.0" + NUL 填充
             uint32_t db_checksum       = 0（Load 时不校验）
             uint32_t double_array_size darts 数组的**单元数**，不是字节数
             int32_t  double_array      相对自身地址的偏移，恒为 4
offset 44  darts 双数组镜像（单元数 × 4 字节）
```

**校验方法**：真实模型 420339756 字节、105084928 个单元 →
`44 + 105084928 × 4 = 420339756` ✓。本工具产出的文件同样满足这个恒等式。

键（key）是 UTF-8 的「**上下文 + 词**」直接拼接，写入前按 `grammar::encode` 编码：

| 码点 | 编码 |
|------|------|
| `u < 0x80` | 1 字节 `u`（`u == 0` 编码成 `0xE0`） |
| `0x4000 ≤ u < 0xA000` | 2 字节 `[(u>>8)+0x40, u&0xFF]`（常用汉字全走这条） |
| 其它 | `0xE0\|len` 前缀 + 每 7 位一字节 |

值（value）= `max(0, int(log(词频) × 10000))`，与万象官方一致。

## 编译

不需要 librime，只要 darts-clone 的头文件（`deps/librime/include/darts.h`）：

```bash
g++ -O2 -std=c++17 \
  -I<项目>/app/src/main/cpp/deps/librime/include \
  -o mini_gram_builder mini_gram_builder.cc
```

## 用法

```bash
# 输入每行两列，空白分隔：<上下文+词> <词频>
printf '这句话没有 500000000\n打全 500000000\n全拼音 500000000\n' | \
  ./mini_gram_builder custom.gram
# -> 写出 custom.gram：键 3 条，数组 256 单元，1068 字节

# 把它放进方案数据目录（名字必须是 <grammar/language>.gram），再跑探针
cp custom.gram <shared>/wanxiang-lts-zh-hans.gram
scripts/rime-probe/rime-schema-probe <shared> <user> zjhmydqpy
```

**键怎么选**：`Octagram::Query` 会把「上下文的最后 ≤5 字」与「词的前 ≤5 字」拼起来查表，
而且上下文长度从 5 逐字递减到 1 各查一次。所以要提升路径 `A/B/C`，需要给
`A+B`、`B+C` 这类**跨边界拼接串**（以及更短的尾巴）记上词频。只记单个字基本没用
（`commonPrefixSearch` 要求再消费至少一个字符）。

## 坑与前提

- **只写 n-gram 键本身不够**：整句分数是逐边的「词典权重 + 语法分」，没有匹配的边
  还要付 `non_collocation_penalty`（默认 -6）。所以**词数多的切分天生吃亏**，
  靠模型只能把前缀纠正过来，补不平尾部。实测：45 条键能把 `zjhmydqpy` 从
  「中几乎没有的去朋友」扭成「这句话没有打桥牌也」（前 6 字变对），但尾部仍错。
- **单边提升有上限**：`log(词频) - collocation_penalty`，词频上限约 2.1e9
  （`int32 / 10000`），所以最多约 **+7.4**。别指望用超大词频硬压。
- **加键不会让别的候选变差**：`octagram.cc:57` 的 `update_result` 是取最大值，
  所以合并模型是「只会抬高、不会压低」。风险只在「你抬的这条顶掉了本来更好的选择」。
- **合并进官方模型要一次性 dump**：darts 是静态结构，加键必须整体重建。官方只发布
  二进制 trie，所以要先把 105M 单元的数组枚举回 `键 词频` 文本、再和你的合并重建。
  不需要重跑 KenLM —— 那段才是真正贵的（32 GB 语料 / 4 GB 内存）。
- **本工具只适合造小模型（几千到几万键）做实验**。要训正式模型走万象的
  `语法模型构建.py`（jieba + KenLM `lmplz` + 它的 `build_grammar`）。
