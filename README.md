# 简意输入法 · 开发与定制手册

> 本仓库是 [`danjian/ime`](https://github.com/danjian/ime) 的 Fork。
> 本文档是基于当前代码（220 个 Kotlin 文件、约 3.3 万行 + C++ JNI）重新整理的**理解 + 定制**指南。

| 项目 | 值 |
|------|----|
| 应用名 | 简意输入法 |
| applicationId / namespace | `com.ninthsoft.ime` |
| versionName / versionCode | `1.6.0` / `10600`（版本规则与发布流程见 [`CHANGELOG.md`](CHANGELOG.md)） |
| minSdk / targetSdk / compileSdk | 24 / 36 / 37 |
| 支持 ABI | 仅 `arm64-v8a` |
| 语言/构建 | Kotlin 2.4.10、AGP 9.1.1、Gradle 9.3.1、CMake 3.22.1 |
| UI | 键盘 = 自定义 View（ConstraintLayout + Canvas）；设置 = Jetpack Compose |
| 输入引擎 | librime（`danjian/librime` 分支）+ lua / octagram / predict 插件 |
| 内置方案数据 | [万象拼音 rime-wanxiang](https://github.com/amzxyz/rime-wanxiang) LTS `17.9.3`（CC BY 4.0） |
| 提交历史 | 82 commits，2026-06-12 ~ 2026-09-11（其中 61 个来自上游，fork 改动从 `v1.1.0` 开始） |

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

- **壳**：`InputMethodService` 及其键盘 UI（Qwerty / 九宫格 T9 / 15 键 T15 / 符号 / Emoji），以及一整套 Compose 设置界面。
- **引擎**：`librime` 的 C++ 代码通过 JNI（`rime_jni`、`marisa_jni`）暴露给 Kotlin；Kotlin 侧用 `IEngine` 接口抽象，当前唯一实现是 `RimeEngine`。插件启用 `librime-lua`、`librime-octagram`（语法模型）、`librime-predict`（预测）。
- **数据**：随包携带一个 65 MB 的 `assets/resource.zip`，解压后是万象拼音的方案、词库、Lua 脚本和 `predict.marisa` 预测模型；运行时解压到外部私有目录给 librime 使用。

特点：**不是**在 Rime 之上套一层通用配置 UI（如 Trime），而是把 Rime 当作纯输入内核，键盘布局、候选面板、主题、剪贴板、语音全部自己实现。

---

## 2. 功能一览

**输入**

- Rime 拼音方案（默认启用 `wanxiang_t9` / `wanxiang_lite` / `wanxiang_english`），支持方案启用、排序、切换
- 三种键盘布局：全键盘（Qwerty）、九宫格（T9）、15 键（T15），由当前 scheme 的 `layout` 字段自动决定
- 符号键盘、Emoji 键盘；全角/半角标点切换
- 简↔繁转换（自带 FMM 转换器，不依赖 OpenCC 运行时）
- 英文/ASCII 模式、Emoji 参与候选

**候选与编辑**

- 候选词面板：横滑候选条 + 展开 5×5 候选网格，可拖拽重排、删除/“忘记”候选
- 候选词预测（marisa `predict.marisa`）与语法模型重排（`.gram`）；可开关
- 文本编辑面板（光标移动、选择、剪切/复制/粘贴）、预编辑悬浮拼音条
- 输入框实时上屏（预览）：设置 → 上屏设置里可选「不上屏 / 原始输入上屏（如 `ni'hao`）/ 首候选上屏」，并可选择切换方案、收起键盘时是否把已上屏内容正式保留
- 剪贴板历史（Room 持久化、云同步标记、自动清理策略）+ 常用语管理

**语音**

- sherpa-onnx（zipformer transducer）离线语音转文字，跑在独立 `:speech` 进程
- 高通 QNN/HTP 加速（`assets/cdsp` + `libQnnHtp/System`），不支持时回退 CPU onnx
- 模型按需从后端下载（`.tar.bz2`，MD5 校验）

**其它**

- 键盘主题：内置 3 套（暗夜 / 素白 / 落日）+ **不限数量**的自定义主题；应用内 GUI 编辑器可直接调色、调按键形状/边框/圆角/间距/高度并命名保存，支持二维码导入导出（`THEME_FORMAT.md`）
- 工具栏工具自定义：键盘上方工具栏中间那排图标可由用户勾选、排序、增删（撤销/重做/剪贴板/主题/语音/表情…）
- **侧栏快捷符号自定义**：设置 → 键盘布局 →「侧栏符号」里可编辑九键 / 数字键左侧那条可滑动符号栏的内容与顺序
  （增删、上下移排序、恢复默认，还能添加任意自定义符号）
- 符号 / 数字输入手势：26 键、九键、15 键支持「长按输入」与「上滑输入」二选一（设置 → 键盘布局 → 按键手势）
- **横屏悬浮键盘**：横屏时键盘自动变成一张可拖动的小卡片悬浮在应用之上（宽度可调、位置可拖动并记忆），
  应用界面不再被键盘顶起；设置 → 键盘布局里可开关与调整宽度
- **键盘内直接调大小**：键盘左上角菜单 →「调整键盘大小」，进入编辑模式后拖上边框调高度、拖左右边框调宽度，
  竖屏/横屏都可用，带实时百分比显示与「重置 / 完成」按钮
- SAF 文件管理（`AppFilesDocumentsProvider`）：无需 root 即可用系统“文件”App 浏览/编辑 `files/` 下的方案与词库
- 运行日志、崩溃日志、版本检查、按键音/震动/水波纹等细节设置
- **振动强度逐级可调**：按键设置 →「点击时振动」可关闭，或在 5 级强度（很轻 / 轻 / 适中 / 强 / 最强）间切换，
  选中即时试振；默认开启「忽略系统振动设置」，让按键振动不再依赖系统「触感 / 触摸时振动」开关

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
| `speech/` | `SherpaSpeechClient`（主进程客户端）、`SpeechRecognitionService`（`:speech` 进程，AudioRecord + sherpa-onnx）、`SpeechIpc`（Messenger 协议）、`ModelDownloader`、`SpeechModelApi`、`SpeechUiBridge`、`SpeechPermissionActivity` |
| `ngram/` | `GramDb`（mmap 读 `.gram`）、`DoubleArrayTrie`、`GramEncoding`（Rime table.bin 编解码）、`GramModelDownloader` |
| `marisa/` | `MarisaTrie`（JNI）、`Prediction`（读 `predict.marisa` 做下一词预测） |
| `net/` | `ApiConfig`（`BASE_URL=https://mapi.lutrip.com/`）、`HttpUtil`（OkHttp + `{code,msg,data}` 信封）、`VersionChecker` |
| `util/` | `TraditionalConverter`（简繁 FMM）、`PinYinUtil`、`ResourceExtractorUtil`/`ResourceUtil`/`TarBz2ExtractorUtil`、`FontManager`、`InputConnectionUtil`、`TextUtil`、`PunctuationUtil`、`ProcessUtil`、`ViewAnimationUtil`、`AssetExtractionProviderUtil` 等 |
| `priority/` | `PriorityCalculator`（候选打分权重） |
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
| 签名 | `release.keystore` | 可选。`app/build.gradle.kts` 未配置 release 签名，release 默认不签名 |

#### 获取 `resource.zip`

上游 release APK 内就打包了它，直接抽取即可（无需 clone 上游源码）：

```bash
# 1) 下载上游 release（约 110 MB）
curl -L -o /tmp/JIme-v1.0.5.apk \
  https://github.com/danjian/ime/releases/download/v1.0.5/JIme-v8a-v1.0.5.apk

# 2) 从 APK 中抽出 assets/resource.zip
unzip -o /tmp/JIme-v1.0.5.apk assets/resource.zip -d /tmp/jime_rz
cp /tmp/jime_rz/assets/resource.zip app/src/main/assets/resource.zip

# 3) 校验（v1.0.5 的值，仅供参考；其他版本可能不同）
md5sum app/src/main/assets/resource.zip
# 98dd07b126483d22b2cc6c99afe07e44
```

`resource.zip` 内容（v1.0.5，86 个条目）：

```
shared/                      # librime shared_data_dir
├── *.schema.yaml            # wanxiang_lite / wanxiang_t9 / wanxiang_english / reverse / mixedcode
├── default.yaml             # schema_list 等默认配置
├── weasel.yaml / wanxiang_*.yaml
├── dicts/*.dict.yaml        # 词库（jichu/lianxiang/shici/...）
├── lua/wanxiang/*.lua       # 万象 Lua 脚本
├── lua/data/*.txt           # 脚本数据
├── plum/*.recipe.yaml
├── custom/*.custom.yaml     # 方案补丁
└── version.txt              # 万象版本：17.9.3
model/
└── predict.marisa           # 37 MB marisa 预测模型
```

> 修改/替换 `resource.zip` 后无需其他操作：`AppStartup` 靠 MD5 判断，下一次启动会自动重新解压。

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

# 4) 编译
./gradlew :app:assembleDebug
# 产物：app/build/outputs/apk/debug/app-debug.apk
```

首次 native 编译耗时较长（librime + Boost + OpenCC）。`install-deps.sh` 是**幂等**的：已存在的目录会 `git fetch + reset --hard`，Boost 已存在则跳过。

### 7.4 安装与首次使用

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
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
| 改 Qwerty 全键盘 | `input/keyboard/impl/QwertyKeyboard.kt` → `buildLayout()`（底行：符号 0.13 / 中英 0.12 / `.` 0.09 / 空格 0.30 / `,` 0.09 / 数字 0.12 / 回车 0.15；`.`/`,` 键帽固定半角，`CommitAction` 仍跟随全角-半角标点模式） |
| 改数字键盘 | `input/keyboard/impl/NumberKeyboard.kt` → `Layout`（4 行 × 5 等列，列宽 `0.17 / 0.22 ×3 / 0.17`：`1 2 3 ⌫` / `4 5 6 @` / `7 8 9 .` / `符号 空格 0 返回 回车`；底行与上面逐列对齐，回车只占底行一格、不再跨行） |
| 改九宫格 / 15 键 | `input/keyboard/impl/T9Keyboard.kt` / `T15Keyboard.kt`（九键：侧栏 + 4 列，列宽同数字键盘，底行 `符号 中英 空格 123` 与九宫格逐列对齐，大回车跨第 3、4 行；15 键：`0.17 + 中间 5×0.132 + 0.17`） |
| 非 26 键键盘列宽 | 最左 / 最右两列由 0.15 加宽到 0.17，多出来的宽度由该行中间各列**均分扣除**（9 键 / 数字键的 0.23333 → 0.22，底行直接与上行对齐；15 键的 0.13998 → 0.132，底行 0.13/0.44/0.13 各让 1/75）；26 键既没有侧栏、分布也不等分，不适用 |
| 符号 / Emoji 键盘 | `input/keyboard/impl/SymbolKeyboard.kt` / `EmojiKeyboard.kt` + `data/Symbol.kt`（左侧栏与其它键盘最左列对齐，占屏宽 0.17——展开的候选词面板 `CandidateGridView` 侧栏同为 0.17；右侧网格仍是 5 等分） |
| 布局数据模型 | `input/keyboard/key/KeyDef.kt`（`KeyDef(appearance, behaviors, popups)`，行是 `List<KeyDef>`，宽度用 `percentWidth` 分数，每行和 ≈ 1） |
| 按键工厂 | `input/keyboard/key/KeyPreset.kt`（`alphabetKey`/`spaceKey`/`returnKey`/`capsLockKey`/`schemaSwitchKey`/`sidePannelKey`…） |
| 手势阈值 | `input/keyboard/key/CustomGestureView.kt`（长按 250ms、重复 100ms、滑动阈值） |
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
- 设置入口：`ui/screen/KeyboardSettingsScreen.kt` 的「按键手势」分组

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

### 9.3.4 侧栏快捷符号自定义（九键 / 数字键）

九键、数字键左侧那条竖排、可上下滑动的符号栏，其内容和顺序由用户在应用内编辑。

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

| 需求 | 位置 |
|------|------|
| 打分权重 | `base/priority/PriorityCalculator.kt`（`WeightConfig`，默认 base .5 / freq .3 / length .1 / count .1） |
| 下一词预测 | `engine/manager/PredictionManager.kt` + `base/marisa/Prediction.kt`（`TOP_K=100`，`ln(1+count)` 加权） |
| 候选重排 | `engine/manager/CandidateRerankManager.kt`（仅对第 1–24 个候选重排，第 0 个固定） |
| 候选面板外观 | `input/panel/component/CandidateGridView.kt`、`input/panel/KawaiiPanelRenderer.kt` |
| 候选条/工具栏按钮 | `input/panel/toolbar/ToolbarRenderer.kt` + `ToolbarRendererResources.kt`（中间工具由 `configuredToolbarButtons()` 读取偏好生成；`KawaiiPanel.kt` 与 `KawaiiPanelView.kt` 两处构造资源，改动要同步） |

### 9.7 网络后端（重点：这是自有服务）

所有在线能力都指向 `https://mapi.lutrip.com/`：

- `base/net/ApiConfig.kt`：`BASE_URL`、超时
- `app/version`：版本检查（`VersionChecker`）
- `model/grammar?language=...`：语法模型下载（`GramModelDownloader`）
- `speech/model?type=qnn|cpu&soc=...`：语音模型清单（`SpeechModelApi`）→ 返回 `.tar.bz2` 链接
- `HttpUtil` 统一追加 `version=<versionName>`，并解析 `{code,msg,data}` 信封

若自建服务，改 `ApiConfig.BASE_URL` 并保证接口返回结构一致即可。

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

1. **`resource.zip` 是私有构建物料**，`app/.gitignore`/根 `.gitignore` 排除了它；本 checkout 里没有 → 必现“无方案/无候选”。按 [7.2](#获取-resourcezip) 从上游 APK 提取。
2. **`assets/checksums.json` 缺失**：`DataManager.sync()` 会走异常分支打印 `Sync not prepared!`，实际数据全靠 `resource.zip` 解压。如果未来把方案改为“放 assets 增量同步”，需要补上 `checksums.json`（格式见 `DataSync`/`DataSum`）。
3. **`install-deps.sh` 仍会 clone `llama.cpp`**，但代码已移除 llamacpp/gguf（提交 `0ebf565`）。可忽略或从脚本删掉以省时间。
4. **ProGuard 规则过时**：`app/proguard-rules.pro` 还保留 opencc4j/houbb 的 keep 规则，实际已改用自带 `TraditionalConverter`；release 目前 `isMinifyEnabled=false`，暂不影响。
5. **两个“反向”布尔设置**：
   - `keyboard_settings.keyboard.expand_borders`：getter 返回 `!pref`，默认实际为 true
   - `candidate_settings.show_border`：`isBorderEnabled = !pref`，默认实际为 true
6. **Room 无 7→8 迁移**：`AppDatabase` 有 1→7 的 Migration，但没有 7→8，且启用了 `fallbackToDestructiveMigration()` → 升级到 v8 会**清空数据库**。加表/改表时请补迁移。
7. **只支持 arm64-v8a**：想支持 32 位或模拟器，需要改 `abiFilters` 并重新编译 native + 处理 sherpa/QNN 产物。
8. **`compileSdk 37` / `targetSdk 36`**：需要较新的 Android SDK（37 可能是预览版平台），老环境需先 `sdkmanager "platforms;android-37"`。
9. **Manifest 申请了 `MANAGE_EXTERNAL_STORAGE`**（`AndroidManifest.xml`），上架应用商店可能被拒；常规使用其实靠 SAF Provider 即可。
10. **两个 `KeyActionListener` 同名**：`input/KeyActionListener.kt` 是引擎桥接，`input/keyboard/key/KeyActionListener.kt` 是 `fun interface`，改动时注意 import。
11. **死代码/未使用类**：`KeyboardThemeSettingsScreen` 引用之外，`input/dialog/SchemaPickerEntryUi.kt`、`SchemaPickerListAdapter.kt`、`panel/toolbar/ToolbarButton.ToggleImageButton` 当前未使用。
12. **release 未配置签名**：`app/build.gradle.kts` 里没有 `signingConfigs`，`assembleRelease` 产物未签名。

---

## 11. 上游与许可

- **本仓库**：上游 `danjian/ime`（均为 “mirror” 描述，无 LICENSE 文件）。
- **Native/引擎**：
  - librime：GPL-3.0-or-later（`cpp/CMakeLists.txt` 带 SPDX 头）
  - librime_jni：Apache-2.0（文件 SPDX 头）
  - OpenCC、snappy、glog、yaml-cpp、leveldb、marisa-trie：各自开源许可
- **方案数据**：万象拼音 [rime-wanxiang](https://github.com/amzxyz/rime-wanxiang)（CC BY 4.0），随 `resource.zip` 分发，文档见 `shared/README.md`
- **语音**：sherpa-onnx（Apache-2.0）
- ⚠️ 由于未声明仓库级 LICENSE，若要**再分发**，请自行确认各组件许可（尤其 librime 的 GPL-3.0 传染性）。

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
| `keyboard.feedback.vibration_level` | Int | 3 | 按键振动强度：0=关闭，1 很轻 → 5 最强；旧版 `keyboard.feedback.vibration` (Bool) 会自动迁移 |
| `keyboard.feedback.vibration_ignore_system` | Bool | true | 忽略系统「触感 / 振动」开关：走媒体振动通道，系统振动总开关关闭时退回无障碍通道（Android 13+） |
| `keyboard.feedback.sound` | Bool | true | 按键音 |
| `keyboard.gap.horizontal` | Int | 3 dp | 键水平间隔 |
| `keyboard.gap.vertical` | Int | 3 dp | 键垂直间隔 |
| `keyboard.key_radius` | Int | 14 dp | 键圆角 |
| `keyboard.ripple_effect` | Bool | false | 水波纹 |
| `keyboard.key_border_stroke` | Bool | true | 绘制键边框 |
| `keyboard.expand_borders` | Bool | false（**反向**） | 展开键边框 |
| `keyboard.gesture_input` | Int | 0 | 符号/数字输入手势：0=长按，1=上滑（互斥） |
| `keyboard.toolbar_tools` | String | `undo,redo,cursor,clipboard,palette` | 工具栏中间工具的有序 key 列表（逗号分隔，空串=全部移除） |
| `keyboard.side_panel_symbols.t9` | String | `，。！？：~...` | 九键左侧符号栏的有序符号列表（`\u001F` 分隔；显示/上屏时仍跟随全角-半角标点模式） |
| `keyboard.side_panel_symbols.number` | String | `+-*/=~?!` | 数字键左侧符号栏的有序符号列表（`\u001F` 分隔，原样使用） |
| `keyboard.width` | Int | 100 | 竖屏键盘宽度（% 屏宽），<100 时改用悬浮卡片布局；由「调整键盘大小」写入 |
| `keyboard.pos_x` | Float | 0.5 | 竖屏卡片水平位置比例（拖动后写入） |
| `keyboard.pos_y` | Float | 1.0 | 竖屏卡片垂直位置比例（拖动后写入） |
| `keyboard.floating.enabled` | Bool | true | 横屏悬浮键盘开关 |
| `keyboard.floating.width` | Int | 未设置=自适应 | 悬浮卡片宽度（% 屏宽）；未手动设置时取「屏短边/长边」，即按键宽度与竖屏一致（20:9 手机约 45%），拖过滑杆后才写入 |
| `keyboard.floating.pos_x` | Float | 0.5 | 悬浮卡片水平位置比例（0=贴左，1=贴右；拖动后写入） |
| `keyboard.floating.pos_y` | Float | 1.0 | 悬浮卡片垂直位置比例（0=贴顶，1=贴底；拖动后写入） |

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

### 12.2 Room 表结构

数据库 `ime_database`，`AppDatabase` version 8。

| 表 | 字段 |
|----|------|
| `candidate_sorting_v2` | `sorting_key` TEXT PK（候选集合 FNV-1a 指纹）、`candidateIds` TEXT（逗号分隔 List<Int>） |
| `clipboard_records` | `id` PK 自增、`text`、`timestamp`、`cloud`、`deleted`=0、`deletedAt`=0 |
| `candidate_prefers` | `text` TEXT PK、`context`、`click_count`=1、`created_at`、`updated_at` |
| `phrase_records` | `id` PK 自增、`text`、`label`、`createdAt` |

DAO：`CandidateSortingDao`、`ClipboardDao`、`CandidatePreferDao`、`PhraseDao`。

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
