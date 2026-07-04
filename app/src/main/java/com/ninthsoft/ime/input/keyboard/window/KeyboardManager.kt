package com.ninthsoft.ime.input.keyboard.window

import android.content.Context
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.core.view.contains
import androidx.core.view.isNotEmpty
import com.ninthsoft.ime.data.keyboard.theme.KeyboardColors
import com.ninthsoft.ime.data.theme.ThemeManager
import com.ninthsoft.ime.input.keyboard.impl.BaseKeyboard
import com.ninthsoft.ime.input.keyboard.impl.NormalKeyboard
import com.ninthsoft.ime.input.keyboard.impl.SymbolKeyboard
import com.ninthsoft.ime.input.keyboard.impl.T9Keyboard
import com.ninthsoft.ime.input.keyboard.key.KeyActionListener

class KeyboardManager(private val context: Context) {
    private var cachedColors: KeyboardColors.ColorScheme = KeyboardColors.resolve(context)

    private val keyboards: MutableMap<String, BaseKeyboard> = hashMapOf()

    private var currentKeyboard: BaseKeyboard? = null

    var keyActionListener: KeyActionListener = KeyActionListener.Empty
        set(value) {
            field = value
            currentKeyboard?.keyActionListener = value
        }

    fun get(name: String): BaseKeyboard? = keyboards[name]

    private fun create(name: String): BaseKeyboard {
        val b: BaseKeyboard = when (name) {
            NormalKeyboard.NAME -> NormalKeyboard(context, cachedColors)
            T9Keyboard.NAME -> T9Keyboard(context, cachedColors)
            SymbolKeyboard.NAME -> SymbolKeyboard(context, cachedColors)
            else -> error("Unknown keyboard: $name")
        }
        b.setRippleEnabled(ThemeManager.Keyboard.RippleEffect.isEnabled(context))
        return b
    }

    private fun attach(name: String, parent: ViewGroup, index: Int = -1) {
        val keyboard = keyboards.getOrPut(name) { create(name) }
        (keyboard.parent as? ViewGroup)?.removeView(keyboard)
        keyboard.keyActionListener = keyActionListener
        parent.addView(
            keyboard,
            if (index >= 0) index else parent.childCount,
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        )
        keyboard.onAttach()
        currentKeyboard = keyboard
    }

    fun switchTo(name: String, parent: ViewGroup, index: Int = -1) {
        if (name !== currentKeyboard?.name()) {
            detachCurrent()
        }
        attach(name, parent, index)
    }

    fun detachCurrent() {
        currentKeyboard?.let {
            it.keyActionListener = null
            it.onDetach()
            (it.parent as? ViewGroup)?.removeView(it)
            currentKeyboard = null
        }
    }

    fun rebuild(colors: KeyboardColors.ColorScheme, parent: ViewGroup) {
        keyboards.clear()
        cachedColors = colors
        if (currentKeyboard != null) {
            val currentName = currentKeyboard?.name().orEmpty()
            detachCurrent()
            attach(currentName, parent)
        }
    }

    fun setRippleEnabled(enabled: Boolean) {
        for (kb in keyboards.values) {
            kb.setRippleEnabled(enabled)
        }
    }
}