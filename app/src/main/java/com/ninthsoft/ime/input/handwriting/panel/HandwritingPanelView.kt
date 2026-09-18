package com.ninthsoft.ime.input.handwriting.panel

import android.content.Context
import android.graphics.PorterDuff
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.ninthsoft.ime.R
import com.ninthsoft.ime.data.keyboard.theme.KeyboardColors
import com.ninthsoft.ime.input.handwriting.HandwritingEngineHolder
import com.ninthsoft.ime.input.handwriting.HwCandidate
import com.ninthsoft.ime.input.keyboard.impl.NumberKeyboard
import com.ninthsoft.ime.input.keyboard.impl.QwertyKeyboard
import com.ninthsoft.ime.input.keyboard.impl.SymbolKeyboard
import splitties.dimensions.dp

/**
 * 手写面板：左边书写区、右边竖排标点栏、底部一条功能行。
 *
 * 这个类只负责「排版 + 手势时序 + 把结果交给宿主」，三条硬边界：
 * - **不上屏**。提交 / 退格 / 空格 / 回车全部走 [Listener] 回调，宿主再转成
 *   `KeyboardAction` —— 面板里绝不碰 `InputConnection`，否则光标、组合态、
 *   上屏埋点这些规则会在两个地方各写一遍。
 * - **不读偏好**。半/全、引擎模式都是宿主的决定，这里只认 [refreshColors] 给的颜色。
 * - **不自己画键**。全部用标准 View/Layout 搭：手绘 canvas 键盘那种实现，键位、命中区、
 *   按下态都要自己维护，是上一版「布局与交互不顺手」的根源。
 *
 * 停手识别：抬笔后 [RECOGNIZE_DELAY_MS] 没有新笔画就送引擎，识别完自动清空笔迹
 * （不需要用户点「清除/撤销」）。
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

        /** 单字 / 单词上屏。 */
        fun onCommit(text: String)

        /** 成对标点：宿主走 `KeyboardAction.CommitPairAction`，让光标停在中间。 */
        fun onCommitPair(open: String, close: String)

        fun onBackspace()

        fun onSpace()

        fun onReturn()

        /** 切到别的键盘。参数取 `SymbolKeyboard.NAME` / `NumberKeyboard.NAME` / `QwertyKeyboard.NAME`。 */
        fun onSwitchKeyboard(name: String)

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
     * 顶栏当前显示的手写候选数。
     *
     * 用来判断「本次手写还在不在手上」：只要还有笔迹或候选，⌫ 就该先撤销本次手写，
     * 而不是去删输入框里已经上屏的字。
     */
    private var candidateCount = 0

    /** 底部功能行。写成数据表而不是五段重复代码：下一步加「半/全」键只需在这里添一行。 */
    private val functionKeys: List<FunctionKey> = listOf(
        // 键面用文字而不是图标：符号图标画出来是一堆「!?#」，在这个位置反而不如两个字清楚
        FunctionKey("符号", label = "符号") {
            it.onSwitchKeyboard(SymbolKeyboard.NAME)
        },
        FunctionKey("中英切换", label = "中/英") {
            it.onSwitchKeyboard(QwertyKeyboard.NAME)
        },
        FunctionKey("空格", iconRes = R.drawable.ic_keyboard_space) { it.onSpace() },
        FunctionKey("数字", label = "123") { it.onSwitchKeyboard(NumberKeyboard.NAME) },
        FunctionKey("回车", iconRes = R.drawable.ic_keyboard_search, accent = true) { it.onReturn() },
    )

    /** 标点栏。单字符直接上屏，成对符号走 [Listener.onCommitPair]。 */
    private val punctuationKeys: List<PunctuationKey> = listOf(
        PunctuationKey("，"),
        PunctuationKey("。"),
        PunctuationKey("？"),
        PunctuationKey("！"),
        PunctuationKey("、"),
        PunctuationKey("；"),
        PunctuationKey("："),
        PunctuationKey("“", "”"),
        PunctuationKey("‘", "’"),
        PunctuationKey("（", "）"),
        PunctuationKey("《", "》"),
        PunctuationKey("【", "】"),
    )

    init {
        addView(
            buildContent(),
            LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
        )

        ink.listener = object : HandwritingInkView.Listener {
            override fun onStrokeStarted() {
                // 用户又落笔了：上一拍的停手计时作废，否则会在他字还没写完时就去识别半截
                cancelScheduledRecognize()
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
     * 只会让人以为「字还在」而误上屏。
     */
    fun onShown() {
        cancelScheduledRecognize()
        ink.setHint(null)
        ink.clearAll()
        clearCandidates()
        // 引擎加载/下载要几十毫秒到几十秒（首次可能触发模型下载），所以放在后台，
        // 失败时才把原因显示在书写区 —— 不挡住面板，用户仍可切走。
        HandwritingEngineHolder.ensureReady(context) { name, hint ->
            if (name == null) showHint(hint ?: "手写引擎不可用")
        }
    }

    /** 面板隐藏。已经发出去的识别也一并作废，免得结果回来时往一个收起来的界面里推候选。 */
    fun onHidden() {
        cancelScheduledRecognize()
        HandwritingEngineHolder.cancelPending()
        ink.clearAll()
    }

    /** 按主题上色。宿主在主题变化时调用；不调也能用（退化成中性色）。 */
    fun refreshColors(colors: KeyboardColors.ColorScheme) {
        setBackgroundColor(colors.background)
        // 墨迹用 keyText：它在任何主题里都是对比度最高的内容色；提示语退一档用 altText
        ink.setColors(colors.keyText, colors.altText)
        val radius = dp(colors.cornerRadius)
        for (ref in themedKeys) styleKey(ref, colors, radius)
    }

    /** 在书写区显示一句提示（识别失败、引擎不可用）。下一次显示面板时会被重置。 */
    fun showHint(text: String) {
        ink.setHint(text)
        // 提示要盖在空画布上才看得见，所以顺手清掉残留笔迹
        ink.clearAll()
    }

    /** 清掉顶栏候选，并把「本次手写还在手上」的计数归零。 */
    fun clearCandidates() {
        candidateCount = 0
        listener?.onCandidates(emptyList())
    }

    // ------------------------------------------------------------------
    // 布局
    // ------------------------------------------------------------------

    /** 竖着两段：上面「书写区 + 标点栏」（吃满剩余高度），下面固定高的功能行。 */
    private fun buildContent(): View = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        addView(buildMiddleRow(), lp(MATCH_PARENT, 0, weight = 1f))
        addView(buildFunctionBar(), lp(MATCH_PARENT, dp(FUNCTION_BAR_HEIGHT_DP)))
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
        for (key in punctuationKeys) {
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

    private fun buildBackspaceKey(): View = ImageView(context).apply {
        setImageResource(R.drawable.ic_keyboard_backspace)
        scaleType = ImageView.ScaleType.CENTER
        contentDescription = context.getString(R.string.backspace)
        setPadding(iconPadding, iconPadding, iconPadding, iconPadding)
        setOnClickListener { onBackspacePressed() }
    }.also { registerKey(it, KeyStyle.Normal) }

    /**
     * ⌫ 一键两用：**先撤销本次手写，没得撤才退格**。
     *
     * 手写时最常见的「删」其实是「我这一笔写错了 / 这个字不是我要的」，
     * 而这时候输入框里并没有东西可删 —— 如果 ⌫ 直接退格，用户会发现「删了别的字，
     * 我写的字还在」。所以按手上的状态分两种：
     * 还有笔迹或候选 → 把本次手写整块撤掉（笔迹 + 候选一起清）；
     * 什么都没有 → 才是真正的退格。
     */
    private fun onBackspacePressed() {
        if (ink.hasInk || candidateCount > 0) {
            cancelScheduledRecognize()
            ink.clearAll()
            candidateCount = 0
            listener?.onCandidates(emptyList())
            return
        }
        listener?.onBackspace()
    }

    private fun buildPunctuationKey(key: PunctuationKey): View = TextView(context).apply {
        text = key.label
        gravity = Gravity.CENTER
        setTextSize(TypedValue.COMPLEX_UNIT_SP, PUNCTUATION_TEXT_SIZE_SP)
        isSingleLine = true
        setOnClickListener {
            val target = listener ?: return@setOnClickListener
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
     * 抬笔后等 [RECOGNIZE_DELAY_MS] 再识别 —— 这个延迟就是「一个字写完了没有」的判据。
     * 没有它就只能一笔一画地去识别，候选会一路抖；用户再落笔时计时会被取消。
     */
    private fun scheduleRecognize() {
        removeCallbacks(recognizeRunnable)
        postDelayed(recognizeRunnable, RECOGNIZE_DELAY_MS)
    }

    private fun cancelScheduledRecognize() {
        removeCallbacks(recognizeRunnable)
    }

    private fun recognizeNow() {
        // 空笔迹不打扰引擎：否则会把候选清成空，还可能把「引擎未就绪」这种提示炸出来
        if (!ink.hasInk) return
        HandwritingEngineHolder.recognize(
            strokes = ink.snapshotStrokes(),
            writingAreaWidth = ink.writingAreaWidth,
            writingAreaHeight = ink.writingAreaHeight,
            nbest = NBEST,
        ) { candidates, error ->
            // 识别完自动清空笔迹：连着写下一个字时不该还要先点「清除」。
            // 放在回调里而不是发请求时清，是为了让刚写完的那一笔在推理期间还留在屏幕上。
            ink.clearAll()
            candidateCount = candidates.size
            if (candidates.isNotEmpty()) {
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
        /**
         * 抬笔后停手多久算「写完一个字」。
         *
         * 偏短会把一个字的笔画切碎、识别出半截；偏长会觉得写完没反应。
         * 0.7s 是「连笔时的自然停顿」和「写完等结果」之间的折中。
         */
        const val RECOGNIZE_DELAY_MS = 700L

        /** 要几个候选。6 个大致是顶栏一行放得下、又不用翻页的数量。 */
        const val NBEST = 6

        const val RAIL_WIDTH_DP = 44
        const val RAIL_KEY_HEIGHT_DP = 44
        const val FUNCTION_BAR_HEIGHT_DP = 48
        const val KEY_MARGIN_DP = 2
        const val ICON_PADDING_DP = 10
        const val FUNCTION_TEXT_SIZE_SP = 16f
        const val PUNCTUATION_TEXT_SIZE_SP = 15f

        const val MATCH_PARENT = ViewGroup.LayoutParams.MATCH_PARENT
    }
}
