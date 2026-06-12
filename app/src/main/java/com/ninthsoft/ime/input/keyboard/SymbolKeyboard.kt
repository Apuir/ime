package com.ninthsoft.ime.input.keyboard

import android.annotation.SuppressLint
import android.content.Context
import com.ninthsoft.ime.data.keyboard.theme.KeyboardColors
import com.ninthsoft.ime.engine.rime.core.RimeKeyMapping
import com.ninthsoft.ime.input.keyboard.key.KeyAction
import com.ninthsoft.ime.input.keyboard.key.KeyDef
import com.ninthsoft.ime.input.keyboard.key.KeyView

@SuppressLint("ViewConstructor")
class SymbolKeyboard(
    context: Context,
    colors: KeyboardColors.ColorScheme,
) : BaseKeyboard(context, colors, Layout) {

    companion object {
        const val NAME = "Symbol"

        val Layout: List<List<KeyDef>> = listOf(
        )
    }
}
