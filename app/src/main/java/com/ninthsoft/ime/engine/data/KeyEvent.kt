package com.ninthsoft.ime.engine.data

import android.view.KeyEvent
import java.util.Locale.getDefault

class KeyEvent(val code: Int, val modifiers: Int, val isVirtual: Boolean) {

    companion object {
        fun code(character: String): Int {
            val code = KeyEvent.keyCodeFromString("KEYCODE_${character.uppercase(getDefault())}")
            if (code == KeyEvent.KEYCODE_UNKNOWN) {
                throw Exception("keyEvent return unknown for character: $character")
            }
            return code
        }
    }
}