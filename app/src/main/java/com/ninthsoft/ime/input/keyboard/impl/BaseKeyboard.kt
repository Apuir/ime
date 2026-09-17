package com.ninthsoft.ime.input.keyboard.impl

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.view.inputmethod.EditorInfo
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.graphics.ColorUtils
import androidx.core.view.updateLayoutParams
import com.ninthsoft.ime.R
import com.ninthsoft.ime.data.PunctuationMode
import com.ninthsoft.ime.data.keyboard.theme.KeyboardColors
import com.ninthsoft.ime.data.manager.KeyboardKeyMapping
import com.ninthsoft.ime.data.manager.KeyboardManager
import com.ninthsoft.ime.input.keyboard.key.AltTextKeyView
import com.ninthsoft.ime.input.keyboard.key.CustomGestureView
import com.ninthsoft.ime.input.keyboard.key.HasKeyBubble
import com.ninthsoft.ime.input.keyboard.key.KeyBubbleItem
import com.ninthsoft.ime.input.keyboard.key.ImageKeyView
import com.ninthsoft.ime.input.keyboard.key.ImageTextKeyView
import com.ninthsoft.ime.input.keyboard.key.KeyboardAction
import com.ninthsoft.ime.input.keyboard.key.KeyActionListener
import com.ninthsoft.ime.input.keyboard.key.KeyDef
import com.ninthsoft.ime.input.keyboard.key.KeyPreviewPopup
import com.ninthsoft.ime.input.keyboard.key.KeyboardPopup
import com.ninthsoft.ime.input.keyboard.key.KeyView
import com.ninthsoft.ime.input.keyboard.key.KeyboardRippleView
import com.ninthsoft.ime.input.keyboard.key.SidePanelKeyView
import com.ninthsoft.ime.input.keyboard.key.TextKeyView
import android.util.Log
import kotlin.math.roundToInt
import splitties.dimensions.dp
import splitties.views.dsl.constraintlayout.above
import splitties.views.dsl.constraintlayout.below
import splitties.views.dsl.constraintlayout.bottomOfParent
import splitties.views.dsl.constraintlayout.centerVertically
import splitties.views.dsl.constraintlayout.constraintLayout
import splitties.views.dsl.constraintlayout.lParams
import splitties.views.dsl.constraintlayout.leftOfParent
import splitties.views.dsl.constraintlayout.leftToRightOf
import splitties.views.dsl.constraintlayout.rightOfParent
import splitties.views.dsl.constraintlayout.rightToLeftOf
import splitties.views.dsl.constraintlayout.topOfParent
import splitties.views.dsl.core.add
import splitties.views.imageResource
import timber.log.Timber

abstract class BaseKeyboard(
    context: Context,
    protected val colors: KeyboardColors.ColorScheme,
    /**
     * 布局工厂。用工厂而不是直接传 `List<List<KeyDef>>`，是因为 26 键 / 九键的键帽
     * 需要读用户的按键映射（26 键字母键下的符号、九键每个数字键下的字母），
     * 而 `buildLayout()` 只有在子类构造完成后才能安全地用 `context`。
     */
    private val keyLayoutFactory: (Context) -> List<List<KeyDef>>,
) : ConstraintLayout(context), IKeyboard {
    override var keyActionListener: KeyActionListener? = null
    var expandKeypressArea = false
    private val previewPopup = KeyPreviewPopup(context)
    private val keyboardPopup = KeyboardPopup(context)
    protected val keyRows: List<ConstraintLayout>
    val rippleView: KeyboardRippleView

    private var spanPanelViews: List<KeyView> = emptyList()
    private var spaceKeyView: TextKeyView? = null
    private var returnKeyView: ImageKeyView? = null
    private var returnKeyIcon: Int = 0

    /** 按键气泡开关：关闭后回到旧行为（长按 / 上滑直接上屏符号或数字）。 */
    private val keyBubbleEnabled: Boolean = KeyboardKeyMapping.isBubbleEnabled(context)

    /** 次级符号 / 数字的触发手势，与「按键手势」设置共用。 */
    private val swipeAltInput: Boolean = KeyboardManager.Keyboard.GestureInput.isSwipeUp(context)

    /**
     * 「上滑输入」的触发距离系数（阈值 = 当前键高 × 它，默认 1.0 = 整整一个键高）。
     *
     * 气泡路径与「直接上滑」路径共用同一个值，保证开不开气泡手感一致。
     */
    private val swipeUpRatio: Float = KeyboardManager.Keyboard.SwipeUp.getRatio(context)

    /**
     * 上滑的「纵向占主导」系数：纵向位移须 ≥ 横向位移 × 它才算上滑。
     *
     * 两条路径共用 —— 加上它之后，横滑时带的纵向漂移不会再被误判成上滑。
     */
    private val swipeUpDirectionTan: Float =
        KeyboardManager.Keyboard.SwipeUp.getDirectionTan(context)

    override fun updateSpaceKeyText(text: String) {
        spaceKeyView?.updateText(text)
    }

    protected open fun updateSidePanel(items: List<KeyDef>) {
        sidePanelView?.updateItems(items)
    }

    protected open fun resetSidePanelPosition() {
        sidePanelView?.resetPosition()
    }

    fun setSidePanelItemListener(listener: (KeyboardAction) -> Unit) {
        sidePanelView?.setOnItemActionListener(listener)
    }

    /** 左侧标点栏。span 列表里可能还有跨行的大回车等，这里只取侧栏。 */
    private val sidePanelView: SidePanelKeyView?
        get() = spanPanelViews.filterIsInstance<SidePanelKeyView>().firstOrNull()

    init {
        data class SpanDef(
            val def: KeyDef,
            val startRow: Int,
            val endRow: Int,
            val alignRight: Boolean,
        )

        val keyLayout = keyLayoutFactory(context)

        val spanDefs = mutableListOf<SpanDef>()
        for (ri in keyLayout.indices) {
            for (def in keyLayout[ri]) {
                val rowSpan = def.appearance.rowSpan
                if (rowSpan > 1) {
                    spanDefs.add(
                        SpanDef(
                            def,
                            ri,
                            (ri + rowSpan - 1).coerceAtMost(keyLayout.size - 1),
                            def.appearance.alignRight,
                        )
                    )
                }
            }
        }
        val spanViews = spanDefs.map { createKeyView(it.def).also { v -> v.id = generateViewId() } }
        spanPanelViews = spanViews

        keyRows = keyLayout.mapIndexed { rowIndex, row ->
            val parts = row.filterNot { it.appearance.rowSpan > 1 }
            val keyViews = parts.map(::createKeyView)
            // 该行被跨行键占掉的宽度：左侧标点栏 + 右侧大回车都要扣掉。
            val spanWidth = spanDefs
                .filter { rowIndex in it.startRow..it.endRow }
                .sumOf { it.def.appearance.percentWidth.toDouble() }
                .toFloat()
            val spanScale = if (spanWidth > 0f && spanWidth < 1f) 1f / (1f - spanWidth) else 1f

            constraintLayout {
                var totalWidth = 0f
                keyViews.forEachIndexed { index, view ->
                    add(view, lParams {
                        centerVertically()
                        if (index == 0) {
                            leftOfParent()
                            horizontalChainStyle = LayoutParams.CHAIN_PACKED
                        } else {
                            leftToRightOf(keyViews[index - 1])
                        }
                        if (index == keyViews.size - 1) {
                            rightOfParent()
                            horizontalChainStyle = LayoutParams.CHAIN_PACKED
                        } else {
                            rightToLeftOf(keyViews[index + 1])
                        }
                        matchConstraintPercentWidth =
                            parts[index].appearance.percentWidth * spanScale
                    })
                    (parts[index].appearance.percentWidth * spanScale).let {
                        totalWidth += if (it != 0f) it else 1f
                    }
                }
                if (expandKeypressArea && totalWidth < 1f) {
                    val free = (1f - totalWidth) / 2f
                    val firstScaledPercent = parts.first().appearance.percentWidth * spanScale
                    val lastScaledPercent = parts.last().appearance.percentWidth * spanScale
                    keyViews.first().apply {
                        updateLayoutParams<LayoutParams> {
                            matchConstraintPercentWidth += free
                        }
                        layoutMarginLeft = free / (firstScaledPercent + free)
                    }
                    keyViews.last().apply {
                        updateLayoutParams<LayoutParams> {
                            matchConstraintPercentWidth += free
                        }
                        layoutMarginRight = free / (lastScaledPercent + free)
                    }
                }
            }
        }
        keyRows.forEachIndexed { index, row ->
            add(row, lParams {
                if (index == 0) topOfParent()
                else below(keyRows[index - 1])
                if (index == keyRows.size - 1) bottomOfParent()
                else above(keyRows[index + 1])
                val covering =
                    spanDefs.indices.filter { index in spanDefs[it].startRow..spanDefs[it].endRow }
                val leftSpan = covering.lastOrNull { !spanDefs[it].alignRight }
                val rightSpan = covering.firstOrNull { spanDefs[it].alignRight }
                if (leftSpan != null) leftToRightOf(spanViews[leftSpan]) else leftOfParent()
                if (rightSpan != null) rightToLeftOf(spanViews[rightSpan]) else rightOfParent()
            })
        }

        for (i in spanDefs.indices) {
            val sd = spanDefs[i]
            add(spanViews[i], lParams {
                topToTop = keyRows[sd.startRow].id
                bottomToBottom = keyRows[sd.endRow].id
                if (sd.alignRight) rightOfParent() else leftOfParent()
                matchConstraintPercentWidth = sd.def.appearance.percentWidth
            })
        }

        rippleView = KeyboardRippleView(context).apply {
            id = generateViewId()
            isClickable = false
            isEnabled = false
            isFocusable = false
        }
        add(rippleView, lParams {
            topOfParent()
            bottomOfParent()
            leftOfParent()
            rightOfParent()
        })
    }

    @SuppressLint("ClickableViewAccessibility")
    protected fun createKeyView(def: KeyDef): KeyView {
        return when (def.appearance) {
            is KeyDef.Appearance.AltText -> AltTextKeyView(context, colors, def.appearance)
            is KeyDef.Appearance.ImageText -> ImageTextKeyView(context, colors, def.appearance)
            is KeyDef.Appearance.Text -> TextKeyView(context, colors, def.appearance)
            is KeyDef.Appearance.Image -> ImageKeyView(context, colors, def.appearance)
            is KeyDef.Appearance.SidePannel -> SidePanelKeyView(context, colors, def.appearance)
        }.apply {
            // 上滑触发距离对每个键统一下发。放在最前面是因为两条上滑路径都要用，
            // 而键高要到运行期（onTouchEvent）才拿得到，所以这里只给系数、不算距离。
            swipeUpRatio = this@BaseKeyboard.swipeUpRatio
            swipeUpDirectionTan = this@BaseKeyboard.swipeUpDirectionTan
            if (def.appearance.viewId == KeyView.button_return && this is ImageKeyView) {
                returnKeyView = this
            }
            if (def.appearance.viewId == KeyView.button_space && this is TextKeyView) {
                spaceKeyView = this
            }
            val bubbleItems = def.bubble.orEmpty()
            val pressAction = def.behaviors.filterIsInstance<KeyDef.Behavior.Press>()
                .firstOrNull()?.action
            val bubbleActive = keyBubbleEnabled && bubbleItems.isNotEmpty() && pressAction != null
            if (bubbleActive && pressAction != null) {
                attachKeyBubble(this, bubbleItems, pressAction)
            }
            borderStroke = KeyboardManager.Keyboard.KeyBorderStroke.isEnabled(context)
            onPressedChanged = { key ->
                if (key.isPressed) {
                    val isSwitchAction = def.behaviors.any { b ->
                        b is KeyDef.Behavior.Press && (b.action is KeyboardAction.LayoutSwitchAction || b.action is KeyboardAction.RotateSchema || b.action is KeyboardAction.ResumeAction)
                    }
                    if (!isSwitchAction) {
                        val keyLoc = IntArray(2)
                        val boardLoc = IntArray(2)
                        key.getLocationOnScreen(keyLoc)
                        this@BaseKeyboard.getLocationOnScreen(boardLoc)
                        val cx = keyLoc[0] + key.width / 2f - boardLoc[0]
                        val cy = keyLoc[1] + key.height / 2f - boardLoc[1]
                        rippleView.startRipple(cx, cy, key)
                    }
                }
            }
            if (this is SidePanelKeyView) {
                onRippleRequest = { screenX, screenY ->
                    val boardLoc = IntArray(2)
                    this@BaseKeyboard.getLocationOnScreen(boardLoc)
                    rippleView.startRipple(screenX - boardLoc[0], screenY - boardLoc[1], this)
                }
            }
            def.popups?.forEach { popup ->
                when (popup) {
                    is KeyDef.Popup.Preview -> {
                        setOnLongClickListener {
                            showPreview(it as KeyView)
                            return@setOnLongClickListener true
                        }
                        onTouchUpListener = { previewPopup.dismiss() }
                    }

                    is KeyDef.Popup.Keyboard -> {
                        var selectedIndex = 0
                        var startIndex = 0
                        var originX = 0f
                        var initialMove = true
                        setOnTouchListener { _, event ->
                            when (event.actionMasked) {
                                android.view.MotionEvent.ACTION_MOVE -> {
                                    if (keyboardPopup.isShowing()) {
                                        if (initialMove) {
                                            originX = event.x
                                            startIndex = keyboardPopup.selectedIndex
                                            initialMove = false
                                        }
                                        val dx = event.x - originX
                                        val step = keyboardPopup.itemStep
                                        val idx = (startIndex + dx / step).roundToInt()
                                            .coerceIn(0, keyboardPopup.itemCount - 1)
                                        if (idx != selectedIndex) {
                                            selectedIndex = idx
                                            keyboardPopup.selectIndex(idx)
                                        }
                                        return@setOnTouchListener true
                                    }
                                }

                                else -> {}
                            }
                            false
                        }
                        setOnLongClickListener {
                            showKeyboardPopup(it as KeyView, popup.keys)
                            selectedIndex = keyboardPopup.selectedIndex
                            startIndex = selectedIndex
                            initialMove = true
                            return@setOnLongClickListener true
                        }
                        onTouchUpListener = {
                            if (keyboardPopup.isShowing()) {
                                val item = popup.keys.getOrNull(selectedIndex)
                                if (item != null) {
                                    onAction(item)
                                }
                                keyboardPopup.dismiss()
                            }
                        }
                    }

                    else -> {}
                }
            }
            def.behaviors.forEach { behavior ->
                when (behavior) {
                    is KeyDef.Behavior.Press -> {
                        setOnClickListener({
                            onAction(behavior.action)
                        })
                    }

                    is KeyDef.Behavior.LongPress -> {
                        if (bubbleActive && this is AltTextKeyView) {
                            // 气泡模式：长按一律弹气泡（不再跟着「按键手势」摇摆，少一个互相打架的维度）。
                            // 「按键手势」只影响上滑：
                            //   上滑模式下快速上滑抬手 = 直接输入符号 / 数字（bubbleSwipeAltAction），
                            //   上滑后停住 = 弹气泡；长按模式下按键不上滑，就是长按弹气泡。
                            bubbleTriggerOnLongPress = true
                            bubbleSwipeAltAction = behavior.action.takeIf { behavior.altInput }
                        } else if (behavior.altInput && swipeAltInput) {
                            // 上滑手势模式：次级符号/数字改为上滑触发，长按不再触发。
                            setupSwipeAltInput(this, behavior.action)
                        } else {
                            longPressEnabled = true
                            setOnLongClickListener {
                                onAction(behavior.action)
                                return@setOnLongClickListener true
                            }
                            if (behavior.action is KeyboardAction.VoiceInputAction) {
                                onTouchMoveListener = { rawX, rawY ->
                                    onAction(KeyboardAction.VoiceDragPosition(rawX, rawY))
                                }
                                onTouchUpListener = {
                                    onAction(KeyboardAction.VoiceDragUp)
                                }
                            }
                        }
                    }

                    is KeyDef.Behavior.Repeat -> {
                        repeatEnabled = true
                        onRepeatListener = { onAction(behavior.action) }
                    }

                    is KeyDef.Behavior.Swipe -> {
                        swipeEnabled = true
                        swipeThresholdX = dp(800f)
                        swipeThresholdY = dp(36f)
                        onGestureListener = CustomGestureView.OnGestureListener { _, event ->
                            when (event.type) {
                                CustomGestureView.GestureType.Up -> {
                                    if (!event.consumed && event.totalY < 0) {
                                        onAction(behavior.action)
                                        true
                                    } else false
                                }

                                else -> false
                            }
                        }
                    }

                    is KeyDef.Behavior.DoubleTap -> {
                        doubleTapEnabled = true
                        onDoubleTapListener = { onAction(behavior.action) }
                    }
                }
            }
        }
    }

    /**
     * 给按键接上「长按 / 上滑弹气泡」。
     *
     * 只负责把内容和配色交给手势层，具体的按下 / 滑动 / 抬手分发在
     * [CustomGestureView] 里完成（它已经管着长按、上滑、重复这些定时器，
     * 气泡用同一套状态机才不会和它们打架）。
     *
     * 气泡第一项固定是「点一下这个键本来会输入的内容」（26 键是小写字母、九宫格是数字），
     * 所以长按后不滑动直接抬手不会出现意外结果。
     */
    private fun attachKeyBubble(view: KeyView, items: List<KeyBubbleItem>, pressAction: KeyboardAction) {
        // 气泡是浮在键盘上的一层，必须是不透明色：主题里的 specialKeyBackground /
        // accentKeyBackground 都带 alpha（半透明键帽），直接拿去画会透出下层内容。
        // 这里统一先与键盘背景合成，得到不透明的等效颜色。
        val bubbleBg = ColorUtils.compositeColors(colors.specialKeyBackground, colors.background)
        val bubbleSelectedBg = ColorUtils.compositeColors(colors.accentKeyBackground, colors.background)
        // 描边与键帽同一套：同样的圆角、同样的描边色 / 厚度，气泡才像是键盘的一部分。
        // 边框由「按键设置 → 绘制键边框」统一控制，关掉就一起不画。
        val strokeColor = if (KeyboardManager.Keyboard.KeyBorderStroke.isEnabled(context)) {
            colors.keyBorderStroke
        } else {
            Color.TRANSPARENT
        }
        view.bubbleController = object : HasKeyBubble {
            override val bubbleItems: List<KeyBubbleItem> = items
            override val bubbleBackgroundColor: Int = bubbleBg
            override val bubbleSelectedBackgroundColor: Int = bubbleSelectedBg
            override val bubbleTextColor: Int = colors.keyText
            override val bubbleSelectedTextColor: Int =
                if (ColorUtils.calculateLuminance(bubbleSelectedBg) > 0.5) Color.BLACK else Color.WHITE
            override val bubbleCornerRadius: Float = dp(colors.cornerRadius).toFloat()
            override val bubbleStrokeColor: Int = strokeColor
            override val bubbleStrokeWidth: Int = dp(colors.keyBorderWidth).toInt()
        }
        view.onBubbleAction = { action -> onAction(action) }
        // 兜底：气泡路径会接管长按 / 上滑，万一气泡没能显示出来（例如 PopupWindow 被系统拒绝），
        // 抬手时还可以走一次普通点击，至少不会「按了没反应」。
        view.setOnClickListener { onAction(pressAction) }
    }

    /**
     * 「上滑输入符号/数字」模式：把原本的长按动作改为上滑触发。
     *
     * 触发距离与气泡路径**共用同一套阈值**（当前键高 × `swipeUpRatio`，默认整整一个键高），
     * 靠 [CustomGestureView.swipeThresholdYFollowsKeyHeight] 切到运行期取值 ——
     * 否则「开不开按键气泡」会得到两种手感。
     */
    private fun setupSwipeAltInput(view: KeyView, action: KeyboardAction) {
        view.swipeEnabled = true
        view.swipeThresholdX = dp(800f)
        // 不再写死 dp(20f)：Y 轴阈值改为锚定这个键自己的高度。
        view.swipeThresholdYFollowsKeyHeight = true
        view.onGestureListener = CustomGestureView.OnGestureListener { _, event ->
            when (event.type) {
                CustomGestureView.GestureType.Up -> {
                    if (!event.consumed && event.totalY < 0) {
                        onAction(action)
                        true
                    } else false
                }

                else -> false
            }
        }
    }

    private fun showPreview(view: KeyView) {
        val text = view.displayText ?: return
        previewPopup.show(
            anchor = view,
            text = text,
            textColor = when (view.def.variant) {
                KeyDef.Appearance.Variant.Normal, KeyDef.Appearance.Variant.AltForeground -> colors.keyText
                KeyDef.Appearance.Variant.Alternative -> colors.specialKeyText
                KeyDef.Appearance.Variant.Accent -> colors.accentKeyText
                KeyDef.Appearance.Variant.None -> colors.keyText
            },
            bgColor = when (view.def.variant) {
                KeyDef.Appearance.Variant.Normal, KeyDef.Appearance.Variant.AltForeground -> colors.keyBackground
                KeyDef.Appearance.Variant.Alternative -> colors.specialKeyBackground
                KeyDef.Appearance.Variant.Accent -> colors.accentKeyBackground
                KeyDef.Appearance.Variant.None -> Color.TRANSPARENT
            },
        )
    }

    private fun showKeyboardPopup(view: KeyView, keys: List<KeyboardAction>) {
        keyboardPopup.show(
            anchor = view,
            items = keys,
            textColor = when (view.def.variant) {
                KeyDef.Appearance.Variant.Normal, KeyDef.Appearance.Variant.AltForeground -> colors.keyText
                KeyDef.Appearance.Variant.Alternative -> colors.specialKeyText
                KeyDef.Appearance.Variant.Accent -> colors.accentKeyText
                KeyDef.Appearance.Variant.None -> colors.keyText
            },
            bgColor = colors.keyBackground,
            keyBgColor = when (view.def.variant) {
                KeyDef.Appearance.Variant.Normal, KeyDef.Appearance.Variant.AltForeground -> colors.keyBackground
                KeyDef.Appearance.Variant.Alternative -> colors.specialKeyBackground
                KeyDef.Appearance.Variant.Accent -> colors.accentKeyBackground
                KeyDef.Appearance.Variant.None -> Color.TRANSPARENT
            },
            keyPressedColor = colors.accentKeyBackground,
        )
    }

    protected open fun onAction(action: KeyboardAction) {
        // 回车不再在这里按「图标」反推该发哪个 editor action：那条推断依赖键盘实例缓存的
        // [returnKeyIcon]，键盘重建后会滞后（从搜索框切到聊天框，图标还是放大镜），
        // 于是发出错误的动作。现在原样透传，由 KeyActionListener.handleReturn
        // 直接读当前输入框的 EditorInfo 决定 —— 图标只负责画，不再参与行为决策。
        keyActionListener?.onKeyAction(action)
    }

    override fun onAttach() = Timber.d("Keyboard onAttach")

    override fun onDetach() {
        Timber.d("Keyboard onDetach")
        rippleView.cancelRipple()
        resetSidePanelPosition()
        previewPopup.dismiss()
        keyboardPopup.dismiss()
    }

    abstract override fun name(): String

    override fun setRippleEnabled(enabled: Boolean) {
        rippleView.rippleEnabled = enabled
        rippleView.visibility = if (enabled) VISIBLE else INVISIBLE
    }


    override fun updateEditorInfo(info: EditorInfo, empty: Boolean, isComposing: Boolean) {
        val action = info.imeOptions and EditorInfo.IME_MASK_ACTION
        val icon = if (empty || isComposing) {
            R.drawable.ic_keyboard_return
        } else {
            when (action) {
                EditorInfo.IME_ACTION_SEARCH -> R.drawable.ic_keyboard_search
                EditorInfo.IME_ACTION_SEND -> R.drawable.ic_keyboard_send
                EditorInfo.IME_ACTION_GO -> R.drawable.ic_keyboard_go
                EditorInfo.IME_ACTION_PREVIOUS -> R.drawable.ic_keyboard_arrow_left
                EditorInfo.IME_ACTION_NEXT -> R.drawable.ic_keyboard_arrow_right
                EditorInfo.IME_ACTION_DONE -> R.drawable.ic_keyboard_done
                else -> R.drawable.ic_keyboard_return
            }
        }
        if (icon != returnKeyIcon) {
            returnKeyIcon = icon
            returnKeyView?.img?.imageResource = returnKeyIcon
        }
    }

    override fun updatePunctuationMode(mode: PunctuationMode) {
        keyRows.forEach { row ->
            for (index in 0 until row.childCount) {
                (row.getChildAt(index) as? KeyView)?.updateMode(mode)
            }
        }
        spanPanelViews.forEach { it.updateMode(mode) }
    }
}
