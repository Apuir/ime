package com.ninthsoft.ime.input.keyboard

import android.content.Context
import com.ninthsoft.ime.data.keyboard.theme.KeyboardColors
import com.ninthsoft.ime.input.keyboard.key.KeyActionListener

class KeyboardWindow(context: Context) {
    val view: KeyboardWindowView = KeyboardWindowView(context)

    val colors: KeyboardColors.ColorScheme get() = KeyboardColors.resolve(view.context)

    fun setKeyActionListener(listener: KeyActionListener) { view.keyActionListener = listener }
    fun switchLayout(name: String) { view.switchKeyboard(name) }
    fun refreshTheme() { view.refreshColors() }
    fun isNormalKeyboard(): Boolean = view.isNormalKeyboard()
    fun getCurrentKeyboard(): BaseKeyboard? = view.getCurrentKeyboard()
}
