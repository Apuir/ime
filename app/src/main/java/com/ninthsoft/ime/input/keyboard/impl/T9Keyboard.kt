package com.ninthsoft.ime.input.keyboard.impl

import android.annotation.SuppressLint
import android.content.Context
import android.view.KeyEvent
import com.ninthsoft.ime.R
import com.ninthsoft.ime.data.keyboard.theme.KeyboardColors
import com.ninthsoft.ime.engine.data.CandidatePinYin
import com.ninthsoft.ime.input.keyboard.key.KeyboardAction
import com.ninthsoft.ime.input.keyboard.key.KeyDef
import com.ninthsoft.ime.input.keyboard.key.KeyDef.Appearance.Variant
import com.ninthsoft.ime.input.keyboard.key.KeyView
import com.ninthsoft.ime.input.keyboard.key.backspaceKey
import com.ninthsoft.ime.input.keyboard.key.commaKey
import com.ninthsoft.ime.input.keyboard.key.layoutSwitchKey
import com.ninthsoft.ime.input.keyboard.key.mixedAlphabetKey
import com.ninthsoft.ime.input.keyboard.key.returnKey
import com.ninthsoft.ime.input.keyboard.key.schemaSwitchKey
import com.ninthsoft.ime.input.keyboard.key.sidePannelKey
import com.ninthsoft.ime.input.keyboard.key.sidePannelNormalItem
import com.ninthsoft.ime.input.keyboard.key.spaceKey
import timber.log.Timber

@SuppressLint("ViewConstructor")
class T9Keyboard(
    context: Context,
    colors: KeyboardColors.ColorScheme,
) : BaseKeyboard(context, colors, Layout), ISidePanelKeyboard {

    init {
        val items = mutableListOf<KeyDef>()
        items.add(sidePannelNormalItem("."))
        items.add(sidePannelNormalItem("?"))
        items.add(sidePannelNormalItem("!"))
        items.add(sidePannelNormalItem("@"))
        this.updateSidePanel(items)
        this.setSidePanelItemListener { action -> this.onAction(action) }
    }

    override fun onPossibleCandidatePinYin(data: Array<CandidatePinYin>) {
        if (data.isEmpty()) {
            super.updateSidePanel(
                listOf(".", "?", "!", "@").map { ch ->
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
            return
        }
        super.updateSidePanel(data.map { pinYin ->
            Timber.d("onPossibleCandidatePinYin %s, position: %d", pinYin.pinYin, pinYin.position)
            KeyDef(
                appearance = KeyDef.Appearance.Text(
                    displayText = pinYin.pinYin,
                    textSize = 15f,
                    percentWidth = 0.5f,
                    margin = false,
                ),
                behaviors = setOf(
                    KeyDef.Behavior.Press(KeyboardAction.SelectCandidatePinYin(pinYin = pinYin))
                ),
            )
        })
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
                    KeyboardAction.KeyCodeAction(KeyEvent.KEYCODE_APOSTROPHE)
                )
            ),
        )

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
                infiniteKey(0.15f),
            ),
            listOf(
                layoutSwitchKey("?123", SymbolKeyboard.NAME, percentWidth = 0.15f),
                schemaSwitchKey(0.13f),
                spaceKey(),
                commaKey(percentWidth = 0.13f),
                returnKey(percentWidth = 0.15f),
            ),
        )
    }

    override fun name(): String {
        return NAME
    }

    override fun onDetach() {
        this.onPossibleCandidatePinYin(emptyArray())
        super.onDetach()
    }
}
