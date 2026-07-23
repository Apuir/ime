package com.ninthsoft.ime.input.keyboard.impl

import android.annotation.SuppressLint
import android.content.Context
import com.ninthsoft.ime.R
import com.ninthsoft.ime.data.keyboard.theme.KeyboardColors
import com.ninthsoft.ime.engine.data.CandidatePinYin
import com.ninthsoft.ime.input.keyboard.key.commitKey
import com.ninthsoft.ime.input.keyboard.key.KeyboardAction
import com.ninthsoft.ime.input.keyboard.key.KeyDef
import com.ninthsoft.ime.input.keyboard.key.KeyDef.Appearance.Variant
import com.ninthsoft.ime.input.keyboard.key.KeyView
import com.ninthsoft.ime.input.keyboard.key.backspaceKey
import com.ninthsoft.ime.input.keyboard.key.peroidKey
import com.ninthsoft.ime.input.keyboard.key.layoutSwitchKey
import com.ninthsoft.ime.input.keyboard.key.returnKey
import com.ninthsoft.ime.input.keyboard.key.schemaSwitchKey
import com.ninthsoft.ime.input.keyboard.key.sidePannelKey
import com.ninthsoft.ime.input.keyboard.key.spaceKey

@SuppressLint("ViewConstructor")
class NumberKeyboard(
    context: Context,
    colors: KeyboardColors.ColorScheme,
) : BaseKeyboard(context, colors, Layout), ISidePanelKeyboard {

    init {
        this.onPossibleCandidatePinYin(emptyArray())
        this.setSidePanelItemListener { action -> this.onAction(action) }
    }

    companion object {
        const val NAME = "Number"


        private fun clearKey(percentWidth: Float = 0.15f): KeyDef = KeyDef(
            appearance = KeyDef.Appearance.Text(
                displayText = "清空",
                textSize = 15f,
                percentWidth = percentWidth,
                variant = Variant.Alternative,
            ),
            behaviors = setOf(
                KeyDef.Behavior.Press(KeyboardAction.ClearAction)
            ),
        )

        private fun infiniteKey(percentWidth: Float): KeyDef = KeyDef(
            appearance = KeyDef.Appearance.Image(
                src = R.drawable.ic_keyboard_infinite,
                viewId = KeyView.button_lang,
                percentWidth = percentWidth,
                variant = Variant.Alternative,
            ),
            behaviors = setOf(KeyDef.Behavior.Press(KeyboardAction.LangSwitchAction)),
        )

        val Layout: List<List<KeyDef>> = listOf(
            listOf(
                sidePannelKey(rowSpan = 3, visableRow = 4),
                commitKey("1"),
                commitKey("2"),
                commitKey("3"),
                backspaceKey(),
            ),
            listOf(
                commitKey("4"),
                commitKey("5"),
                commitKey("6"),
                clearKey(0.15f),
            ),
            listOf(
                commitKey("7"),
                commitKey("8"),
                commitKey("9"),
                infiniteKey(0.15f),
            ),
            listOf(
                layoutSwitchKey("?123", SymbolKeyboard.NAME, percentWidth = 0.15f),
                schemaSwitchKey(0.13f),
                spaceKey(),
                peroidKey(percentWidth = 0.13f),
                returnKey(percentWidth = 0.15f),
            ),
        )
    }

    override fun name(): String {
        return NAME
    }

    override fun onPossibleCandidatePinYin(data: Array<CandidatePinYin>) {
        super.updateSidePanel(
            listOf("+", "-", "*", "/", "=", "~", "?", "!").map { ch ->
                KeyDef(
                    appearance = KeyDef.Appearance.Text(
                        displayText = ch,
                        textSize = 15f,
                        percentWidth = 0.5f,
                        margin = false,
                        variant = Variant.Alternative
                    ),
                    behaviors = setOf(KeyDef.Behavior.Press(KeyboardAction.CommitAction(ch))),
                )
            })
    }
}