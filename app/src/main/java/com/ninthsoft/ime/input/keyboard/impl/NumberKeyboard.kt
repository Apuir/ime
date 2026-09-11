package com.ninthsoft.ime.input.keyboard.impl

import android.annotation.SuppressLint
import android.content.Context
import com.ninthsoft.ime.data.PunctuationMode
import com.ninthsoft.ime.data.keyboard.theme.KeyboardColors
import com.ninthsoft.ime.data.manager.KeyboardManager
import com.ninthsoft.ime.engine.data.CandidatePinYin
import com.ninthsoft.ime.input.keyboard.key.KeyDef
import com.ninthsoft.ime.input.keyboard.key.KeyDef.Appearance.Variant
import com.ninthsoft.ime.input.keyboard.key.KeyboardAction
import com.ninthsoft.ime.input.keyboard.key.atKey
import com.ninthsoft.ime.input.keyboard.key.backspaceKey
import com.ninthsoft.ime.input.keyboard.key.commitKey
import com.ninthsoft.ime.input.keyboard.key.symbolPageKey
import com.ninthsoft.ime.input.keyboard.key.miniSpaceKey
import com.ninthsoft.ime.input.keyboard.key.resumeLayoutKey
import com.ninthsoft.ime.input.keyboard.key.returnKey
import com.ninthsoft.ime.input.keyboard.key.sidePannelKey

@SuppressLint("ViewConstructor")
class NumberKeyboard(
    context: Context,
    colors: KeyboardColors.ColorScheme,
) : BaseKeyboard(context, colors, Layout), ISidePanelKeyboard {

    init {
        this.onPossibleCandidatePinYin(emptyList())
        this.setSidePanelItemListener { action -> this.onAction(action) }
    }

    companion object {
        const val NAME = "Number"

        /**
         * 宽度：最左右两列由 0.15 加宽到 0.17，多出来的宽度从中间三列均分扣除
         * （0.23333 → 0.22），于是整块键盘正好是 0.17 / 0.22 ×3 / 0.17 的 5 等列。
         * 底行的空格、0、返回与上面的九宫格按键同宽（0.22），符号 / 回车与外侧两列对齐（0.17）。
         *
         * 回车不再跨行，只占底行最右一格；它在第 3 行空出来的那格放小数点「.」，
         * 第 2 行对应的「清空」换成「@」。
         */
        val Layout: List<List<KeyDef>> = listOf(
            listOf(
                sidePannelKey(rowSpan = 3, visableRow = 4, percentWidth = 0.17f),
                commitKey("1", percentWidth = 0.22f, fontSize = 22f),
                commitKey("2", percentWidth = 0.22f, fontSize = 22f),
                commitKey("3", percentWidth = 0.22f, fontSize = 22f),
                backspaceKey(percentWidth = 0.17f),
            ),
            listOf(
                commitKey("4", percentWidth = 0.22f, fontSize = 22f),
                commitKey("5", percentWidth = 0.22f, fontSize = 22f),
                commitKey("6", percentWidth = 0.22f, fontSize = 22f),
                // 原来的「清空」换成 @（回车缩回底行后腾出来的位置）
                atKey(percentWidth = 0.17f),
            ),
            listOf(
                commitKey("7", percentWidth = 0.22f, fontSize = 22f),
                commitKey("8", percentWidth = 0.22f, fontSize = 22f),
                commitKey("9", percentWidth = 0.22f, fontSize = 22f),
                // 回车改成只占底行后，这一格空出来放小数点
                commitKey(".", percentWidth = 0.17f, variant = Variant.Alternative),
            ),
            listOf(
                // 底行：符号 | 空格 | 0 | 切换（返回切进来的九键/26键）| 回车
                symbolPageKey(percentWidth = 0.17f),
                miniSpaceKey(percentWidth = 0.22f),
                commitKey("0", percentWidth = 0.22f, variant = Variant.Alternative),
                resumeLayoutKey("返回", percentWidth = 0.22f),
                returnKey(percentWidth = 0.17f),
            ),
        )
    }

    override fun name(): String {
        return NAME
    }

    override fun onPossibleCandidatePinYin(data: List<CandidatePinYin>) {
        super.updateSidePanel(
            KeyboardManager.Keyboard.SidePanelSymbols.getNumber(context).map { ch ->
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

    override fun updatePunctuationMode(mode: PunctuationMode)= run { }
}