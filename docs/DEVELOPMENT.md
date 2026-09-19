# 简意输入法（ime）· 开发与定制手册

> 📖 项目简介、功能与快速开始见根目录 [`README.md`](../README.md)；版本历史见 [`CHANGELOG.md`](../CHANGELOG.md)。
>
> 本仓库 `ime` 基于 [`danjian/ime`](https://github.com/danjian/ime)（该上游仓库没有 LICENSE 文件），
> 作为个人 fork 维护。
> 本文档是基于当前代码（220 个 Kotlin 文件、约 3.3 万行 + C++ JNI）重新整理的**理解 + 定制**指南，
> 面向要读源码、改功能、做二次开发的人。

| 项目 | 值 |
|------|----|
| 应用名 | 简意输入法 |
| applicationId / namespace | `com.ninthsoft.ime` |
| versionName / versionCode | `2.3.1` / `20301`（版本规则与发布流程见 [`CHANGELOG.md`](../CHANGELOG.md)） |
| minSdk / targetSdk / compileSdk | 24 / 36 / 37 |
| 支持 ABI | 仅 `arm64-v8a` |
| 语言/构建 | Kotlin 2.4.10、AGP 9.1.1、Gradle 9.3.1、CMake 3.22.1 |
| UI | 键盘 = 自定义 View（ConstraintLayout + Canvas）；设置 = Jetpack Compose |
| 输入引擎 | librime（`danjian/librime` 分支）+ lua / octagram / predict 插件 |
| 内置方案数据 | [万象拼音 rime-wanxiang](https://github.com/amzxyz/rime-wanxiang) LTS `17.9.9`（CC BY 4.0） |
| 提交历史 | 92 commits，2026-06-12 ~ 2026-09-12（其中 61 个来自上游，fork 改动从 `v1.1.0` 开始） |

---

## 目录

1. [这是什么项目](#1-这是什么项目)
2. [功能一览](#2-功能一览)
3. [技术栈与依赖](#3-技术栈与依赖)
4. [架构总览](#4-架构总览)
5. [代码目录地图](#5-代码目录地图)
6. [关键运行流程](#6-关键运行流程)
7. [构建与运行](#7-构建与运行)  ← **包含当前 checkout 缺失的构建物料**
8. [数据与存储布局](#8-数据与存储布局)
9. [定制指南](#9-定制指南)  ← **后续二次开发看这里**
10. [注意事项 / 已知坑](#10-注意事项--已知坑)
11. [上游与许可](#11-上游与许可)
12. [附录](#12-附录)

---

## 1. 这是什么项目

一个**基于 Rime（librime）引擎的 Android 中文输入法**，整体是一个“壳 + 引擎 + 数据”的结构：

- **壳**：`InputMethodService` 及其键盘 UI（Qwerty / 九宫格 T9 / 15 键 T15 / 手写 / 符号 / Emoji），以及一整套 Compose 设置界面。
- **引擎**：`librime` 的 C++ 代码通过 JNI（`rime_jni`、`marisa_jni`）暴露给 Kotlin；Kotlin 侧用 `IEngine` 接口抽象，当前唯一实现是 `RimeEngine`。插件启用 `librime-lua`、`librime-octagram`（语法模型）、`librime-predict`（预测）。
- **数据**：随包携带一个 65 MB 的 `assets/resource.zip`，解压后是万象拼音的方案、词库、Lua 脚本和 `predict.marisa` 预测模型；运行时解压到外部私有目录给 librime 使用。

特点：**不是**在 Rime 之上套一层通用配置 UI（如 Trime），而是把 Rime 当作纯输入内核，键盘布局、候选面板、主题、剪贴板、语音、手写全部自己实现。

---

## 2. 功能一览

**输入**

- **键盘槽只有两个**：英文槽（固定用 `wanxiang_english`，不可更改）与中文槽（在输入方式之间切换）。
  底层仍是 Rime 方案（`wanxiang_t9` / `wanxiang` / `wanxiang_english`），但用户不再直接管理「启用哪些方案」
- **模糊音默认开启**（6 组：平翘舌 / 前后鼻音 / n-l），`zongguo` → 中国；
  开关位置与取舍见 [9.4](#模糊音本项目默认全开)
- **首字母简拼**：`qryt` → 杞人忧天、`zjh` → 这句话；候选数量与耗时可用
  [`scripts/rime-probe/`](../scripts/rime-probe/README.md) 在开发机上实测
- **首字母整句**：整句候选 8 条（`translator/max_sentences`，引擎默认只有 1 条），
  见 [9.6.1](#961-首字母整句translatormax_sentences)
- 四种输入方式：全键盘（Qwerty）、九宫格（T9）、15 键（T15）、手写。
  同一输入方式有多个方案时在切换列表里平铺（`九键1 / 九键2`）。
  **可用性 = 应用支持该键盘 ∩ 存在候选类型（`candidateKind`）匹配的方案**（手写不走引擎、不需要方案）；
  键盘由「当前槽的输入方式偏好」决定，方案的 `layout` 字段只作兜底默认值
- 符号键盘、Emoji 键盘；全角/半角标点切换
- 简↔繁转换（自带 FMM 转换器，不依赖 OpenCC 运行时）
- 英文/ASCII 模式、Emoji 参与候选

**候选与编辑**

- 候选词面板：横滑候选条 + 展开 5×5 候选网格，可拖拽重排、删除/“忘记”候选
- 候选词预测（marisa `predict.marisa`）与语法模型重排（`.gram`）；可开关。
  **语法模型真正参与打分**，Rime 自身排序作为位次先验，详见 [9.6](#96-候选词排序--预测)
- **常用词学习 + 误选降权**：正向点击与「上屏后又删掉」的负向信号都进排序，
  可正可负、按 24 h 半衰期衰减（误选不会永久钉死一个词）
- 学习数据可在「设置 → 候选词 → 学习数据」一键重置
- 文本编辑面板（光标移动、选择、剪切/复制/粘贴）、预编辑悬浮拼音条
- 输入框实时上屏（预览）：设置 → 上屏设置里可选「不上屏 / 原始输入上屏（如 `ni'hao`）/ 首候选上屏」，并可选择切换方案、收起键盘时是否把已上屏内容正式保留
- 剪贴板历史（Room 持久化、云同步标记、自动清理策略）+ 常用语管理

**手写**

- 双引擎：本地 ochwpro ONNX（随包、离线、飞行模式可用；经 JNI `dlopen` 复用 APK 内已有的
  `libonnxruntime.so`，不额外打包 ONNX Runtime）+ Google ML Kit 数字墨水识别（主引擎，需 GMS，首次下载约 20 MB）
- `AUTO`：优先 Google，不可用静默落本地；显式选 Google 时不自动降级
- 面板：书写区 + 右侧标点栏（⌫ 固定）+ 功能行；候选显示在顶栏候选位（与九键共用渲染与命中）
- 候选为**组合态**：写完即进输入框但未定型，点其它候选直接替换；写下一个字 / 切键盘 / 收键盘 /
  关输入法 / 空格回车标点 时正式保留
- 抬笔停手自动识别并清空笔迹，停手时长可调（0.2–2.0 秒）
- 「半/全」切换键盘区域内手写 / 整屏手写（整屏时写在应用内容之上、应用不被顶起）

**语音**

- sherpa-onnx（zipformer transducer）离线语音转文字，跑在独立 `:speech` 进程
- 高通 QNN/HTP 加速（`assets/cdsp` + `libQnnHtp/System`），不支持时回退 CPU onnx
- 模型按需从后端下载（`.tar.bz2`，MD5 校验）

**其它**

- 键盘主题：内置 3 套（暗夜 / 素白 / 落日）+ **不限数量**的自定义主题；应用内 GUI 编辑器可直接调色、调按键形状/边框/圆角/间距/高度并命名保存，支持二维码导入导出（格式见 [`THEME_FORMAT.md`](THEME_FORMAT.md)）
- 工具栏工具自定义：键盘上方工具栏中间那排图标可由用户勾选、排序、增删（撤销/重做/剪贴板/主题/语音/表情…）
- **侧栏快捷符号自定义**：设置 → 键盘布局 →「侧栏符号」里可编辑九键 / 手写面板 / 数字键那条可滑动符号栏的内容与顺序（九键与手写共用一份）
  （增删、上下移排序、恢复默认，还能添加任意自定义符号）
- **按键映射自定义**：设置 → 键盘布局 →「按键映射」里可改 26 键每个字母键下的符号 / 数字（`q→1`、`g→$` …）、
  九键每个数字键包含的字母（`2→abc`、`7→pqrs`）；改完键帽显示、气泡内容、上屏结果三处同时生效
- **按键气泡**：在字母键 / 九宫格数字键上长按（或上滑后停住约 0.3s）弹出气泡——主体悬在按键上方、
  一条**与按键同宽同高的指针压住键帽**，同一条轮廓、同一套圆角与描边，看起来和键帽是一体的；
  手指按住不放左右滑动切换高亮项、松开输入；26 键气泡为「小写字母 / 符号 / 大写字母」，九键为「数字 / 该键的每个字母」。
  气泡永远在按键上方（顶行也不例外），屏幕边缘自动夹取；**快速上滑仍然直接输入符号 / 数字**，气泡只在你想选别的候选项时出现。
  可在「按键映射」页开关（关闭即回到原来的直接输入）
- 符号 / 数字输入手势：26 键、九键、15 键支持「长按输入」与「上滑输入」二选一（设置 → 键盘布局 → 按键手势）；
  开启「按键气泡」后该设置只决定气泡的触发方式（长按还是上滑）
- **横屏悬浮键盘**：横屏时键盘自动变成一张可拖动的小卡片悬浮在应用之上（宽度可调、位置可拖动并记忆），
  应用界面不再被键盘顶起；设置 → 键盘布局里可开关与调整宽度
- **键盘内直接调大小**：键盘左上角菜单 →「调整键盘大小」，进入编辑模式后拖上边框调高度、拖左右边框调宽度，
  竖屏/横屏都可用，带实时百分比显示与「重置 / 完成」按钮
- SAF 文件管理（`AppFilesDocumentsProvider`）：无需 root 即可用系统“文件”App 浏览/编辑 `files/` 下的方案与词库
- 运行日志、崩溃日志、版本检查、按键音/震动/水波纹等细节设置
- **按键振动可切换效果来源**：按键设置 →「点击时振动」可关闭，并可在两种模式间切换 ——
  「系统触感」（默认）把按键触感原样交给系统渲染 `HapticFeedbackConstants.KEYBOARD_TAP`，走厂商调校过的预置效果（驱动带 overdrive / active braking，没有多余余振），
  与系统键盘、系统 UI 同一条通路；「自定义强度」提供 10 级强度（极弱 / 很弱 / 较弱 / 偏弱 / 轻微 / 很轻 / 轻 / 适中 / 强 / 最强），
  可做出比系统更轻的手感并可忽略系统开关，但振感取决于设备 HAL 对自定义波形的处理

---

## 3. 技术栈与依赖

**Gradle / 版本目录**：`gradle/libs.versions.toml`

| 类别 | 依赖 |
|------|------|
| 核心 | AndroidX core-ktx、lifecycle-runtime-ktx、activity-compose |
| UI | Compose BOM `2026.06.01`、Material3、material-icons-extended、material（View 侧）、Splitties views-dsl 系列 |
| 序列化/存储 | kotlinx-serialization-json、Room `2.8.4`（KSP） |
| 网络/归档 | OkHttp `4.12.0`、commons-compress `1.27.1` |
| 二维码 | zxing-android-embedded `4.3.0` |
| 日志 | Timber `5.0.1` |
| 语音 | 本地 AAR `app/libs/sherpa-onnx-1.13.5-qnn.aar`（已入库） |
| 手写（主引擎） | `com.google.mlkit:digital-ink-recognition`（Maven，模型运行时按需下载） |
| 手写（兜底） | ochwpro ONNX + 仓库内自写 JNI；模型随包，二进制不入库（`scripts/fetch-handwriting-model.sh`） |

> 死依赖提示：`kotlinpoet` / `kotlinpoet-ksp` 已声明但代码中无引用；`libs.tokenizer` 被注释。KSP 目前只服务于 Room。

**Native（`app/src/main/cpp`）**

- Boost `1.89.0`（cmake 版，按需下载）
- `danjian/librime` + `librime-lua`（含 thirdparty）+ `librime-octagram` + `librime-predict`
- OpenCC、snappy、glog、yaml-cpp、leveldb、marisa-trie
- `librime_jni`（`rime_engine/rime_key/rime_levers/rime_config/rime_opencc.cc`）+ `libmarisa_jni`

---

## 4. 架构总览

```
                       ┌──────────────────── Android 系统 ────────────────────┐
                       │ InputMethodService                                   │
  KeyActionListener ──►│  ImeInputMethodService                               │
  PanelActionListener ─┤    └── KeyboardWindow ── KeyboardWindowView          │
                       │           ├── KawaiiPanel      （候选条 / 工具栏）    │
                       │           ├── CandidateGridView（展开候选网格）       │
                       │           ├── PreeditPinner     （预编辑悬浮条）      │
                       │           ├── Clipboard/TextEdit/Confirm 组件         │
                       │           └── SpeechOverlayView （语音可视化）        │
                       │                     ▲                                │
                       │   processKey/commit/selectCandidate                  │
                       │                     │                                │
                       │            IEngine（EngineFactory）                  │
                       │                     │                                │
                       │              RimeEngine                              │
                       │   Action Channel（单线程归约） ──► BehaviorHost        │
                       │                     │                                │
                       │                     ▼  suspend jobs（串行）           │
                       │              RimeApi / Rime  ──JNI──► librime        │
                       │                     │                                │
                       │                     ▼  RimeMessage                   │
                       │      EngineMessageConverter ──► EngineMessage        │
                       │                     │  SharedFlow（messages）        │
                       └─────────────────────┼────────────────────────────────┘
                                             ▼
                                   KeyboardWindow / UI 更新

  设置界面（Compose）：MainActivity → SetupActivity / 各 Settings*Activity
                        └─ 读写 SharedPreferences / Room / Storage
```

核心设计要点：

- **引擎抽象**：`engine/IEngine.kt` 定义 UI 可见的全部能力；`EngineFactory` 负责单例注册与切换（`switchTo`）。想换引擎只需实现 `IEngine` 并改 `AppStartup.setupEngine`。
- **单线程归约器**：`RimeEngine` 把来自 UI 的所有操作封装成 `Action`，投入 `Channel<Action>`，由**唯一一个协程**顺序执行 `reduce(action)`；所有 librime 调用又通过第二个 `jobs` channel 交给 `RimeSession.runOnReady`，最终都在 `rime-main` 单线程执行 → 天然规避并发竞态。
- **单向消息流**：native → `RimeMessage` → `EngineMessageConverter` → `EngineMessage`（SharedFlow）→ `KeyboardWindow`/`KawaiiPanel` 渲染。UI 不直接读 librime 状态。
- **行为模式**：`IBehavior`（`InputKey/InputString/Backspace/Reset/Segmentation/Selection/SelectPinYin`）+ `BehaviorHost` 调度，是 fcitx5 行为的移植。

---

## 5. 代码目录地图

所有 Kotlin 位于 `app/src/main/java/com/ninthsoft/ime/`。

### `base/` —— 与业务无关的基础设施（37 文件）

| 目录 | 内容 |
|------|------|
| `speech/` | `SherpaSpeechClient`（主进程客户端）、`SpeechRecognitionService`（`:speech` 进程，AudioRecord + sherpa-onnx）、`SpeechIpc`（Messenger 协议）、`ModelDownloader`、`SpeechUiBridge`、`SpeechPermissionActivity` |
| `ngram/` | `GramDb`（mmap 读 `.gram`）、`DoubleArrayTrie`、`GramEncoding`（Rime table.bin 编解码）、`GramModelDownloader` |
| `marisa/` | `MarisaTrie`（JNI）、`Prediction`（读 `predict.marisa` 做下一词预测） |
| `net/` | `VersionChecker`（在线检查更新；地址留空即关闭，入口也会隐藏） |
| `util/` | `TraditionalConverter`（简繁 FMM）、`PinYinUtil`、`ResourceExtractorUtil`/`ResourceUtil`/`TarBz2ExtractorUtil`、`FontManager`、`InputConnectionUtil`、`TextUtil`、`PunctuationUtil`、`ProcessUtil`、`ViewAnimationUtil`、`AssetExtractionProviderUtil` 等 |
| `priority/` | `PriorityCalculator`（候选打分权重）、`PreferenceScorer`（用户偏好净分 + 时间衰减，纯函数，有单测） |
| `feedback/` | `InputFeedbacks`（SoundPool + 震动） |
| `log/` | `AppLogBuffer`（Timber tree + logcat 环形缓冲 + `crash.log`） |
| `once/` | `Once`（线程安全只执行一次） |

### `data/` —— 数据模型、设置、持久化（30 文件）

| 文件/目录 | 内容 |
|-----------|------|
| `App.kt` | 外部目录常量：`themes/`、`log/`、`download/`、`model/`、`model/speech/` |
| `manager/` | `KeyboardManager`、`CandidateManager`、`SchemaManager`、`ClipboardManager`、`PhraseManager`、`CandidateSortingManager` —— 所有 SharedPreferences 键见[附录 12.1](#121-sharedpreferences-清单) |
| `database/` | Room v8：`CandidateSorting`、`ClipboardRecord`、`CandidatePrefer`、`PhraseRecord` + DAO |
| `ThemeStore.kt` + `theme/` | 自定义主题读写（`themes.json`）、`ReadableTheme`（长 key/hex）、`CompactTheme`（二维码短 key） |
| `keyboard/theme/` | `KeyboardColors.ColorScheme`、`KeyboardTheme`、`KeyboardThemePresets`（3 内置） |
| `Symbol.kt` | 符号 / Emoji 分类数据（约 2400 行） |
| `PunctuationMode.kt` | 全角/半角映射 |
| `SchemaLayout.kt` | layout → 文案（T9/T15/全键盘） |

### `engine/` —— 输入引擎（57 文件）

| 目录 | 内容 |
|------|------|
| 顶层 | `IEngine`、`EngineFactory`、`RimeEngine`（核心归约器）、`AppStartup`（启动编排）、`IBehaviorHost` |
| `behavior/` | 行为抽象（`IBehavior` 及各命令） |
| `rime/behavior/` | 上述行为在 Rime 上的实现（委托 `RimeBehavior.Impl()`） |
| `rime/core/` | `Rime`（JNI 声明 + 响应合成）、`RimeApi`、`RimeDispatcher`（`rime-main` 单线程）、`RimeLifecycle`、`RimeMessage`/`RimeMessageConverter`、`RimeProto`（镜像 `cpp/.../rime_data.h`）、`RimeConfig`、`RimeSchema`、`KeyMapping`、`KeyValue`、`RimeKeyEvent` |
| `rime/daemon/` | `RimeDaemon`（进程级单例 + 命名 Session）、`RimeSession` |
| `rime/data/` | `DataManager`（assets→shared 增量同步）、`DataDiff`/`DataSum`、`opencc/`（`.txt`→`.ocd2`）、`userdict/` |
| `rime/host/` | `BehaviorHost`（输入串/行为队列调度） |
| `rime/util/` | `OptionsApplier`（应用设置 → Rime runtime options） |
| `manager/` | `PredictionManager`、`CandidateRerankManager` |
| `event/`、`data/` | `KeyEvent`/`KeyModifiers`；`EngineMessage`（UI 消息）、`CandidatePinYin`、`constant.kt` |

### `input/` —— 键盘与 IME 服务（67 文件）

| 目录 | 内容 |
|------|------|
| `ImeInputMethodService.kt` | 输入法服务入口（生命周期、连接管理、对话框） |
| `ImeInputConnection.kt` | 内存 InputConnection（“添加常用语”桥接模式用） |
| `KeyActionListener.kt` / `PanelActionListener.kt` | 按键 → 引擎；面板/工具栏 → 各功能 |
| `keyboard/impl/` | `BaseKeyboard`、`QwertyKeyboard`、`NumberKeyboard`、`T9Keyboard`、`T15Keyboard`、`SymbolKeyboard`、`EmojiKeyboard` |
| `keyboard/key/` | `KeyDef`（声明式按键模型）、`KeyPreset`（按键工厂）、`KeyView` 系列、`KeyboardAction`、`CustomGestureView`、`GridKeyboardView`、`KeyDrawable`、`KeyPreviewPopup`、`SidePanelView` |
| `keyboard/window/` | `KeyboardWindow`（门面）、`KeyboardWindowView`（根 FrameLayout）、`KeyboardStateManager`（进程级状态/键盘注册表）、`InputBoxLayerView`、`MessageHandler` |
| `panel/` | `KawaiiPanel` + 渲染器 + `component/`（候选网格、剪贴板、菜单、文本编辑、确认浮层）+ `toolbar/` + `state/` |
| `pinner/` | 预编辑悬浮条 |
| `speech/` | 语音可视化 View（粒子波/频谱波） |
| `handwriting/` | 手写引擎与面板：`HandwritingEngineHolder`（引擎持有/串行/取消）、`OnnxEngine`+`ochwpro/`（本地）、`MlKitEngine`（Google）、`HandwritingModelStore`、`panel/`（面板与书写视图） |
| `keyboard/slot/` | 键盘槽：`KeyboardSlotPlan`（纯逻辑：平铺列表与可用性判定）+ `SlotLabels`（文案映射） |
| `dialog/` | 方案选择对话框 |

### `ui/` —— Compose 设置界面（33 文件）

`MainActivity`（首页/路由）→ `InitActivity`（初始化闪屏）、`SetupActivity`（引导启用/设为默认）
→ `MainScreen` 里的入口：`SchemaSettingsScreen`、`KeyboardSettingsScreen`、`KeyboardThemeSettingsScreen`（扫码/分享）、`ClipboardScreen`、`CandidateSettingsScreen`、`VoiceSettingsScreen`、`AboutScreen`、`LogScreen` + `AppFilesDocumentsProvider`。

### `app/src/main/cpp/` —— Native

`CMakeLists.txt`（顶层，聚合所有 deps 与 JNI）、`cmake/`（Find*.cmake）、`librime_jni/`、`libmarisa_jni/`、`deps/`（**gitignore，需 `install-deps.sh` 生成**）。

---

## 6. 关键运行流程

### 6.1 冷启动

`ImeApplication.onCreate` →（主进程）`AppStartup.initialize`：

1. `setupLogger`（debug 才装 Timber Tree，release 全部 no-op）
2. `setupThemeStore`（读 `themes/themes.json`）
3. `releaseResourcesIfNeeded`：对 `assets/resource.zip` 求 MD5，与 `<外部目录>/version.txt` 比对；**不一致或首次**则解压到 `getExternalFilesDir(null)/` 并写回 MD5
4. `setupInputFeedbacks`、`setupSherpaSpeech`
5. `setupEngine`：`EngineFactory.switchTo(RimeEngine)` → `engine.initialize`
6. `prewarmOpencc`（后台预热简繁词库）

App 状态机：`Starting → ResourcePreparing → EngineStarting → Finished`，`InitActivity` 据此显示进度。

### 6.2 Rime 部署（deploy）

`RimeEngine.initialize` 中 `sendJob { joinMaintenanceThread(); ... }` → 本地 `bootstrap(shared, user, versionName, fullCheck)`：

- `DataManager.sync()`：按 `assets/checksums.json`（**注意**：当前资源包里没有此文件，见[第 10 节](#10-注意事项--已知坑)）做增量同步到 `shared/`；并确保 `user/default.custom.yaml` 存在
- native `bootstrap` → librime `setup/initialize/start_maintenance`
- 维护线程完成 → 发出 `DeployMessage.Finish` → `RimeEngine` 置 `initialized=true`、初始化 `enabled_schema_ids`、读取 `grammar/language` 并加载预测模型、触发一次“初始化钩子”

部署过程会通过 `RimeDaemon` 发系统通知（进度/成功/失败）。

### 6.3 一次按键

```
KeyView 事件
  └─ BaseKeyboard.onAction(KeyboardAction)
       └─ input/KeyActionListener.onKeyAction
            ├─ KeySequenceAction / KeyCodeAction → IEngine.processKey
            ├─ CommitAction → IEngine.commit
            ├─ Backspace/Return/Space → CodeEvent("DEL"/"ENTER"/"SPACE")
            └─ SelectSchema / SelectCandidatePinYin / MultiReturnAction ...
  └─ RimeEngine.processKey → Action channel
       └─ processKeyInternal：空输入时 Space/Enter 直接提交；DEL→Backspace；'→Segmentation；其余→InputKey
            └─ BehaviorHost.flowed → 行为.sendJob → jobs channel
                 └─ RimeSession.runOnReady → RimeApi → JNI → librime
                      └─ Rime.processKeyInner → emitResponse() → RimeMessage
                           └─ EngineMessageConverter → EngineMessage → SharedFlow
                                └─ KeyboardWindow.handleEngineMessage → 面板/预编辑/提交
```

### 6.4 选中候选

`PanelActionListener` → `RimeEngine.selectCandidate`：

- 预测候选（`type=imePrediction`）：直接 `EngineMessage.Commit` + 请求下一轮预测
- Rime 候选：记录 `candidate_prefers`（用于重排）、`suppressNextEmptyCandidates=true`、发 `Selection(index)` → native `select_candidate` → 返回 commit + 新候选
- 候选列表回来后：开启重排则用 `CandidateRerankManager` 重排，否则按用户拖拽保存的顺序（`candidate_sorting_v2`）还原

---

## 7. 构建与运行

### 7.1 环境要求

- JDK 17+（当前机器为 21）
- Android SDK：`platforms;android-37`、`build-tools`、`platform-tools`
- Android NDK（`app/build.gradle.kts` 未 pin `ndkVersion`，用 AGP 默认；本机有 25/28 系列）
- CMake `3.22.1`（`externalNativeBuild` 指定）
- `git`、`curl`、`python3`（构建 librime 相关脚本可能用到）

### 7.2 ⚠️ 当前 checkout 缺失的构建物料

克隆下来后**不能直接编译**，缺以下内容（均被 `.gitignore` 排除或从未入库）：

| 缺失项 | 路径 | 说明 / 获取方式 |
|--------|------|-----------------|
| `resource.zip` | `app/src/main/assets/resource.zip` | **必需**。65 MB 的万象拼音方案/词库 + `predict.marisa`。见下方获取步骤 |
| native 依赖 | `app/src/main/cpp/deps/` | 运行 `./install-deps.sh` 生成（含 Boost 1.89.0） |
| SDK 路径 | `local.properties` | 写入 `sdk.dir=/path/to/Android/Sdk` |
| 签名 | `keystore.properties`（已 gitignore） | 可选。填了就用 `release` 签名，不填则产出未签名的 release 包。CI 可用 `IME_STORE_FILE` / `IME_STORE_PASSWORD` / `IME_KEY_ALIAS` / `IME_KEY_PASSWORD` 环境变量覆盖 |

#### 获取 `resource.zip`

**推荐：用脚本从万象拼音 17.9.9 重建。**

```bash
python3 scripts/build-rime-resource.py --dry-run   # 先看会做什么，不写文件
python3 scripts/build-rime-resource.py             # 重建（覆盖 assets/resource.zip）
```

`--source` 默认取本机 fcitx5 的 rime 用户目录；也可以指向万象官方发布包解出的目录，
或上一次的输出目录（脚本幂等）。脚本负责：

1. **白名单取数**：排除用户状态（`*.userdb` / `user.yaml` / `installation.yaml` /
   `build` / `sync` / `*.gram`）。
2. **注入 app 桥接字段**（万象官方包里**没有**，缺了功能会瘸）：
   - `schema/{layout,punctuation,kind,candidateKind}` —— `candidateKind` 决定该方案能驱动哪种输入方式（`PinYin` → 26 键/15 键、`T9PinYin` → 九键），`layout` 只作兜底、
     方案列表标签、预编辑拼音条都靠它；
   - 顶层 `options:` 块 —— `OptionsApplier` 据此把 app 的「繁体 / Emoji / 英文模式」
     三个设置映射成 Rime 运行时选项。
3. **注入模糊音规则**（见 [9.4](#94-输入方案rime-schema)），`--fuzzy` 可选档位。
4. **注入整句候选配置**（见 [9.6.1](#961-首字母整句translatormax_sentences)）：
   `translator/max_sentences: 8` + `sentence_cutoff_threshold: 0.5`，
   `--max-sentences 1` 可关掉。**librime 默认 `max_sentences: 1`，整句只给一条猜测**，
   这是「首字母整句」手感差的直接原因。
5. 保留上一版 zip 里的 `model/predict.marisa`（app 专用，万象官方包没有）。
6. **引用校验**：扫 yaml 的 `import_tables` / `files` 与 lua 里的 `lua/data/...`
   字面量，有「被引用但没打包」的文件就报错退出，避免打出启动即报错的包。
7. 输出 `scripts/rime-resource-manifest.json`（文件清单 + sha256 + 万象版本 +
   注入内容），**建议随代码一起提交**用于追溯。

> **改了方案文件必须重新部署才生效**：librime 部署时会把方案副本写进
> `user/build/<schema_id>.schema.yaml`，之后**从那里读**（`shared/` 下的原文件不再参与）。
> 在探针里直接改 `shared/` 下的 yaml 是不生效的 —— 改 `user/build/` 里的副本，
> 或者跑一次部署。app 侧不用管：`startRime(false)` → `start_maintenance(false)` →
> `detect_modifications` 比对 user/shared 两个目录的文件变更，变了就会重新部署，
> **不需要用户手动点「重载引擎」**。

打包结构（librime 要求根目录直接是 `shared/` 和 `model/`）：

```
shared/                      # librime shared_data_dir
├── *.schema.yaml            # wanxiang / wanxiang_t9 / wanxiang_t9i / wanxiang_english
│                            #   / wanxiang_mixedcode / wanxiang_reverse
├── default.yaml             # schema_list 等默认配置
├── weasel.yaml / wanxiang_algebra.yaml / wanxiang_symbols.yaml
├── dicts/*.dict.yaml        # 词库（jichu/lianxiang/shici/...）
├── lua/wanxiang/*.lua       # 万象 Lua 脚本
├── lua/data/*.txt           # 脚本数据
├── custom/*.custom.yaml     # 方案补丁（注意：这个目录**不参与部署**，见 10.13）
├── AGENTS.md / README.md    # 万象自带文档（随包携带，保留 CC BY 署名）
└── version.txt              # 万象版本：17.9.9
model/
└── predict.marisa           # 37 MB marisa 预测模型
```

<details>
<summary>备选：从上游 release APK 抽（拿到的是旧版 17.2.4 数据，不推荐）</summary>

上游 release APK 里打包了**旧版**资源（`danjian/ime` v1.0.5 对应万象 17.2.4，没有模糊音、
缺 `context_reorder` / `unicode_conversion` / `random_tools` / `super_sequence` 等模块）。
只在无法获取 17.9.9 时使用。`JIme-v1.0.5.apk` 是上游 release 的文件名，照抄即可：

```bash
curl -L -o /tmp/JIme-v1.0.5.apk \
  https://github.com/danjian/ime/releases/download/v1.0.5/JIme-v8a-v1.0.5.apk
unzip -o /tmp/JIme-v1.0.5.apk assets/resource.zip -d /tmp/jime_rz
cp /tmp/jime_rz/assets/resource.zip app/src/main/assets/resource.zip
md5sum app/src/main/assets/resource.zip   # 旧值：98dd07b126483d22b2cc6c99afe07e44
```

注意这条路径拿到的 package **没有** app 桥接字段注入，也是它能工作（上游 APK 里的
schema 已经被上游改过了），但换掉之后就没法用脚本复现了。

</details>

> 修改/替换 `resource.zip` 后无需其他操作：`AppStartup` 靠 MD5 判断，下一次启动会自动重新解压。
>
> ⚠️ **改了 `speller/algebra`（例如开关模糊音）会强制重建 `table.bin` / `prism.bin`**，
> 手机上首次启动的部署会明显变慢（一次性成本）。
>
> 打包时用 `cd resource && zip -r ../resource.zip .`，别用 `zip -r resource.zip resource/`（那会多出一层壳）。
> 多套一层 `resource/` 也能用（`ResourceExtractorUtil` 会自动剥掉），但以文档这套结构为准。

### 7.3 构建步骤

```bash
# 0) 准备工作目录
cd /path/to/ime

# 1) 配置 SDK 路径
echo "sdk.dir=$HOME/Android/Sdk" > local.properties

# 2) 拉取 native 依赖（Boost 1.89.0 + librime 及插件、OpenCC、snappy 等）
chmod +x install-deps.sh
./install-deps.sh

# 3) 放入 resource.zip（见 7.2）
ls -lh app/src/main/assets/resource.zip

# 4) 编译（发布用 release，调试试 debug）
./gradlew :app:assembleRelease
# 产物：app/build/outputs/apk/release/ime-<版本号>.apk

./gradlew :app:assembleDebug
# 产物：app/build/outputs/apk/debug/ime-<版本号>-debug.apk
```

首次 native 编译耗时较长（librime + Boost + OpenCC）。`install-deps.sh` 是**幂等**的：已存在的目录会 `git fetch + reset --hard`，Boost 已存在则跳过。

**APK 命名**：`app/build.gradle.kts` 末尾在两个 `assemble<BuildType>` 后面挂了 `rename<BuildType>Apk`
（`assembleDebug` / `assembleRelease` 结束后自动执行，也可以单独 `./gradlew :app:renameReleaseApk`），
把 AGP 默认的 `app-<buildType>.apk` 改成 `ime-<versionName>.apk` / `ime-<versionName>-debug.apk`。
之所以用后置改名而不是 AGP 的 `outputFileName`：AGP 9 的新 Variant API 已经移除了这个可写入口
（`applicationVariants` / `outputFileName` 都不可用），改名是版本无关的做法。

> release 需要签名：仓库根的 `keystore.properties`（已 `.gitignore`）或环境变量
> `IME_STORE_FILE` / `IME_STORE_PASSWORD` / `IME_KEY_ALIAS` / `IME_KEY_PASSWORD`；
> 两者都没有时 `assembleRelease` 产出的是未签名的 `ime-<版本号>.apk`（装不上，需要先签名）。

### 7.4 安装与首次使用

```bash
adb install -r app/build/outputs/apk/release/ime-2.3.1.apk
```

打开 App → `InitActivity` 等待资源解压/引擎部署 → `SetupActivity` 引导：
① 在系统设置里启用“简意输入法” → ② 设为默认输入法 → ③ 开始使用。
之后可在任意输入框切换过来；键盘的“文件管理”入口（或系统“文件”App）可访问 `Android/data/com.ninthsoft.ime/files/`。

---

## 8. 数据与存储布局

### 8.1 外部私有目录

`getExternalFilesDir(null)` = `/sdcard/Android/data/com.ninthsoft.ime/files/`

| 路径 | 用途 |
|------|------|
| `shared/` | librime `shared_data_dir`（方案、词库、lua、`opencc/`、`<lang>.gram`、`build/`） |
| `user/` | librime `user_data_dir`（用户词典、`build/` 编译产物、`default.custom.yaml`） |
| `model/predict.marisa` | marisa 预测模型 |
| `model/speech/` | 语音模型（`tokens.txt` + encoder/decoder/joiner） |
| `themes/themes.json` | 自定义键盘主题（数量不限，可由 GUI 编辑器生成） |
| `download/` | 模型下载临时目录 |
| `log/crash.log` | 崩溃日志 |
| `version.txt` | `resource.zip` 的 MD5 戳记（内部 `filesDir` 下另有 `checksums.json` 记录） |

### 8.2 访问方式

- **自带的 SAF Provider**：`AppFilesDocumentsProvider`（authority `com.ninthsoft.ime.files.documents`），把 `files/` 整个目录暴露给系统“文件”App，支持增删改查/重命名/复制/移动/搜索；`.yaml/.txt/.json/.lua/...` 会以 `text/plain` 打开，方便直接改方案。
- **ADB**：`adb shell run-as com.ninthsoft.ime ls files` 或直接访问外部目录（debug 包）。

### 8.3 配置持久化

- **SharedPreferences**：见附录 [12.1](#121-sharedpreferences-清单)
- **Room v8**（`ime_database`）：`candidate_sorting_v2`、`clipboard_records`、`candidate_prefers`、`phrase_records`，见附录 [12.2](#122-room-表结构)

---

## 9. 定制指南

> 本节是二次开发索引：改什么 → 动哪个文件。

### 9.1 键盘布局 / 按键

| 需求 | 位置 |
|------|------|
| 改 Qwerty 全键盘 | `input/keyboard/impl/QwertyKeyboard.kt` → `buildLayout(context)`（字母键的次级符号 / 气泡内容来自 `KeyboardKeyMapping`，其余仍写死；底行：符号 0.13 / 中英 0.12 / `.` 0.09 / 空格 0.30 / `,` 0.09 / 数字 0.12 / 回车 0.15；`.`/`,` 键帽固定半角，`CommitAction` 仍跟随全角-半角标点模式） |
| 改数字键盘 | `input/keyboard/impl/NumberKeyboard.kt` → `Layout`（4 行 × 5 等列，列宽 `0.17 / 0.22 ×3 / 0.17`：`1 2 3 ⌫` / `4 5 6 @` / `7 8 9 .` / `符号 空格 0 返回 回车`；底行与上面逐列对齐，回车只占底行一格、不再跨行） |
| 改九宫格 / 15 键 | `input/keyboard/impl/T9Keyboard.kt` / `T15Keyboard.kt`（九键：侧栏 + 4 列，列宽同数字键盘，底行 `符号 中英 空格 123` 与九宫格逐列对齐，大回车跨第 3、4 行；15 键：`0.17 + 中间 5×0.132 + 0.17`） |
| 非 26 键键盘列宽 | 最左 / 最右两列由 0.15 加宽到 0.17，多出来的宽度由该行中间各列**均分扣除**（9 键 / 数字键的 0.23333 → 0.22，底行直接与上行对齐；15 键的 0.13998 → 0.132，底行 0.13/0.44/0.13 各让 1/75）；26 键既没有侧栏、分布也不等分，不适用 |
| 符号 / Emoji 键盘 | `input/keyboard/impl/SymbolKeyboard.kt` / `EmojiKeyboard.kt` + `data/Symbol.kt`（左侧栏与其它键盘最左列对齐，占屏宽 0.17——展开的候选词面板 `CandidateGridView` 侧栏同为 0.17；右侧网格仍是 5 等分） |
| 布局数据模型 | `input/keyboard/key/KeyDef.kt`（`KeyDef(appearance, behaviors, popups, bubble)`，行是 `List<KeyDef>`，宽度用 `percentWidth` 分数，每行和 ≈ 1；`bubble` 是该键长按 / 上滑时的气泡内容） |
| 按键工厂 | `input/keyboard/key/KeyPreset.kt`（`alphabetKey`/`spaceKey`/`returnKey`/`capsLockKey`/`schemaSwitchKey`/`sidePannelKey`…） |
| 手势阈值 | `input/keyboard/key/CustomGestureView.kt`（长按 250ms、重复 100ms、滑动阈值；按键气泡也在这套状态机里） |
| 按键外观 / 圆角 / 描边 | `input/keyboard/key/KeyView.kt`、`KeyDrawable.kt`、`data/keyboard/theme/KeyboardColors.kt` |
| 新增键盘类型 | 实现 `input/keyboard/impl/IKeyboard.kt`（侧栏再加 `ISidePanelKeyboard`），并在 `KeyboardWindowView.createKeyboard()` 注册 |

**加一个自定义按键的例子**（概念）：

```kotlin
// QwertyKeyboard.buildLayout()
listOf(
    alphabetKey("q", "1"),
    // 长按输入 emoji、单击提交固定文本
    textKey(
        displayText = "😊",
        actions = setOf(
            KeyDef.Behavior.Press to KeyboardAction.CommitAction("😊"),
            KeyDef.Behavior.LongPress to KeyboardAction.CommitAction("😂"),
        ),
    ),
    backspaceKey(),
)
```

（实际 API 以 `key/KeyPreset.kt` 中的签名为准。）

### 9.2 符号 / Emoji 数据

`data/Symbol.kt`：

- `Symbol.Symbol: List<Pair<Category, Array<String>>>` —— 符号分类（最近/中文/英文/数学/序号/括号/箭头/全角/其他 + `SymbolExtra` 追加的单位/货币/拼音/注音/希腊/俄文/日文/韩文/框线/天气/星座/音乐，共 21 类）
- `Symbol.Emoji: List<Pair<Category, Array<String>>>` —— Emoji 分类

`data/SymbolExtra.kt` 是符号页的补充数据，拆出来是为了不再撑大以 Emoji 为主、已 2000+ 行的 `Symbol.kt`：

- `SymbolExtra.Extra: Map<String, Array<String>>` —— 给**已有分类**追加符号，key 必须与既有 `label` 完全一致；
- `SymbolExtra.Categories` —— **新增分类**，追加在既有分类之后。

`Symbol.Symbol` 会在初始化时把两者合并，并做一次 `distinct()` 去重（原始数据里本来就有 `∉ ∞ ∩ ∪ ⅸ 〉 〘 〙 ○` 等重复项）。
直接往对应 `arrayOf(...)` 增删即可，键盘会自动按 `GridKeyboardView` 分页展示。

> 注意：分类里的符号越多，切换该分类时 `GridKeyboardView.setItems()` 创建的 `KeyView` 越多（目前最大一类接近 300 个），
> 切分类会有一次性的构建开销。滚动本身已经只做画布平移 + 视口裁剪，不受影响。

其中「最近」的内容不写在 `Symbol.kt`，而是由 `data/manager/RecentSymbolManager.kt` 记录用户在符号页点过的符号
（去重、最多 25 个、持久化到 `symbol_recent` SharedPreferences，为空时回退到一组常用符号）。
`data/SymbolPair.kt` 维护成对符号表（前半 → 后半），符号页点前半符号时会自动补齐后半、把光标停在中间并切回上一个键盘；
半角直引号 `'` `"` 刻意不参与配对，避免 `don't` 被补成 `don''t`。

### 9.3 键盘主题

- 内置主题：`data/keyboard/theme/KeyboardThemePresets.kt`（`Amoled` / `Light` / `Sunset`）
- 颜色模型与默认值：`data/keyboard/theme/KeyboardColors.kt`（含 `keyBorderWidth` 边框厚度、`keyShape` 按键形状、`geometry` 几何快照）
- 自定义主题读写：`data/ThemeStore.kt` + `data/theme/ReadableTheme.kt`（磁盘 JSON）+ `data/theme/CompactTheme.kt`（二维码短 key）
- `themes.json` 与二维码字段格式：**详见 [`THEME_FORMAT.md`](THEME_FORMAT.md)**
- 导入/导出/扫码界面：`ui/screen/KeyboardThemeSettingsScreen.kt`（前缀 `IMEKBTHEME:`，zxing 生成 512×512 PNG，FileProvider 分享）
- **GUI 主题编辑器**：`ui/screen/ThemeEditorScreen.kt` + `ui/ThemeEditorActivity.kt`（含 RGBA 取色器、按键形状/边框/圆角/间距/高度滑杆；**实时预览固定在顶部**，直接嵌入真实的 26 键 / 九键 / 15 键键盘 View——可按但无输入，重建做了 60ms 防抖）
- 按键形状与描边实际渲染：`input/keyboard/key/KeyView.kt` + `KeyDrawable.kt`（`keyBackgroundDrawable`）

自定义主题**数量不设上限**（相同 id 覆盖）；运行时覆盖顺序为“内置 → 自定义”，`KeyboardTheme.byId()` 找不到会回退到 Amoled。带 `geometry` 的主题在应用时会写回全局圆角/间距/高度设置。

### 9.3.1 工具栏工具自定义

- 工具目录：`input/panel/toolbar/ToolbarTools.kt`（枚举：key / 文案 / 图标 / `PanelAction`）
- 偏好读写：`KeyboardManager.Keyboard.ToolbarTools`（`keyboard.toolbar_tools`，逗号分隔的有序 key 列表）
- 渲染：`input/panel/toolbar/ToolbarRenderer.kt` 的中央按钮由 `ToolbarRendererResources.centerButtons`（`configuredToolbarButtons()`）动态生成；`KawaiiPanel.refreshToolbarConfig()` 在配置变更后重建渲染器
- 配置界面：`ui/screen/ToolbarSettingsScreen.kt` + `ui/ToolbarSettingsActivity.kt`
- 注意：菜单网格与工具栏共用「切换类」动作处理，逻辑集中在 `KawaiiPanel.handleToggleAction()`，新增工具时在这里补分支

### 9.3.2 符号 / 数字输入手势（长按 vs 上滑）

- 偏好：`KeyboardManager.Keyboard.GestureInput`（`keyboard.gesture_input`，`0`=长按、`1`=上滑，互斥）
- 标记：`KeyDef.Behavior.LongPress(action, altInput = true)` 表示“次级符号/数字输入”；26 键 `alphabetKey`、九键 `mixedAlphabetKey`、T15 自定义 `mixedAlphabetKey`、`segmentKey`、`zeroKey`、`infiniteKey` 均已标记
- 接线：`input/keyboard/impl/BaseKeyboard.kt#createKeyView`——上滑模式下把这类长按改为 `setupSwipeAltInput()`（复用 `CustomGestureView` 的上滑手势），长按不再触发
- **触发条件**（两个都要满足；判定统一收在 `input/keyboard/key/SwipeUpMath.kt`，
  气泡路径与直接上滑路径**共用同一套**，所以开不开「按键气泡」手感一致）：
  1. **距离** = 当前按键高度 × `keyboard.swipe_up.ratio`（默认 `1.0` = 整整一个键高）
  2. **方向** = 纵向位移 ≥ 横向位移 × `keyboard.swipe_up.direction_tan`（默认 `1.5`，≈34° 以内）
- 设置入口：`ui/screen/KeyboardSettingsScreen.kt` 的「按键手势」分组 —— 「上滑触发距离」滑块
  （40%～150% 键高，仅在有上滑行为时显示）。
  **方向系数暂时没有 UI**，调参直接改偏好或调 `SwipeUpMath.DEFAULT_DIRECTION_TAN`

### 9.3.3 横屏悬浮键盘

横屏时键盘不再铺满整屏宽度，而是一张可拖动的小卡片；**应用窗口不会被键盘顶起**，卡片之外的手势直接穿透给下层应用。

实现方式（与 FlorisBoard 的 floating window 同思路，不需要 `SYSTEM_ALERT_WINDOW` 等额外权限）：

1. **IME 窗口占满全屏但背景透明**：悬浮模式下 `KeyboardWindowView.onMeasure()` 把自身测成整个可用高度
   （IME 窗口默认 `MATCH_PARENT × WRAP_CONTENT`，于是窗口变成全屏），键盘本体绘制在窗口内的一张小卡片里。
2. **用 `onComputeInsets` 控制两件事**（`input/ImeInputMethodService.kt`）：
   - `contentTopInsets` / `visibleTopInsets` = 内容底边 → 上报给应用的内容边衬为 0，应用不 resize；
   - `touchableRegion` = 卡片矩形 + `TOUCHABLE_INSETS_REGION` → 只有卡片接收触摸，其余穿透。
   框架在 `ViewRootImpl.performTraversals` 里于 layout 之后派发该回调，并把结果通过
   `mWindowSession.setInsets()` 交给 WMS，所以拖动时只要 `requestLayout()` 就会同步新的可触摸区域。
3. **卡片几何与拖动**：`KeyboardWindowView` 用 `onMeasure()` 算出卡片矩形（宽度按屏宽百分比、高度沿用
   「键盘高度（横屏）」），`onLayout()` 把候选栏/键盘/面板都布局到卡片内；`dispatchDraw()` 画圆角卡片、
   阴影和顶部拖动条；顶部 18dp 手柄区域由 `onTouchEvent()` 处理拖动。
   宽度未手动设置时取 **屏幕短边 / 长边**（`Keyboard.Floating.adaptiveWidthPercent()`）：横屏短边就是竖屏宽度，
   于是悬浮键盘的按键大小与竖屏基本一致，不会变成又宽又扁的横条。
4. **位置记忆**：拖动结束后把位置换算成「可移动余量」的比例写入偏好（`keyboard.floating.pos_x/pos_y`），
   这样换尺寸/换分辨率也能合理还原。拖动过程中用内存中的比例，避免 `onMeasure` 读到旧值把卡片弹回。
5. **键盘内实时调大小**：键盘左上角菜单 →「调整键盘大小」（`PanelAction.ResizeKeyboard`）进入编辑模式：
   强制切到卡片布局、整窗可触摸（屏蔽按键误触），拖上边框改高度、拖左右边框改宽度，
   顶部控制条实时显示「宽 x% · 高 y%」，另有「重置 / 完成」。完成后把宽高百分比与位置比例写回
   当前方向的偏好（竖屏 `keyboard.width`/`keyboard.height`/`keyboard.pos_*`，横屏 `keyboard.floating.*` +
   `keyboard.height_landscape`）；横屏缩窄时会自动打开悬浮开关。
   > 竖屏宽度 < 100% 时同样走「全屏透明窗口 + 卡片」布局，应用不会被顶起。

相关代码与设置：

| 需求 | 位置 |
|------|------|
| 偏好读写 | `data/manager/KeyboardManager.kt` → `Keyboard.Floating` |
| 模式判定（横屏 + 开关） | `Keyboard.Floating.shouldUseFloating(context)`，由 `ImeInputMethodService` 的 `onCreateInputView()` / `onConfigurationChanged()` / `onWindowShown()` / 偏好变更回调应用 |
| 卡片布局 / 绘制 / 拖动 | `input/keyboard/window/KeyboardWindowView.kt`（`onMeasure` / `onLayout` / `dispatchDraw` / `onTouchEvent`） |
| 触摸区域上报 | `input/ImeInputMethodService.kt` → `onComputeInsets()` |
| 设置界面 | `ui/screen/KeyboardSettingsScreen.kt` 的「键盘布局」分组 |

> 注意：悬浮模式下只有卡片的矩形区域（或添加常用语 / 语音时的整窗口）可触摸，其余区域会穿透到下层应用，
> 因此若要新增“浮动在卡片之外”的交互元素，必须同步扩展 `KeyboardWindowView.floatingTouchableRegion()`。

### 9.3.4 侧栏快捷符号自定义（九键 / 手写 / 数字键）

九键、手写面板、数字键的符号栏，其内容和顺序由用户在应用内编辑。

> 手写面板（半屏右侧竖排 + 全屏底部横排）与九键**共用** `keyboard.side_panel_symbols.t9` 这一份列表：
> 面板在 `rebuildContent()` 时读一次，偏好变化由宿主在 `onConfigChanged` 里转发给
> `HandwritingPanelView.refreshPunctuationSymbols()`。成对符号（`“（《【「`）的闭符号表是写死的，
> 描述的是「符号本身成不成对」这个事实，与用户在列表里放什么无关；不在列表里的符号不会出现。
> 手写不做全角 / 半角转换（面板没有标点模式的概念），列表里存的是什么就显示什么。

- 偏好读写：`KeyboardManager.Keyboard.SidePanelSymbols`（`keyboard.side_panel_symbols.t9` / `.number`，
  用不可见控制符 `\u001F` 分隔的有序字符串；未设置时用 `DEFAULT_T9` / `DEFAULT_NUMBER`）
- 九键：`input/keyboard/impl/T9Keyboard.kt#sidePanelPunctuations()` 读取用户列表后，**仍按全角 / 半角标点模式各转换一次**
  （`PunctuationUtil.toFullWidth/toHalfWidth`），因此默认列表与旧版行为一致（唯一差别：全角模式下 `~` 会变成 `～`，
  与 26 键字母键长按 `~` 的既有行为相同）
- 数字键：`input/keyboard/impl/NumberKeyboard.kt#onPossibleCandidatePinYin()` 原样使用列表（数字键盘没有标点模式）
- 配置界面：`ui/screen/SidePanelSymbolsScreen.kt` + `ui/SidePanelSymbolsActivity.kt`
  （入口：设置 → 键盘布局 →「侧栏符号」；支持上下移、删除、从备选池点选添加、自定义符号、恢复默认）
- 注意：编辑结果在下一次键盘弹出（`onAttach()` → `onPossibleCandidatePinYin(emptyList())`）时生效

> T15（15 键）也有一条侧栏，但仍使用 `T15Keyboard.kt` 里写死的 `fullWidthPunctuations` /
> `halfWidthPunctuations`，未接入本设置。

### 9.3.5 按键映射自定义（26 键符号 / 九键字母）

26 键每个字母键下面的符号或数字（`q→1`、`g→$` …）、九键每个数字键包含的字母（`2→abc`、`7→pqrs`）
都可以在应用内改，键帽显示、长按气泡内容、点击上屏三处同时生效。

- 偏好读写：`data/manager/KeyboardKeyMapping.kt`（`keyboard.key_mapping.qwerty` / `.t9`，只存**用户改过的键**，
  未改动的回落到 `DEFAULT_QWERTY_SYMBOLS` / `DEFAULT_T9_LETTERS`，所以以后调默认值老用户也能跟上）
- 存储格式：`键\u001F值\u001E键\u001F值…`（记录分隔 `\u001E`、键值分隔 `\u001F`，都不会和符号本身撞车）
- 26 键建键：`QwertyKeyboard.buildLayout(context)` → `alphabetKey(character, KeyboardKeyMapping.qwertySymbol(ctx, ch), …)`
- 九键建键：`T9Keyboard.buildLayout(context)` → `mixedAlphabetKey(digit, KeyboardKeyMapping.t9Letters(ctx, digit), …)`
- 配置界面：`ui/screen/KeyMappingScreen.kt` + `ui/KeyMappingActivity.kt`
  （入口：设置 → 键盘布局 →「按键映射」；26 键是可点的迷你键盘预览，九键是数字行列表，支持符号池点选与恢复默认）
- 生效时机：改完立刻写偏好，`KeyboardWindowView.onConfigChanged()` 收到这几个 key 后调用
  `KeyboardStateManager.rebuild()` 重建键盘（`refreshColors()` 只重建视图、不会重跑 `buildLayout`）
- 回归测试：`app/src/test/java/com/ninthsoft/ime/KeyMappingDefaultsTest.kt` 钉住默认键位（必须与旧版写死的一致）
- 注意：15 键**不接入**九键字母映射 —— 15 键自己有一套「数字键 ≈ 声母/韵母」的键位（`4` 管 `r` 和 `rf`），
  与九宫格的 ABC/DEF 分组不是一回事。15 键的字母串仍写死在 `T15Keyboard.buildLayout(context)` 里，
  只有气泡复用了 `KeyboardKeyMapping.t9BubbleItems()`

> ⚠️ `data/manager/` 下已有 `engine/rime/core/KeyMapping`（Rime 的键码常量表），
> 所以按键映射的偏好对象取名 **`KeyboardKeyMapping`**，不要写回 `KeyMapping`（会撞名、编译期直接 Unresolved）。

### 9.3.6 按键气泡（长按 / 上滑弹候选）

在字母键或九宫格数字键上长按（「按键手势」设为上滑时为上滑）弹出气泡：一块圆角主体 + **与按键同宽同高、压住键帽的指针**，
手指**不离开屏幕**左右滑动切换高亮项，松开输入高亮项。26 键气泡是「小写字母 / 该键符号 / 大写字母」，
九键是「数字 / 该键的每个字母」，15 键是「数字 / 该键现有字母」。

| 需求 | 位置 |
|------|------|
| 气泡外观与几何 | `input/keyboard/key/KeyBubbleLayer.kt`（**在键盘窗口内部**用同一条 `Path` 画出「圆角主体 + 键宽指针」，交界处两个内凹圆角；主体永远在按键**上方**、指针压住键帽，屏幕边缘自动夹取）；几何的纯计算在 `KeyBubbleGeometry.kt`（`KeyBubbleGeometryMath.compute()`，可离线测试，回归见 `KeyBubbleGeometryTest`） |
| 谁把气泡画出来 | `input/keyboard/window/KeyboardWindowView.kt` 实现 `KeyBubbleHost`，在 `dispatchDraw()` 的最后画气泡（压在所有子 View 之上、可盖住顶栏） |
| 手势状态机 | `input/keyboard/key/CustomGestureView.kt`（长按 250ms 起气泡；沿 View 树向上找 `KeyBubbleHost`；滑动按**气泡的屏幕坐标**取最近一项，与手指位置一一对应；抬手提交） |
| 内容与配色 | `HasKeyBubble` 接口；`BaseKeyboard.attachKeyBubble()` 把 `KeyDef.bubble` 与主题色交进去（**背景取该键自己的键帽色**再与键盘背景合成成不透明，指针压住键帽后才像键帽长出来的；高亮色用 `accentKeyBackground` + 按亮度自动选黑/白字） |
| 气泡内容生成 | `KeyboardKeyMapping.qwertyBubbleItems()`（小写 → 符号 → 大写，符号走 `CommitAction` 以跟随全角/半角标点模式，字母走 `KeySequenceAction` 交给 Rime）、`KeyboardKeyMapping.t9BubbleItems()`（数字走 `CommitAction`，字母走 `KeySequenceAction`） |
| 开关 | `KeyboardKeyMapping.isBubbleEnabled()`（`keyboard.key_bubble`，默认开）；关闭后回到旧行为：长按 / 上滑直接上屏符号或数字 |
| 与「按键手势」的关系 | 只决定**触发方式和时长**，见下表 |
| 描边 / 圆角 | 与键帽同一套：`keyBorderStroke` 颜色 + `keyBorderWidth` 厚度 + `keyRadius` 圆角，沿着「主体 + 指针」的同一条轮廓描边；**描边色自带 alpha 必须保留**，关掉「绘制键边框」时气泡也不描边 |
| 入口 | 设置 → 键盘布局 →「按键映射」页顶部的开关；也可从下文提到的 `keyboard.key_bubble` 直接改 |

> 气泡第一项固定是「点一下这个键本来会输入的内容」（26 键小写字母、九键数字），
> 所以弹出后不滑动直接抬手 = 普通点击，不会因为长按误上屏符号。
>
> ⚠️ **气泡不能做成 `PopupWindow`**（2026-09-19 改，借鉴 Xime `ui/keyboard/SwipeBubble.kt`）。
> 两个原因：① IME 窗口是 `MATCH_PARENT × WRAP_CONTENT`，窗口上边界就是顶栏上沿，
> 作为子窗口的 `PopupWindow` 画不出窗口之外，主体没地方悬；② 旧实现按「窗口内剩余空间」
> 决定气泡朝上还是朝下，而顶行按键上方只剩顶栏那 48dp（比主体还矮 1dp），
> 于是**九宫格第一行的气泡一律翻到按键下面**。现在由窗口自己在 `dispatchDraw` 里画，
> 永远在按键上方。改这条之前先看 `KeyBubbleGeometryTest`。

**触发时序（26 键 / 九键共用一套状态机，距离常量在 `SwipeUpMath`）**

| 操作 | 结果 |
|------|------|
| 轻点 | 普通输入（26 键打字母、九键进候选） |
| 按住 250ms（`bubblePressDelay`） | 弹气泡，左右划选，抬手提交 |
| 快速上滑抬手（纵向位移 ≥ 当前键高 × `swipeUpRatio`，**且纵向占主导**） | **直接输入符号 / 数字**（气泡不出现） |
| 上滑后停住（长按那一档到时） | 弹气泡 |

> ⚠️ **上滑触发距离锚定「按键高度」，不要改回固定 dp。** 这里踩过坑：最早的阈值是
> `touchSlop * 2`（约 16dp），而 `scaledTouchSlop` 由厂商 overlay 定义（真机 8～12dp 浮动），
> 结果是「稍微上滑一点点就出符号」，而且换台手机手感还变。现在统一走
> `input/keyboard/key/SwipeUpMath.kt`：阈值 = `当前键高 × ratio`（默认 1.0），
> 26 键 / 九键 / 各档键盘高度自动适配，下限 `2 × touchSlop` 兜住极小的悬浮键盘。
>
> 量级依据：本节上面那条真机日志 `dy=222px / h=157px ≈ 1.4 个键高` —— 真实上滑本来就在
> 「一个键高」量级，阈值定在这里不会把正常上滑挡在门外。回归见
> `app/src/test/java/com/ninthsoft/ime/SwipeUpThresholdTest.kt`。
>
> ⚠️ **光有距离不够，还必须卡方向。** 只看纵向位移的话，「手指横向滑动时顺带产生的纵向漂移」
> 也会被当成上滑 —— 典型场景是从 `q` 斜着划到 `e`，纵向一过阈值就蹦出符号。
> 所以判定改成两条**同时**成立：
> 距离 ≥ `键高 × ratio`，且 `纵向 ≥ |横向| × direction_tan`（默认 1.5，对应夹角 ≈34°）。
> 斜着上滑只要纵向更多，仍然照常触发。
>
> 参照仓输入法的 `tangentThreshold`（横向/纵向正切上限），但它默认 tan15° ——
> 换算下来纵向要达横向的 **3.73 倍**才认，比本项目的 1.5 严格得多。
> 单键上滑的活动范围只有一个键帽，卡那么死会让斜着上滑几乎出不来，所以这里取宽松值。
> 方向系数目前**没有 UI**，要调就改 `KeyboardManager.Keyboard.SwipeUp.KEY_DIRECTION_TAN`
> 或 `SwipeUpMath.DEFAULT_DIRECTION_TAN`。

**长按一律弹气泡**，不再跟着「按键手势」摇摆 —— 少一个互相打架的维度；那个设置只影响上滑行为的次要细节。

> ⚠️ 上滑识别**不能**挂在「长按弹气泡」这个标志下面。这里也踩过坑：曾写成
> `if (bubbleController != null && !bubbleTriggerOnLongPress)`，而长按弹气泡时该标志恒为 `true`，
> 上滑分支直接变成死代码，表现就是「普通上滑再也输入不了数字 / 符号，只能长按或上滑停留出气泡」。
> 现在上滑判定只看位移，与长按弹不弹气泡完全解耦（回归见 `BubbleSelectionMathTest`）。

> ⚠️ **不要在气泡弹出前用「手指离按键多远」当闸门**。这里踩过坑：最早用
> `(downY - y) > height || |x - downX| > width` 判断「手指飘走就取消」，结果 26 键键宽只有 ~35px，
> 长按/上滑时手指的自然漂移（实测 dy 能到 220～330px）几乎必然超标，气泡几乎永远弹不出来
> （真机日志：`showBubble: finger moved too far dy=222 h=157 w=259`）。
> 现在改成：**定时器一旦启动就必须跑到点**，由 `showBubble()` 决定弹不弹，靠「弹出来之后手指往哪儿滑」决定选哪一项。
> 另外气泡是浮层，背景必须是不透明实色（主题里的 `specialKeyBackground` / `accentKeyBackground` 都带 alpha，
> 用之前先和键盘背景合成；见 `BaseKeyboard.attachKeyBubble()`），否则会看起来像没画出来。

> 诊断：`adb logcat -s ImeBubble:I`（或 设置 → 关于 → 运行日志 里搜 `ImeBubble`）。
> 气泡弹出时会打 `showBubble: labels=[...] showing=true left=... width=...`；没弹会打原因；
> 气泡路径下没弹出气泡时还会补一次普通点击，保证不会「按了没反应」。

相关：T15 的混合键在 `T15Keyboard.buildLayout(context)` 里通过 `KeyboardKeyMapping.t9BubbleItems()` 取气泡，
字母串仍写死在布局里（见 9.3.5 的注意）。

### 9.4 输入方案（Rime schema）

方案数据在 `shared/`，用户补丁在 `user/`。常见做法：

1. 用 App 内“文件管理”打开 `files/`，把新方案的 `.schema.yaml` 及词库放进 `shared/`
2. 修改 `user/default.custom.yaml`（或 `shared/default.yaml`）的 `schema_list` 增删方案
3. 在设置里启用/排序方案（写入 `schema_settings.enabled_schema_ids`），或直接“重启引擎”触发 deploy

相关代码：

| 需求 | 位置 |
|------|------|
| 方案启用/排序 | `data/manager/SchemaManager.kt`、`ui/screen/SchemaSettingsScreen.kt` |
| 方案解析（layout/punctuation/kind） | `engine/rime/core/RimeSchema.kt`、`SchemaItem.kt` |
| 数据同步逻辑 | `engine/rime/data/DataManager.kt` |
| 语法模型下载 | `base/ngram/GramModelDownloader.kt` + `SchemaSettingsScreen` |
| OpenCC 词典编译 | `engine/rime/data/opencc/OpenCCDictManager.kt` |

> 万象默认 `menu.page_size: 6`。要改候选个数、标签、开关记忆等，改 `shared/default.yaml` 或 `shared/custom/*.custom.yaml`。

#### 模糊音（本项目默认全开）

万象内置 10 组模糊音规则，定义在 `shared/wanxiang_algebra.yaml` 的顶层节点里
（`模糊音_nl` / `_ry` / `_hf` / `_rl` / `_kg` / `_en_eng` / `_in_ing` / `_c_ch` /
`_z_zh` / `_s_sh`），**默认是注释状态**，由 `scripts/build-rime-resource.py`
注入到 `shared/wanxiang.schema.yaml` 的 `speller/algebra/__patch` 里：

```yaml
speller:
  algebra:
    __patch:
      - wanxiang_algebra:/base/全拼
# >>> ime:fuzzy (由 build-rime-resource.py 注入，勿手改)
      - wanxiang_algebra:/模糊音_nl
      ...（共 10 行）
# <<< ime:fuzzy
```

几个必须知道的点：

- **不能靠 `shared/custom/wanxiang.custom.yaml` 开**。万象的 `custom/` 补丁按它自己的
  设计要放到**用户目录**才生效，而 app 从不把 `shared/custom/` 拷到 `user/`，
  放那里等于不生效（见 10.13）。
- **只注入 `wanxiang`**。九宫格（`wanxiang_t9` / `_t9i`）的拼写运算是把字母即时转成
  数字键，全拼模糊音规则不适用；它的简拼走 `lua/data/t9_abbrev.txt` 那套简码。
- **改档位**：`--fuzzy safe`（平翘舌 + 前后鼻音 + n/l 共 6 组，**默认**）/
  `all`（10 组）/ `none`。默认不用 `all` 的依据是实测：`all` 多开 r/l、r/y、h/f、k/g
  之后，`qryt` 的候选数从 2546 涨到 4011、首选从「杞人忧天」变成「去了一趟」
  （多出的歧义来自 r/l、r/y），而 `safe` 该有的模糊音效果一个不少
  （`zongguo`→中国、`cang`→常、`si`→是、`gen`/`geng`→跟、`xin`/`xing`→新）。
- **改完必须重新部署**：algebra 变了 → `table.bin` / `prism.bin` 必须重建。
- **验证手段**：别靠刷机试，用 [`scripts/rime-probe/`](../scripts/rime-probe/README.md)
  在开发机上几秒钟出结果（`--expect 中国 zongguo` 这类）。

### 9.5 引擎行为 / 按键映射 / 选项

| 需求 | 位置 |
|------|------|
| 换引擎实现 | 实现 `engine/IEngine.kt`，在 `engine/AppStartup.kt#setupEngine` 用 `EngineFactory.switchTo(...)` 替换 |
| 新增输入行为 | `engine/behavior/` 定义抽象 → `engine/rime/behavior/` 实现（委托 `RimeBehavior.Impl()`）→ 在 `engine/rime/host/BehaviorHost.kt#flowed` 分派 |
| 特殊按键处理（空格/回车/退格/分号） | `engine/RimeEngine.kt#processKeyInternal` |
| Android KeyCode ↔ Rime 键值 | `engine/rime/core/KeyMapping.kt`、`KeyValue.kt` |
| 应用设置 → Rime 开关（简繁/Emoji/ASCII） | `engine/rime/util/OptionsApplier.kt` |
| 预编辑拼音拆分（全拼/双拼） | `engine/rime/core/RimeMessageConverter.kt`（`PinYinSpellingSplitter` / `ShuangPinSpellingSplitter`） |
| 分词符号 | `engine/data/constant.kt` + `BehaviorHost` |

### 9.6 候选词排序 / 预测

排序是**三层信号叠加**，改任何一层之前先读这张表：

| 层 | 信号 | 位置 |
|----|------|------|
| L1 | **Rime 自己的排序**（词库权重 + `enable_user_dict` 用户词典） | 作为「位次先验」参与打分，见 `CandidateFeature.rank` |
| L2 | **语法模型**（`.gram` / octagram） | `PredictionManager.gramDb` → 传给重排的 `baseScore` |
| L3 | **app 侧用户反馈**（正向点击 / 负向误选，带时间衰减） | `candidate_prefers` 表 + `base/priority/PreferenceScorer.kt` |

| 需求 | 位置 |
|------|------|
| 打分权重 | `base/priority/PriorityCalculator.kt` → `WeightConfig`：`baseScore .20` / `preference .30` / `rankPrior .40` / `wordLength .10`。取向是**引擎排序为主**（`rankPrior` 最大），用户偏好可以撼动它，语法与词长只做微调 |
| 用户偏好净分（可正可负 + 时间衰减） | `base/priority/PreferenceScorer.kt`（纯函数，半衰期 24 h；正向用 `ln1p` 软压，负向线性见效） |
| 下一词预测 | `engine/manager/PredictionManager.kt` + `base/marisa/Prediction.kt`（`TOP_K=100`，`ln(1+count)` 加权）；语法模型实例由它持有并对外只读暴露 |
| 候选重排 | `engine/manager/CandidateRerankManager.kt`（只重排第 1–24 位，**第 0 个固定**；同分按引擎原始位次兜底） |
| 误选降权（负反馈） | `engine/RimeEngine.kt`：`lastSelection` / `handleBackspace` → `onTextDeleted` → `demoteCandidate`；写库走 `CandidatePreferDao.demote` |
| 引擎侧兜底（同码回删再输首次交换） | 万象 `context_reorder/enable_fallback_reorder: true`（由 `scripts/build-rime-resource.py` 注入） |
| 重置学习数据 | `ui/screen/CandidateSettingsScreen.kt` →「学习数据」分组（清 `candidate_prefers`） |
| 候选面板外观 | `input/panel/component/CandidateGridView.kt`、`input/panel/KawaiiPanelRenderer.kt` |
| 候选条/工具栏按钮 | `input/panel/toolbar/ToolbarRenderer.kt` + `ToolbarRendererResources.kt`（中间工具由 `configuredToolbarButtons()` 读取偏好生成；`KawaiiPanel.kt` 与 `KawaiiPanelView.kt` 两处构造资源，改动要同步） |

#### 这条链路上踩过的坑（别踩回去）

1. **`gramDb` 曾经恒传 `null`**。`RimeEngine.restoreCandidates()` 里写死
   `rerankManager.rerank(list, inputContext, null)`，导致权重最大的 `baseScore`
   项**永远为 0** —— 语法模型白下载、白加载。改成传
   `predictionManager?.gramDb` 之后语法分才真正参与。
2. **`candidateCount` 是个假特征**。它取「这一批候选的个数」，对所有候选都是同一个值，
   对排序没有任何影响（原来还恒传 0）。已从 `CandidateFeature` / `WeightConfig` 移除。
   以后要加特征，先确认它在候选之间**有区分度**。
3. **重排不能覆盖引擎的位次**。改造前只用「点击数的对数 + 词长」，于是长词被无条件抬前、
   Rime 排好的顺序被整段打乱。现在 `rankPrior` 是最大的一项，且同分按原始位次兜底。
4. **负反馈必须会过期**。只加不减会让用户误选一次就永久压死那个词；
   `PreferenceScorer` 用 24 h 半衰期，7 天后残值 < 1%，并有正负双向封顶。
5. **第 0 个候选刻意不动**。它是「按空格上屏」的目标，随统计漂移会毁掉肌肉记忆；
   误选到第 0 个的情况交给引擎侧的 `enable_fallback_reorder`（Rime 自己会把
   同码回删再输的首选换掉），两边分工不重叠。

> 排查排序问题时：debug 构建里 `RimeEngine.logCandidateDiagnostics()` 会在
> 输入码 ≥ 3 个字符时打一行 `diag schema=... input=... candidates=... top=...`，
> 一眼就能看出「候选是引擎没给出来」还是「被排序挪走了」。release 构建里
> `Timber.treeCount == 0`，这行日志不会产生任何开销。

### 9.6.0 先看这里：简拼/整句在**实机**上不通，先怀疑内置 librime 的私人补丁

> **2026-09-19 实测确认过一次，代价很大**：手机上 `zj` → 「传记」、`zjhjszydcld` →
> 「传记和健身转悠电池了的」，而**同一份方案数据、同一份手机自己编译出的 prism/table**，
> 用上游 librime 跑出来是 `zj` → 「自己」、`zjhjszydcld` → 「这句话就是这样多出来的」。
>
> 原因在 `app/src/main/cpp/deps/librime`（`danjian/librime` fork）的
> `src/rime/dict/table.cc`：补丁 `feat: kAbbreviation rate limit when table search` 里，
> `kMaxAbbreviationExpand = 2` 被写成了**整个 BFS 共用一个计数器**。BFS 是广度优先，
> 于是只有最先展开的两三条简拼路径能活下来 —— 每个音节都是简拼的全简拼输入因此被砍废。
> 已修：改成**逐路径**计数（`TableQueryState.abbreviation_count`），上限 32；
> 总迭代护栏 5120 → 65536。
>
> **自查方法（发现整句/简拼「不像桌面」时按这个顺序走）**：
> 1. `adb pull <files>/user/build/wanxiang.{prism,table}.bin` + 部署副本 schema，
>    放进一个空 `user` 目录，用 `scripts/rime-probe` 加载 —— **如果探针结果正常、
>    手机不正常，那问题就在引擎二进制（fork 补丁），不在方案数据、不在用户词典、不在语法模型**。
> 2. 再看 fork 的 `git log`：`cd app/src/main/cpp/deps/librime && git log --oneline`，
>    凡涉及 `dict/`、`gear/`、`algo/` 的提交都要读一遍 —— 探针用系统 librime，
>    **看不到这些补丁**（`scripts/rime-probe/README.md` 已写明）。

### 9.6.1 首字母整句（`translator/max_sentences`）

「每个字只打一个声母就出整句」（`zjhmydqpy` 这类）由 librime 的
`script_translator` 负责，**不是 app 侧的功能**，改 app 代码不会影响它。
两个开关都在方案文件的 `translator:` 下，由 `scripts/build-rime-resource.py` 注入：

| 键 | 引擎默认 | 本项目 | 作用 |
|----|---------|--------|------|
| `max_sentences` | `1` | **`8`** | 整句候选条数。**默认 1 是关键**：`=1` 走 `Poet::MakeSentence`（单条最优路径），`>1` 走 `Poet::MakeSentences`（带语法模型的束搜索），这是完全不同的两条代码路径 |
| `sentence_cutoff_threshold` | `0.1` | **`0.5`** | 与上一条整句的**相对**分差超过多少就停止产出。判据是 `|cur-last| / |last| > threshold`，所以**越大给得越多**（`poet.cc:325-341`） |

源码位置（fork 在 `app/src/main/cpp/deps/librime`）：
`src/rime/gear/translator_commons.cc`（读配置，`max_sentences` 被
`std::min(std::max(1, x), 100)` 夹住）、`src/rime/gear/script_translator.cc:495`（何时合成整句）、
`src/rime/gear/poet.cc:258`（束搜索）。

**为什么停在 8**：实测 8 能出「明天再讲」「你怎么样」这类可用备选；
20 会开始夹带「就头疼」「将他推」这种碎词（整句候选挤掉的是候选栏位置）。

**整句质量的上限在哪**：`MakeSentences` 是**全局最优单路径的束搜索**，一个强搭配
可以把整句锁死。`zjhmydqpy` 无论 `max_sentences` 调到 50 都只出「中/在/这 几乎没有的
去朋友」的变体，正确句子**根本不在候选里**；连全拼
`zhejuhuameiyoudaquanpinyin` 的首选都是「这句话没有打**拳**拼音」（`全`/`拳` 同音，
`打拳` 是词、`打全` 不是）。这是引擎架构决定的，**不要把它当 bug 修**——
主流输入法靠的是网量级 n-gram 语言模型。

**唯一的例外杠杆是语法模型**：同一句 `jttqzm`，挂上 `wanxiang-lts-zh-hans.gram`
后首选变「今天天气怎么」，没挂是「具体天谴之门」。模型 420 MB，见 `GramModelDownloader`；
随包会让 APK 到 ~530 MB，所以只能手动下载。

> ⚠️ **2026-09-19 复测：上面这条「例外杠杆」的说法不成立，模型对「首字母整句」是负作用。**
> 表格里每一行都用 `scripts/rime-probe` 在同一份 `resource.zip` 数据 + 干净用户目录上跑出来
> （模型两份都试了：仓库文档记的 420339756 字节版与下载器地址给的 419911724 字节版，结论一致）：
>
> | 配置 | `zjhjszydcld` 首选 | 目标句「这句话就是这样打出来的」 |
> |------|------------------|------------------------------|
> | **现状（模糊音 + `max_sentences: 8`，不装模型）** | 这句话就是这样多出来的 | **第 2 位**（唯一能进候选的一档）|
> | 模糊音 + `max_sentences: 1` | 同上 | 第 2 位 |
> | 无模糊音，不装模型 | 同上 | 第 2 位 |
> | 模糊音 + 模型 | 「这句话就是这样打出来的计划就是这句话就是这样打出来的有点出来的」（31 字怪句）| 第 1354 位 |
> | 模糊音 + `max_sentences: 1` + 模型 | 同上（怪句与束宽无关）| 第 1354 位 |
> | 无模糊音 + 模型 | 自己好就是这样的处理的 | **不在候选里** |
> | 桌面 fcitx5 自己的 schema + 桌面那份模型 | 这句话就是这样的处理的 | 不在候选里 |
> | Xime 的 `pinyin_simp` | 这句话就是这样的出来的 | 不在候选里 |
>
> 而且 `jttqzm`（短简拼）装模型后从「今天天气怎么在第 4 位」变成
> 「今天天气**这句话就是这样打出来的**吗」这种怪句、正确句消失 —— 与上一段原来的记录相反。
> 全拼不受影响：`zhejuhuajiushizheyangdachulaide` 装不装模型都是首选正确。
>
> 机制上说得通：装模型后每条边的语法分从常数惩罚（-6）变成 **0 ~ +7 的非负加分**
> （`octagram.cc` 的 `update_result` 取最大值、`gram_max` 上限 `log(词频)`），
> 而整句总分是**逐边求和、不按词数归一**，于是「多拼几段」本身就能加分 ——
> 模糊音把词图放大之后，束搜索就会拼出越来越长的怪句。
> **结论：给「首字母整句」装这个模型是有害的**，设置页因此给了「删除」按钮（`SchemaSettingsScreen`）。
> 想在 2026-09-19 之后改这条结论，先把上表用同一套命令重跑一遍。
>
> 另外两件被这次实测排掉的事：① `enable_completion` 关掉、② 抽掉全部 Lua
> 处理器/翻译器/过滤器，怪句都**照旧出现** —— 它来自核心引擎（script_translator + octagram），
> 不是某个 Lua 模块或补全条目。

#### 语法模型到底是什么（别指望「换更大的模型」）

- **它已经是 32 GB 语料训出来的了**。万象官方（`amzxyz/RIME-LMDG`）用
  问答 / 博文 / 公众号 / 百科 / 新闻 / 歌词 / 诗词 / 评论 / 法律 / 文学等多领域语料
  共 **32 GB** 训练，产出 `wanxiang-lts-zh-hans.gram`（420 MB）与 `-hant` 两个版本，
  **没有更大或更小的变体**。官方在 22.3 万句语料上评测：

  | 配置 | 句子正确率 | 文字正确率 |
  |------|-----------|-----------|
  | `wanxiang` 无模型 | 62.34% | 93.50% |
  | `wanxiang` + gram | **76.86%** | **96.32%** |

  即 **+14.5 个百分点**。所以整句质量的现状不是「引擎没有大模型」，而是
  **「大模型没被装上」**（APK 里不带，要用户在设置里下 420 MB）。

- **模型格式与容量上限**（`plugins/librime-octagram`）：`GramDb` 是个
  **darts 双数组**，存放在 `MappedFile`（**mmap，不整体读进内存**）；
  键是 `context(≤5 字) + word(≤5 字)` 直接拼接的 UTF-8，值是
  `max(0, int(log(次数) × 10000))`，**每个上下文最多只存 8 个后继**
  （`GramDb::kMaxResults = 8`），`kMaxEncodedUnicode = 8`、
  `collocation_max_length` 决定上下文取多少（本方案 6 → 取 5）。

- **为什么「网量级」不等于「整句强」**：打分接口是
  `Grammar::Query(context, word, is_rear) -> double`，**一次只给一条边的分值**，
  没有回退插值、没有整句重打分；`is_rear` 只是个 `"$"` 句尾键。
  而整句是 `beam_width = max_sentences × 3` 的束搜索，**逐位置只保留 150 条候选线**
  （`max_sentences=50` 时）。**候选在进入语法模型打分之前就被剪掉了** ——
  这才是 `zjhmydqpy` 的正确句子根本不在候选里、把 `max_sentences` 提到 50 也没用的原因。
  结论：**再换更大的模型也救不了这类例子，瓶颈在解码器而不是模型**。

- **官方推荐参数与我们的实际值实测等价（不必改）**：官网推荐
  `collocation_min_length: 3` + `rear_penalty: -20`，我们包里是 `2` / `-18`
  （插件默认值）。在 12 个测试输入（含 `zjhmydqpy` 这类极端简拼）上逐条对比，
  输出**完全一致** —— 万象自己写 `2` 是有意的，别照抄文档改。

- **想要「自己的句子更准」的正确方向是自训定向模型，不是加大模型**：格式极简
  （`(context+word, count)` 列表 → 上面那个公式），万象公开了构建链路
  （jieba 分词 + pypinyin 标注 + `语法模型构建.py`，见
  `amzxyz/rime-build-grammar-word-frequency` 的 wiki）。用**自己的语料**
  （笔记 / 文档 / 聊天记录）训几十 MB 的模型，让你高频表达排前面，
  比把模型从 420 MB 加到 4 GB 有效得多。做的话记得**保留官方模型打底**。

#### 「让自己的句子打出来」：先试 `custom_phrase`，别急着训模型

用户实测（2026-09-15）：**装了 420 MB 官方模型仍然不满意**。实测结论是
`custom_phrase` 才是「我常打的那几句话」的正解，且成本几乎为零。

**先看方案的现成能力**：`wanxiang.schema.yaml` 的 `engine/translators` 里已经有
`table_translator@custom_phrase`，配置为：

```yaml
custom_phrase:
  dictionary: ""
  user_dict: custom_phrase      # 读 <user_data_dir>/custom_phrase.txt
  db_class: stabledb            # 文本直读，**不需要编译**
  enable_completion: false
  enable_sentence: false
  initial_quality: 99           # 权重远高于拼音与 wanxiang_en，保证置顶
```

文件格式是 `文本<TAB>编码<TAB>权重` 三列。实测（`scripts/rime-probe`，**没有执行
`--deploy`**）：

```
custom_phrase.txt 加一行：这句话没有打全拼音\tzjhmydqpy\t1

zjhmydqpy → 1. 这句话没有打全拼音     ← 命中
            2. 中几乎没有的去朋友     ← 官方模型的答案退到第 2 位，没被破坏
```

**为什么它比自训模型更该先做**（三条常见顾虑的对照）：

| 顾虑 | 结论 | 依据 |
|------|------|------|
| 要收集很多打字习惯 | **不需要** | `custom_phrase` 是精确编码查表，你只需要写你要的那几句；`enable_completion: false` 意味着打全码即出，n-gram 那套完全用不上 |
| 每次增长都要重建 | **此路不用重建** | `db_class: stabledb` 直接读文本，**加一行即生效**（已实测，未跑 deploy）。自训模型这条顾虑成立：darts 是静态结构，加键必须整体重建（但贵的 KenLM 那段永远只需做一次） |
| 模型小会过拟合 | **不适用** | 语法模型不是统计模型而是查找表，`update_result` 是**取最大值**（`octagram.cc:57`），所以往 `.gram` 里加键只会让这条边的分**升高或不变**，不可能破坏别的候选。真正的风险是「你加的键太强、把更好的选择顶掉」，而 `Query` 单边上限只有 `log(词频) - collocation_penalty ≈ +7.4`，顶不飞 |

**「过拟合」这个担心用在自训模型上也是错的方向**：官方模型负责通用能力、
你的条目只补充你自己的说法，两者**合并**而不是替换，所以不存在「学少了就退化」。

**`custom_phrase` 的限制**：只认**完整编码**（`enable_completion: false`），
所以它是「打全声母即出整句」，不是「打两个字母就猜出来」。要后者就得靠模型的
束搜索，而那条路的上限见本节开头（`beam_width` 剪枝）。

**接入 app 的落点**：现有「常用语」（`data/manager/PhraseManager` +
`phrase_records` 表）**只进面板、没接拼音索引**，所以当不了简码用。要做的就是
把它同步写进 `<userDataDir>/custom_phrase.txt`，并给每条常用语一个可编辑的
「编码」（默认按首字母自动生成）。改完调 `IEngine.updateConfig()`（`startRime(false)`，
轻量重载）即可生效，**不要用 `reload()`** —— 那是 `deploy()`，`fullCheck=true`
会把词库表全部重建一遍，为改一句话不值得。

**实验工具**：`mini_gram_builder`（纯 `darts.h` + 44 字节头，不依赖 librime）
可以从 `键 词频` 两列直接造出 `.gram`，用来验证「定向模型能不能影响整句解码」。
实测：45 条手写键能把 `zjhmydqpy` 从「中几乎没有的去朋友」扭成
「这句话没有打桥牌也」（前 6 个字变对），但**补不平尾部** —— 因为整句分数是
逐边的「词典权重 + 语法分」，没有匹配时每条边还要付 `non_collocation_penalty: -6`
（无模型时是 `Grammar::Evaluate` 里的 `log(1e-6) = -13.8`），**词数多的切分天生吃亏**。
这解释了为什么长句子靠模型打折、靠 `custom_phrase` 才可靠。

**验证方法**：`scripts/rime-probe/`，改完先在那里跑（几秒钟），
别靠刷机试。回归基线是「`qryt` 首选仍是杞人忧天、`nihao`/`zhongguo` 不变」。

### 9.7 在线能力（不再依赖任何中间服务器）

需要联网的只有三处，全部直连内容作者自己的发布地址；地址都写在各自文件顶部，想换成自己的服务器只改那一个常量：

| 能力 | 现在的地址 | 位置 |
|------|-----------|------|
| 语音模型 | k2-fsa/sherpa-onnx 官方 release（优先 `gh-proxy.org` 镜像，失败回退官方） | `base/speech/ModelDownloader.kt` → `SPEECH_MODEL_URLS` |
| 语法模型 | 万象拼音作者发布（CNB 优先，GitHub 备用） | `base/ngram/GramModelDownloader.kt` → `GRAMMAR_MODEL_URLS` |
| 检查更新 | **暂时留空**（未配置时设置页不显示入口，只显示当前版本号） | `base/net/VersionChecker.kt` → `UPDATE_INFO_URL` |

- 语音模型是 `sherpa-onnx-x-asr-160ms-streaming-zipformer-transducer-zh-en-punct-int8-2026-06-05`，md5 已固定成本地常量，不再由服务器下发。
- 语法模型对应方案里 `grammar/language` 声明的 `wanxiang-lts-zh-hans`，下载后存成 `<language>.gram`。
- 上游原本的 `mapi.lutrip.com` 后端（`ApiConfig` / `HttpUtil` / `SpeechModelApi`）已整个删除。
- 以后要恢复在线检查更新：填上 `UPDATE_INFO_URL`，返回 `{"latestVersion": "…", "website": "…"}` 即可。

### 9.8 语音

| 需求 | 位置 |
|------|------|
| 识别服务 / 采样率 / 分块 | `base/speech/SpeechRecognitionService.kt`（16 kHz 单声道，40ms 块） |
| 主进程客户端与状态机 | `base/speech/SherpaSpeechClient.kt` |
| IPC 协议 | `base/speech/SpeechIpc.kt` |
| 模型下载与解包 | `base/speech/ModelDownloader.kt` + `TarBz2ExtractorUtil` |
| QNN/HTP 加速 | `assets/cdsp`（`.so`）+ `nativeLibraryDir` 的 `libQnnHtp/System`；`isQnnRuntimeSupported` 只看 `arm64-v8a` |
| 波形 UI | `input/speech/SpeechOverlayView.kt` + `ParticleWaveView.kt` / `SpectrumWaveView.kt` |

### 9.9 设置项 / 数据库 / 包名

| 需求 | 位置 |
|------|------|
| 新增设置项 | 在 `data/manager/*.kt` 加 prefs 读写，在对应 `ui/screen/*Screen.kt` 加 UI |
| 新数据库表 | `data/database/` 加 Entity/DAO，改 `AppDatabase.kt`（**记得加 Migration**，当前没有 7→8 的迁移） |
| 应用主题（设置界面） | `ui/theme/Theme.kt`、`Color.kt`、`Type.kt` |
| 应用名 / 字符串 | `app/src/main/res/values/strings.xml` |
| 图标 | `app/src/main/res/mipmap-*` |

**若要把 fork 发布成自己的 App**，至少改：`app/build.gradle.kts` 的 `namespace`/`applicationId`、`AndroidManifest.xml` 中 FileProvider 与 `AppFilesDocumentsProvider` 的 authority、`strings.xml` 的 `app_name`、以及 `repackaging` 相关的包名引用。

### 9.10 减少包体

- `abiFilters` 目前只留 `arm64-v8a`（`app/build.gradle.kts`）
- `resource.zip` 65 MB 是大头，其中 `model/predict.marisa` 37 MB、`dicts/shici.lite` 14 MB。裁剪词库/预测模型能显著减小 APK
- `assets/cdsp` 12.6 MB 是 QNN skel，不用语音加速可删（会回退 CPU）

### 9.11 列表滚动 / 手感

项目里没有用系统 `ScrollView`/`RecyclerView`，列表滚动都是自绘或自布局的：

| 位置 | 实现 | 滚动方式 |
|------|------|----------|
| 展开候选词列表 | `input/panel/component/CandidateGridView.kt` → 内部 `gridCanvas` | `OverScroller` + `scrollOffsetY`，`onDraw` 只画可见行 |
| 符号 / Emoji 网格 | `input/keyboard/key/GridKeyboardView.kt` | `OverScroller` + `View.scrollTo` 画布平移，视口外的 `KeyView` 置 `GONE` |
| 侧栏（分类 / 拼音） | `input/keyboard/key/widget/SidePanelView.kt` | `OverScroller` + `scrollOffset`，Canvas 绘制 |

改这块时注意几条已经踩过的坑：

1. **长按任务要随滑动取消**。`CandidateGridView` 的长按用于“拖动排序 / 长按删除”，若在手指开始拖动后不 `removeCallbacks(longPressRunnable)`，长按会在滑动中途触发，`ACTION_MOVE` 里 `longPressTriggered` 分支又排在 `dragging` 之前 → 手势被接管、划到一半卡住，必须松手重划。现在：手指一旦移动超过 `touchSlop` 就取消长按，且长按任务本身在拖动中直接 return；长按落在空白区域时降级为普通滑动。
2. **滑动过程中不要逐帧 `requestLayout()`**。符号分类有 100~390 个符号，`GridKeyboardView` 早期每次 `ACTION_MOVE` 都重新测量/布局全部 `KeyView`（每个内部还套 `ConstraintLayout`），直接掉帧。现在滚动只做 `scrollTo` + `invalidate`，仅当可见行范围变化时才 `requestLayout`，并跳过视口外的按键。
3. **`requestDisallowInterceptTouchEvent` 不要调在自己身上**。它会把 `FLAG_DISALLOW_INTERCEPT` 设在**调用者自己**并向上传播，等于告诉系统“别调用我的 `onInterceptTouchEvent`”。在 `GridKeyboardView` 里这么写过一次，结果网格自己永远拦不到手势、完全滑不动——正确做法是 `parent.requestDisallowInterceptTouchEvent(true)`（禁的是祖先），而这里的父容器本来就不拦截，所以不需要。
4. **`onInterceptTouchEvent` 不是每次 MOVE 都会被调用**。一旦某个 `ACTION_DOWN` 没有被子 View 接住（例如符号页最后一行右侧的空白格），`mFirstTouchTarget` 为空，后续 MOVE 会直接进 `onTouchEvent` 而不再经过拦截回调。所以拖动判定要抽成 `startDragIfNeeded()` 在两条路径上都能跑，否则从空白格起手的那次滑动是无效的。

---

## 10. 注意事项 / 已知坑

1. **`resource.zip` 是私有构建物料**，`app/.gitignore`/根 `.gitignore` 排除了它；本 checkout 里没有 → 必现“无方案/无候选”。用 `scripts/build-rime-resource.py` 重建（见 [7.2](#获取-resourcezip)）。
2. **`assets/checksums.json` 缺失**：`DataManager.sync()` 会走异常分支打印 `Sync not prepared!`，实际数据全靠 `resource.zip` 解压。如果未来把方案改为“放 assets 增量同步”，需要补上 `checksums.json`（格式见 `DataSync`/`DataSum`）。
3. **`install-deps.sh` 仍会 clone `llama.cpp`**，但代码已移除 llamacpp/gguf（提交 `0ebf565`）。可忽略或从脚本删掉以省时间。
4. **ProGuard 规则过时**：`app/proguard-rules.pro` 还保留 opencc4j/houbb 的 keep 规则，实际已改用自带 `TraditionalConverter`；release 目前 `isMinifyEnabled=false`，暂不影响。
5. **两个“反向”布尔设置**：
   - `keyboard_settings.keyboard.expand_borders`：getter 返回 `!pref`，默认实际为 true
   - `candidate_settings.show_border`：`isBorderEnabled = !pref`，默认实际为 true
6. **Room 少了 7→8 的迁移，8→9 已补上**：`AppDatabase` 现在 version = 9，有 `MIGRATION_8_9`（给 `candidate_prefers` 加 `bad_count` / `last_bad_at`）。**7→8 依然没有**，那是上游 `danjian/ime` 加 `candidate_sorting_v2` 时留下的，本次没动。但库启用了 `fallbackToDestructiveMigration()` —— **加表/改表时必须同时补迁移**，否则用户的剪贴板历史、常用语、候选排序记录会被静默清空。
7. **只支持 arm64-v8a**：想支持 32 位或模拟器，需要改 `abiFilters` 并重新编译 native + 处理 sherpa/QNN 产物。
8. **`compileSdk 37` / `targetSdk 36`**：需要较新的 Android SDK（37 可能是预览版平台），老环境需先 `sdkmanager "platforms;android-37"`。
9. **Manifest 申请了 `MANAGE_EXTERNAL_STORAGE`**（`AndroidManifest.xml`），上架应用商店可能被拒；常规使用其实靠 SAF Provider 即可。
10. **两个 `KeyActionListener` 同名**：`input/KeyActionListener.kt` 是引擎桥接，`input/keyboard/key/KeyActionListener.kt` 是 `fun interface`，改动时注意 import。
    **`KeyMapping` 也同名**：`engine/rime/core/KeyMapping.kt` 是 Rime 键码常量，按键映射的偏好对象叫 `KeyboardKeyMapping`，别再取回 `KeyMapping`。
11. **死代码/未使用类**：`KeyboardThemeSettingsScreen` 引用之外，`input/dialog/SchemaPickerEntryUi.kt`、`SchemaPickerListAdapter.kt`、`panel/toolbar/ToolbarButton.ToggleImageButton` 当前未使用。
12. **release 签名依赖本机材料**：`keystore.properties`（已 gitignore）或 `IME_*` 环境变量，缺一个都产出未签名 APK。
13. **`shared/custom/*.custom.yaml` 不参与部署**。万象的 `custom/` 补丁按它自己的设计是「藏起来的备份」，要**拷到用户目录**（`user/<方案>.custom.yaml`）才生效；而 app 从不做这一步（`DataManager.sync()` 只在缺 `user/default.custom.yaml` 时创建一个 luna 补丁）。所以：
    - 想让某个补丁生效，**直接改方案文件本身**（`shared/<方案>.schema.yaml`），像模糊音那样由脚本注入；
    - 放在 `shared/custom/` 里的文件只是随包携带，别指望它有作用。
14. **`user/default.custom.yaml` 的初始内容是个地雷**：`DataManager.sync()` 在文件不存在时会写入一份 `schema_list: [luna_pinyin, luna_pinyin_simp]` 的补丁，而包内根本没有这两个方案。app 自己用 `SchemaManager` 管方案所以日常没事，但如果你在真机上看到「方案列表空 / 方案对不上」，先看这个文件。
15. **本仓库的文件可能属于 `root`**（取决于构建环境以什么身份跑）。Gradle 会按 Java 的 `user.home` 找缓存目录，**以 root 跑时 `user.home` 是 `/root`**，会去 `/root/.gradle` 找 wrapper 并失败。解决办法是显式指定：`env GRADLE_USER_HOME=/home/<user>/.gradle ./gradlew ...`。

---

## 11. 上游与许可

- **本仓库**：`Apuir/ime`，基于上游 `danjian/ime`；两者均无仓库级 LICENSE 文件。
- **Native/引擎**：
  - librime：GPL-3.0-or-later（`cpp/CMakeLists.txt` 带 SPDX 头）
  - librime_jni：Apache-2.0（文件 SPDX 头）
  - OpenCC、snappy、glog、yaml-cpp、leveldb、marisa-trie：各自开源许可
- **方案数据**：万象拼音 [rime-wanxiang](https://github.com/amzxyz/rime-wanxiang)（CC BY 4.0），随 `resource.zip` 分发，文档见解压后的 `shared/README.md`（不在本仓库中）
- **语音**：sherpa-onnx（Apache-2.0）
- **QNN/CDSP**：`app/src/main/assets/cdsp/*.so` 为高通（Qualcomm）运行库，随本项目捆绑，仅供本机自用。

### 11.1 自用 vs 分发（重要）

只要不分发 APK 或源码，GPL / CC BY 的义务不会触发。
一旦要**分发**（发 APK 给他人、公开仓库、上应用商店），就必须同时做到：

1. 随附 librime 及其衍生部分（含本项目 JNI 与 native 构建产物）的**完整对应源码**，并保留 GPL-3.0-or-later 许可证全文与版权声明；
2. 保留万象拼音的 **CC BY 4.0** 署名与来源链接；
3. 不再宣称上游未声明 LICENSE 就等于“随便用”——上游没写许可证不改变其内部各组件的既有许可。


---

## 12. 附录

### 12.1 SharedPreferences 清单

**`keyboard_settings`**（`KeyboardManager`）

| key | 类型 | 默认 | 含义 |
|-----|------|------|------|
| `theme.mode` | Int | 0 | 0 跟随系统 / 1 浅色 / 2 深色 |
| `keyboard.height` | Int | 24 | 竖屏键盘高度（%） |
| `keyboard.height_landscape` | Int | 44 | 横屏键盘高度（%） |
| `keyboard.ignore_insets` | Bool | false | 忽略系统边衬 |
| `keyboard.theme` | String | `amoled` | 当前主题 id |
| `keyboard.follow_system` | Bool | false | 跟随系统深浅色 |
| `keyboard.light_theme` | String | `light` | 浅色主题 id |
| `keyboard.dark_theme` | String | `amoled` | 深色主题 id |
| `keyboard.padding.horizontal` | Int | 4 dp | 两侧边距 |
| `keyboard.padding.bottom` | Int | 4 dp | 底部边距 |
| `keyboard.feedback.vibration_effect` | Int | 0 | 振动效果来源：0=系统触感（走厂商预置效果，最干脆），1=应用自定义强度（配合 `vibration_level`） |
| `keyboard.feedback.vibration_level` | Int | 8 | 按键振动强度（仅「自定义强度」模式生效）：0=关闭，1 极弱 → 10 最强（其中 6~10 等同旧版 1~5 的力度）；旧版 `keyboard.feedback.vibration` (Bool) 与五档时代的存量档位都会自动迁移 |
| `keyboard.feedback.vibration_scale` | Int | 2 | 档位代次标记（内部使用，用于把五档时代的存量档位一次性平移到 6~10） |
| `keyboard.feedback.vibration_ignore_system` | Bool | true | 忽略系统「触感 / 振动」开关：走媒体振动通道，系统振动总开关关闭时退回无障碍通道（Android 13+） |
| `keyboard.feedback.sound` | Bool | true | 按键音 |
| `keyboard.gap.horizontal` | Int | 3 dp | 键水平间隔 |
| `keyboard.gap.vertical` | Int | 3 dp | 键垂直间隔 |
| `keyboard.key_radius` | Int | 14 dp | 键圆角 |
| `keyboard.ripple_effect` | Bool | false | 水波纹 |
| `keyboard.key_border_stroke` | Bool | true | 绘制键边框 |
| `keyboard.expand_borders` | Bool | false（**反向**） | 展开键边框 |
| `keyboard.gesture_input` | Int | 0 | 符号/数字输入手势：0=长按，1=上滑（互斥） |
| `keyboard.toolbar_tools` | String | `undo,redo,cursor,clipboard,palette,handwriting` | 工具栏中间工具的有序 key 列表（逗号分隔，空串=全部移除） |
| `keyboard.side_panel_symbols.t9` | String | `，。！？：~...` | 九键与手写面板共用的符号栏有序符号列表（`\u001F` 分隔；九键显示/上屏时仍跟随全角-半角标点模式，手写原样使用） |
| `keyboard.side_panel_symbols.number` | String | `+-*/=~?!` | 数字键左侧符号栏的有序符号列表（`\u001F` 分隔，原样使用） |
| `keyboard.key_mapping.qwerty` | String | 未设置=全默认 | 26 键字母键的次级符号 / 数字；只存用户改过的键，格式 `键\u001F值\u001E…` |
| `keyboard.key_mapping.t9` | String | 未设置=全默认 | 九键 / 15 键气泡里每个数字键包含的字母；只存用户改过的键，格式同上 |
| `keyboard.key_bubble` | Bool | true | 长按 / 上滑是否弹出按键气泡；关闭后直接上屏符号 / 数字 |
| `keyboard.width` | Int | 100 | 竖屏键盘宽度（% 屏宽），<100 时改用悬浮卡片布局；由「调整键盘大小」写入 |
| `keyboard.pos_x` | Float | 0.5 | 竖屏卡片水平位置比例（拖动后写入） |
| `keyboard.pos_y` | Float | 1.0 | 竖屏卡片垂直位置比例（拖动后写入） |
| `keyboard.floating.enabled` | Bool | true | 横屏悬浮键盘开关 |
| `keyboard.floating.width` | Int | 未设置=自适应 | 悬浮卡片宽度（% 屏宽）；未手动设置时取「屏短边/长边」，即按键宽度与竖屏一致（20:9 手机约 45%），拖过滑杆后才写入 |
| `keyboard.floating.pos_x` | Float | 0.5 | 悬浮卡片水平位置比例（0=贴左，1=贴右；拖动后写入） |
| `keyboard.floating.pos_y` | Float | 1.0 | 悬浮卡片垂直位置比例（0=贴顶，1=贴底；拖动后写入） |
| `keyboard.swipe_up.ratio` | Float | 1.0 | 上滑触发距离（当前键高的倍数），见 9.3.6 |
| `keyboard.swipe_up.direction_tan` | Float | 1.5 | 上滑方向判定系数（纵向 ≥ 横向 × 该值）；无 UI，仅调参用 |
| `keyboard.slot.active` | String | 未设置=中文 | 当前键盘槽（`Chinese` / `English`），运行时状态 |
| `keyboard.slot.chinese.keyboard` | String | 未设置=默认 | 中文槽当前输入方式（键盘名），如 `qwerty` / `t9` / `handwriting` |
| `keyboard.slot.chinese.schema` | String | 未设置=默认 | 中文槽当前方案 id；手写没有方案，此时该键不存在 |
| `handwriting.engine_mode` | Int | 0 | 手写引擎：0=自动，1=Google，2=本地（`HwEngineMode` 序号） |
| `handwriting.recognize_on_lift` | Bool | true | 抬笔后是否自动识别 |
| `handwriting.recognize_delay_ms` | Int | 700 | 抬笔后停手多久才识别（200–2000 ms） |
| `handwriting.full_screen` | Bool | false | 是否整屏手写（true）/ 键盘区域内手写（false） |
| `handwriting.google_usable` | Int | -1 | Google 引擎可用性探测缓存：-1 未探测 / 0 不可用 / 1 可用（设备相关，不参与备份） |

**注**：`keyboard_settings` 里还有两个一次性迁移标记（`keyboard.feedback.vibration_scale`、
`keyboard.toolbar_tools.handwriting_added`）与已被取代的 legacy 键
`keyboard.feedback.vibration`，只用于存量设置迁移，不参与备份。

**`candidate_settings`**（`CandidateManager`）

| key | 类型 | 默认 | 含义 |
|-----|------|------|------|
| `prediction_enabled` | Bool | true | 候选词预测 |
| `traditional_chinese_enabled` | Bool | false | 繁体输出 |
| `emoji_enabled` | Bool | false | Emoji 参与候选 |
| `ascii_mode_enabled` | Bool | true | ASCII/英文模式 |
| `rerank_enabled` | Bool | true | 候选重排增强 |
| `show_index` | Bool | true | 显示候选序号 |
| `show_comment` | Bool | false | 显示候选注释 |
| `show_border` | Bool | false（**反向**） | 绘制候选边框 |
| `preview_mode` | Int | 0 | 输入框实时上屏：0=不上屏，1=原始输入（`ni'hao`），2=首候选 |
| `commit_preview_on_switch` | Bool | false | 切换方案 / 收起键盘时是否保留已上屏的预览内容 |

**`schema_settings`**（`SchemaManager`）

| key | 类型 | 默认 | 含义 |
|-----|------|------|------|
| `enabled_schema_ids` | String | `""` | 逗号分隔的启用方案（空=全部，部署后回填） |
| `grammar_model` | Bool | true | 启用语法模型 |

**`clipboard_settings`**（`ClipboardManager`）：`max_entries`=100（20–500）、`retention_days`=30（1–365）、`poll_interval_seconds`=5（1–60）

**`phrase_prefs`**（`PhraseManager`）：`seeded`=false（内置常用语是否已初始化）

**`asset_extract_prefs`**：`extracted`=false（首次启动资源复制标记）

「设置 → 设置分享」导出的键是这份清单里可迁移部分的子集（内部标记、设备探测缓存、
运行时状态与 Room 数据都不导出），白名单在
`data/settings/SettingsBackup.kt` 的 `SettingsBackupSpec.PREFS`，格式见
[`SETTINGS_BACKUP.md`](./SETTINGS_BACKUP.md)。

### 12.2 Room 表结构

数据库 `ime_database`，`AppDatabase` version 9。

| 表 | 字段 |
|----|------|
| `candidate_sorting_v2` | `sorting_key` TEXT PK（候选集合 FNV-1a 指纹）、`candidateIds` TEXT（逗号分隔 List<Int>） |
| `clipboard_records` | `id` PK 自增、`text`、`timestamp`、`cloud`、`deleted`=0、`deletedAt`=0 |
| `candidate_prefers` | `text` TEXT PK、`context`、`click_count`=1、`bad_count`=0、`created_at`、`updated_at`（最近一次**正向**信号）、`last_bad_at`=0（最近一次**误选**） |
| `phrase_records` | `id` PK 自增、`text`、`label`、`createdAt` |

DAO：`CandidateSortingDao`、`ClipboardDao`、`CandidatePreferDao`、`PhraseDao`。

`candidate_prefers` 的两个方向都由 `CandidatePreferDao` 维护，注意它**只在库层存原始计数与时间戳**，
不做衰减：

- `upsert(text, context)` —— 候选被选中上屏：`click_count + 1`、`updated_at = now`；
- `demote(text)` —— 上屏后又删掉、或长按删除候选：`bad_count + 1`、`last_bad_at = now`，
  **不碰** `click_count` / `updated_at`（否则会把「这个词曾被正常用过」的历史抹掉）；
- 净分由 `base/priority/PreferenceScorer` 在读取时算，`CandidateRerankManager` 使用。

迁移：`MIGRATION_1_2` … `MIGRATION_6_7`、`MIGRATION_8_9`。
**7→8 仍然缺失**（上游遗留），见[第 10 节第 6 条](#10-注意事项--已知坑)。

### 12.3 内置键盘主题

| id | 名称 | 定位 |
|----|------|------|
| `amoled` | 暗夜 | 默认 + 深色默认 |
| `light` | 素白 | 浅色默认 |
| `sunset` | 落日 | — |

自定义主题数量不限，存于 `themes/themes.json`；运行时 `KeyboardThemePresets.ALL = 内置 + 自定义`，`KeyboardTheme.PRESETS = ALL`（主题选择列表）。

### 12.4 启动入口速查

| 入口 | 文件 |
|------|------|
| Application | `ImeApplication.kt` |
| 启动编排 | `engine/AppStartup.kt` |
| 输入法服务 | `input/ImeInputMethodService.kt` |
| 引擎 | `engine/RimeEngine.kt` |
| JNI 门面 | `engine/rime/core/Rime.kt` |
| 设置首页 | `ui/MainActivity.kt` / `ui/screen/MainScreen.kt` |
| 键盘根视图 | `input/keyboard/window/KeyboardWindowView.kt` |
| 候选面板 | `input/panel/KawaiiPanel.kt` |

---

*本文档由对当前代码的静态阅读整理而成；如与实现不一致，以代码为准。*

### 9.3.7 手写面板与整屏手写

手写面板是 `KeyboardWindowView` 的普通子 View（默认 `GONE`）：不在 measure/layout 排除名单里、且非 `GONE`，
就会被自动铺满键盘内容区（含悬浮卡片），所以**布局代码不需要为它改动**。配套要动的只有四处：
`floatingTouchableRegion()` 的条件、`refreshColors()` 补配色、
`switchKeyboard` / `toggleMenu` / `enterResizeMode` / `onDetachedFromWindow` 四个时机收起面板。

- **上屏通道**：面板自己不碰 `InputConnection`。提交/退格/空格/回车一律经 `Listener` 回到宿主，
  由宿主发 `KeyboardAction`（`CommitAction` / `CommitPairAction` / `BackspaceAction` / `SpaceAction` / `ReturnAction`）。
- **候选为组合态**：`ImeInputMethodService` 提供手写专用入口
  （`setHandwritingComposing` / `finalizeHandwritingComposing` / `discardHandwritingComposing`），
  走的就是 `setComposingText` / `finishComposingText` 这条与拼音相同的组合通道；
  只是不受「上屏模式」偏好影响。**不要**在面板或 `PanelListener` 里持有 `InputConnection`。
- **进手写时先安顿 Rime 组合区**：`settleCompositionForHandwriting()`（`finalizeForKeyboardSwitch()` +
  `engine.resetComposition()` + 清方案候选），否则手写组合态与 Rime 组合区会互相顶掉。
- **整屏手写（`半/全`）**：`HandwritingManager.FULL_SCREEN_IMPL` 有两种实现：
  - `OVERLAY`（默认）：进入整屏时把输入法窗口整屏化 —— 覆写
    `InputMethodService.onConfigureWindow()` 并在整屏时把窗口高度设为 `MATCH_PARENT`
    （`ImeInputMethodService.syncImeWindow()` 是窗口背景+尺寸的唯一入口），
    窗口背景透明、`floatingTouchableRegion()` 给整窗、应用底衬报 0（应用不被顶起），
    书写层铺满窗口并叠一层很淡的遮罩；键盘只剩底部三条（顶栏 + 标点行 + 功能行）。
  - `GROW`：退化方案 —— 键盘临时加高到 62% 屏高，形态不变、窗口/触摸/背景全不动。
    **真机上若整屏异常，把这一行改掉即可回退。**

> ⚠️ 两个踩过的坑：
> 1. 书写视图在「半屏 ⇄ 整屏」之间换父容器时，**必须先 `(ink.parent as? ViewGroup)?.removeView(ink)`**。
>    `removeAllViews()` 只清直接子视图，而它是孙子；漏了这一步会命中
>    `IllegalStateException: The specified child already has a parent`，
>    而异常发生在点击回调里没人接 —— **整个输入法进程会当场崩溃**，表现为「键盘整块消失、乱点才回来」。
> 2. 只靠 `WRAP_CONTENT` 撑高窗口要经过「测量 → relayout → 改窗口 → 再测」一圈才收敛，
>    中间掉一步底部三条就排到窗口外；所以整屏时显式设 `MATCH_PARENT` 更稳。

### 9.3.8 Google 手写模型（探测 / 下载 / 删除）

ML Kit 数字墨水**只有「按需下载」一种安装路径**（不能随包、也不是 Play 服务预置），模型落在
应用私有目录 `files/mlkit_digital_ink_recognition/shared/datadownload/public/datadownloadfile_<毫秒>/`，
清单（下载地址 + size + md5）在 AAR 的 `assets/manifest.json` 里；官方说明见
[ML Kit 模型安装路径](https://developers.google.com/ml-kit/tips/installation-paths)。

- **`isModelDownloaded()` 有三态**：`MlKitSupport.queryModelDownloaded()` 返回 `null` 表示**查询失败**，
  与「查到了、没下载」不是一回事 —— 前者不能拿去引导下载，见 `queryModelDownloaded` 的注释。
- **下载任务的返回值 ≠ 模型可用**。模型已经下过时 `download()` 会直接给失败回执，而模型完全可用；
  能下结论的只有自检（`HandwritingEngineHolder.verifyNow`）。设置页的「下载模型」因此总是
  「先请求下载 → 再按本机现状自检」，成败由自检定。
- **删除只删文件组的一部分**（2026-09-19 实测）：三件套（`chinese_lstm_4x192.tflite` 7.8 MB、
  `zh_cn.compact.fst.local` 18.3 MB、`qrnn.zh_cn…recospec.local` 174 KB）点一次「删除模型」后
  只剩 tflite，标记变成未下载；再点「下载模型」只补缺失的两个（压缩包约 1.4 MB 下行），
  没被删的那个文件 mtime 与目录 id 都不变。所以「删完瞬间又能用」时先看还剩下什么，别急着下结论。
- ⚠️ **`MlKitEngine` 的 recognizer 是内存缓存**：模型文件被删掉之后 `isAvailable()` 仍然为真。
  因此 `verifyGoogleLocked()` **必须先 `close()` 再 `load()`** —— 只查 `isAvailable()` 会把一份
  已经不存在的模型报成「确认可用」，设置页的「重新检测」「删除模型」正好都踩在这条上。
- 探测结果缓存在 `handwriting.google_usable`（-1 未探测 / 0 硬不可用 / 1 确认可用）；
  **自检没过不写 0**（残缺模型可以靠删除后重下修好），而是退回 -1，下次解析如实再查。
- AUTO 模式在模型未下载时会**自动下载**（`HandwritingEngineResolver` 的 `DOWNLOAD_GOOGLE`），
  也就是「第一次用手写」会静默拉一次模型；这是 ML Kit 的默认行为，但 App 不提示。
