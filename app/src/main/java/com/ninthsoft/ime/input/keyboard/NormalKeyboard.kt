package com.ninthsoft.ime.input.keyboard

import android.annotation.SuppressLint
import android.content.Context
import com.ninthsoft.ime.data.keyboard.theme.KeyboardColors
import com.ninthsoft.ime.engine.rime.core.KeyMapping
import com.ninthsoft.ime.input.keyboard.key.KeyAction
import com.ninthsoft.ime.input.keyboard.key.KeyDef
import com.ninthsoft.ime.input.keyboard.key.KeyView
import com.ninthsoft.ime.input.keyboard.key.alphabetKey
import com.ninthsoft.ime.input.keyboard.key.backspaceKey

@SuppressLint("ViewConstructor")
class NormalKeyboard(
    context: Context,
    colors: KeyboardColors.ColorScheme,
) : BaseKeyboard(context, colors, buildLayout(uppercase = false)) {

    enum class CapsState { None, Once, Lock }

    companion object {
        const val NAME = "Normal"

        fun buildLayout(uppercase: Boolean): List<List<KeyDef>> {
            if (uppercase) "Q" else "q"
            val w = if (uppercase) "W" else "w"
            val e = if (uppercase) "E" else "e"
            val r = if (uppercase) "R" else "r"
            val t = if (uppercase) "T" else "t"
            val y = if (uppercase) "Y" else "y"
            val u = if (uppercase) "U" else "u"
            val i = if (uppercase) "I" else "i"
            val o = if (uppercase) "O" else "o"
            val p = if (uppercase) "P" else "p"
            val a = if (uppercase) "A" else "a"
            val s = if (uppercase) "S" else "s"
            val d = if (uppercase) "D" else "d"
            val f = if (uppercase) "F" else "f"
            val g = if (uppercase) "G" else "g"
            val h = if (uppercase) "H" else "h"
            val j = if (uppercase) "J" else "j"
            val k = if (uppercase) "K" else "k"
            val l = if (uppercase) "L" else "l"
            val z = if (uppercase) "Z" else "z"
            val x = if (uppercase) "X" else "x"
            val c = if (uppercase) "C" else "c"
            val v = if (uppercase) "V" else "v"
            val b = if (uppercase) "B" else "b"
            val n = if (uppercase) "N" else "n"
            val m = if (uppercase) "M" else "m"

            return listOf(
                listOf(
                    alphabetKey("q", "1"),
                    alphabetKey(w, "2"),
                    alphabetKey(e, "3"),
                    alphabetKey(r, "4"),
                    alphabetKey(t, "5"),
                    alphabetKey(y, "6"),
                    alphabetKey(u, "7"),
                    alphabetKey(i, "8"),
                    alphabetKey(o, "9"),
                    alphabetKey(p, "0"),
                ),
                listOf(
                    alphabetKey(a, "@"),
                    alphabetKey(s, "+"),
                    alphabetKey(d, "-"),
                    alphabetKey(f, "*"),
                    alphabetKey(g, "/"),
                    alphabetKey(h, "="),
                    alphabetKey(j, "#"),
                    alphabetKey(k, "("),
                    alphabetKey(l, ")"),
                ),
                listOf(
                    makeCapsKey(),
                    alphabetKey(z, "\u3002"),
                    alphabetKey(x, ","),
                    alphabetKey(c, "!"),
                    alphabetKey(v, "?"),
                    alphabetKey(b, ";"),
                    alphabetKey(n, ":"),
                    alphabetKey(m, "~"),
                    backspaceKey(),
                ),
                listOf(
                    makeLayoutSwitchKey("?123", NAME, percentWidth = 0.15f),
                    makeLangSwitchKey(percentWidth = 0.15f),
                    makeSpaceKey(),
                    makeCommaKey(percentWidth = 0.15f),
                    makeReturnKey(percentWidth = 0.15f),
                ),
            )
        }

        private fun makeCapsKey(): KeyDef = KeyDef(
            appearance = KeyDef.Appearance.Image(
                src = android.R.drawable.ic_menu_edit,
                viewId = KeyView.button_caps,
                percentWidth = 0.15f,
                variant = KeyDef.Appearance.Variant.Alternative,
            ),
            behaviors = setOf(
                KeyDef.Behavior.Press(KeyAction.CapsAction(false)),
                KeyDef.Behavior.LongPress(KeyAction.CapsAction(true)),
                KeyDef.Behavior.DoubleTap(KeyAction.CapsAction(true)),
            ),
        )


        private fun makeReturnKey(percentWidth: Float): KeyDef = KeyDef(
            appearance = KeyDef.Appearance.Image(
                src = android.R.drawable.ic_menu_send,
                viewId = KeyView.button_return,
                percentWidth = percentWidth,
                variant = KeyDef.Appearance.Variant.Accent,
                border = KeyDef.Appearance.Border.Special,
            ),
            behaviors = setOf(KeyDef.Behavior.Press(KeyAction.ReturnAction)),
        )

        private fun makeSpaceKey(): KeyDef = KeyDef(
            appearance = KeyDef.Appearance.Text(
                displayText = " ", textSize = 13f,
                border = KeyDef.Appearance.Border.Special,
                viewId = KeyView.button_space,
                variant = KeyDef.Appearance.Variant.Alternative,
            ),
            behaviors = setOf(KeyDef.Behavior.Press(KeyAction.SpaceAction)),
        )

        private fun makeCommaKey(percentWidth: Float): KeyDef = KeyDef(
            appearance = KeyDef.Appearance.ImageText(
                displayText = ".", textSize = 23f,
                src = android.R.drawable.ic_menu_gallery,
                percentWidth = percentWidth,
                variant = KeyDef.Appearance.Variant.Alternative,
            ),
            behaviors = setOf(KeyDef.Behavior.Press(KeyAction.NormalKeyAction(0, 0))),
        )

        fun makeLangSwitchKey(percentWidth: Float): KeyDef = KeyDef(
            appearance = KeyDef.Appearance.Image(
                src = android.R.drawable.ic_menu_compass,
                viewId = KeyView.button_lang,
                percentWidth = percentWidth,
                variant = KeyDef.Appearance.Variant.Alternative,
            ),
            behaviors = setOf(
                KeyDef.Behavior.Press(KeyAction.LangSwitchAction),
                KeyDef.Behavior.LongPress(KeyAction.ShowInputMethodPickerAction),
            ),
        )

        fun makeLayoutSwitchKey(
            displayText: String,
            target: String,
            percentWidth: Float = 0.15f,
        ): KeyDef = KeyDef(
            appearance = KeyDef.Appearance.Text(
                displayText = displayText, textSize = 15f,
                textStyle = android.graphics.Typeface.BOLD,
                percentWidth = percentWidth,
                variant = KeyDef.Appearance.Variant.Alternative,
            ),
            behaviors = setOf(KeyDef.Behavior.Press(KeyAction.LayoutSwitchAction(target))),
        )
    }

    private var capsState = CapsState.None

    fun getCapsState(): CapsState = capsState

    override fun onAction(action: KeyAction) {
        val transformed = when (action) {
            is KeyAction.CapsAction -> {
                switchCapsState(action.lock)
                action
            }

            is KeyAction.NormalKeyAction -> {
                val isLetter = KeyMapping.keyValToName(action.code).let { n ->
                    n.length == 1 && n[0].isLetter()
                }
                if (isLetter) {
                    when (capsState) {
                        CapsState.None -> {
                            KeyMapping.keyValToName(action.code).lowercase()
                            KeyAction.NormalKeyAction(0, 0)
                        }

                        CapsState.Once -> {
                            val up = KeyMapping.keyValToName(action.code).uppercase()
                            switchCapsState()
                            KeyAction.NormalKeyAction(
                                KeyMapping.nameToKeyVal(up), action.modifiers
                            )
                        }

                        CapsState.Lock -> {
                            val up = KeyMapping.keyValToName(action.code).uppercase()
                            KeyAction.NormalKeyAction(
                                KeyMapping.nameToKeyVal(up), action.modifiers
                            )
                        }
                    }
                } else action
            }

            else -> action
        }
        super.onAction(transformed)
    }

    override fun onAttach() {
        capsState = CapsState.None
    }

    private fun switchCapsState(lock: Boolean = false) {
        capsState = if (lock) {
            when (capsState) {
                CapsState.Lock -> CapsState.None; else -> CapsState.Lock
            }
        } else {
            when (capsState) {
                CapsState.None -> CapsState.Once; else -> CapsState.None
            }
        }
    }

    fun syncCapsState(newState: CapsState) {
        capsState = newState
    }
}
