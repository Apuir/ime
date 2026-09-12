package com.ninthsoft.ime.input.keyboard.impl

import android.annotation.SuppressLint
import android.content.Context
import com.ninthsoft.ime.base.util.PunctuationUtil
import com.ninthsoft.ime.data.PunctuationMode
import com.ninthsoft.ime.data.keyboard.theme.KeyboardColors
import com.ninthsoft.ime.data.manager.KeyboardKeyMapping
import com.ninthsoft.ime.data.manager.KeyboardManager
import com.ninthsoft.ime.engine.data.CandidatePinYin
import com.ninthsoft.ime.input.keyboard.key.KeyboardAction
import com.ninthsoft.ime.input.keyboard.key.KeyDef
import com.ninthsoft.ime.input.keyboard.key.KeyDef.Appearance.Variant
import com.ninthsoft.ime.input.keyboard.key.backspaceKey
import com.ninthsoft.ime.input.keyboard.key.clearKey
import com.ninthsoft.ime.input.keyboard.key.layoutSwitchKey
import com.ninthsoft.ime.input.keyboard.key.mixedAlphabetKey
import com.ninthsoft.ime.input.keyboard.key.returnKey
import com.ninthsoft.ime.input.keyboard.key.schemaSwitchKey
import com.ninthsoft.ime.input.keyboard.key.segmentKey
import com.ninthsoft.ime.input.keyboard.key.sidePannelKey
import com.ninthsoft.ime.input.keyboard.key.spaceKey
import com.ninthsoft.ime.input.keyboard.key.symbolPageKey

@SuppressLint("ViewConstructor")
class T9Keyboard(
    context: Context,
    colors: KeyboardColors.ColorScheme,
) : BaseKeyboard(context, colors, { ctx -> buildLayout(ctx) }), ISidePanelKeyboard {
    private var state: PunctuationMode = PunctuationMode.FullWidth

    init {
        this.updatePunctuationMode(state)
        this.setSidePanelItemListener { action -> this.onAction(action) }
    }

    override fun onPossibleCandidatePinYin(data: List<CandidatePinYin>) {
        if (data.isEmpty()) {
            super.updateSidePanel(
                sidePanelPunctuations().map { ch ->
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

        /**
         * 宽度：最左右两列由 0.15 加宽到 0.17，多出来的宽度从中间各列均分扣除
         * ——上行 3 个数字键 0.23333 → 0.22，整块正好是 0.17 / 0.22 ×3 / 0.17 的 5 等列。
         * 底行的中英切换、空格、123 与上面的九宫格同宽（0.22），符号 / 回车与外侧两列对齐（0.17），
         * 于是底行与上面逐列对齐。
         *
         * 每个数字键对应的字母来自用户映射（[KeyboardKeyMapping]），键帽显示与气泡内容都跟着它走。
         */
        fun buildLayout(context: Context): List<List<KeyDef>> {
            fun digitKey(digit: String): KeyDef {
                val letters = KeyboardKeyMapping.t9Letters(context, digit)
                return mixedAlphabetKey(
                    digit = digit,
                    letters = letters,
                    percentWidth = 0.22f,
                    bubble = KeyboardKeyMapping.t9BubbleItems(context, digit, letters),
                )
            }

            return listOf(
                listOf(
                    sidePannelKey(rowSpan = 3, visableRow = 4, percentWidth = 0.17f),
                    segmentKey(percentWidth = 0.22f),
                    digitKey("2"),
                    digitKey("3"),
                    backspaceKey(percentWidth = 0.17f),
                ),
                listOf(
                    digitKey("4"),
                    digitKey("5"),
                    digitKey("6"),
                    clearKey(0.17f),
                ),
                listOf(
                    digitKey("7"),
                    digitKey("8"),
                    digitKey("9"),
                    // 大回车：跨第 3、4 两行，占掉原来独立 @ 键的位置
                    returnKey(percentWidth = 0.17f, rowSpan = 2),
                ),
                listOf(
                    // 底行：符号 | 中英切换 | 空格 | 数字（与上面的九宫格逐列对齐）
                    symbolPageKey(percentWidth = 0.17f),
                    schemaSwitchKey(0.22f),
                    spaceKey(percentWidth = 0.22f),
                    layoutSwitchKey("123", NumberKeyboard.NAME, percentWidth = 0.22f),
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
        if (changed) {
            this.onPossibleCandidatePinYin(emptyList())
        }
        super.updatePunctuationMode(mode)
    }

    /**
     * 左侧符号栏内容：读取用户在「设置 → 键盘布局 → 侧栏符号」里编辑的列表，
     * 再按当前标点模式做半角 / 全角转换，保持与旧版本一致的手感。
     */
    private fun sidePanelPunctuations(): List<String> {
        val symbols = KeyboardManager.Keyboard.SidePanelSymbols.getT9(context)
        return when (state) {
            PunctuationMode.FullWidth -> symbols.map { PunctuationUtil.toFullWidth(it) }
            PunctuationMode.HalfWidth -> symbols.map { PunctuationUtil.toHalfWidth(it) }
        }
    }
}
