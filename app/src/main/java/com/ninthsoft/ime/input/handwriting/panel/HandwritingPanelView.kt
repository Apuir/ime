package com.ninthsoft.ime.input.handwriting.panel

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PorterDuff
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Space
import android.widget.TextView
import com.ninthsoft.ime.R
import com.ninthsoft.ime.data.keyboard.theme.KeyboardColors
import com.ninthsoft.ime.data.manager.KeyboardManager
import com.ninthsoft.ime.input.handwriting.HandwritingEngineHolder
import com.ninthsoft.ime.input.handwriting.HandwritingManager
import com.ninthsoft.ime.input.handwriting.HwCandidate
import com.ninthsoft.ime.input.keyboard.key.CustomGestureView
import com.ninthsoft.ime.input.keyboard.impl.NumberKeyboard
import com.ninthsoft.ime.input.keyboard.impl.SymbolKeyboard
import splitties.dimensions.dp

/**
 * 手写面板每一条横排控件的高度（dp）。
 *
 * 半屏形态下只有底部一条功能行；全屏形态下是「顶栏占位 + 标点行 + 功能行」三条。
 * 宿主 [com.ninthsoft.ime.input.keyboard.window.KeyboardWindowView] 摆最上面那条顶栏时
 * 要用同一份值，所以放在文件级而不是 private companion 里 —— 两处各写一个 48，
 * 迟早会对不上。
 */
const val HANDWRITING_ROW_HEIGHT_DP = 48

/** 全屏手写底部三条的总高度（dp）。 */
const val HANDWRITING_FULL_SCREEN_STACK_DP = HANDWRITING_ROW_HEIGHT_DP * 3

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
 * - **不自己画键**。全部用标准 View/Layout 搭：手绘 canvas 键盘那种实现，键位、命中区、
 *   按下态都要自己维护，是上一版「布局与交互不顺手」的根源。
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

    /**
     * 所有需要跟着主题换色的按键。
     *
     * 集中登记而不是在 [refreshColors] 里逐个字段去摸：加一个键只需要在建键时
     * [registerKey] 一次，不会再出现「新键忘了刷新主题」这种漏色。
     */
    private val themedKeys = ArrayList<KeyRef>()

    /** 停手计时。用一个常驻 Runnable 反复 post/remove，避免每次抬笔都分配一个 lambda。 */
    private val recognizeRunnable = Runnable { recognizeNow() }

    /**
     * 退格长按连删。触发时序照抄键盘键的 [CustomGestureView]
     * （[CustomGestureView.longPressDelay] 起、[CustomGestureView.RepeatInterval] 一次）：
     * 同一个动作在键盘和手写面板上手感必须一致，所以引用它的常量而不是各写一份。
     */
    private var backspaceRepeating = false
    private val backspaceRepeatHandler = Handler(Looper.getMainLooper())
    private val backspaceRepeatRunnable = object : Runnable {
        override fun run() {
            backspaceRepeating = true
            onBackspacePressed()
            backspaceRepeatHandler.postDelayed(this, CustomGestureView.RepeatInterval)
        }
    }

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

    /** 底部功能行。写成数据表而不是六段重复代码：加键只需在这里添一行。 */
    private val functionKeys: List<FunctionKey> = listOf(
        // 键面用文字而不是图标：符号图标画出来是一堆「!?#」，在这个位置反而不如两个字清楚
        FunctionKey("符号", label = "符号") {
            it.onSwitchKeyboard(SymbolKeyboard.NAME)
        },
        // 键面用键盘上同一枚图标：中/英键在哪个键盘上都是这个地球标，
        // 只有手写这里曾经写成文字「中/英」，看着像另一个东西
        FunctionKey("中英切换", iconRes = R.drawable.ic_keyboard_language) {
            it.onSwitchLanguage()
        },
        // 空格 / 回车 / 标点都属于「下一个动作」：先收尾当前组合（保留这个字），再执行动作
        FunctionKey("空格", iconRes = R.drawable.ic_keyboard_space) { onSpacePressed() },
        // 半/全放在空格与 123 之间：左边是「输入」类键（符号/中英/空格），
        // 右边是「换键盘」类键（123/回车），范围切换贴着空格这一侧最顺手
        FunctionKey("切换手写范围", label = "半/全") { it.onToggleFullScreen() },
        FunctionKey("数字", label = "123") { it.onSwitchKeyboard(NumberKeyboard.NAME) },
        FunctionKey("回车", iconRes = R.drawable.ic_keyboard_search, accent = true) { onReturnPressed() },
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

    /** 面板隐藏。已经发出去的识别也一并作废，免得结果回来时往一个收起来的界面里推候选。 */
    fun onHidden() {
        cancelScheduledRecognize()
        stopBackspaceRepeat()
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
        fullScreenStack?.setPadding(0, 0, 0, px)
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
        lastColors = colors
        applyColors(colors)
    }

    /**
     * 真正上色。与 [refreshColors] 分开是因为切半/全要重建布局：新键帽建出来后
     * 必须拿最近一次的配色再刷一遍，否则它们的底色/文字色是空的。
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
        val radius = dp(colors.cornerRadius)
        for (ref in themedKeys) styleKey(ref, colors, radius)
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
        // 重建会把 themedKeys 里登记的旧视图一起丢掉，所以先清空登记表再重新登记，
        // 否则会拿着一批已经脱离视图树的键去上色
        themedKeys.clear()
        // 底部三条的容器同样会被丢掉：留着旧引用会让 [applyColors] 给一个脱离视图树的
        // 视图上色，看起来「换了主题底部还是旧底色」
        fullScreenStack = null
        // 键都被丢掉了，长按连删也必须停：否则换形态后手指还没抬，退格会继续跑
        stopBackspaceRepeat()
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
        addView(buildFunctionBar(), lp(MATCH_PARENT, dp(FUNCTION_BAR_HEIGHT_DP)))
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
        setPadding(0, 0, 0, fullScreenBottomSpacePx)
        isClickable = true
        addView(Space(context), lp(MATCH_PARENT, dp(HANDWRITING_ROW_HEIGHT_DP)))
        addView(buildFullScreenPunctuationRow(), lp(MATCH_PARENT, dp(HANDWRITING_ROW_HEIGHT_DP)))
        addView(buildFunctionBar(), lp(MATCH_PARENT, dp(HANDWRITING_ROW_HEIGHT_DP)))
    }.also { fullScreenStack = it }

    /**
     * 全屏符号行：与半屏同一份符号列表，只是横过来排、可以左右滑；⌫ 固定在最右、**不参与滚动**。
     *
     * 半屏时符号竖排在右边、⌫ 也永远钉在同一个位置（见 [buildRail]）；全屏把符号横过来，
     * 但「⌫ 必须永远在同一个位置」这条不能变，所以它同样必须放在滚动容器之外。
     */
    private fun buildFullScreenPunctuationRow(): View = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL

        val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        for (key in buildPunctuationKeys()) {
            row.addView(
                buildPunctuationKey(key),
                lp(dp(PUNCTUATION_KEY_WIDTH_DP), MATCH_PARENT)
                    .apply { setMargins(keyMargin, keyMargin, keyMargin, keyMargin) },
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
        addView(scroll, lp(0, MATCH_PARENT, weight = 1f))

        addView(
            buildBackspaceKey(),
            lp(dp(FULL_SCREEN_BACKSPACE_WIDTH_DP), MATCH_PARENT)
                .apply { setMargins(keyMargin, keyMargin, keyMargin, keyMargin) },
        )
    }

    /** 书写区吃满剩余宽度，标点栏固定 [RAIL_WIDTH_DP]。 */
    private fun buildMiddleRow(): View = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        addView(ink, lp(0, MATCH_PARENT, weight = 1f))
        addView(buildRail(), lp(dp(RAIL_WIDTH_DP), MATCH_PARENT))
    }

    /**
     * 右侧标点栏：⌫ 固定在上面，标点在下面单独滚动。
     *
     * ⌫ 必须在滚动容器**之外**。这是上一版明确踩过的坑：⌫ 和标点放进同一个滚动列表后，
     * 标点一多、往下滚，⌫ 就被一起顶出屏幕 —— 而它是书写时唯一需要「永远在同一个位置」的键。
     */
    private fun buildRail(): View = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL

        addView(
            buildBackspaceKey(),
            lp(MATCH_PARENT, dp(RAIL_KEY_HEIGHT_DP)).apply { setMargins(keyMargin, keyMargin, keyMargin, keyMargin) },
        )

        val column = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        for (key in buildPunctuationKeys()) {
            column.addView(
                buildPunctuationKey(key),
                lp(MATCH_PARENT, dp(RAIL_KEY_HEIGHT_DP)).apply { setMargins(keyMargin, keyMargin, keyMargin, keyMargin) },
            )
        }

        // 标点多的时候这里滚动；滚动条与边缘光晕在 IME 里视觉上很脏，都关掉
        val scroll = ScrollView(context).apply {
            isFillViewport = false
            isVerticalScrollBarEnabled = false
            overScrollMode = OVER_SCROLL_NEVER
            addView(column, LayoutParams(MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        addView(scroll, lp(MATCH_PARENT, 0, weight = 1f))
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun buildBackspaceKey(): View = ImageView(context).apply {
        setImageResource(R.drawable.ic_keyboard_backspace)
        scaleType = ImageView.ScaleType.CENTER
        contentDescription = context.getString(R.string.backspace)
        setPadding(iconPadding, iconPadding, iconPadding, iconPadding)
        // 长按连删：按住不放就按 [CustomGestureView] 的节奏一直退格。
        // 长按已经触发过时不再补一次点击，否则抬手那一下会多删一个字。
        setOnClickListener { if (!backspaceRepeating) onBackspacePressed() }
        setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> startBackspaceRepeat()
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> stopBackspaceRepeat()
            }
            false
        }
    }.also { registerKey(it, KeyStyle.Normal) }

    private fun startBackspaceRepeat() {
        stopBackspaceRepeat()
        backspaceRepeating = false
        backspaceRepeatHandler.postDelayed(backspaceRepeatRunnable, CustomGestureView.longPressDelay)
    }

    private fun stopBackspaceRepeat() {
        backspaceRepeatHandler.removeCallbacks(backspaceRepeatRunnable)
    }

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

    private fun buildPunctuationKey(key: PunctuationKey): View = TextView(context).apply {
        text = key.label
        gravity = Gravity.CENTER
        setTextSize(TypedValue.COMPLEX_UNIT_SP, PUNCTUATION_TEXT_SIZE_SP)
        isSingleLine = true
        setOnClickListener {
            val target = listener ?: return@setOnClickListener
            // 标点是「下一个动作」：先把手上这个字收尾，再上屏标点，用户看到的是
            // 「字 + 标点」而不是「标点把组合文本替换掉」。
            finalizeRound()
            val close = key.close
            if (close == null) target.onCommit(key.open) else target.onCommitPair(key.open, close)
        }
    }.also { registerKey(it, KeyStyle.Normal) }

    private fun buildFunctionBar(): View = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        for (key in functionKeys) {
            val button = if (key.iconRes != 0) buildIconKey(key) else buildTextKey(key)
            button.setOnClickListener {
                val target = listener ?: return@setOnClickListener
                key.action(target)
            }
            registerKey(button, if (key.accent) KeyStyle.Accent else KeyStyle.Special)
            addView(button, lp(0, MATCH_PARENT, weight = 1f).apply { setMargins(keyMargin, keyMargin, keyMargin, keyMargin) })
        }
    }

    private fun buildIconKey(key: FunctionKey): View = ImageView(context).apply {
        setImageResource(key.iconRes)
        scaleType = ImageView.ScaleType.CENTER
        contentDescription = key.description
        setPadding(iconPadding, iconPadding, iconPadding, iconPadding)
    }

    private fun buildTextKey(key: FunctionKey): View = TextView(context).apply {
        text = key.label
        gravity = Gravity.CENTER
        setTextSize(TypedValue.COMPLEX_UNIT_SP, FUNCTION_TEXT_SIZE_SP)
        isSingleLine = true
    }

    private fun lp(width: Int, height: Int, weight: Float = 0f): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(width, height, weight)

    private val keyMargin: Int get() = dp(KEY_MARGIN_DP)
    private val iconPadding: Int get() = dp(ICON_PADDING_DP)

    // ------------------------------------------------------------------
    // 主题
    // ------------------------------------------------------------------

    private fun registerKey(view: View, style: KeyStyle) {
        themedKeys += KeyRef(view, style)
    }

    /**
     * 按「取色家族」上色。
     *
     * 底色 + 按下态 + 内容色三件套对文字键和图标键是一样的，只有「内容色怎么抹上去」不同
     * （文字是 setTextColor，图标是 tint），所以在这里分一次就够。
     */
    private fun styleKey(ref: KeyRef, colors: KeyboardColors.ColorScheme, radius: Float) {
        val palette = when (ref.style) {
            KeyStyle.Normal -> KeyPalette(colors.keyBackground, colors.keyPressed, colors.keyText)
            KeyStyle.Special -> KeyPalette(
                colors.specialKeyBackground, colors.specialKeyPressed, colors.specialKeyText,
            )
            KeyStyle.Accent -> KeyPalette(
                colors.accentKeyBackground, colors.accentKeyPressed, colors.accentKeyText,
            )
        }
        ref.view.background = pressedAwareBackground(palette, radius)
        when (val view = ref.view) {
            is TextView -> view.setTextColor(palette.content)
            is ImageView -> view.setColorFilter(palette.content, PorterDuff.Mode.SRC_IN)
        }
    }

    /**
     * 圆角底色 + 按下态。
     *
     * 用 [StateListDrawable] 挂 View 自己的 `pressed` 状态，而不是自己写 onTouch 切色：
     * 点击、按住、手指滑出键外再抬起（ACTION_CANCEL）这几条路径，系统都已经维护好了
     * pressed，手写 touch 逻辑最容易漏的正是最后那条，会把键卡在按下色上。
     */
    private fun pressedAwareBackground(palette: KeyPalette, radius: Float): Drawable =
        StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_pressed), roundedRect(palette.pressed, radius))
            addState(intArrayOf(), roundedRect(palette.background, radius))
        }

    private fun roundedRect(color: Int, radius: Float): Drawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = radius
        setColor(color)
    }

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
     * 一个功能键。图标键与文字键共用一份数据，[iconRes] 为 0 时退化成文字键。
     */
    private class FunctionKey(
        val description: String,
        val label: String = "",
        val iconRes: Int = 0,
        val accent: Boolean = false,
        val action: (Listener) -> Unit,
    )

    /** 一个标点键。[close] 为 null 表示单字符。 */
    private class PunctuationKey(val open: String, val close: String? = null) {
        /** 键面：成对符号显示两个字符，单字符就是它自己。 */
        val label: String get() = if (close == null) open else open + close
    }

    /** 按键取色的三个家族，对应主题里的三套底色/文字色。 */
    private enum class KeyStyle { Normal, Special, Accent }

    private class KeyRef(val view: View, val style: KeyStyle)

    private class KeyPalette(val background: Int, val pressed: Int, val content: Int)

    private companion object {
        /** 要几个候选。6 个大致是顶栏一行放得下、又不用翻页的数量。 */
        const val NBEST = 6

        const val RAIL_WIDTH_DP = 44
        const val RAIL_KEY_HEIGHT_DP = 44

        /**
         * 半屏形态下功能行的高度。
         *
         * 直接引用文件级的行高：半屏的功能行与全屏的顶栏/标点行/功能行必须一样高，
         * 否则切范围时底部会跳一下。
         */
        const val FUNCTION_BAR_HEIGHT_DP = HANDWRITING_ROW_HEIGHT_DP

        /**
         * 全屏标点行里每个标点键的宽度（dp）。
         *
         * 6 个标点 + ⌫ 加内外边距合计约 356dp，360dp 宽的机器上一屏放得下、不用滑；
         * 更窄的机器（320dp）才需要横向滚动 —— 这正是「标点可滚、⌫ 不滚」要兜的情况。
         */
        const val PUNCTUATION_KEY_WIDTH_DP = 46

        /** 全屏标点行里 ⌫ 的宽度（dp）。比标点键略宽，因为它是这一行唯一的图标键。 */
        const val FULL_SCREEN_BACKSPACE_WIDTH_DP = 52

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

        const val KEY_MARGIN_DP = 2
        const val ICON_PADDING_DP = 10
        const val FUNCTION_TEXT_SIZE_SP = 16f
        const val PUNCTUATION_TEXT_SIZE_SP = 15f

        const val MATCH_PARENT = ViewGroup.LayoutParams.MATCH_PARENT
    }
}
