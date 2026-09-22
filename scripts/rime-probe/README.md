# rime-schema-probe

在开发机上直接加载方案数据跑 librime，验证输入行为的小工具。

## 为什么要有它

本项目的方案数据（万象拼音）打包在 `app/src/main/assets/resource.zip` 里，
而那个 zip 不入库。「模糊音有没有生效」「简拼还能不能出成语」「候选数涨了多少」
这类问题，如果只能刷 APK 到手机上试：

- 一轮十几分钟（构建 + 安装 + 首次部署）；
- 看不到候选总数和某个词的真实位次，只能靠肉眼翻候选面板；
- 出问题时说不清是「方案配置不对」还是「app 加载的数据不对」。

这个探针用系统的 librime 加载同一份 `shared/` 数据，几秒钟给出确定答案。
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
| 整句候选够翻 | `--expect 明天再讲 mtzj` → 应在第 4 位附近 |
| 整句候选够翻（2） | `--expect 你怎么样 nzmy` → 应在第 4 位附近 |
| 模糊音 zh/z | `--expect 中国 zongguo` |
| 模糊音 c/ch | `--expect 长 cang` |
| 模糊音 s/sh | `--expect 是 si` |
| 模糊音 en/eng | `--expect 跟 gen geng` |
| 模糊音 in/ing | `--expect 新 xin xing` |
| 候选规模（判断是否爆炸） | 看输出的「候选总数」 |

`zongguo` 出「中国」= 模糊音生效；只出「总过」= 模糊音没生效。

## 坑与前提

- `--deploy` 之后的 `user/` 目录不要删：词库编译产物在里面，删了要重编。
- `shared_dir` / `user_dir` 一定要传绝对路径。传 `./shared` 会看到
  `[super_symbols] cannot open data: lua/data/codex_sym.txt` 之类的报错 ——
  那是假警报：万象 lua 的 `get_filename_with_fallback()` 要求
  `rime_api.get_shared_data_dir()` 是绝对路径，否则直接退回相对路径去 `io.open`。
  app 里 `sharedDataDir` 本来就是绝对路径，所以这个报错不出现在真机上。
  跑之前先 `cd` 到数据目录再用 `$PWD/...`，或者一开始就用绝对路径。
- 改了 `shared/` 下的方案文件，不重新部署是不生效的：librime 部署时会把方案
  副本写进 `user/build/<schema_id>.schema.yaml`，之后从那里读。所以在探针里
  改 `shared/wanxiang.schema.yaml` 看不到任何变化 —— 要么改 `user/build/` 里的副本
  （秒级，适合试参数），要么 `--deploy` 重建（分钟级）。试 `translator/*` 这类
  翻译器配置用前者最省时间。
- 别把真实的 rime 用户目录当 `user/`：探针会在里面建 `*.userdb`、写 `user.yaml`。
- `/tmp` 在有些沙箱里每次命令都会重置，中间产物放 `~/.cache/<名字>/` 之类的持久目录。
- librime 版本：探针用系统 librime（本次是 1.17.0），而 app 用的是
  `danjian/librime` fork。核心的拼写运算 / 翻译器行为一致，但不覆盖 app 侧
  JNI 与 Kotlin 逻辑，所以探针通过 ≠ 实机通过，最终仍要真机验证。
- 输出里的 `E2026...` 开头的行是 librime 的 glog 错误（例如找不到可选的
  `wanxiang-lts-zh-hans.gram` 语法模型），与方案数据无关，可以忽略或 `rg -v "^E2026"` 过滤。

## 探针看不到 app 的 librime 私人补丁

探针用的是系统 librime，而 app 用的是 `app/src/main/cpp/deps/librime`
（`danjian/librime` fork，带私人补丁）。所以：

- 探针通过 ≠ 实机通过。2026-09-19 就踩过一次：fork 在 `src/rime/dict/table.cc`
  加的 `kAbbreviation rate limit`（全局计数、上限 2）把全简拼输入的候选砍得只剩
  最先展开的几条，手机上是「传记和健身转悠电池了的」这种不成句的候选，
  而探针（上游 librime）给出的是「这句话就是这样多出来的」。
- 怀疑引擎行为时，正确的做法是：把手机编译出来的
  `user/build/wanxiang.prism.bin` / `wanxiang.table.bin` + 部署副本 schema 拉下来，
  放进一个空 `user` 目录，让探针加载手机的编译产物：
  - 探针结果 = 手机结果 → 问题在方案数据 / 用户数据；
  - 探针结果 ≠ 手机结果 → 问题在引擎二进制（fork 补丁），去读
    `deps/librime` 的 `git log`，重点看 `dict/`、`gear/`、`algo/`。

## 要验证 fork 的补丁：本机编一份 librime

`install-deps.sh` 给 `deps/librime` 打的补丁（schema `kind`、简拼逐路径限流、
简拼总开关 + 九键的 `abbrev_max_length`）只影响 app 那份引擎；要在开发机上验证它们，
把同一份源码编成本机动态库，再让探针链接它：

```bash
cmake -S app/src/main/cpp/deps/librime -B /tmp/rime-build \
  -DCMAKE_BUILD_TYPE=Release -DBUILD_SHARED_LIBS=ON -DBUILD_TEST=OFF
cmake --build /tmp/rime-build -j"$(nproc)"

gcc -O2 -o /tmp/rime-schema-probe scripts/rime-probe/rime-schema-probe.c \
  -Iapp/src/main/cpp/deps/librime/src -Iapp/src/main/cpp/deps/librime/include \
  -L/tmp/rime-build/lib -lrime -Wl,-rpath,/tmp/rime-build/lib

/tmp/rime-schema-probe "$PWD/shared" "$PWD/user" --schema wanxiang_t9 95
```

`BUILD_MERGED_PLUGINS` 默认为开，lua / octagram / predict 会一起编进来（插件目录
`deps/librime/plugins/*` 是指向 `deps/librime-*` 的软链，Android 构建首次配置时创建；
缺了也能编，但万象那套 lua 过滤器加载不了，只能跑不带 lua 的最小方案）。
运行时选项（如简拼开关 `abbrev_disabled`）用 `rime_api.h` 的 `set_option` 在输入前设置。
