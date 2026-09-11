package com.ninthsoft.ime.input.keyboard.impl

import android.annotation.SuppressLint
import android.content.Context
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.FrameLayout
import com.ninthsoft.ime.data.keyboard.theme.KeyboardColors
import com.ninthsoft.ime.data.manager.KeyboardManager
import com.ninthsoft.ime.data.manager.RecentSymbolManager
import com.ninthsoft.ime.data.PunctuationMode
import com.ninthsoft.ime.data.Symbol
import com.ninthsoft.ime.data.SymbolPair
import com.ninthsoft.ime.input.keyboard.key.GridKeyboardView
import com.ninthsoft.ime.input.keyboard.key.KeyActionListener
import com.ninthsoft.ime.input.keyboard.key.KeyDef
import com.ninthsoft.ime.input.keyboard.key.KeyView
import com.ninthsoft.ime.input.keyboard.key.KeyboardAction
import com.ninthsoft.ime.input.keyboard.key.KeyboardRippleView
import com.ninthsoft.ime.input.keyboard.key.SidePanelKeyView
import com.ninthsoft.ime.input.keyboard.key.TextKeyView
import splitties.views.dsl.core.lParams
import splitties.views.dsl.core.matchParent
import com.ninthsoft.ime.data.Symbol.Category

@SuppressLint("ViewConstructor")
class SymbolKeyboard(
    context: Context,
    private val colors: KeyboardColors.ColorScheme,
) : FrameLayout(context), IKeyboard {

    companion object {
        const val NAME = "Symbol"
        private const val COLUMNS = 5
        private const val ROWS = 5

        private fun keyDef(text: String): KeyDef {
            val close = SymbolPair.closeFor(text)
            val press = if (close != null) {
                // 成对符号的前半：补齐后半、光标居中，并回到进入符号页前的键盘
                KeyDef.Behavior.Press(
                    KeyboardAction.CommitPairAction(text, close, resume = true)
                )
            } else {
                KeyDef.Behavior.Press(KeyboardAction.CommitAction(text))
            }
            return KeyDef(
                appearance = KeyDef.Appearance.Text(
                    displayText = text, textSize = 18f,
                    percentWidth = 1f / COLUMNS,
                    variant = KeyDef.Appearance.Variant.Alternative,
                ),
                behaviors = setOf(press),
            )
        }
    }

    override var keyActionListener: KeyActionListener? = null

    /** 静态分类顺序；「最近」的具体符号运行时从 [RecentSymbolManager] 取。 */
    private val categories: List<Category> = Symbol.Symbol.map { it.first }
    private var currentCategoryIndex = 0

    private val sidePanelKey = SidePanelKeyView(
        context, colors,
        KeyDef.Appearance.SidePannel(
            rowSpan = 4, visableRow = 4, margin = false,
            variant = KeyDef.Appearance.Variant.Alternative,
            border = KeyDef.Appearance.Border.On,
        ),
        keepSelection = true,
    )

    private val gridView = GridKeyboardView(context, colors, COLUMNS, ROWS).apply {
        onKeyAction = { action -> handleKeyAction(action) }
        onKeyPressed = { key -> triggerRipple(key) }
    }

    private val rippleView = KeyboardRippleView(context).apply {
        id = generateViewId()
        isClickable = false
        isEnabled = false
        isFocusable = false
    }

    private val returnKeyDef = KeyDef(
        appearance = KeyDef.Appearance.Text(
            displayText = "返回", textSize = 15f,
            textStyle = android.graphics.Typeface.BOLD,
            percentWidth = 1f,
            variant = KeyDef.Appearance.Variant.Alternative,
        ),
        behaviors = setOf(KeyDef.Behavior.Press(KeyboardAction.ResumeAction)),
    )

    private val sideReturnBtn =
        TextKeyView(context, colors, returnKeyDef.appearance as KeyDef.Appearance.Text)

    private val sidePanelItemDefs: List<KeyDef>

    init {
        sideReturnBtn.borderStroke = KeyboardManager.Keyboard.KeyBorderStroke.isEnabled(context)
        sideReturnBtn.hMargin = 0
        sideReturnBtn.vMargin = 0
        sideReturnBtn.setOnClickListener {
            keyActionListener?.onKeyAction(KeyboardAction.ResumeAction)
        }
        sideReturnBtn.onPressedChanged = { key ->
            if (key.isPressed) triggerRipple(key)
        }

        isClickable = true

        sidePanelItemDefs = categories.map { category ->
            KeyDef(
                appearance = KeyDef.Appearance.Text(
                    displayText = category.label, textSize = 15f, percentWidth = 0.5f,
                    margin = false, variant = KeyDef.Appearance.Variant.Alternative,
                ),
                behaviors = setOf(KeyDef.Behavior.Press(KeyboardAction.CommitAction(category.label))),
            )
        }
        sidePanelKey.updateItems(sidePanelItemDefs)

        sidePanelKey.setOnItemActionListener { action ->
            val index = if (action is KeyboardAction.CommitAction) {
                categories.indexOfFirst { it.label == action.text }
            } else {
                -1
            }
            if (index >= 0) {
                showCategory(index)
            } else {
                keyActionListener?.onKeyAction(action)
            }
        }

        addView(sidePanelKey, lParams(0, matchParent))
        addView(sideReturnBtn, lParams(0, matchParent))
        addView(gridView, lParams(0, matchParent))
        addView(rippleView, LayoutParams(matchParent, matchParent))
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val tw = MeasureSpec.getSize(widthMeasureSpec)
        val th = MeasureSpec.getSize(heightMeasureSpec)
        // 侧栏与其它键盘最左列保持一致：0.15 加宽到 0.17
        val sw = (tw * 0.17f).toInt()
        val gw = tw - sw
        val sidePanelH = th * 3 / 4
        sidePanelKey.measure(mES(sw, MeasureSpec.EXACTLY), mES(sidePanelH, MeasureSpec.EXACTLY))
        sideReturnBtn.measure(
            mES(sw, MeasureSpec.EXACTLY), mES(th - sidePanelH, MeasureSpec.EXACTLY)
        )
        gridView.measure(mES(gw, MeasureSpec.EXACTLY), mES(th, MeasureSpec.EXACTLY))
        setMeasuredDimension(tw, th)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val sw = ((r - l) * 0.17f).toInt()
        val sidePanelH = (b - t) * 3 / 4
        sidePanelKey.layout(0, 0, sw, sidePanelH)
        sideReturnBtn.layout(0, sidePanelH, sw, b - t)
        gridView.layout(sw, 0, r - l, b - t)
    }

    private fun mES(size: Int, mode: Int) = MeasureSpec.makeMeasureSpec(size, mode)

    override fun name(): String = NAME
    override fun updateSpaceKeyText(text: String) {}
    override fun updatePunctuationMode(mode: PunctuationMode) {}
    override fun updateEditorInfo(info: EditorInfo, empty: Boolean, isComposing: Boolean) {}

    override fun setRippleEnabled(enabled: Boolean) {
        rippleView.rippleEnabled = enabled
        rippleView.visibility = if (enabled) View.VISIBLE else View.INVISIBLE
    }

    private fun triggerRipple(key: KeyView) {
        val keyLoc = IntArray(2)
        val boardLoc = IntArray(2)
        key.getLocationOnScreen(keyLoc)
        getLocationOnScreen(boardLoc)
        val cx = keyLoc[0] + key.width / 2f - boardLoc[0]
        val cy = keyLoc[1] + key.height / 2f - boardLoc[1]
        rippleView.startRipple(cx, cy, key)
    }

    override fun onAttach() {
        reset()
    }

    override fun onDetach() {
        rippleView.cancelRipple()
    }

    fun reset() {
        showCategory(categories.indexOfFirst { it.label == RecentSymbolManager.LABEL })
        sidePanelKey.resetPosition()
    }

    private fun showCategory(index: Int) {
        if (index !in categories.indices) return
        currentCategoryIndex = index
        gridView.setItems(symbolsOf(index).map { keyDef(it) })
        sidePanelKey.selectIndex(index)
    }

    private fun symbolsOf(index: Int): Array<String> {
        val category = categories[index]
        if (category.label == RecentSymbolManager.LABEL) {
            return RecentSymbolManager.get(getContext()).toTypedArray()
        }
        return Symbol.Symbol.firstOrNull { it.first.label == category.label }?.second ?: emptyArray()
    }

    private fun handleKeyAction(action: KeyboardAction) {
        val symbol = when (action) {
            is KeyboardAction.CommitAction -> action.text
            is KeyboardAction.CommitPairAction -> action.open
            else -> null
        }
        if (symbol != null) {
            RecentSymbolManager.record(getContext(), symbol)
        }
        keyActionListener?.onKeyAction(action)
    }
}
