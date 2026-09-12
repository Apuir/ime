package com.ninthsoft.ime.input.keyboard.impl

import android.annotation.SuppressLint
import android.content.Context
import com.ninthsoft.ime.data.PunctuationMode
import com.ninthsoft.ime.data.keyboard.theme.KeyboardColors
import com.ninthsoft.ime.data.manager.KeyboardKeyMapping
import com.ninthsoft.ime.engine.data.CandidatePinYin
import com.ninthsoft.ime.input.keyboard.key.KeyBubbleItem
import com.ninthsoft.ime.input.keyboard.key.KeyboardAction
import com.ninthsoft.ime.input.keyboard.key.KeyDef
import com.ninthsoft.ime.input.keyboard.key.KeyDef.Appearance.Variant
import com.ninthsoft.ime.input.keyboard.key.backspaceKey
import com.ninthsoft.ime.input.keyboard.key.clearKey
import com.ninthsoft.ime.input.keyboard.key.layoutSwitchKey
import com.ninthsoft.ime.input.keyboard.key.peroidKey
import com.ninthsoft.ime.input.keyboard.key.returnKey
import com.ninthsoft.ime.input.keyboard.key.schemaSwitchKey
import com.ninthsoft.ime.input.keyboard.key.sidePannelKey
import com.ninthsoft.ime.input.keyboard.key.spaceKey
import com.ninthsoft.ime.input.keyboard.key.zeroKey

@SuppressLint("ViewConstructor")
class T15Keyboard(
    context: Context,
    colors: KeyboardColors.ColorScheme,
) : BaseKeyboard(context, colors, { ctx -> buildLayout(ctx) }), ISidePanelKeyboard {
    private val fullWidthPunctuations = listOf("，", "。", "！", "？", "：", "~", "...")
    private val halfWidthPunctuations = listOf(",", ".", "!", "?", ":", "~", "...")
    var punctuations = fullWidthPunctuations
    private var state: PunctuationMode = PunctuationMode.FullWidth

    init {
        this.updatePunctuationMode(state)
        this.setSidePanelItemListener { action -> this.onAction(action) }
    }

    override fun onPossibleCandidatePinYin(data: List<CandidatePinYin>) {
        if (data.isEmpty()) {
            super.updateSidePanel(
                punctuations.map { ch ->
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
        return
    }


    companion object {
        const val NAME = "T15"

        /**
         * 宽度：最左右两列由 0.15 加宽到 0.17（即 [npercentWidth]），
         * 多出来的 0.04 由中间 5 列均分，每列让出 0.008：0.13998 → 0.132。
         * 底行中间三列同理，0.13 / 0.44 / 0.13 各让出 1/75。
         */
        const val percentWidth = 0.132f
        const val npercentWidth = 0.17f

        fun mixedAlphabetKey(
            digit: String,
            send: String,
            letters: String,
            percentWidth: Float = 0.23333f,
            mainTextTranslationY: Int = 0,
            altTextTranslationY: Int = 4,
            bubble: List<KeyBubbleItem>? = null,
        ) = KeyDef(
            appearance = KeyDef.Appearance.AltText(
                displayText = letters,
                altText = digit,
                textSize = 20f,
                percentWidth = percentWidth,
                mainTextTranslationY = mainTextTranslationY,
                altTextTranslationY = altTextTranslationY
            ),
            behaviors = setOf(
                KeyDef.Behavior.Press(KeyboardAction.KeySequenceAction(send)),
                KeyDef.Behavior.LongPress(KeyboardAction.CommitAction(digit), altInput = true)
            ),
            bubble = bubble,
        )

        /**
         * 15 键布局。
         *
         * 15 键自己有一套「数字键 ≈ 一组声母 / 韵母」的键位（如 `4` 同时管 `r`，`rf` 两个韵母），
         * 和九宫格按 ABC/DEF 分组的字母不是一回事，所以这里**不接入九键的字母映射**，
         * 保持原有键位；只是给每个键补上气泡（主键 + 该键现有的字母），
         * 让长按 / 上滑时有和 26 键一致的左右划选体验。
         */
        fun buildLayout(context: Context): List<List<KeyDef>> {
            fun mixed(
                digit: String,
                send: String,
                letters: String,
                percentWidth: Float = this.percentWidth,
                mainTextTranslationY: Int = 0,
                altTextTranslationY: Int = 4,
            ): KeyDef = mixedAlphabetKey(
                digit = digit,
                send = send,
                letters = letters,
                percentWidth = percentWidth,
                mainTextTranslationY = mainTextTranslationY,
                altTextTranslationY = altTextTranslationY,
                bubble = KeyboardKeyMapping.t9BubbleItems(context, digit, letters),
            )

            return listOf(
                listOf(
                    sidePannelKey(rowSpan = 3, visableRow = 4, percentWidth = npercentWidth),
                    mixed("1", "q", "b", percentWidth = percentWidth),
                    mixed("2", "w", "p", percentWidth = percentWidth),
                    mixed("3", "e", "m", percentWidth = percentWidth),
                    mixed("4", "r", "rf", percentWidth = percentWidth),
                    mixed("5", "t", "ẑz", percentWidth = percentWidth),
                    backspaceKey(percentWidth = npercentWidth),
                ),
                listOf(
                    mixed("6", "a", "d", percentWidth = percentWidth),
                    mixed("7", "s", "t", percentWidth = percentWidth),
                    mixed("8", "d", "n", percentWidth = percentWidth),
                    mixed("9", "f", "l", percentWidth = percentWidth),
                    mixed("0", "g", "ĉc", percentWidth = percentWidth),
                    clearKey(percentWidth = npercentWidth),
                ),
                listOf(
                    mixed("&", "z", "gj", altTextTranslationY = 0),
                    mixed("*", "x", "kq", altTextTranslationY = 4),
                    mixed("^", "c", "hx", altTextTranslationY = 6),
                    mixed("#", "v", "yw", altTextTranslationY = 2),
                    mixed(";", "b", "ŝs", altTextTranslationY = 2),
                    zeroKey(percentWidth = npercentWidth),
                ),
                listOf(
                    layoutSwitchKey("?123", NumberKeyboard.NAME, percentWidth = npercentWidth),
                    schemaSwitchKey(0.11667f),
                    spaceKey(percentWidth = 0.42667f),
                    peroidKey(percentWidth = 0.11667f),
                    returnKey(percentWidth = npercentWidth),
                ),
            )
        }
    }

    override fun name(): String {
        return NAME
    }

    override fun onAttach() {
        this.onPossibleCandidatePinYin(emptyList())
        super.onAttach()
    }

    override fun updatePunctuationMode(mode: PunctuationMode) {
        val changed = mode != state
        state = mode
        punctuations = when (mode) {
            PunctuationMode.FullWidth -> fullWidthPunctuations
            PunctuationMode.HalfWidth -> halfWidthPunctuations
        }
        if (changed) {
            this.onPossibleCandidatePinYin(emptyList())
        }
        super.updatePunctuationMode(mode)
    }
}
