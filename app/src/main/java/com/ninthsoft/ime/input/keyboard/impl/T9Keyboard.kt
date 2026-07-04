package com.ninthsoft.ime.input.keyboard.impl

import android.annotation.SuppressLint
import android.content.Context
import android.view.KeyEvent
import com.ninthsoft.ime.R
import com.ninthsoft.ime.data.keyboard.theme.KeyboardColors
import com.ninthsoft.ime.input.keyboard.impl.BaseKeyboard
import com.ninthsoft.ime.input.keyboard.key.KeyAction
import com.ninthsoft.ime.input.keyboard.key.KeyDef
import com.ninthsoft.ime.input.keyboard.key.KeyView
import com.ninthsoft.ime.input.keyboard.key.backspaceKey
import com.ninthsoft.ime.input.keyboard.key.layoutSwitchKey
import com.ninthsoft.ime.input.keyboard.key.mixedAlphabetKey
import com.ninthsoft.ime.input.keyboard.key.sidePannelKey
import com.ninthsoft.ime.input.keyboard.key.sidePannelNormalItem
import com.ninthsoft.ime.input.keyboard.key.spaceKey

@SuppressLint("ViewConstructor")
class T9Keyboard(
    context: Context,
    colors: KeyboardColors.ColorScheme,
) : BaseKeyboard(context, colors, Layout) {

    init {
        val items = mutableListOf<KeyDef>()
        items.add(sidePannelNormalItem("."))
        items.add(sidePannelNormalItem("?"))
        items.add(sidePannelNormalItem("!"))
        items.add(sidePannelNormalItem("@"))
        this.updateSidePanel(items)
    }

    companion object {
        const val NAME = "T9"
        private fun segmentKey(percentWidth: Float = 0.23333f): KeyDef = KeyDef(
            appearance = KeyDef.Appearance.AltText(
                displayText = "分词",
                altText = "1",
                textSize = 16f,
                percentWidth = percentWidth,
                mainTextTranslationY = 4,
                altTextTranslationY = 6
            ),
            behaviors = setOf(
                KeyDef.Behavior.Press(
                    KeyAction.PressKeyAction(KeyEvent.KEYCODE_APOSTROPHE, 0)
                )
            ),
        )

        private fun returnKey(percentWidth: Float): KeyDef = KeyDef(
            appearance = KeyDef.Appearance.Image(
                src = R.drawable.ic_keyboard_return,
                viewId = KeyView.button_return,
                percentWidth = percentWidth,
                variant = KeyDef.Appearance.Variant.Accent,
                border = KeyDef.Appearance.Border.Special,
            ),
            behaviors = setOf(KeyDef.Behavior.Press(KeyAction.ReturnAction)),
        )

        private fun clearKey(percentWidth: Float = 0.15f): KeyDef = KeyDef(
            appearance = KeyDef.Appearance.Text(
                displayText = "清空",
                textSize = 15f,
                percentWidth = percentWidth,
                variant = KeyDef.Appearance.Variant.Alternative,
            ),
            behaviors = setOf(
                KeyDef.Behavior.Press(KeyAction.ClearAction)
            ),
        )

        private fun langSwitchKey(percentWidth: Float): KeyDef = KeyDef(
            appearance = KeyDef.Appearance.Image(
                src = R.drawable.ic_keyboard_language,
                viewId = KeyView.button_lang,
                percentWidth = percentWidth,
                variant = KeyDef.Appearance.Variant.Alternative,
            ),
            behaviors = setOf(KeyDef.Behavior.Press(KeyAction.LangSwitchAction)),
        )

        private fun makeCommaKey(percentWidth: Float): KeyDef = KeyDef(
            appearance = KeyDef.Appearance.ImageText(
                displayText = ".",
                textSize = 23f,
                src = R.drawable.ic_keyboard_emoticon,
                percentWidth = percentWidth,
                variant = KeyDef.Appearance.Variant.Alternative,
            ),
            behaviors = setOf(KeyDef.Behavior.Press(KeyAction.PressKeyAction(0, 0))),
        )

        private fun makeReturnKey(percentWidth: Float): KeyDef = KeyDef(
            appearance = KeyDef.Appearance.Image(
                src = R.drawable.ic_keyboard_return,
                viewId = KeyView.button_return,
                percentWidth = percentWidth,
                variant = KeyDef.Appearance.Variant.Accent,
                border = KeyDef.Appearance.Border.Special,
            ),
            behaviors = setOf(KeyDef.Behavior.Press(KeyAction.ReturnAction)),
        )


        val Layout: List<List<KeyDef>> = listOf(
            listOf(
                sidePannelKey(rowSpan = 3, visableRow = 4),
                segmentKey(percentWidth = 0.23333f),
                mixedAlphabetKey("2", "ABC"),
                mixedAlphabetKey("3", "DEF"),
                backspaceKey(),
            ),
            listOf(
                mixedAlphabetKey("4", "GHI"),
                mixedAlphabetKey("5", "JKL"),
                mixedAlphabetKey("6", "MNO"),
                clearKey(0.15f),
            ),
            listOf(
                mixedAlphabetKey("7", "PQRS"),
                mixedAlphabetKey("8", "TUV"),
                mixedAlphabetKey("9", "WXYZ"),
                langSwitchKey(0.15f),
            ),
            listOf(
                layoutSwitchKey("?123", SymbolKeyboard.NAME, percentWidth = 0.15f),
                layoutSwitchKey("26键", NormalKeyboard.NAME, percentWidth = 0.13f),
                spaceKey(),
                makeCommaKey(percentWidth = 0.13f),
                makeReturnKey(percentWidth = 0.15f),
            ),
        )
    }

    override fun name(): String {
        return NAME
    }
}
