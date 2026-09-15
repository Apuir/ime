# rime-schema-probe

在**开发机上**直接加载方案数据跑 librime，验证输入行为的小工具。

## 为什么要有它

本项目的方案数据（万象拼音）打包在 `app/src/main/assets/resource.zip` 里，
而那个 zip 不入库。「模糊音有没有生效」「简拼还能不能出成语」「候选数涨了多少」
这类问题，如果只能刷 APK 到手机上试：

- 一轮十几分钟（构建 + 安装 + 首次部署）；
- 看不到**候选总数**和某个词的真实位次，只能靠肉眼翻候选面板；
- 出问题时说不清是「方案配置不对」还是「app 加载的数据不对」。

这个探针用系统的 librime 加载同一份 `shared/` 数据，**几秒钟**给出确定答案。
改 `speller/algebra`（比如开关模糊音）之前先在这里验证，再谈打包。

## 编译

需要 librime 的开发头文件。Arch 上 `extra/librime` 自带 `/usr/include/rime_api.h`：

```bash
gcc -O2 -o rime-schema-probe rime-schema-probe.c -lrime
```

Ubuntu/Debian 需要 `librime-dev`；lua / octagram / predict 这三个插件是方案数据
依赖的（`librime-lua`、`librime-octagram`），装在 `<libdir>/rime-plugins/` 下即可。

## 用法

```bash
# 1) 解出 app 里打包的方案数据
mkdir -p /tmp/ime-probe && cd /tmp/ime-probe
unzip -q /path/to/ime/app/src/main/assets/resource.zip

# 2) 首次要部署一次（编译词库，几分钟）。user 目录随便给个空目录，别用真实的 rime 用户目录。
mkdir -p user
./rime-schema-probe shared user --deploy --expect 杞人忧天 qryt

# 3) 之后再测别的输入就不用加 --deploy 了（部署产物缓存在 user/ 里）
./rime-schema-probe shared user --expect 中国 zongguo
./rime-schema-probe shared user --all-schemas qryt      # 遍历所有已部署方案
```

输出示例：

```
  qryt         [wanxiang / 万象拼音]
      preedit = q r y t
      1. 去了一趟
      2. 杞人忧天
      ...
      · 「杞人忧天」在第 2 位
      候选总数 = 4011，首选 = 去了一趟
```

## 常用验证清单

改完方案数据（用 `scripts/build-rime-resource.py` 重建 zip 之后），建议跑这几组：

| 目的 | 命令 |
|------|------|
| 简拼还能出成语 | `--expect 杞人忧天 qryt` |
| 全拼基线没坏 | `--expect 你好 --expect 中国 nihao zhongguo` |
| 模糊音 zh/z | `--expect 中国 zongguo` |
| 模糊音 c/ch | `--expect 长 cang` |
| 模糊音 s/sh | `--expect 是 si` |
| 模糊音 en/eng | `--expect 跟 gen geng` |
| 模糊音 in/ing | `--expect 新 xin xing` |
| 候选规模（判断是否爆炸） | 看输出的「候选总数」 |

`zongguo` 出「中国」= 模糊音生效；只出「总过」= 模糊音没生效。

## 坑与前提

- **`--deploy` 之后的 `user/` 目录不要删**：词库编译产物在里面，删了要重编。
- **别把真实的 rime 用户目录当 `user/`**：探针会在里面建 `*.userdb`、写 `user.yaml`。
- **`/tmp` 在有些沙箱里每次命令都会重置**，中间产物放 `~/.cache/<名字>/` 之类的持久目录。
- **librime 版本**：探针用系统 librime（本次是 1.17.0），而 app 用的是
  `danjian/librime` fork。核心的拼写运算 / 翻译器行为一致，但**不覆盖 app 侧
  JNI 与 Kotlin 逻辑**，所以探针通过 ≠ 实机通过，最终仍要真机验证。
- 输出里的 `E2026...` 开头的行是 librime 的 glog 错误（例如找不到可选的
  `wanxiang-lts-zh-hans.gram` 语法模型），与方案数据无关，可以忽略或 `rg -v "^E2026"` 过滤。
