package com.ninthsoft.ime.input.keyboard.key

fun interface KeyActionListener {
    fun onKeyAction(action: KeyAction)

    companion object {
        val Empty = KeyActionListener {}
    }
}
