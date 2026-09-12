package com.ninthsoft.ime.input.keyboard.impl

import android.annotation.SuppressLint
import android.content.Context
import com.ninthsoft.ime.R
import com.ninthsoft.ime.base.util.PunctuationUtil
import com.ninthsoft.ime.data.PunctuationMode
import com.ninthsoft.ime.data.keyboard.theme.KeyboardColors
import com.ninthsoft.ime.data.manager.KeyboardKeyMapping
import com.ninthsoft.ime.engine.event.KeyEvent
import com.ninthsoft.ime.input.keyboard.key.AltTextKeyView
import com.ninthsoft.ime.input.keyboard.key.KeyboardAction
import com.ninthsoft.ime.input.keyboard.key.KeyDef
import com.ninthsoft.ime.input.keyboard.key.KeyDef.Appearance.Variant
import com.ninthsoft.ime.input.keyboard.key.ImageKeyView
import com.ninthsoft.ime.input.keyboard.key.TextKeyView
import com.ninthsoft.ime.input.keyboard.key.alphabetKey
import com.ninthsoft.ime.input.keyboard.key.backspaceKey
import com.ninthsoft.ime.input.keyboard.key.capsLockKey
import com.ninthsoft.ime.input.keyboard.key.commitKey
import com.ninthsoft.ime.input.keyboard.key.layoutSwitchKey
import com.ninthsoft.ime.input.keyboard.key.returnKey
import com.ninthsoft.ime.input.keyboard.key.schemaSwitchKey
import com.ninthsoft.ime.input.keyboard.key.spaceKey
import com.ninthsoft.ime.input.keyboard.key.symbolPageKey

@SuppressLint("ViewConstructor")
class QwertyKeyboard(
    context: Context,
    colors: KeyboardColors.ColorScheme,
) : BaseKeyboard(context, colors, { ctx -> buildLayout(ctx) }) {

    enum class CapsState { None, Once, Lock }

    var punctuationState: PunctuationMode = PunctuationMode.FullWidth

    companion object {
        const val NAME = "Qwerty"

        /** 字母键上主文字 / 次级文字的微调，保持旧版写死布局里的手工对齐。 */
        private val keyTextOffsets: Map<String, Pair<Int, Int>> = mapOf(
            // 字母 -> (altTextTranslationY, mainTextTranslationY)
            "g" to (2 to -2),
            "j" to (4 to -2),
            "l" to (4 to -2),
        )

        /**
         * 26 键布局。字母键下的符号 / 数字来自用户映射（[KeyboardKeyMapping]），
         * 没改过的键就是旧版写死的那套（q→1、g→$ …），因此默认行为不变。
         *
         * [withBubble] 同时给字母键带上气泡内容：小写字母 → 该键的符号 / 数字 → 大写字母。
         */
        fun buildLayout(context: Context, withBubble: Boolean = true): List<List<KeyDef>> {
            fun letter(character: String): KeyDef {
                val (altY, mainY) = keyTextOffsets[character] ?: (2 to -2)
                return alphabetKey(
                    character = character,
                    punctuation = KeyboardKeyMapping.qwertySymbol(context, character),
                    altTextTranslationY = altY,
                    mainTextTranslationY = mainY,
                    bubble = if (withBubble) {
                        KeyboardKeyMapping.qwertyBubbleItems(context, character)
                    } else {
                        null
                    },
                )
            }

            return listOf(
                listOf("q", "w", "e", "r", "t", "y", "u", "i", "o", "p").map(::letter),
                listOf("a", "s", "d", "f", "g", "h", "j", "k", "l").map(::letter),
                listOf(
                    capsLockKey(),
                    *listOf("z", "x", "c", "v", "b", "n", "m").map(::letter).toTypedArray(),
                    backspaceKey(),
                ),
                listOf(
                    // 底行：符号 | 中英切换 | . | 空格 | , | 数字 | 回车
                    // 空格两侧各放一个窄的 . / , 键。键帽固定显示半角（displayFollowsPunctuationMode
                    // = false），但 CommitAction 仍会经过标点转换，所以全角模式下上屏的是 。/，。
                    symbolPageKey(percentWidth = 0.13f),
                    schemaSwitchKey(0.12f),
                    commitKey(
                        ".",
                        percentWidth = 0.09f,
                        variant = Variant.Alternative,
                        displayFollowsPunctuationMode = false,
                    ),
                    spaceKey(percentWidth = 0.30f),
                    commitKey(
                        ",",
                        percentWidth = 0.09f,
                        variant = Variant.Alternative,
                        displayFollowsPunctuationMode = false,
                    ),
                    layoutSwitchKey("123", NumberKeyboard.NAME, percentWidth = 0.12f),
                    returnKey(percentWidth = 0.15f),
                ),
            )
        }

    }

    private var capsState = CapsState.None
    private val letterKeyViews = mutableListOf<AltTextKeyView>()
    private var capsKeyView: ImageKeyView? = null

    init {
        this.updatePunctuationMode(punctuationState)
        for (row in keyRows) {
            for (i in 0 until row.childCount) {
                when (val child = row.getChildAt(i)) {
                    is AltTextKeyView -> {
                        val text = child.mainText.text.toString()
                        if (text.length == 1 && text[0].isLetter()) {
                            letterKeyViews.add(child)
                        }
                    }

                    is ImageKeyView -> {
                        if ((child.def as KeyDef.Appearance.Image).src == R.drawable.ic_keyboard_capslock_none) {
                            capsKeyView = child
                        }
                    }
                }
            }
        }
    }

    override fun onAction(action: KeyboardAction) {
        val transformed = when (action) {
            is KeyboardAction.CapsAction -> {
                switchCapsState()
                return
            }

            is KeyboardAction.CommitAction -> {
                KeyboardAction.CommitAction(
                    PunctuationUtil.convert(
                        action.text, punctuationState == PunctuationMode.FullWidth
                    )
                )
            }

            is KeyboardAction.KeySequenceAction -> {
                val transformed =
                    if (capsState != CapsState.None && KeyEvent.SequenceEvent.isLowerAlphabet(action.sequence)) {
                        if (capsState == CapsState.Once) {
                            switchCapsState(CapsState.None)
                        }
                        KeyboardAction.KeySequenceAction(action.sequence.uppercase())
                    } else {
                        action
                    }
                transformed
            }

            else -> action
        }
        super.onAction(transformed)
    }

    override fun onAttach() {
        super.onAttach()
        capsState = CapsState.None
        updateKeyTextForState(capsState)
    }

    override fun name(): String {
        return NAME
    }

    private fun switchCapsState(target: CapsState? = null) {
        capsState = target ?: when (capsState) {
            CapsState.None -> CapsState.Once
            CapsState.Once -> CapsState.Lock
            CapsState.Lock -> CapsState.None
        }
        updateKeyTextForState(capsState)
    }


    private fun updateKeyTextForState(state: CapsState) {
        val uppercase = state != CapsState.None
        for (kv in letterKeyViews) {
            val text = kv.mainText.text.toString()
            kv.updateText(if (uppercase) text.uppercase() else text.lowercase())
        }
        capsKeyView?.img?.setImageResource(
            when (state) {
                CapsState.None -> R.drawable.ic_keyboard_capslock_none
                CapsState.Once -> R.drawable.ic_keyboard_capslock_once
                CapsState.Lock -> R.drawable.ic_keyboard_capslock_lock
            }
        )
    }

    override fun updatePunctuationMode(mode: PunctuationMode) {
        punctuationState = mode
        super.updatePunctuationMode(mode)
    }
}
