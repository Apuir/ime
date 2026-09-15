# 更新日志（CHANGELOG）

本仓库 `ime`（私有）基于 [`danjian/ime`](https://github.com/danjian/ime)。
上游版本线停在 `1.0.5`（`1.0.6` 已写进代码但未发布），因此 **fork 从 `1.1.0` 起走自己的版本线**，
不再与上游的 `1.0.x` 混用。

## 版本号规则

| 字段 | 位置 | 规则 |
|------|------|------|
| `versionName` | `app/build.gradle.kts` | `主.次.修订`。**加新功能 → 次 +1**；**只修 bug → 修订 +1**；不兼容的大改才动主版本 |
| `versionCode` | `app/build.gradle.kts` | `主*10000 + 次*100 + 修订`，整数，**必须每次发布都递增** |

`versionCode` 是 Android 判断“能否覆盖安装”的唯一依据：它不增大，新 APK 就装不到旧版上
（会提示“应用未安装”）。`versionName` 只用于显示和更新检查（`base/net/VersionChecker.kt`）。

> ⚠️ 从上游继承的 `versionCode` 是 `1`，fork 期间一直没变。若你之前装过任何自己构建的 APK，
> 请务必先卸载旧版再安装 `1.6.0`，之后就不会再有这个问题。

## 发布方式

```bash
# 1) 改 app/build.gradle.kts 里的 versionName / versionCode
# 2) 提交
git commit -am "chore(release): 1.6.1"
# 3) 打标签（annotated）
git tag -a v1.6.1 -m "v1.6.1"
# 4) 推送
git push origin main --tags
```

在 GitHub 上还可以用这个 tag 建 Release，把 APK 传上去，这样比在群里发文件更好追溯。

---

---

## [2.2.0] — 2026-09-15

输入质量优化：**模糊音全开**、**常用词判断重做**、**误选后自动降权**，并把内置的
万象拼音方案数据从 `17.2.4` 升级到 `17.9.9`（与作者桌面一致）。

### 方案数据

- `feat(schema)`: 内置万象拼音升级 `17.2.4` → `17.9.9`。新增
  `context_reorder`（上下文调频）、`super_sequence`（手动排序）、`random_tools`
  （UUID / 随机密码）、`unicode_conversion`、`number_conversion` 等模块，
  `wanxiang_algebra.yaml` 从 133 KB 涨到 152 KB。
- `feat(schema)`: **模糊音默认开启**（`safe` 档 6 组：zh/z、ch/c、sh/s、n/l、
  an/ang、en/eng、in/ing）。`zongguo` → 中国。规则本身是万象自带的
  （`wanxiang_algebra.yaml` 的 `模糊音_*` 节点），本次只是把它们从注释状态启用；
  档位与取舍见下方「升级提示」与 [`docs/DEVELOPMENT.md` 9.4](docs/DEVELOPMENT.md#模糊音本项目默认全开)。
- `feat(schema)`: 开启万象原生的 `enable_fallback_reorder`（同码回删再输首次交换），
  作为「误选到第 0 个候选」时的引擎侧兜底。
- `chore(schema)`: 方案文件由脚本注入 app 桥接字段（`schema/layout`、
  `punctuation`、`kind`、`candidateKind` 与顶层 `options:` 块）。这些是上游
  `danjian/ime` 加的，万象官方包里没有，缺了会导致键盘不随方案切换、简繁/Emoji/
  英文模式三个设置失效。

### 候选排序与学习

- `feat(ime)`: **语法模型真正接入重排**。`RimeEngine` 之前恒传 `gramDb = null`，
  导致权重最大的语法分（0.5）永远为 0 —— 下载并加载了 `.gram` 却完全没用上。
- `feat(ime)`: 打分引入**位次先验**（`WeightConfig.rankPrior` 0.40，最大项）。
  改造前只用「点击数的对数 + 词长」，会无条件把长词抬前并整段打乱 Rime 的排序。
- `feat(ime)`: 用户偏好改为**可正可负的净分**（`base/priority/PreferenceScorer`）：
  正向用 `ln1p` 软压、负向线性见效，按 **24 h 半衰期**衰减（7 天后残值 < 1%），
  正负双向封顶。
- `feat(ime)`: **误选后自动降权** —— 候选上屏后又在 8 秒内被删掉，即判为误选，
  记一条负反馈；长按删除候选同样记。对应需求「手快选错了、删掉重打时那个词应该降权」。
- `chore(ime)`: 移除 `CandidateFeature.candidateCount`。它取「这一批候选的个数」，
  对所有候选都是同一个值、对排序没有任何影响（原来还恒传 0）。
- `refactor(ime)`: `CandidateRerankManager` 从「覆盖引擎顺序」改为「在引擎顺序上做
  有依据的调整」；同分按原始位次兜底，结果是确定的。仍只重排第 1–24 位，
  **第 0 个候选固定不动**（它是「按空格上屏」的目标，漂移会毁掉肌肉记忆）。
- `feat(ui)`: 设置 → 候选词 → 新增「学习数据」分组，可查看记录条数并一键重置
  （剪贴板与常用语不受影响）。

### 修复

- `fix(db)`: 补 `MIGRATION_8_9` —— `candidate_prefers` 增加 `bad_count` /
  `last_bad_at` 两列。**必须有迁移**：库启用了 `fallbackToDestructiveMigration()`，
  缺迁移会静默清空用户的剪贴板历史、常用语与候选排序记录。
  （`7→8` 的迁移仍然缺失，是上游遗留，见 `docs/DEVELOPMENT.md` 第 10 节。）
- `fix(ime)`: 退格删除时改为一次读取光标前 24 个字符（原来只读 1 个），用于判断
  「删的是不是刚上屏的那个词」；不额外增加 IPC 往返。

### 工具与文档

- `chore(scripts)`: 新增 `scripts/build-rime-resource.py` —— 可复现地重建
  `assets/resource.zip`（白名单取数、注入桥接字段与模糊音、保留 `predict.marisa`、
  引用校验、输出 manifest），支持 `--dry-run` / `--fuzzy` 档位，**幂等**。
- `chore(scripts)`: 新增 `scripts/rime-probe/` —— 在开发机上直接加载方案数据跑
  librime 的探针。改 `speller/algebra` 之前先在那里几秒钟验完，别再靠刷机试。
- `test`: 新增 `PreferenceScorerTest`（9 例）与 `CandidatePriorityTest`（10 例），
  钉住「净分可负」「惩罚会过期」「引擎排序为主」这几条取向；脚本侧新增
  `test_build_rime_resource.py`（22 例，覆盖注入正确性与幂等性）。
- `chore(ime)`: debug 构建下 `rawInput ≥ 3` 个字符时打一行诊断日志
  （`schema` / `input` / 候选总数 / 前 5 个候选），用于定位「简拼出不来」这类
  只在真机复现的问题；release 构建 `Timber.treeCount == 0`，无开销。
- `docs`: README / DEVELOPMENT 更新（版本、模糊音位置、重排设计、`resource.zip`
  重建流程、脚本索引）；新增 `docs/plans/feat-pinyin-input-quality/`（设计说明 + 任务清单）。

> ⚠️ **升级提示**
> - `versionCode` 从 `20101` 升到 `20200`，可直接覆盖安装。
> - 首次启动的**引擎部署会明显变慢**：模糊音改变了拼写运算，`table.bin` /
>   `prism.bin` 必须重建（一次性成本）。请耐心等待，别在部署中途杀掉进程。
> - 模糊音档位默认 **`safe`（6 组）**：平翘舌（zh/z、ch/c、sh/s）+ 前后鼻音
>   （an/ang、en/eng、in/ing）+ n/l。选它的依据是实测 —— 这 6 组拿到了模糊音的
>   全部收益（`zongguo`→中国、`cang`→常、`si`→是、`gen`/`geng` 都出「跟」、
>   `xin`/`xing` 都出「新」），而且 `qryt` 的首选仍是「杞人忧天」、候选数
>   2544 → 2546 几乎不涨。
>   想要更多组可以重建：`--fuzzy all`（加上 r/l、r/y、h/f、k/g）会让 `qryt` 的
>   候选数涨到 4011、首选变成「去了一趟」（多出的歧义来自 r/l、r/y）；
>   `--fuzzy none` 则完全关闭模糊音。
> - 学习数据表结构变了（v8 → v9），已带迁移，历史记录保留。

## [2.1.1] — 2026-09-12

修复「换主题只换了一半」：设置页里切换主题后，面板 / 背景 / 工具栏已经变色，
但键帽仍然是上一个主题（例如自定义主题看起来「还是暗夜」）。

- `fix(ime)`: 修掉 `KeyboardStateManager.rebuild()` 在键盘收起时的提前返回。
  键盘实例在创建时就把 `ColorScheme` 固化进 `KeyView`，而主题切换通常发生在键盘收起时
  （在设置页里改），此前的提前返回让注册表继续留着**上一个主题**创建的键盘实例；
  再次弹出键盘时 `onAttach()` 直接复用它们，于是只有走 `KeyboardColors.resolve()` 实时取色的
  面板 / 背景 / 工具栏跟着变了，键帽还是旧配色。现在 `rebuild()` 一定会清空注册表，
  已挂载时立刻用新配色重建，未挂载时留待下次 `onAttach()` / `startInput()` 重新创建
- `fix(ime)`: `onAttach()` 增加兜底——注册表里没有当前键盘时按新配置重新创建，
  不再出现「把 `keyboardAttached` 置真却没有键盘可显示」的状态
- `test`: 修正长期失败的 `SymbolPairTest.symbolCategoriesOrderMatchesDesign`。
  该断言写于符号页 9 分类时期，`19b78c3` 把符号页扩到 21 类（`SymbolExtra`）时没同步更新，
  于是它一直期望「9 类」而实际是「基础 9 类 + SymbolExtra 12 类」。
  现在改为校验「基础 9 类顺序 + 追加分类顺序」，并补上分类非空 / 名称唯一 / 类内去重断言

## [2.1.0] — 2026-09-12

26 键符号 / 九键字母可以自己改了，并补上主流输入法都有的按键气泡。

- `feat(ime)`: 26 键字母键下的符号 / 数字改为可配置（q→1、g→$ 这些），键帽右下角与气泡内容同步更新
- `feat(ime)`: 九键每个数字键包含的字母改为可配置（2→abc、7→pqrs），键帽显示与气泡候选项同步更新
- `feat(ime)`: 新增按键气泡——在字母键 / 九宫格数字键上长按或上滑停留后弹出带尾巴的气泡，
  手指不离开屏幕左右滑动切换高亮项，松开输入高亮项；26 键气泡为「小写字母 / 符号 / 大写字母」，
  九键为「数字 / 该键的每个字母」；顶行按键自动翻到下方弹出，屏幕边缘自动夹取
- `feat(ime)`: 气泡描边 / 圆角跟键帽完全一致（同 `keyBorderStroke` 颜色、同 `keyBorderWidth` 厚度、同 `keyRadius` 圆角），
  不再和键盘糊在一起；关掉「绘制键边框」时气泡也不描边
- `feat(ime)`: 「按键手势」设为上滑时，**快速上滑仍然直接输入符号 / 数字**，上滑后停住 200ms 才弹气泡
  （气泡成为「想选别的候选项」时才出现的入口，不打断原有的上滑手感）；长按则在两种设置下都弹气泡
- `fix(ime)`: 修掉「气泡几乎弹不出来」：原先用「手指离按键漂移超过一个键宽/键高就取消」当闸门，
  26 键键宽只有 ~35px，长按与上滑时的自然漂移（实测 220～330px）几乎必然超标；改为定时器启动即跑到点
- `fix(ime)`: 气泡背景改为显式不透明实色绘制（原来经 `ShapeDrawable(PathShape)` 画出来偏透明，
  看起来像没画出来）
- `fix(ime)`: 修掉「普通上滑不再输入数字 / 符号」：上滑判定原先挂在 `!bubbleTriggerOnLongPress` 下面，
  而长按弹气泡时该标志恒为 true，上滑分支成了死代码；现在上滑只看位移，与长按是否弹气泡解耦
- `feat(ui)`: 新增「设置 → 键盘布局 → 按键映射」页，可视化编辑 26 键三行键位与九键字母，支持自定义符号与恢复默认
- `feat(ime)`: 改造键盘布局构建：`BaseKeyboard` 改为接收 layout 工厂（键盘布局现在需要读用户配置），
  26 键 / 九键 / 15 键的 `buildLayout(context)` 按映射生成键帽与气泡
- `chore(ime)`: 新增 `KeyboardKeyMapping` 偏好（`keyboard.key_mapping.qwerty` / `.t9` / `keyboard.key_bubble`），
  映射只存用户改过的键，未改动的跟随默认值
- `chore(test)`: 新增 `KeyMappingDefaultsTest` 钉住 26 键 / 九键的默认键位（与旧版写死的一致）
- `chore(build)`: APK 产物改名——release 为 `ime-<版本号>.apk`、debug 为 `ime-<版本号>-debug.apk`
  （AGP 9 的新 Variant API 已移除 `outputFileName`，改为 assemble 后置改名）

> 气泡弹出时的默认高亮项就是「点一下这个键本来会输入的内容」（26 键是小写字母、九键是数字），
> 所以不滑动直接抬手的结果和普通点击一致，不会误上屏符号。
>
> 关掉「按键气泡」即回到旧行为：长按（或上滑）直接上屏符号 / 数字。
>
> 九键的数字键在两种手势下都是**长按**弹气泡（九键的数字本来就是上滑要输入的次级字符，
> 上滑再要求停住会互相打架）；26 键则跟随「按键手势」设置。

## [2.0.0] — 2026-09-12

改名换包名，并彻底去掉对上游服务器的依赖。

- **不兼容**：applicationId / namespace 改为 `com.ninthsoft.ime`，应用名改为「简意输入法」。
  这是另一个应用，装之前要先卸载旧的 `com.ninthsoft.ime`，旧版的 IME 启用状态和设置不会带过来
- `chore(ime)`: 语音模型直连 k2-fsa/sherpa-onnx 官方发布地址（国内优先走 gh-proxy 镜像），md5 固定在本地
- `chore(ime)`: 语法模型直连万象拼音作者发布地址（CNB 优先，GitHub 备用）
- `chore(ime)`: 版本信息地址留空，暂不提供在线检查更新（入口隐藏）；删除 lutrip 后端协议层
- `chore(build)`: librime 依赖固定到 commit `95d3e11`，仓库地址可用 `LIBRIME_REPO` 覆盖
- `fix(ime)`: manifest 里 `<attribution>` 的 tag 改为与代码一致的 `keyboard_feedback`

## [1.7.1] — 2026-09-12

设置页文案收拾干净。

- `fix(ui)`: 振动效果、上屏设置、关于页的说明文字改回正常产品文案，去掉括号注解和长篇解释
- `fix(ui)`: 通用成功提示从 `done` 改为「完成」

## [1.7.0] — 2026-09-12

按键振动对齐系统键盘的振感。

- `feat(ime)`: 新增「振动效果」设置，可选「系统触感（默认，走厂商预置效果）」或「自定义强度（10 档）」
- `feat(ime)`: 自定义强度由 5 档扩到 10 档，新增 5 个更弱档位，旧档位自动迁移、手感不变
- `fix(ime)`: 自定义强度优先使用厂商调校的 primitive，消除裸波形余振带来的拖沓
- `fix(ime)`: 按键振动不再每次按键查询系统 `vibrate_on`，消除起振延迟

## [1.6.0] — 2026-09-11

数字键盘重排与列宽统一。

- `feat(ime)`: 数字键盘重排并统一非 26 键键盘的左右列宽

## [1.5.0] — 2026-09-11

键盘尺寸编辑、触感调节与侧栏符号自定义。

- `feat(ime)`: 键盘内「调整键盘大小」编辑模式
- `feat(ime)`: 按键振动强度逐级可调并支持忽略系统振动设置
- `feat(ime)`: 26 键空格两侧新增 `.` `/` `,` 快捷键
- `feat(ime)`: 九键 / 数字键侧栏符号支持自定义内容与顺序
- `docs`: 补充 26 键底行与侧栏符号设置的 README 说明

## [1.4.0] — 2026-09-10

实时上屏模式与横屏悬浮键盘。

- `feat(ime)`: 新增输入框实时上屏模式与切换时保留开关
- `feat(ime)`: 横屏悬浮键盘（可拖动卡片 + 自适应宽度）
- `docs`: 更新主题格式与 README（GUI 编辑器、工具栏工具、手势）
- `docs`: 补充上屏设置的 README 说明

## [1.3.0] — 2026-09-10

应用内主题编辑器、工具栏自定义与上滑输入。

- `feat(theme)`: 新增应用内 GUI 主题编辑器，自定义主题不再限制数量
- `feat(ime)`: 工具栏中间工具支持用户增删与排序
- `feat(ime)`: 26 键 / 九键支持上滑输入符号数字，并与长按互斥

## [1.2.1] — 2026-09-10

滚动性能修复。

- `fix(ime)`: 修复符号页与候选列表滑动中途卡住，并去掉滚动时的逐帧全量布局

## [1.2.0] — 2026-09-10

符号页重做与构建改进。

- `feat(ime)`: 符号页改为「最近 / 中文 / 英文 / 数学…」并支持成对符号自动补齐
- `feat(ime)`: 符号页扩充至 21 类，新增 `SymbolExtra` 数据源
- `build`: release 签名配置、`.gitignore` 加固与 `install-deps` 修复

## [1.1.1] — 2026-09-10

返回键行为修复。

- `fix(ime)`: 「返回」改为按导航栈回退上一层键盘

## [1.1.0] — 2026-09-10

fork 版本线起点：键盘底行重做。

- `feat(ime)`: 支持跨行按键并重做九键 / 26 键 / 数字键盘底行布局
- `docs`: 重写 README（架构、构建物料、定制指南）

---

## 上游历史（供对照）

上游 `danjian/ime` 的 `1.0.x` 发布与 fork 的关系：

| 上游版本 | 说明 |
|----------|------|
| `v.1.0.1` / `v.1.0.2` / `v1.0.4` / `v1.0.5` | 上游在 GitHub 上发布的 Release（对应其 `master` 分支历史） |
| `1.0.6` | 上游代码里已写入、**未发布**；本 fork 就是从这个状态分出来的 |

fork 点：上游提交 `efc045d`（*Add initial README with project description*）。
其之前的 61 个提交为上游作者 DanJian 所写，从 `1.1.0` 开始的提交为本 fork 的改动。
