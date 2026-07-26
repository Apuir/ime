package com.ninthsoft.ime.input.keyboard.impl

import android.annotation.SuppressLint
import android.content.Context
import android.view.inputmethod.EditorInfo
import android.widget.FrameLayout
import com.ninthsoft.ime.data.Punctuation
import com.ninthsoft.ime.data.keyboard.theme.KeyboardColors
import com.ninthsoft.ime.data.manager.KeyboardManager
import com.ninthsoft.ime.engine.event.KeyEvent
import com.ninthsoft.ime.input.keyboard.key.GridKeyboardView
import com.ninthsoft.ime.input.keyboard.key.KeyActionListener
import com.ninthsoft.ime.input.keyboard.key.KeyDef
import com.ninthsoft.ime.input.keyboard.key.KeyboardAction
import com.ninthsoft.ime.input.keyboard.key.SidePanelKeyView
import com.ninthsoft.ime.input.keyboard.key.TextKeyView
import splitties.views.dsl.core.lParams
import splitties.views.dsl.core.matchParent
import androidx.core.view.isEmpty
import timber.log.Timber

@SuppressLint("ViewConstructor")
class SymbolKeyboard(
    context: Context,
    private val colors: KeyboardColors.ColorScheme,
) : FrameLayout(context), IKeyboard {

    companion object {
        const val NAME = "Symbol"

        private val CATEGORIES: Map<String, List<String>> = mapOf(
            "表情" to listOf(
                "😀", "😃", "😄", "😁", "😆", "😅", "🤣", "😂", "🙂", "🙃",
                "🫠", "😉", "😊", "😇", "🥰", "😍", "🤩", "😘", "😗", "☺",
                "😚", "😙", "🥲", "😋", "😛", "😜", "🤪", "😝", "🤑", "🤗",
                "🤭", "🫢", "🫣", "🤫", "🤔", "🫡", "🤐", "🤨", "😐", "😑",
                "😶", "😏", "😒", "🙄", "😬", "🤥", "😌", "😔", "😪", "🤤",
                "😴", "😷", "🤒", "🤕", "🤢", "🤮", "🤧", "🥵", "🥶", "🥴",
                "😵", "🤯", "🤠", "🥳", "🥸", "😎", "🤓", "🧐", "😕", "🫤",
                "😟", "🙁", "😮", "😯", "😲", "😳", "🥺", "🥹", "😦", "😧",
                "😨", "😰", "😥", "😢", "😭", "😱", "😖", "😣", "😞", "😓",
                "😩", "😫", "🥱", "😤", "😡", "😠", "🤬", "👋", "🤚", "🖐",
                "✋", "🖖", "🫱", "🫲", "🫳", "🫴", "👌", "🤌", "🤏", "🤞",
                "🫰", "🤟", "🤘", "🤙", "👈", "👉", "👆", "🖕", "👇", "🫵",
                "👍", "👎", "✊", "👊", "🤛", "🤜", "💊", "👀",
            ),
            "符" to listOf("@", "#", "$", "%", "&", "*", "-", "+", "(", ")"),
            "标" to listOf("!", "\"", "'", ":", ";", "/", "?", "~", ",", "."),
            "括" to listOf("[", "]", "{", "}", "<", ">", "/", "\\", "_", "|"),
            "^" to listOf("^", "`", "€", "£", "¥", "¢", "§", "©", "®", "™"),
            "°" to listOf("°", "±", "×", "÷", "√", "∞", "≈", "≠", "≤", "≥"),
        )

        private const val COLUMNS = 5
        private const val ROWS = 5

        private fun keyDef(text: String): KeyDef {
            return KeyDef(
                appearance = KeyDef.Appearance.Text(
                    displayText = text, textSize = 18f,
                    percentWidth = 1f / COLUMNS,
                    variant = KeyDef.Appearance.Variant.Alternative,
                ),
                behaviors = setOf(
                    KeyDef.Behavior.Press(KeyboardAction.CommitAction(text))
                ),
            )
        }
    }

    override var keyActionListener: KeyActionListener? = null

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
        onKeyAction = { action -> keyActionListener?.onKeyAction(action) }
    }

    private val returnKeyDef = KeyDef(
        appearance = KeyDef.Appearance.Text(
            displayText = "返回", textSize = 15f,
            textStyle = android.graphics.Typeface.BOLD,
            percentWidth = 1f,
            variant = KeyDef.Appearance.Variant.Alternative,
        ),
        behaviors = setOf(KeyDef.Behavior.Press(KeyboardAction.BackAction)),
    )

    private val sideReturnBtn =
        TextKeyView(context, colors, returnKeyDef.appearance as KeyDef.Appearance.Text)

    private val sidePanelItemDefs: List<KeyDef>

    init {
        sideReturnBtn.borderStroke = KeyboardManager.Keyboard.KeyBorderStroke.isEnabled(context)
        sideReturnBtn.hMargin = 0
        sideReturnBtn.vMargin = 0
        sideReturnBtn.setOnClickListener {
            keyActionListener?.onKeyAction(KeyboardAction.BackAction)
        }

        isClickable = true

        sidePanelItemDefs = CATEGORIES.keys.map { label ->
            KeyDef(
                appearance = KeyDef.Appearance.Text(
                    displayText = label, textSize = 15f, percentWidth = 0.5f,
                    margin = false, variant = KeyDef.Appearance.Variant.Alternative,
                ),
                behaviors = setOf(KeyDef.Behavior.Press(KeyboardAction.CommitAction(label))),
            )
        }
        sidePanelKey.updateItems(sidePanelItemDefs)

        sidePanelKey.setOnItemActionListener { action ->
            if (action is KeyboardAction.CommitAction && action.text in CATEGORIES) {
                val symbols = CATEGORIES[action.text] ?: return@setOnItemActionListener
                gridView.setItems(symbols.map { keyDef(it) })
                val index = CATEGORIES.keys.indexOf(action.text)
                if (index >= 0) sidePanelKey.selectIndex(index)
            } else {
                keyActionListener?.onKeyAction(action)
            }
        }

        addView(sidePanelKey, lParams(0, matchParent))
        addView(sideReturnBtn, lParams(0, matchParent))
        addView(gridView, lParams(0, matchParent))
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val tw = MeasureSpec.getSize(widthMeasureSpec)
        val th = MeasureSpec.getSize(heightMeasureSpec)
        val sw = (tw * 0.15f).toInt()
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
        val sw = ((r - l) * 0.15f).toInt()
        val sidePanelH = (b - t) * 3 / 4
        sidePanelKey.layout(0, 0, sw, sidePanelH)
        sideReturnBtn.layout(0, sidePanelH, sw, b - t)
        gridView.layout(sw, 0, r - l, b - t)
    }

    private fun mES(size: Int, mode: Int) = MeasureSpec.makeMeasureSpec(size, mode)

    override fun name(): String = NAME
    override fun updateSpaceKeyText(text: String) {}
    override fun updatePeriodKeyText(text: String) {}
    override fun updatePunctuation(punctuation: Punctuation) {}
    override fun updateEditorInfo(info: EditorInfo, empty: Boolean) {}

    override fun setRippleEnabled(enabled: Boolean) {}

    override fun onAttach() {
        reset()
    }

    override fun onDetach() {}

    fun reset() {
        val first = CATEGORIES.keys.first()
        val symbols = CATEGORIES[first] ?: return
        gridView.setItems(symbols.map { keyDef(it) })
        sidePanelKey.selectIndex(0)
        sidePanelKey.resetPosition()
    }
}
