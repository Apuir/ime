# 简意输入法 · ime

基于 [Rime / librime](https://github.com/rime/librime) 引擎的 Android 中文输入法。Rime 在这里只作输入内核，键盘、候选面板、主题、剪贴板、语音、手写均由应用自身实现。

仓库由上游 [`danjian/ime`](https://github.com/danjian/ime) fork 而来，沿用其引擎接入与构建脚手架，在此之上重写键盘与交互层、扩充输入方式与设置项。包名沿用上游的 `com.ninthsoft.ime`，因此两者不能共存安装；版本线与内置方案数据则各自独立。

| | |
|---|---|
| 应用名 | 简意输入法 |
| 包名 | `com.ninthsoft.ime` |
| 当前版本 | `2.8.0`（20800） |
| 系统要求 | Android 7.0+（minSdk 24） |
| 架构 | 仅 `arm64-v8a` |
| 引擎 | librime + lua / octagram（语法模型）/ predict（预测） |
| 内置方案 | [万象拼音 rime-wanxiang](https://github.com/amzxyz/rime-wanxiang) LTS `17.9.9`（CC BY 4.0） |

---

## 目录

- [特性](#特性)
- [截图](#截图)
- [安装](#安装)
- [从源码构建](#从源码构建)
- [文档索引](#文档索引)
- [已知限制](#已知限制)
- [上游与许可](#上游与许可)

## 特性

**输入**

- 四个输入方式：全键盘（Qwerty）、九宫格（T9）、15 键（T15）、手写
- 键盘槽收敛为两个：英文槽固定（不可更改），中文槽里选输入方式；同一输入方式有多个方案时平铺展示（如 `九键1 / 九键2`）
- 输入方式的可用性：应用支持该键盘，且方案里声明了对应布局（`schema/layout`）与候选类型（`candidateKind`）才可用；方案里没有的输入方式整项不出现（手写不走引擎、无需方案）
- 模糊音默认开启：平翘舌（zh/z、ch/c、sh/s）、前后鼻音（an/ang、en/eng、in/ing）、n/l 共 6 组，`zongguo` 直接出「中国」
- 首字母简拼：打 `qryt` 出「杞人忧天」、`zjh` 出「这句话」；九键同样可用（`954` 出「自己」、`7'7'9'8` 出「杞人忧天」）。「设置 → 输入方案 → 拼音 → 简拼」是总开关，关掉后所有中文键盘都不再出简拼候选（九键的简码表也一并停用）
- 首字母整句：整句候选给 8 条、可以往下翻（`mtzj` 第 4 位出「明天再讲」、`nzmy` 第 4 位出「你怎么样」）；质量上限与语法模型的关系见 [`docs/DEVELOPMENT.md` 9.6.1](docs/DEVELOPMENT.md#961-首字母整句translatormax_sentences)
- 符号键盘、Emoji 键盘、全角/半角标点、简↔繁转换、英文/ASCII 模式；方案启用、排序、切换，多套万象拼音方案开箱可用

**候选与编辑**

- 横滑候选条 + 展开式 5×5 候选网格，候选可拖拽重排、删除 / “忘记”
- 候选按字长分组：4 字 → 5 字及以上 → 3 字 → 2 字 → 1 字，每批固定凑够 50 条（4 字×6 + 5 字以上×3 + 3 字×6 + 2 字×6 + 单字补齐）；数量不设上限，划到底再要下一批，下一批只会追加在末尾。短词在引擎里排得很靠后（四码输入下单字能排到两千多位），会自动异步往深里读把一批凑满，读的时候不阻塞按键
- marisa 预测 + `.gram` 语法模型重排，可开关（语法模型真正参与打分）；但官方 420 MB 语法模型实测对首字母整句是负作用（候选会被拼成很长的怪句），见 [`docs/DEVELOPMENT.md` 9.6.1](docs/DEVELOPMENT.md#961-首字母整句translatormax_sentences)
- 常用词学习：候选选自用户实际选择，带时间衰减；误选后自动降权 —— 选错了删掉重打，那个词会自己往下掉，且惩罚会随时间过期
- 学习数据可在「设置 → 候选词 → 学习数据」里一键重置；文本编辑面板支持光标移动 / 选择 / 剪切复制粘贴，并有预编辑悬浮拼音条
- 输入框实时上屏预览（不上屏 / 原始输入 / 首候选）+ 剪贴板历史 + 常用语管理

**键盘定制**

- 主题：内置 3 套（暗夜 / 素白 / 落日）+ 不限数量自定义，应用内 GUI 调色并支持二维码分享
- 设置分享：全部设置项与自定义主题导出为一个 JSON 文件，换设备导入即还原（[格式说明](docs/SETTINGS_BACKUP.md)）
- 工具栏图标自定义（含展开工具栏里的「切换布局」：一键换九键 / 26键 / 15键 / 手写）；展开工具栏是单页纵向无级滑动、行内左对齐，图标与文字随键盘大小等比缩放（横屏悬浮卡片也不糊）
- 侧栏快捷符号自定义（九键与手写共用一份，改哪边两边都变）、26 键符号与九键字母映射自定义
- 按键气泡：长按 / 上滑弹出，主体悬在按键上方、与按键同宽同高的指针压住键帽，连成一体；长按与上滑手势二选一
- 横屏悬浮键盘、键盘内直接拖拽调大小、按键振动（系统触感 / 10 级自定义强度）

**手写**

- 双引擎：本地 ochwpro ONNX 模型（随包、离线、飞行模式可用）+ Google ML Kit（主引擎，需 GMS，首次下载约 20 MB）
- `AUTO` 下优先 Google、不可用静默落到本地；显式选 Google 时不自动降级，设置页给提示与「改用本地」
- Google 模型状态如实反映（未下载 / 已下载未确认 / 确认可用 / 残缺 / 状态未知，且每次都真跑自检），失败时给出 Play 服务返回的原因，并可「删除模型」重新下载
- 面板：书写区 + 右侧符号栏（与九键共用同一份自定义符号列表，可滑动；⌫ 固定不随其滚动）+ 底部功能行；候选显示在顶栏候选位，与九键共用同一套交互；回车键图标跟随输入框动作（搜索 / 发送 / 换行…）
- 候选为组合态：写完即进输入框但未定型，点任意候选都会换字并立刻收尾这一轮（顶栏收回工具条）；写下一个字 / 切键盘 / 收键盘 / 关输入法 / 空格回车标点 时正式保留
- 抬笔停手自动识别并清空笔迹，停手时长可在设置里调（0.2–2.0 秒，步长 0.1 秒）
- ⌫ 长按连续删除（与九键 / 26 键同一套时序）；「中/英」一次点按即可切回手写
- 「半/全」切换键盘区域内手写 / 整屏手写：整屏时键盘区域保持实体（标点行 / 功能行 + 导航栏那条都是键盘底色），只有书写区是一层很淡的膜；应用/输入框会被顶到键盘之上，不再压在键盘底下

**语音**

- sherpa-onnx zipformer 离线语音转文字，跑在独立 `:speech` 进程
- 高通 QNN/HTP 加速，不支持时回退 CPU onnx；模型按需下载并 MD5 校验

**其它**

- SAF 文件管理：无需 root，用系统“文件”App 即可浏览编辑方案与词库
- 运行日志 / 崩溃日志 / 版本检查 / 按键音效与水波纹

完整功能说明（含每项的入口与实现位置）见 [`docs/DEVELOPMENT.md`](docs/DEVELOPMENT.md#2-功能一览)。

## 截图

仓库暂未收录界面截图，需要时自行采集：`mkdir -p docs/images` 建目录，再执行 `adb exec-out screencap -p > docs/images/keyboard-qwerty.png`。常收录的视角是 26 键键盘、九键键盘、候选网格、主题编辑器与设置首页。

## 安装

不发布 Release APK，APK 由本机自行构建（见下一节），然后用 `adb` 安装：

```bash
adb install -r app/build/outputs/apk/release/ime-2.8.0.apk
```

首次打开会依次进入：

1. `InitActivity` —— 解压 `resource.zip`、部署 Rime 引擎（首次较慢，请等待）
2. `SetupActivity` —— 引导启用输入法 → 设为默认输入法 → 开始使用

之后在任意输入框切换到「简意输入法」即可。键盘的“文件管理”入口（或系统“文件”App 里的 `Android/data/com.ninthsoft.ime/files/`）可以查看和修改方案、词库、主题。

从旧版本升级时注意：`versionCode` 必须递增才能覆盖安装；若曾装过上游 `danjian/ime` 版本，请先卸载。

## 从源码构建

### 环境要求

- JDK 17+（开发机为 21）
- Android SDK：`platforms;android-37`、`build-tools`、`platform-tools`
- Android NDK（AGP 默认版本）与 CMake `3.22.1`
- `git`、`curl`、`python3`

### 克隆下来不能直接编译

以下物料被 `.gitignore` 排除或从未入库，必须先补齐：

| 缺失项 | 路径 | 获取方式 |
|--------|------|----------|
| `resource.zip` | `app/src/main/assets/resource.zip` | 必需，约 65 MB。用 `scripts/build-rime-resource.py` 从万象拼音 17.9.9 重建（见下） |
| native 依赖 | `app/src/main/cpp/deps/` | 运行 `./install-deps.sh` 自动拉取 |
| SDK 路径 | `local.properties` | 写入 `sdk.dir=/path/to/Android/Sdk` |
| 签名材料 | `keystore.properties` | 可选；缺省时 release 产出未签名 APK |

重建 `resource.zip`（推荐，能拿到与作者桌面一致的 17.9.9 数据）：

```bash
# 来源可以是任意一份干净的万象拼音 17.9.9 数据目录：
#   - 本机 fcitx5 的 rime 用户目录（默认值）
#   - 万象官方发布包解出来的目录
#   - 上一次的输出目录（脚本幂等，重复跑结果一致）
python3 scripts/build-rime-resource.py --dry-run          # 先看会做什么
python3 scripts/build-rime-resource.py                    # 正式重建
```

脚本会自动做三件不能省的事：注入 app 桥接字段（`schema/layout`、`punctuation`、`kind`、`candidateKind` 与 `options` 块）、注入模糊音规则、保留 `model/predict.marisa`；并输出 `scripts/rime-resource-manifest.json`（文件清单 + sha256 + 万象版本）用于追溯。细节见 [`docs/DEVELOPMENT.md` 7.2](docs/DEVELOPMENT.md#72-当前-checkout-缺失的构建物料)。

备选方案是从上游 release APK 里抽，`danjian/ime` v1.0.5 打包的是旧版 17.2.4 数据，没有模糊音、也缺若干模块，仅在无法获取 17.9.9 时使用：

```bash
curl -L -o /tmp/JIme-v1.0.5.apk \
  https://github.com/danjian/ime/releases/download/v1.0.5/JIme-v8a-v1.0.5.apk
unzip -o /tmp/JIme-v1.0.5.apk assets/resource.zip -d /tmp/jime_rz
cp /tmp/jime_rz/assets/resource.zip app/src/main/assets/resource.zip
```

### 构建

```bash
# 1) 配置 SDK 路径
echo "sdk.dir=$HOME/Android/Sdk" > local.properties

# 2) 拉取 native 依赖（Boost + librime 及插件、OpenCC、snappy 等，幂等）
chmod +x install-deps.sh && ./install-deps.sh

# 3) 放入 resource.zip（见上）
ls -lh app/src/main/assets/resource.zip

# 4) 编译
./gradlew :app:assembleDebug     # → app/build/outputs/apk/debug/ime-2.8.0-debug.apk
./gradlew :app:assembleRelease   # → app/build/outputs/apk/release/ime-2.8.0.apk
```

首次 native 编译（librime + Boost + OpenCC）耗时较长。release 签名可用仓库根的 `keystore.properties`，或用环境变量 `IME_STORE_FILE` / `IME_STORE_PASSWORD` / `IME_KEY_ALIAS` / `IME_KEY_PASSWORD` 覆盖。

构建细节、目录结构、缺失物料清单与常见坑，见 [`docs/DEVELOPMENT.md`](docs/DEVELOPMENT.md) 第 7 节。

## 文档索引

| 文档 | 内容 |
|------|------|
| [`docs/DEVELOPMENT.md`](docs/DEVELOPMENT.md) | 开发与定制手册：架构总览、代码目录地图、关键运行流程、构建细节、定制指南、已知坑、SharedPreferences / Room 附录 |
| [`docs/THEME_FORMAT.md`](docs/THEME_FORMAT.md) | 键盘主题 `themes.json` 与二维码分享格式的字段说明 |
| [`docs/SETTINGS_BACKUP.md`](docs/SETTINGS_BACKUP.md) | 「设置分享」导出的设置备份 JSON 的字段、包含范围与导入规则 |
| [`scripts/README.md`](scripts/README.md) | 构建脚本：方案数据重建、本地引擎探针 |
| [`CHANGELOG.md`](CHANGELOG.md) | 版本历史、版本号规则与发布流程 |

常用直达：[架构总览](docs/DEVELOPMENT.md#4-架构总览) · [代码目录地图](docs/DEVELOPMENT.md#5-代码目录地图) · [构建与运行](docs/DEVELOPMENT.md#7-构建与运行) · [定制指南](docs/DEVELOPMENT.md#9-定制指南) · [注意事项 / 已知坑](docs/DEVELOPMENT.md#10-注意事项--已知坑)

## 已知限制

- 整屏手写依赖各家 ROM 对输入法窗口的处理；异常时可把 `HandwritingManager.FULL_SCREEN_IMPL` 从 `OVERLAY` 改为 `GROW`（键盘临时加高、形态不变）
- 手写识别准确率与耗时尚未量化；悬浮（横屏）下的手写触摸仍需真机确认
- **仅支持 `arm64-v8a`**：不支持 32 位设备与模拟器
- **`resource.zip` 不入库**：空 checkout 构建出来会没有方案和候选词
- `MANAGE_EXTERNAL_STORAGE`：Manifest 申请了该权限，上架应用商店可能被拒（日常使用靠 SAF 其实够用）
- Room 无 7→8 迁移：数据库升级会触发 `fallbackToDestructiveMigration()` 清库
- 更多坑见 [`docs/DEVELOPMENT.md` 第 10 节](docs/DEVELOPMENT.md#10-注意事项--已知坑)

---

## 上游与许可

- 仓库 `Apuir/ime` 基于上游 [`danjian/ime`](https://github.com/danjian/ime)；两者均没有仓库级 LICENSE 文件
- 引擎：librime 为 GPL-3.0-or-later；librime_jni 为 Apache-2.0；OpenCC / snappy / glog / yaml-cpp / leveldb / marisa-trie 各自开源许可
- 方案数据：万象拼音 [rime-wanxiang](https://github.com/amzxyz/rime-wanxiang)，CC BY 4.0
- 语音：sherpa-onnx，Apache-2.0。QNN/CDSP：`app/src/main/assets/cdsp/*.so` 为高通运行库

分发 APK 或源码时，必须随附 librime 及其衍生部分的完整对应源码与许可证全文，并保留万象拼音的署名与来源链接。详见 [`docs/DEVELOPMENT.md` 第 11.1 节](docs/DEVELOPMENT.md#111-自用-vs-分发重要)。
