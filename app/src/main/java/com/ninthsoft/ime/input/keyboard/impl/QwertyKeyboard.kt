package com.ninthsoft.ime.input.keyboard.impl

import android.annotation.SuppressLint
import android.content.Context
import com.ninthsoft.ime.R
import com.ninthsoft.ime.base.util.PunctuationUtil
import com.ninthsoft.ime.data.Punctuation
import com.ninthsoft.ime.data.keyboard.theme.KeyboardColors
import com.ninthsoft.ime.engine.event.KeyEvent
import com.ninthsoft.ime.input.keyboard.key.AltTextKeyView
import com.ninthsoft.ime.input.keyboard.key.KeyboardAction
import com.ninthsoft.ime.input.keyboard.key.KeyDef
import com.ninthsoft.ime.input.keyboard.key.ImageKeyView
import com.ninthsoft.ime.input.keyboard.key.TextKeyView
import com.ninthsoft.ime.input.keyboard.key.alphabetKey
import com.ninthsoft.ime.input.keyboard.key.backspaceKey
import com.ninthsoft.ime.input.keyboard.key.capsLockKey
import com.ninthsoft.ime.input.keyboard.key.layoutSwitchKey
import com.ninthsoft.ime.input.keyboard.key.returnKey
import com.ninthsoft.ime.input.keyboard.key.spaceKey
import com.ninthsoft.ime.input.keyboard.key.peroidKey
import com.ninthsoft.ime.input.keyboard.key.schemaSwitchKey

@SuppressLint("ViewConstructor")
class QwertyKeyboard(
    context: Context,
    colors: KeyboardColors.ColorScheme,
) : BaseKeyboard(context, colors, buildLayout()) {

    enum class CapsState { None, Once, Lock }

    var punctuationState: Punctuation = Punctuation.FullWidth

    var asciiPunctuationState: Punctuation? = null

    companion object {
        const val NAME = "Qwerty"

        fun buildLayout(): List<List<KeyDef>> {
            return listOf(
                listOf(
                    alphabetKey("q", "1"),
                    alphabetKey("w", "2"),
                    alphabetKey("e", "3"),
                    alphabetKey("r", "4"),
                    alphabetKey("t", "5"),
                    alphabetKey("y", "6"),
                    alphabetKey("u", "7"),
                    alphabetKey("i", "8"),
                    alphabetKey("o", "9"),
                    alphabetKey("p", "0"),
                ),
                listOf(
                    alphabetKey("a", "~"),
                    alphabetKey("s", "!"),
                    alphabetKey("d", "@"),
                    alphabetKey("f", "#"),
                    alphabetKey("g", "$", mainTextTranslationY = -3),
                    alphabetKey("h", "%"),
                    alphabetKey("j", "^", altTextTranslationY = 6),
                    alphabetKey("k", "&"),
                    alphabetKey("l", "*", altTextTranslationY = 6),
                ),
                listOf(
                    capsLockKey(),
                    alphabetKey("z", "(", altTextTranslationY = 0),
                    alphabetKey("x", ")", altTextTranslationY = 0),
                    alphabetKey("c", ":"),
                    alphabetKey("v", ";"),
                    alphabetKey("b", ",", altTextTranslationY = 0),
                    alphabetKey("n", "?"),
                    alphabetKey("m", "/"),
                    backspaceKey(),
                ),
                listOf(
                    layoutSwitchKey("?123", NumberKeyboard.NAME, percentWidth = 0.15f),
                    schemaSwitchKey(0.13f),
                    spaceKey(),
                    peroidKey(percentWidth = 0.13f),
                    returnKey(percentWidth = 0.15f),
                ),
            )
        }


    }

    private var spaceRawText: String = ""
    private var asciiMode = false
    private var capsState = CapsState.None
    private val letterKeyViews = mutableListOf<TextKeyView>()
    private var capsKeyView: ImageKeyView? = null

    init {
        this.updatePunctuation(punctuationState)
        for (row in keyRows) {
            for (i in 0 until row.childCount) {
                when (val child = row.getChildAt(i)) {
                    is TextKeyView -> {
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

            is KeyboardAction.ToggleAscIIAction -> {
                if (asciiMode) {
                    asciiPunctuationState?.let { updatePunctuation(it) }
                    asciiPunctuationState = null
                } else {
                    asciiPunctuationState = punctuationState
                }
                asciiMode = !asciiMode
                updateSpaceKeyText(if (asciiMode) "English" else spaceRawText)
                switchCapsState(CapsState.None)
                return
            }

            is KeyboardAction.CommitAction -> {
                KeyboardAction.CommitAction(
                    PunctuationUtil.convert(
                        action.text, punctuationState == Punctuation.FullWidth
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
                // 如果 asciiMode 触发，应该使用转换后（transformed）的数据
                if (asciiMode) {
                    KeyboardAction.CommitAction(transformed.sequence)
                } else {
                    transformed
                }
            }

            else -> action
        }
        super.onAction(transformed)
    }

    override fun onAttach() {
        capsState = CapsState.None
        asciiMode = false
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
        if (asciiPunctuationState != null) {
            updatePunctuation(if (asciiMode) Punctuation.HalfWidth else asciiPunctuationState!!)
        }
    }


    private fun updateKeyTextForState(state: CapsState) {
        val uppercase = state != CapsState.None
        for (kv in letterKeyViews) {
            val text = kv.mainText.text.toString()
            kv.mainText.text = if (uppercase) text.uppercase() else text.lowercase()
        }
        capsKeyView?.img?.setImageResource(
            when (state) {
                CapsState.None -> if (!asciiMode) R.drawable.ic_keyboard_capslock_none else R.drawable.ic_keyboard_capslock_none_ascii
                CapsState.Once -> if (!asciiMode) R.drawable.ic_keyboard_capslock_once else R.drawable.ic_keyboard_capslock_once_ascii
                CapsState.Lock -> if (!asciiMode) R.drawable.ic_keyboard_capslock_lock else R.drawable.ic_keyboard_capslock_lock_ascii
            }
        )
    }

    override fun updateSpaceKeyText(text: String) {
        if (!asciiMode) {
            spaceRawText = text
        }
        super.updateSpaceKeyText(text)
    }

    override fun updatePunctuation(punctuation: Punctuation) {
        this@QwertyKeyboard.punctuationState = punctuation
        this.updatePeriodKeyText(if (punctuation == Punctuation.FullWidth) "。" else ".")
        for (kv in letterKeyViews) {
            if (kv is AltTextKeyView) {
                val altText = kv.altText.text.toString()
                val altNewText =
                    PunctuationUtil.convert(altText, punctuation == Punctuation.FullWidth)
                if (altText != altNewText) {
                    kv.updateAltText(altNewText)
                }
            }
        }
    }
}
