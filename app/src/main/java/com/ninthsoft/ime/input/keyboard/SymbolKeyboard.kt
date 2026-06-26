package com.ninthsoft.ime.input.keyboard

import android.annotation.SuppressLint
import android.content.Context
import com.ninthsoft.ime.data.keyboard.theme.KeyboardColors
import com.ninthsoft.ime.engine.data.KeyEvent
import com.ninthsoft.ime.input.keyboard.key.KeyAction
import com.ninthsoft.ime.input.keyboard.key.KeyDef
import com.ninthsoft.ime.input.keyboard.key.backspaceKey
import com.ninthsoft.ime.input.keyboard.key.layoutSwitchKey
import com.ninthsoft.ime.input.keyboard.key.spaceKey

@SuppressLint("ViewConstructor")
class SymbolKeyboard(
    context: Context,
    colors: KeyboardColors.ColorScheme,
) : BaseKeyboard(context, colors, Layout) {

    companion object {
        const val NAME = "Symbol"

        private fun digitKey(char: String, percentWidth: Float = 0.1f) = KeyDef(
            appearance = KeyDef.Appearance.Text(
                displayText = char,
                textSize = 23f,
                percentWidth = percentWidth,
            ),
            behaviors = setOf(
                KeyDef.Behavior.Press(
                    KeyAction.PressKeyAction(code = KeyEvent.code(char), modifiers = 0)
                )
            ),
        )

        private fun symbolKey(char: String, percentWidth: Float = 0.1f) = KeyDef(
            appearance = KeyDef.Appearance.Text(
                displayText = char,
                textSize = 19f,
                percentWidth = percentWidth,
                variant = KeyDef.Appearance.Variant.Alternative,
            ),
            behaviors = setOf(KeyDef.Behavior.Press(KeyAction.CommitAction(char))),
        )

        val Layout: List<List<KeyDef>> = listOf(
            listOf(
                digitKey("1"), digitKey("2"), digitKey("3"),
                digitKey("4"), digitKey("5"), digitKey("6"),
                digitKey("7"), digitKey("8"), digitKey("9"), digitKey("0"),
            ),
            listOf(
                symbolKey("@"), symbolKey("#"), symbolKey("$"),
                symbolKey("%"), symbolKey("&"), symbolKey("*"),
                symbolKey("-"), symbolKey("+"), symbolKey("("), symbolKey(")"),
            ),
            listOf(
                symbolKey("!"), symbolKey("\""), symbolKey("'"),
                symbolKey(":"), symbolKey(";"), symbolKey("/"),
                symbolKey("?"), symbolKey("~"), symbolKey(","), backspaceKey(),
            ),
            listOf(
                layoutSwitchKey("26键", NormalKeyboard.NAME, percentWidth = 0.18f),
                layoutSwitchKey("9键", T9Keyboard.NAME, percentWidth = 0.18f),
                spaceKey(),
                symbolKey(".", 0.18f),
                layoutSwitchKey("符", NAME, percentWidth = 0.18f),
            ),
        )
    }
}
