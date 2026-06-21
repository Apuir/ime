package com.ninthsoft.ime.input.keyboard

import android.annotation.SuppressLint
import android.content.Context
import com.ninthsoft.ime.data.keyboard.theme.KeyboardColors
import com.ninthsoft.ime.input.keyboard.key.KeyDef

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
