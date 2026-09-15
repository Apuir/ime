# scripts

构建与验证用的脚本。都是**独立可跑**的，不参与 Gradle 构建。

| 脚本 | 作用 | 什么时候用 |
|------|------|-----------|
| [`build-rime-resource.py`](build-rime-resource.py) | 重建 `app/src/main/assets/resource.zip`（万象拼音方案数据）：白名单取数、注入 app 桥接字段 / 模糊音规则 / 整句候选配置、保留 `model/predict.marisa`、做引用校验、输出 manifest | **改动方案数据时**；换机器要重建时 |
| [`test_build_rime_resource.py`](test_build_rime_resource.py) | 上面那个脚本的单元测试（注入正确性 + 幂等性 + 引用校验） | 改那个脚本之后 |
| [`rime-probe/`](rime-probe/) | 在开发机上直接加载方案数据跑 librime，验证输入行为 | **改 `speller/algebra` 之前先验**，别靠刷机试 |
| [`rime-resource-manifest.json`](rime-resource-manifest.json) | 上一次 `resource.zip` 的文件清单 + sha256 + 万象版本（由脚本生成，**建议入库**用于追溯） | 查「这一版包里到底是什么」 |

## 常用命令

```bash
# 方案数据：先干看，再重建
python3 scripts/build-rime-resource.py --dry-run
python3 scripts/build-rime-resource.py

# 换个模糊音档位（safe = 平翘舌+前后鼻音+n/l 共 6 组，默认；all = 10 组；none = 关）
python3 scripts/build-rime-resource.py --fuzzy all

# 整句候选档位（默认 max_sentences=8 / cutoff=0.5；=1 表示沿用引擎默认的「只给一条整句」）
python3 scripts/build-rime-resource.py --max-sentences 1
python3 scripts/build-rime-resource.py --max-sentences 20 --sentence-cutoff 0.8

# 脚本自己的测试
python3 scripts/test_build_rime_resource.py

# 本地引擎探针
cd scripts/rime-probe && gcc -O2 -o /tmp/rime-schema-probe rime-schema-probe.c -lrime
```

## 为什么 `resource.zip` 要脚本化重建

`app/src/main/assets/resource.zip` 约 65 MB，被 `.gitignore` 排除、**不入库**。
没有脚本的话，「这一版包里的方案数据是从哪来的、改过什么」就完全不可追溯，
换台机器也没法复现。所以约定：

1. 只用脚本生成，不手工拼 zip；
2. 每次重建后把 `rime-resource-manifest.json` 一起提交（它记录了来源目录、
   万象版本、注入内容、每个文件的 sha256）；
3. 改脚本的行为时同步更新 `test_build_rime_resource.py`。
