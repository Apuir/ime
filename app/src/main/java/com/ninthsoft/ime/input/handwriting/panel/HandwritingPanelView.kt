package com.ninthsoft.ime.input.handwriting.panel

import android.content.Context
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.Space
import androidx.annotation.DrawableRes
import com.ninthsoft.ime.R
import com.ninthsoft.ime.data.keyboard.theme.KeyboardColors
import com.ninthsoft.ime.data.manager.KeyboardManager
import com.ninthsoft.ime.input.handwriting.HandwritingEngineHolder
import com.ninthsoft.ime.input.handwriting.HandwritingManager
import com.ninthsoft.ime.input.handwriting.HwCandidate
import com.ninthsoft.ime.input.keyboard.impl.NumberKeyboard
import com.ninthsoft.ime.input.keyboard.impl.SymbolKeyboard
import com.ninthsoft.ime.input.keyboard.key.ImageKeyView
import com.ninthsoft.ime.input.keyboard.key.KeyDef
import com.ninthsoft.ime.input.keyboard.key.KeyDef.Appearance.Border
import com.ninthsoft.ime.input.keyboard.key.KeyDef.Appearance.Variant
import com.ninthsoft.ime.input.keyboard.key.KeyView
import com.ninthsoft.ime.input.keyboard.key.KeyViewFactory
import com.ninthsoft.ime.input.keyboard.key.KeyboardAction
import com.ninthsoft.ime.input.keyboard.key.SidePanelKeyView
import splitties.dimensions.dp

/**
 * 手写顶栏（候选 / 工具条）的高度（dp）。
 *
 * 宿主摆全屏形态最上面那条顶栏占位时要用同一份值，所以放在文件级而不是 private companion 里
 * —— 两处各写一个数，迟早会对不上。
 */
const val HANDWRITING_ROW_HEIGHT_DP = 48

/**
 * 手写自己的键行高度（dp）：半屏的功能行，全屏的标点行与功能行。
 *
 * 比顶栏高：键帽本身还要内缩一圈（见 `KeyView` 的键帽内缩），48dp 的行扣完只剩 42dp，
 * 一排又宽又矮的按钮看着很扁。
 */
const val HANDWRITING_KEY_ROW_HEIGHT_DP = 56

/** 全屏手写底部三条的总高度（dp）：顶栏占位 + 标点行 + 功能行。 */
const val HANDWRITING_FULL_SCREEN_STACK_DP =
    HANDWRITING_ROW_HEIGHT_DP + HANDWRITING_KEY_ROW_HEIGHT_DP * 2

/**
 * 手写面板：半屏时「左边书写区、右边竖排标点栏、底部一条功能行」，
 * 全屏时「铺满窗口的书写层 + 底部三条（顶栏/标点行/功能行）」。
 *
 * 这个类只负责「排版 + 手势时序 + 把结果交给宿主」，三条硬边界：
 * - **不上屏**。提交 / 退格 / 空格 / 回车全部走 [Listener] 回调，宿主再转成
 *   `KeyboardAction` —— 面板里绝不碰 `InputConnection`，否则光标、组合态、
 *   上屏埋点这些规则会在两个地方各写一遍。
 * - **几乎不读偏好**。半/全、引擎模式都是宿主的决定，这里只认 [refreshColors] 给的颜色。
 *   唯一的例外是「停手识别时长」：它是纯粹的**手写时序**参数，面板自己调度计时器，
 *   再让宿主多绕一层回灌反而是两处维护 —— 但读也只读一次（见 [refreshRecognizeDelay]），
 *   不在每次落笔时摸 SharedPreferences。
 * - **键用键盘那一套**。面板的按键是 [KeyDef] + [KeyViewFactory] 建出来的键盘同款键帽
 *   （按下态、音效、振动、长按连删都在 `CustomGestureView` 里），不是自己搭的
 *   TextView / ImageView：手绘 canvas 键盘那种实现，键位、命中区、按下态都要自己维护，
 *   是上一版「布局与交互不顺手」的根源，而自己搭普通控件则是「同一个动作两套手感」的根源。
 *
 * 停手识别：抬笔后等偏好里的时长没有新笔画就送引擎，识别完自动清空笔迹
 * （不需要用户点「清除/撤销」）。识别出的**首候选先进入组合态**：跟拼音的 preedit 一样，
 * 作为未上屏文本停在输入框里（见 [Listener.onComposing]），顶栏候选留着供用户换字；
 * 等「落笔写下一个字 / 切键盘 / 收起键盘 / 空格回车标点 / 面板收起」时再把它收尾成正式文本
 * （即用户说的「保留这个字」，见 [finalizeRound]），然后开始下一个字。
 *
 * 这套「组合态 → 收尾」的时序状态全部在面板手里（识别的生命周期本来就在这儿），
 * 面板只把事件报给 [Listener]，由宿主翻译成对 `InputConnection` 的操作。
 */
class HandwritingPanelView(context: Context) : FrameLayout(context) {

    /**
     * 面板对外的全部出口。宿主实现它、接到自己的上屏与键盘切换逻辑上。
     *
     * 这里刻意不提供「识别」相关的方法：识别何时发起是面板自己的时序，
     * 宿主只需要接收 [onCandidates] 或 [onRecognizeFailed]。
     */
    interface Listener {
        /** 候选列表，空列表表示清空（识别不出、面板重置）。宿主显示在顶栏。 */
        fun onCandidates(candidates: List<HwCandidate>)

        /**
         * 首候选进入组合态，或用户点了别的候选换字。
         *
         * 面板不认识 `InputConnection`、也不认识 `KeyboardAction`：这里只报「输入框里此刻
         * 应该显示哪段未上屏文本」，宿主转给 IME 服务的组合态入口。走的是与拼音 preedit
         * 同一条 composing 通道，所以换字是**替换**组合文本，而不是删掉重打。
         */
        fun onComposing(text: String)

        /** 把当前组合文本收尾成正式文本（保留在输入框里）。见 [finalizeRound]。 */
        fun onFinalizeComposing()

        /** 丢掉当前组合文本（不保留在输入框里）。⌫ 撤销本次手写时用。 */
        fun onDiscardComposing()

        /** 单字 / 单词上屏。调用前面板已经收尾了当前组合（标点属于「下一个动作」）。 */
        fun onCommit(text: String)

        /** 成对标点：宿主走 `KeyboardAction.CommitPairAction`，让光标停在中间。 */
        fun onCommitPair(open: String, close: String)

        fun onBackspace()

        fun onSpace()

        fun onReturn()

        /** 切到别的键盘。参数取 `SymbolKeyboard.NAME` / `NumberKeyboard.NAME`。 */
        fun onSwitchKeyboard(name: String)

        /**
         * 「中/英」键：交给宿主的**键盘槽**机制切到英文，而不是直接换一个键盘。
         *
         * 手写是中文槽里的一种输入方式，直接 `switchKeyboard` 去 26 键会让「显示的键盘」
         * 与「槽里选的中文输入方式」对不上：这时再按中/英键会先把槽切到英文（画面没变化，
         * 因为两个槽都是 26 键），要按第二下才回得到手写。
         */
        fun onSwitchLanguage()

        /**
         * 「半/全」键：切换手写范围（键盘区域内 ⇄ 整个屏幕）。
         *
         * 面板只报告「用户按了」，范围状态与窗口形态都是宿主的决定（要落偏好、要动窗口）；
         * 宿主定完之后用 [setFullScreenLayout] 把「是否走整屏形态」灌回来。
         */
        fun onToggleFullScreen()

        /** 识别失败或引擎不可用时的可读提示（面板已经把它显示在书写区，宿主也可以再提示一次）。 */
        fun onRecognizeFailed(message: String)
    }

    var listener: Listener? = null

    private val ink = HandwritingInkView(context)

    /** 停手计时。用一个常驻 Runnable 反复 post/remove，避免每次抬笔都分配一个 lambda。 */
    private val recognizeRunnable = Runnable { recognizeNow() }

    /**
     * 顶栏当前显示的手写候选（本轮还没落定的那一批）。
     *
     * 它同时是「本轮组合态还在不在手上」的唯一判据：非空 ⇔ 首候选正作为组合文本停在
     * 输入框里（识别出候选时一起置上，收尾 / ⌫ / 收起面板时一起清掉）。
     * ⌫ 该撤销本次手写还是正常退格、落笔该不该收尾，都看它空不空。
     */
    private var pendingCandidates: List<HwCandidate> = emptyList()

    /**
     * 已发出识别的轮次号。
     *
     * ⌫ 与「收起面板」会把它 +1，让「已经发出去、结果还在路上」的那一次结果作废：
     * 否则晚到的结果会在用户已经撤销之后又把那个字推进组合态。
     * [HandwritingEngineHolder.cancelPending] 挡的是「还没跑到回调」的那部分，
     * 这里挡的是「回调已经派到主线程、只是排在 ⌫ 后面」的那部分。
     *
     * 落笔**不**加号：连写时上一拍的结果正是要落定、要收尾的那个字（见 [onStrokeStarted]）。
     */
    private var recognitionRound = 0

    /** 停手识别时长（毫秒）。见 [refreshRecognizeDelay]：只在面板显示时读一次偏好。 */
    private var recognizeDelayMs: Int = HandwritingManager.RECOGNIZE_DELAY_MS_DEFAULT

    /** 是否处于整屏手写形态（真·全屏；GROW 兜底模式下宿主不会打开它，因为形态不变）。 */
    private var fullScreenLayout = false

    /**
     * 整屏形态下底部三条（顶栏占位 / 标点行 / 功能行）的容器。
     *
     * 它需要一块**实体**底色：整屏时面板整体只是一层很淡的遮罩，而底部三条属于键盘区域，
     * 键帽自身又是半透明的（暗色主题的 `specialKeyBackground` 只有 120 alpha），
     * 只靠遮罩兜底会把应用内容透出来 —— 看起来整个键盘都变成了半透明。
     */
    private var fullScreenStack: View? = null

    /**
     * 整屏形态下底部三条要留出的总空间（px）：系统导航栏高度 + 键盘自己的底部内边距。
     *
     * 两条都必须留，少了哪一条都会和其他键盘对不齐 —— 前者会露出一条空档（半屏键盘在那一条上
     * 画的正是键盘底色），后者会让底部三条比九键 / 26 键矮一截、整体更靠下。
     */
    private var fullScreenBottomSpacePx = 0

    /**
     * 最近一次 [refreshColors] 给的配色。
     *
     * 半/全切换要整块重建布局（书写区在两个布局里是同一个 View，不能同时挂在两处），
     * 重建后得照旧配色重新上色 —— 否则切一次范围，键帽就退回默认色。
     */
    private var lastColors: KeyboardColors.ColorScheme? = null

    /** 回车键的图标参考；默认回车，输入框有搜索/发送等动作时由宿主下发替换。 */
    private var returnKeyView: ImageKeyView? = null
    private var returnKeyIconRes = 0

    /**
     * 底部功能行。键面（图标 / 文字 / 取色家族 / 描边）用键盘的 [KeyDef] 描述，
     * 键视图由 [KeyViewFactory] 建 —— 与键盘是同一份，不再自己搭 TextView / ImageView。
     * 动作由 [onPanelAction] 解释。
     */
    private val functionKeys: List<FunctionKey> = listOf(
        // 键面用文字而不是图标：符号图标画出来是一堆「!?#」，在这个位置反而不如两个字清楚
        FunctionKey(
            def = functionTextKey("符号", KeyboardAction.LayoutSwitchAction(SymbolKeyboard.NAME)),
            description = "符号",
        ),
        // 键面用键盘上同一枚图标：中/英键在哪个键盘上都是这个地球标，
        // 只有手写这里曾经写成文字「中/英」，看着像另一个东西
        FunctionKey(
            def = functionIconKey(R.drawable.ic_keyboard_language, KeyboardAction.RotateSchema),
            description = "中英切换",
        ),
        // 空格 / 回车 / 标点都属于「下一个动作」：先收尾当前组合（保留这个字），再执行动作。
        // 空格是功能行里唯一加宽的键：打得最多、最怕按偏，其余键一律等宽。
        FunctionKey(
            def = functionIconKey(R.drawable.ic_keyboard_space, KeyboardAction.SpaceAction),
            description = "空格",
            weight = SPACE_KEY_WEIGHT,
        ),
        // 半/全放在空格与 123 之间：左边是「输入」类键（符号/中英/空格），
        // 右边是「换键盘」类键（123/回车），范围切换贴着空格这一侧最顺手
        FunctionKey(
            def = functionTextKey("半/全", KeyboardAction.ToggleHandwritingFullScreen),
            description = "切换手写范围",
        ),
        FunctionKey(
            def = functionTextKey("123", KeyboardAction.LayoutSwitchAction(NumberKeyboard.NAME)),
            description = "数字",
        ),
        FunctionKey(
            def = functionIconKey(
                iconRes = R.drawable.ic_keyboard_return,
                action = KeyboardAction.ReturnAction(),
                variant = Variant.Accent,
                border = Border.Special,
            ),
            description = "回车",
            isReturn = true,
        ),
    )

    /**
     * 符号栏内容：与九键左侧栏**同一份偏好**（设置 → 侧栏符号），顺序也照抄。
     *
     * 不在这里另立一份默认表：用户在九键里排的符号、顺序，进手写必须原样出现；
     * 手写这边改了（改的还是同一份偏好）回九键也一致。
     */
    private var punctuationSymbols: List<String> = emptyList()

    /**
     * 成对符号的闭符号。表是写死的，因为它描述的是「符号本身成不成对」这个事实，
     * 与用户在偏好里放哪些符号无关；只有偏好里真出现开符号时才用得上。
     */
    private val symbolPairs: Map<String, String> = mapOf(
        "“" to "”", "‘" to "’", "（" to "）", "《" to "》", "【" to "】", "「" to "」",
    )

    private fun buildPunctuationKeys(): List<PunctuationKey> =
        punctuationSymbols.map { PunctuationKey(it, symbolPairs[it]) }

    init {
        rebuildContent()

        ink.listener = object : HandwritingInkView.Listener {
            override fun onStrokeStarted() {
                // 用户又落笔了：上一拍的停手计时作废，否则会在他字还没写完时就去识别半截
                cancelScheduledRecognize()
                // 落笔 = 「上一个字我认了，开始写下一个」：把上一轮的组合文本收尾成正式文本，
                // 那个字就此留在输入框里。这就是「保留这个字、开始下一个字」的时机。
                finalizeRound()
            }

            override fun onStrokeFinished() {
                scheduleRecognize()
            }
        }
    }

    // ------------------------------------------------------------------
    // 对外方法
    // ------------------------------------------------------------------

    /**
     * 面板显示。每一轮都从干净状态开始：上一轮的笔迹和候选跟过来，
     * 只会让人以为「那个字还在组合态里」。
     *
     * 不在这里收尾上一轮的组合文本：进面板前宿主会先把引擎的组合收尾（见
     * [com.ninthsoft.ime.input.keyboard.window.KeyboardWindowView.showHandwritingPanel]），
     * 上一轮手写的组合也在面板隐藏时收尾过了。
     */
    fun onShown() {
        cancelScheduledRecognize()
        // 偏好只在这一次读：识别时长的读取点固定在「面板显示」这一刻，
        // 落笔/抬笔的高频路径上不再碰 SharedPreferences（改动要热生效由宿主转发，见下）
        refreshRecognizeDelay()
        ink.setHint(null)
        ink.clearAll()
        clearCandidates()
        // 引擎加载/下载要几十毫秒到几十秒（首次可能触发模型下载），所以放在后台，
        // 失败时才把原因显示在书写区 —— 不挡住面板，用户仍可切走。
        HandwritingEngineHolder.ensureReady(context) { name, hint ->
            if (name == null) showHint(hint ?: "手写引擎不可用")
        }
    }

    /**
     * 符号栏偏好变了（设置页改的，或从九键那边改的）：重读列表并整块重建。
     *
     * 由宿主在 `keyboard.side_panel_symbols.t9` 变化时转发 —— 面板自己不监听偏好，
     * 与「识别时长」同一个做法。
     */
    fun refreshPunctuationSymbols() {
        if (punctuationSymbols == KeyboardManager.Keyboard.SidePanelSymbols.getT9(context)) return
        rebuildContent()
        requestLayout()
        invalidate()
    }

    /**
     * 设置回车键图标。宿主在输入框动作变化（搜索 / 发送 / 换行…）时下发，
     * 用的是与键盘同一份映射（`key/ReturnKeyIcon.kt`）；传 0 表示还不知道，用默认回车图标。
     */
    fun setReturnKeyIcon(iconRes: Int) {
        if (returnKeyIconRes == iconRes) return
        returnKeyIconRes = iconRes
        // 只换图：内容色挂在 ImageView 的 tint 上，换资源不会丢
        returnKeyView?.updateImage(returnKeyIcon())
    }

    /** 当前该用的回车键图标：宿主还没下发（0）时用默认回车。 */
    private fun returnKeyIcon(): Int =
        returnKeyIconRes.takeIf { it != 0 } ?: R.drawable.ic_keyboard_return

    /** 面板隐藏。已经发出去的识别也一并作废，免得结果回来时往一个收起来的界面里推候选。 */
    fun onHidden() {
        cancelScheduledRecognize()
        HandwritingEngineHolder.cancelPending()
        // 轮次 +1：连「回调已经派到主线程、只是排在这次收起后面」的结果也一并作废，
        // 否则收键盘这一下会把它当作新候选推进一个已经换了场景的输入框里。
        recognitionRound++
        ink.clearAll()
        // 面板收起也是一次收尾（切键盘 / 收起键盘都从这里走）：当前组合的字保留在输入框里，
        // 只是顶栏要还回去。这里**只收尾、不推空候选**：下面的宿主紧接着会把顶栏恢复成工具条，
        // 多推一次空候选会让它先闪一下手写态。
        if (pendingCandidates.isNotEmpty()) listener?.onFinalizeComposing()
        resetCandidateState()
    }

    /**
     * 重读「停手识别时长」偏好。
     *
     * 面板自己不去监听偏好（那要注册监听器、还要管生命周期），改为由宿主在偏好变化时
     * 调这里 —— 见 `KeyboardWindowView.onConfigChanged` 里 [HandwritingManager.KEY_RECOGNIZE_DELAY_MS]
     * 的分支。**这就是热生效的保证**：设置页一改，正在写的手写也立刻按新时长识别；
     * 若宿主忘了转发，最多退化成「下次开面板才生效」，不会用错值。
     */
    fun refreshRecognizeDelay() {
        recognizeDelayMs = HandwritingManager.recognizeDelayMs(context)
    }

    /**
     * 切到/退出「整屏手写」形态。
     *
     * 只改面板自己的排版（书写层铺满 + 底部三条），窗口与触摸区域是宿主的事
     * （真·全屏要 [com.ninthsoft.ime.input.keyboard.window.KeyboardWindowView] 把 IME 窗口铺满屏幕）。
     */
    /** 整屏形态下底部要留出的总空间（px），由宿主在进入整屏与 insets / 内边距变化时灌进来。 */
    fun setFullScreenBottomSpace(px: Int) {
        if (fullScreenBottomSpacePx == px) return
        fullScreenBottomSpacePx = px
        // 左右那份键盘边距要一起给，不能只覆盖底部 —— setPadding 是整份替换
        fullScreenStack?.setPadding(horizontalPaddingPx, 0, horizontalPaddingPx, px)
    }

    fun setFullScreenLayout(enabled: Boolean) {
        if (fullScreenLayout == enabled) return
        fullScreenLayout = enabled
        rebuildContent()
        requestLayout()
        invalidate()
    }

    /** 按主题上色。宿主在主题变化时调用；不调也能用（退化成中性色）。 */
    fun refreshColors(colors: KeyboardColors.ColorScheme) {
        if (lastColors == colors) return
        lastColors = colors
        // KeyView 的配色是构造期注入的，改色只能把键重建一遍（重建末尾会用 lastColors 重新上色）。
        // 这也是半/全切换走的那条路 —— 键视图本来就要整块重来，不额外为配色开一条刷新通道。
        rebuildContent()
        requestLayout()
        invalidate()
    }

    /**
     * 重新上色。键帽的颜色是建键时注入的，所以这里只处理「不是键」的那几块
     * （面板底、底部三条的实体底、墨迹）；键跟着 [rebuildContent] 一起重建。
     */
    private fun applyColors(colors: KeyboardColors.ColorScheme) {
        // 全屏手写时面板底不是键盘底色，而是一层极淡的遮罩：全屏的意义就是让笔迹盖在
        // 应用内容上，底色一旦不透明，下层应用整个就看不见了。
        setBackgroundColor(
            if (fullScreenLayout) scrimColor(colors.background) else colors.background,
        )
        // 但底部三条（标点行 + 功能行 + 顶栏占位）是键盘区域，必须保持实体：
        // 它们压在遮罩上，不补一块不透明底色的话，键帽自带的那点半透明会把应用内容透出来。
        fullScreenStack?.setBackgroundColor(colors.background)
        // 墨迹用 keyText：它在任何主题里都是对比度最高的内容色；提示语退一档用 altText
        ink.setColors(colors.keyText, colors.altText)
        // 半屏时书写区只是面板里的一块，用键帽那套底色/描边圈出来才看得出字该落在哪儿；
        // 全屏时它铺满整窗、本身是遮罩，再套一圈边框反而怪。
        ink.setWritingSurface(
            enabled = !fullScreenLayout,
            fill = colors.keyBackground,
            stroke = colors.keyBorderStroke,
            radius = dp(colors.cornerRadius),
            strokeWidth = if (KeyboardManager.Keyboard.KeyBorderStroke.isEnabled(context)) {
                dp(colors.keyBorderWidth)
            } else {
                0f
            },
        )
    }

    /**
     * 全屏遮罩色：暗色主题刷一层淡白、亮色主题刷一层淡黑，都是 6%。
     *
     * 判据用背景色亮度、而不是主题开关：自定义主题的深浅只有它自己的颜色知道，
     * 亮度是唯一一处两个方向都成立的判据（也不会随「跟随系统」开关漂移）。
     */
    private fun scrimColor(background: Int): Int {
        val r = (background shr 16) and 0xFF
        val g = (background shr 8) and 0xFF
        val b = background and 0xFF
        val luminance = 0.299f * r + 0.587f * g + 0.114f * b
        return if (luminance < DARK_LUMINANCE) SCRIM_ON_DARK else SCRIM_ON_LIGHT
    }

    /** 在书写区显示一句提示（识别失败、引擎不可用）。下一次显示面板时会被重置。 */
    fun showHint(text: String) {
        ink.setHint(text)
        // 提示要盖在空画布上才看得见，所以顺手清掉残留笔迹
        ink.clearAll()
    }

    /**
     * 清掉顶栏候选，并把「本轮组合态还在手上」的标记归零。
     *
     * 只在本轮结束时用（收尾 / ⌫ 撤销 / 面板重开）：换字（点第 2..N 个候选）**不**走这里
     * ——候选条要留着让用户继续挑，直到本轮结束。
     */
    private fun clearCandidates() {
        resetCandidateState()
        listener?.onCandidates(emptyList())
    }

    /** 只清面板内部的候选状态，不通知宿主（宿主自己会决定顶栏接下来显示什么）。 */
    private fun resetCandidateState() {
        pendingCandidates = emptyList()
    }

    /**
     * 顶栏（或展开网格）里的手写候选被点：把组合文本换成它，然后**立刻收尾这一轮**。
     *
     * 收尾是为了给出反馈：候选栏随即收回工具条，跟写完一个字落定时一样。此前只是把组合文本
     * 换掉、候选栏原样留着，输入框里的确换了字，但界面上看不出这一按生效了 ——
     * 用户会以为「点其他候选没反应，只能用第一个」。
     *
     * 换字与收尾的顺序不能反：收尾会清掉本轮候选状态，之后再换就只能改到下一轮去了。
     */
    fun selectCandidate(text: String) {
        if (pendingCandidates.isEmpty()) return
        listener?.onComposing(text)
        finalizeRound()
    }

    /**
     * 收尾本轮：把组合文本定为正式文本（保留在输入框里），并收起顶栏候选。
     *
     * 这是「开始写下一个字 / 空格 / 回车 / 标点」共用的那一句 —— 它们在语义上都是
     * 「上一个字结束了，接下来要做别的事」。落笔那条路径见 [init] 里的 `onStrokeStarted`。
     */
    private fun finalizeRound() {
        if (pendingCandidates.isEmpty()) return
        listener?.onFinalizeComposing()
        clearCandidates()
    }

    // ------------------------------------------------------------------
    // 布局
    // ------------------------------------------------------------------

    /**
     * 按当前形态重建内容。书写区在两种形态里是同一个 [ink]，不能同时挂在两处，
     * 所以切换形态时整块重建，而不是切换两个布局的可见性。
     *
     * **摘书写区这一步不能省**：`removeAllViews()` 只摘掉面板的**直接**子视图
     * （半屏时是一层 LinearLayout，书写区是它的孙子），书写区仍然挂在那层容器上。
     * 此时直接 `addView(ink, …)` 会命中 ViewGroup 的「The specified child already has a
     * parent」检查，抛 IllegalStateException —— 点击事件跑在消息循环里，异常一路冒到
     * 线程默认处理器，**整个输入法进程当场挂掉**：表现就是真机上按「半/全」后键盘
     * 整块消失、写不了字，过一会儿系统重启输入法后（引擎还没加载完，槽回落成 26 键）
     * 键盘才又回来、而且不是手写形态。
     */
    private fun rebuildContent() {
        // 符号栏每次都重读偏好：它是「设置 → 侧栏符号」那份列表，改了要跟着变
        punctuationSymbols = KeyboardManager.Keyboard.SidePanelSymbols.getT9(context)
        // 键帽会跟着整块重来（它们的颜色是建键时注入的），所以这里只重置「不是键」的引用：
        // 底部三条的容器若留着旧引用，[applyColors] 会给一个脱离视图树的视图上色，
        // 看起来就是「换了主题底部还是旧底色」
        fullScreenStack = null
        returnKeyView = null
        // 键都被丢掉了，长按连删也要停：否则换形态后手指还没抬，退格会继续跑。
        // 键视图自己会处理（CustomGestureView 脱离窗口、或跟着面板一起变不可见时收掉重复触发）。
        // 先把书写区从旧父容器上摘下来；它可能挂在面板自己（全屏形态）或半屏的
        // LinearLayout 上，两种情况都要处理，所以对 parent 泛化地 remove
        (ink.parent as? ViewGroup)?.removeView(ink)
        removeAllViews()
        if (fullScreenLayout) {
            addView(ink, LayoutParams(MATCH_PARENT, MATCH_PARENT))
            addView(
                buildFullScreenBottomStack(),
                LayoutParams(MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM),
            )
        } else {
            addView(buildContent(), LayoutParams(MATCH_PARENT, MATCH_PARENT))
        }
        lastColors?.let { applyColors(it) }
    }

    /** 竖着两段：上面「书写区 + 标点栏」（吃满剩余高度），下面固定高的功能行。 */
    private fun buildContent(): View = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        addView(buildMiddleRow(), lp(MATCH_PARENT, 0, weight = 1f))
        addView(buildFunctionBar(), lp(MATCH_PARENT, dp(HANDWRITING_KEY_ROW_HEIGHT_DP)))
    }

    /**
     * 全屏时的底部三条：顶栏占位 + 标点行 + 功能行。
     *
     * 顶栏（候选/工具条）是 `KawaiiPanel.view`，由宿主按 [HANDWRITING_FULL_SCREEN_STACK_DP]
     * 摆在最上面那条的位置；这里只留一块空白占位，**不能画任何东西** —— 否则两边会抢同一块像素。
     *
     * 这三条整块是「键盘区域」，底色由 [applyColors] 刷成不透明的键盘底（见 [fullScreenStack]），
     * 底部还留出导航栏 + 键盘底部内边距（见 [fullScreenBottomSpacePx]）。
     */
    private fun buildFullScreenBottomStack(): View = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        // 导航栏 + 键盘底部内边距靠内边距留出来，底色由 [applyColors] 刷成键盘底色
        // （见 [fullScreenBottomSpacePx]）。吃掉这一条上的触摸：留空的话手指会落到下面的书写层上，
        // 在导航栏区域画出一条看不见的笔迹。
        //
        // 左右还要留出键盘的「边距」偏好：半屏时面板本身已经被宿主按这份边距收窄了
        // （见 KeyboardWindowView 的 contentLeft / contentW），整屏时面板铺满整窗、没有这层收窄
        // —— 不补上，整屏的键会比半屏的宽一圈、两侧也更贴边。
        setPadding(horizontalPaddingPx, 0, horizontalPaddingPx, fullScreenBottomSpacePx)
        isClickable = true
        addView(Space(context), lp(MATCH_PARENT, dp(HANDWRITING_ROW_HEIGHT_DP)))
        addView(buildFullScreenPunctuationRow(), lp(MATCH_PARENT, dp(HANDWRITING_KEY_ROW_HEIGHT_DP)))
        addView(buildFunctionBar(), lp(MATCH_PARENT, dp(HANDWRITING_KEY_ROW_HEIGHT_DP)))
    }.also { fullScreenStack = it }

    /**
     * 全屏符号行：与半屏同一份符号列表，只是横过来排、可以左右滑；⌫ 固定在最右、**不参与滚动**。
     *
     * 半屏时符号竖排在右边、⌫ 也永远钉在同一个位置（见 [buildRail]）；全屏把符号横过来，
     * 但「⌫ 必须永远在同一个位置」这条不能变，所以它同样必须放在滚动容器之外。
     *
     * 标点键宽度写死（它在一条横向滚动带里，本来就与下面的功能行不同列），
     * 但 ⌫ 与功能行的回车键**占同一份权重**，于是最右边那一列宽度、高度都对得上。
     */
    private fun buildFullScreenPunctuationRow(): View = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL

        val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        for (key in buildPunctuationKeys()) {
            row.addView(
                buildKey(punctuationKeyDef(key), key.label),
                lp(dp(PUNCTUATION_KEY_WIDTH_DP), MATCH_PARENT),
            )
        }

        // 标点多了在这里横向滚动；滚动条在 IME 里视觉上很脏，关掉
        val scroll = HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            overScrollMode = OVER_SCROLL_NEVER
            addView(
                row,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, MATCH_PARENT,
                ),
            )
        }
        addView(scroll, lp(0, MATCH_PARENT, weight = functionWeightSum - 1f))
        addView(buildBackspaceKey(), lp(0, MATCH_PARENT, weight = 1f))
    }

    /**
     * 书写区吃满剩余宽度，右栏占「回车」那一格。
     *
     * 两块都走权重、都不加 LayoutParams 边距。键与键之间的间隔由**主题的键帽内缩**给
     * （和键盘同一份）；这里一旦再补一层边距，这一行的可用宽度就变了，
     * 右栏那一列会比回车键宽出几个 dp —— 两列宽度对不上就是这么来的。
     *
     * 书写区自己的四周空档因此也不能用边距（那会改变可用宽度），改用一层不带权重的
     * 容器的 padding，既不参与分摊、又能和键帽留一样的空隙。
     */
    private fun buildMiddleRow(): View = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        val inkBox = FrameLayout(context).apply {
            setPadding(keyCapInsetH, keyCapInsetV, keyCapInsetH, keyCapInsetV)
            addView(ink, LayoutParams(MATCH_PARENT, MATCH_PARENT))
        }
        addView(inkBox, lp(0, MATCH_PARENT, weight = functionWeightSum - 1f))
        addView(buildRail(), lp(0, MATCH_PARENT, weight = 1f))
    }

    /**
     * 右侧标点栏：⌫ 固定在上面，标点在下面单独滚动。
     *
     * ⌫ 必须在滚动容器**之外**。这是上一版明确踩过的坑：⌫ 和标点放进同一个滚动列表后，
     * 标点一多、往下滚，⌫ 就被一起顶出屏幕 —— 而它是书写时唯一需要「永远在同一个位置」的键。
     *
     * 符号那半条直接用键盘九键的侧栏 [SidePanelKeyView]：滚动、惯性回弹、按压高亮、
     * 主题取色都与九键同源，不再自己搭 ScrollView + 一堆 TextView。
     */
    private fun buildRail(): View = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL

        // ⌫ 与下面的符号栏同宽（都是这一列的整幅），且 ⌫ 那一格的高度与功能行一样 ——
        // 于是它和回车键是**同样大小**的一块，两个方向都对得上。
        addView(buildBackspaceKey(), lp(MATCH_PARENT, dp(HANDWRITING_KEY_ROW_HEIGHT_DP)))

        val rail = SidePanelKeyView(
            context,
            keyColors,
            KeyDef.Appearance.SidePannel(
                visableRow = RAIL_FALLBACK_ITEMS,
                variant = Variant.Alternative,
                border = Border.Off,
            ),
        ).apply {
            updateItems(symbolItems())
            setOnItemActionListener { action -> onPanelAction(action) }
            // 一屏放几个符号由栏高决定，一格 [RAIL_ITEM_HEIGHT_DP]（与键帽同宽同高的方格子）；
            // 九键那边行数写死是因为它的栏高本来就是按行算出来的。
            addOnLayoutChangeListener { _, _, top, _, bottom, _, _, _, _ ->
                updateVisibleItemCount(
                    ((bottom - top) / dp(RAIL_ITEM_HEIGHT_DP)).coerceAtLeast(1),
                )
            }
        }
        addView(rail, lp(MATCH_PARENT, 0, weight = 1f))
    }

    /**
     * ⌫。长按连删、按下态、点击音效全部交给键盘的键视图
     * （[com.ninthsoft.ime.input.keyboard.key.CustomGestureView] 的 `repeatEnabled` +
     * `onRepeatListener`），面板不再自己排一套时序 —— 那套时序本来就和键盘差一点点。
     */
    private fun buildBackspaceKey(): KeyView = buildKey(
        def = KeyDef(
            appearance = KeyDef.Appearance.Image(
                src = R.drawable.ic_keyboard_backspace,
                variant = Variant.Alternative,
            ),
            behaviors = setOf(
                KeyDef.Behavior.Press(KeyboardAction.BackspaceAction),
                // 重复触发挂在 Press 的同一个动作上：按住不放＝一直退格
                KeyDef.Behavior.Repeat(KeyboardAction.BackspaceAction),
            ),
        ),
        description = context.getString(R.string.backspace),
    )

    /**
     * ⌫ 一键两用：**先撤销本次手写，没得撤才退格**。
     *
     * 手写时最常见的「删」其实是「我这一笔写错了 / 这个字不是我要的」，
     * 而这时候输入框里并没有东西可删 —— 如果 ⌫ 直接退格，用户会发现「删了别的字，
     * 我写的字还在」。所以按手上的状态分两种：
     * 还有笔迹、组合文本或候选 → 把本次手写整块撤掉（组合文本丢弃 + 笔迹 + 候选一起清）；
     * 什么都没有 → 才是真正的退格。
     *
     * 这里**只撤「还在手上」的这一轮**：已经收尾（正式上屏）的字不在 [pendingCandidates] 里，
     * 不会被这一按删掉 —— 与拼音里「⌫ 先删拼音、再删已上屏的字」一致。
     */
    private fun onBackspacePressed() {
        if (ink.hasInk || pendingCandidates.isNotEmpty()) {
            cancelScheduledRecognize()
            // 已经发出去的识别也作废：否则它稍后回来会把用户刚按 ⌫ 撤掉的那一轮又推进组合态。
            HandwritingEngineHolder.cancelPending()
            recognitionRound++
            ink.clearAll()
            // 组合文本也要丢掉：⌫ 的意图是「这一轮不要了」，不能把它留在输入框里。
            // 注意丢弃要在 clearCandidates 之前 —— 两者都读/写本轮状态，顺序反了不影响结果，
            // 但先丢组合再清候选与「先撤销手写、再清顶栏」的语义一致。
            if (pendingCandidates.isNotEmpty()) listener?.onDiscardComposing()
            clearCandidates()
            return
        }
        listener?.onBackspace()
    }

    /** 空格：先把当前组合收尾（保留这个字），再走空格动作。 */
    private fun onSpacePressed() {
        finalizeRound()
        listener?.onSpace()
    }

    /** 回车：同上，先收尾再执行输入框语义（搜索 / 发送 / 换行）。 */
    private fun onReturnPressed() {
        finalizeRound()
        listener?.onReturn()
    }

    /**
     * 一个符号键。成对符号走 `CommitPairAction`（光标停在中间），单符号走 `CommitAction`；
     * 两者都由 [onPanelAction] 先收尾本轮组合再上屏。
     */
    private fun punctuationKeyDef(key: PunctuationKey): KeyDef = KeyDef(
        appearance = KeyDef.Appearance.Text(
            displayText = key.label,
            textSize = PUNCTUATION_TEXT_SIZE_DP,
            variant = Variant.Alternative,
            // 手写不做全角/半角转换：符号列表里存的是什么就显示什么
            displayFollowsPunctuationMode = false,
        ),
        behaviors = setOf(
            KeyDef.Behavior.Press(
                key.close
                    ?.let { KeyboardAction.CommitPairAction(key.open, it) }
                    ?: KeyboardAction.CommitAction(key.open),
            ),
        ),
    )

    /** 符号栏的内容。半屏竖栏与全屏横排都从这一份来，两处不会走散。 */
    private fun symbolItems(): List<KeyDef> = buildPunctuationKeys().map(::punctuationKeyDef)

    private fun buildFunctionBar(): View = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        for (key in functionKeys) {
            val view = buildKey(key.def, key.description)
            if (key.isReturn) {
                returnKeyView = view as? ImageKeyView
                // 回车键的图标跟着输入框动作走：模板里是默认回车，建完按当前值补一次
                returnKeyView?.updateImage(returnKeyIcon())
            }
            addView(view, lp(0, MATCH_PARENT, weight = key.weight))
        }
    }

    /**
     * 建一个键盘同款键视图，并按 [KeyDef.behaviors] 接线。
     *
     * `viewId` 一个都不给：面板与键盘在**同一个窗口**里，键帽上那几个 id
     * （`button_lang` / `button_return` / `button_space`）一旦撞上，宿主的 `findViewById`
     * 会命中面板里的这个实例，键盘的空格键文案与回车键图标就被改坏了。
     *
     * 只接 `Press` 与 `Repeat`：手写面板没有按键气泡、也没有上滑次级输入，
     * 那两条交互的接线留在 `BaseKeyboard`。
     */
    private fun buildKey(def: KeyDef, description: String): KeyView =
        KeyViewFactory.create(context, keyColors, def).apply {
            contentDescription = description
            // 描边跟随键盘的同一条偏好，两边的键帽描边才不会各走各的
            borderStroke = KeyboardManager.Keyboard.KeyBorderStroke.isEnabled(context)
            def.behaviors.forEach { behavior ->
                when (behavior) {
                    is KeyDef.Behavior.Press ->
                        setOnClickListener { onPanelAction(behavior.action) }

                    is KeyDef.Behavior.Repeat -> {
                        repeatEnabled = true
                        onRepeatListener = { onPanelAction(behavior.action) }
                    }

                    else -> Unit
                }
            }
        }

    /**
     * 面板所有按键的统一出口：把 [KeyboardAction] 翻译成 [Listener] 调用。
     *
     * 功能行、⌫、符号栏都从这里走，「按什么键做什么」只有一处定义。面板自己的时序也落在这里：
     * 标点 / 空格 / 回车属于「下一个动作」，先收尾本轮组合；⌫ 先撤销本轮、没得撤才真退格。
     */
    private fun onPanelAction(action: KeyboardAction) {
        val target = listener
        when (action) {
            is KeyboardAction.LayoutSwitchAction -> target?.onSwitchKeyboard(action.target)

            // 中/英键切的是键盘**槽**、不是直接换键盘，理由见 [Listener.onSwitchLanguage]
            KeyboardAction.RotateSchema -> target?.onSwitchLanguage()

            KeyboardAction.ToggleHandwritingFullScreen -> target?.onToggleFullScreen()

            is KeyboardAction.CommitAction -> {
                finalizeRound()
                target?.onCommit(action.text)
            }

            is KeyboardAction.CommitPairAction -> {
                finalizeRound()
                target?.onCommitPair(action.open, action.close)
            }

            KeyboardAction.BackspaceAction -> onBackspacePressed()

            KeyboardAction.SpaceAction -> onSpacePressed()

            is KeyboardAction.ReturnAction -> onReturnPressed()

            else -> Unit
        }
    }

    /** 功能行的文字键。取色家族照旧用「特殊键」那套（与键盘的 `Variant.Alternative` 同一个）。 */
    private fun functionTextKey(
        label: String,
        action: KeyboardAction,
        variant: Variant = Variant.Alternative,
        border: Border = Border.Default,
    ): KeyDef = KeyDef(
        appearance = KeyDef.Appearance.Text(
            displayText = label,
            textSize = FUNCTION_TEXT_SIZE_DP,
            variant = variant,
            border = border,
            // 手写不做全角/半角转换：符号列表里存的是什么就显示什么
            displayFollowsPunctuationMode = false,
        ),
        behaviors = setOf(KeyDef.Behavior.Press(action)),
    )

    private fun functionIconKey(
        @DrawableRes iconRes: Int,
        action: KeyboardAction,
        variant: Variant = Variant.Alternative,
        border: Border = Border.Default,
    ): KeyDef = KeyDef(
        appearance = KeyDef.Appearance.Image(src = iconRes, variant = variant, border = border),
        behaviors = setOf(KeyDef.Behavior.Press(action)),
    )

    private fun lp(width: Int, height: Int, weight: Float = 0f): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(width, height, weight)

    /**
     * 建键用的配色。
     *
     * [KeyView] 的颜色是构造期注入的，所以主题一变就得重建键（见 [refreshColors]）；
     * 还没收到过配色时按偏好现算一次，免得首帧建出一堆无色键。
     */
    private val keyColors: KeyboardColors.ColorScheme
        get() = lastColors ?: KeyboardColors.resolve(context)

    /**
     * 键帽在视图里的内缩（px）。
     *
     * 面板不再自己加 LayoutParams 边距：键与键之间的间隔**就是**主题这份内缩（和键盘同一份），
     * 书写区四周的空档也照它留，这样书写区、右栏、功能行才落在同一个网格上。
     */
    private val keyCapInsetH: Int get() = dp(keyColors.keyHMargin).toInt()
    private val keyCapInsetV: Int get() = dp(keyColors.keyVMargin).toInt()

    /**
     * 键盘「边距」偏好里的左右内边距（px）。
     *
     * 半屏时宿主按这份值把手写面板收窄了（`contentLeft = hPad`），整屏时面板铺满整窗，
     * 得由面板自己给底部三条留出来，两个形态的键宽才会一致。
     */
    private val horizontalPaddingPx: Int
        get() = dp(KeyboardManager.Keyboard.Padding.getHorizontalDp(context))

    /**
     * 功能行所有键的权重和。
     *
     * 右栏与全屏标点行都按「拿走回车那一格、剩下的给别的」来分宽度，
     * 所以这个和是从 [functionKeys] 算出来的 —— 改了键表，三处的宽度会一起跟着变。
     */
    private val functionWeightSum: Float
        get() = functionKeys.sumOf { it.weight.toDouble() }.toFloat()

    // ------------------------------------------------------------------
    // 停手识别
    // ------------------------------------------------------------------

    /**
     * 抬笔后等 [recognizeDelayMs]（可在设置页调）再识别 —— 这个延迟就是「一个字写完了没有」的判据。
     * 没有它就只能一笔一画地去识别，候选会一路抖；用户再落笔时计时会被取消。
     */
    private fun scheduleRecognize() {
        removeCallbacks(recognizeRunnable)
        postDelayed(recognizeRunnable, recognizeDelayMs.toLong())
    }

    private fun cancelScheduledRecognize() {
        removeCallbacks(recognizeRunnable)
    }

    private fun recognizeNow() {
        // 空笔迹不打扰引擎：否则会把候选清成空，还可能把「引擎未就绪」这种提示炸出来
        if (!ink.hasInk) return
        // 本次识别的轮次号：结果回来时若已经对不上（中途按了 ⌫ 或收起了面板），整份结果作废
        val round = ++recognitionRound
        HandwritingEngineHolder.recognize(
            strokes = ink.snapshotStrokes(),
            writingAreaWidth = ink.writingAreaWidth,
            writingAreaHeight = ink.writingAreaHeight,
            nbest = NBEST,
        ) { candidates, error ->
            // 这一轮已经被撤销（⌫ / 收起面板）：既不进组合态也不推候选，否则会凭空多出一个字
            if (round != recognitionRound) return@recognize
            // 识别完清掉「已落定」的笔迹：连着写下一个字时不该还要先点「清除」。
            // 放在回调里而不是发请求时清，是为了让刚写完的那一笔在推理期间还留在屏幕上。
            // 只清已落定的那部分：识别要等一段停手时间，结果回来时用户很可能已经在下个字上落笔了，
            // 整块清空会把正在画的墨迹一起抹掉（还会让那一笔从此不再计入识别）。
            ink.clearRecognizedStrokes()
            // 连写时上一拍的识别结果可能晚于下一笔落下才回来：那时上一轮还挂在组合态。
            // 先把它收尾成正式文本，否则下面首候选的 setComposingText 会把那个字整段替换掉。
            if (pendingCandidates.isNotEmpty()) listener?.onFinalizeComposing()
            pendingCandidates = candidates
            if (candidates.isNotEmpty()) {
                // 能识别出东西就说明引擎此刻是可用的：把开面板时留下的「引擎不可用」提示擦掉，
                // 否则引擎后来恢复可用（例如在设置页点过重新检测）也还是一直挂着那句话
                ink.setHint(null)
                // 首候选先进入**组合态**（未上屏，和拼音 preedit 一样显示在输入框里），
                // 顶栏候选留着供用户换字；收尾时机见 [finalizeRound]。
                // 顺序是先写组合文本、再推候选：反过来的话用户会先看到顶栏亮出候选、
                // 再被输入框里的组合文本顶一下。
                listener?.onComposing(candidates.first().text)
                listener?.onCandidates(candidates)
            } else {
                listener?.onCandidates(emptyList())
                if (error != null) listener?.onRecognizeFailed(error)
            }
        }
    }

    // ------------------------------------------------------------------
    // 数据结构
    // ------------------------------------------------------------------

    /**
     * 一个功能键。键面（图标 / 文字 / 取色家族 / 描边）全在 [def] 里，和键盘共用一套描述；
     * [description] 只给无障碍用。动作不在这里 —— 它就在 [def] 的 `Press` 行为里，
     * 由 [onPanelAction] 统一解释。
     */
    private class FunctionKey(
        val def: KeyDef,
        val description: String,
        /** 回车键的图标跟随输入框动作（见 [setReturnKeyIcon]），建键时要留个引用。 */
        val isReturn: Boolean = false,
        /** 这一格在功能行里占的宽度权重。只有空格用非 1 的值（见 [SPACE_KEY_WEIGHT]）。 */
        val weight: Float = 1f,
    )

    /** 一个标点键。[close] 为 null 表示单字符。 */
    private class PunctuationKey(val open: String, val close: String? = null) {
        /** 键面：成对符号显示两个字符，单字符就是它自己。 */
        val label: String get() = if (close == null) open else open + close
    }

    private companion object {
        /** 要几个候选。6 个大致是顶栏一行放得下、又不用翻页的数量。 */
        const val NBEST = 6

        /**
         * 符号栏里一格的高度（dp）。
         *
         * 换成 [SidePanelKeyView] 后一格的疏密由「栏高 ÷ 可见格数」决定，这个值就是拿它
         * 反算可见格数的尺子：一格约等于一个键帽高，符号不会泡在大片空白里。
         */
        const val RAIL_ITEM_HEIGHT_DP = 48

        /** 符号栏可见格数的初值。真正的格数在第一次布局后按栏高算（见 [buildRail]）。 */
        const val RAIL_FALLBACK_ITEMS = 5

        /**
         * 全屏标点行里每个标点键的宽度（dp）。
         *
         * 它是一条横向滚动带，本来就与下面的功能行不同列，所以宽度写死、键多了一眼看不全就滑；
         * ⌫ 不写死 —— 它占功能行「回车」那一格的权重，两行最右边那一列才对得齐。
         */
        const val PUNCTUATION_KEY_WIDTH_DP = 52

        /**
         * 全屏遮罩的两种颜色，都是约 6% 不透明度。
         *
         * 暗色主题刷淡白（`rgba(255,255,255,.06)`）、亮色主题刷淡黑（`rgba(0,0,0,.06)`）：
         * 目的只是「让笔迹和划过的区域与下方内容区分开」，不能压到看不清应用本身
         * —— 全屏手写时用户还要看着输入框里的字。
         */
        const val SCRIM_ON_DARK = 0x0FFFFFFF
        const val SCRIM_ON_LIGHT = 0x0F000000

        /** 背景亮度低于它就算暗色主题（0..255）。 */
        const val DARK_LUMINANCE = 128f

        const val FUNCTION_TEXT_SIZE_DP = 16f

        /**
         * 空格键在功能行里占的宽度权重（其余键都是 1）。
         *
         * 1.6 ≈ 主流输入法空格相对普通键的宽度比；**不能再大** —— 功能行一共 6 格，
         * 空格吃掉的每一分宽度都是从其余 5 个键身上扣的，扣到 44dp 以下「半/全」就写不下了。
         */
        const val SPACE_KEY_WEIGHT = 1.6f

        /** 符号的字号。符号栏一格只有一个键帽那么大，字小了整栏看着空、像没画完。 */
        const val PUNCTUATION_TEXT_SIZE_DP = 18f

        const val MATCH_PARENT = ViewGroup.LayoutParams.MATCH_PARENT
    }
}
