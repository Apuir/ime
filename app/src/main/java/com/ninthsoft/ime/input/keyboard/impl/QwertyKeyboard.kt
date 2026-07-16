package com.ninthsoft.ime.input.keyboard.impl

import android.annotation.SuppressLint
import android.content.Context
import com.ninthsoft.ime.R
import com.ninthsoft.ime.data.keyboard.theme.KeyboardColors
import com.ninthsoft.ime.engine.event.KeyEvent
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
import com.ninthsoft.ime.input.keyboard.key.commaKey
import com.ninthsoft.ime.input.keyboard.key.schemaSwitchKey
import timber.log.Timber

@SuppressLint("ViewConstructor")
class QwertyKeyboard(
    context: Context,
    colors: KeyboardColors.ColorScheme,
) : BaseKeyboard(context, colors, buildLayout()) {

    enum class CapsState { None, Once, Lock }

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
                    alphabetKey("a", "@"),
                    alphabetKey("s", "+"),
                    alphabetKey("d", "-"),
                    alphabetKey("f", "*"),
                    alphabetKey("g", "/", mainTextTranslationY = -2),
                    alphabetKey("h", "="),
                    alphabetKey("j", "#"),
                    alphabetKey("k", "("),
                    alphabetKey("l", ")"),
                ),
                listOf(
                    capsLockKey(),
                    alphabetKey("z", ".", altTextTranslationY = -4),
                    alphabetKey("x", ",", altTextTranslationY = -4),
                    alphabetKey("c", "!"),
                    alphabetKey("v", "?"),
                    alphabetKey("b", ";", altTextTranslationY = -2),
                    alphabetKey("n", ":"),
                    alphabetKey("m", "~"),
                    backspaceKey(),
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


    }

    private var capsState = CapsState.None
    private val letterKeyViews = mutableListOf<TextKeyView>()
    private var capsKeyView: ImageKeyView? = null

    init {
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

            is KeyboardAction.KeySequenceAction -> {
                if (capsState != CapsState.None && KeyEvent.SequenceEvent.isLowerAlphabet(action.sequence)) {
                    if (capsState == CapsState.Once) {
                        switchCapsState(CapsState.None)
                    }
                    KeyboardAction.KeySequenceAction(action.sequence.uppercase())
                } else action
            }

            else -> action
        }
        super.onAction(transformed)
    }

    override fun onAttach() {
        capsState = CapsState.None
        updateKeyTextForState(capsState)
    }

    override fun onDetach() {
        Timber.d("oNormalKeyboard detached")
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
            kv.mainText.text = if (uppercase) text.uppercase() else text.lowercase()
        }
        capsKeyView?.img?.setImageResource(
            when (state) {
                CapsState.None -> R.drawable.ic_keyboard_capslock_none
                CapsState.Once -> R.drawable.ic_keyboard_capslock_once
                CapsState.Lock -> R.drawable.ic_keyboard_capslock_lock
            }
        )
    }
}
