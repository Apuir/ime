package com.ninthsoft.ime.input.pinner

import android.content.Context
import com.ninthsoft.ime.data.keyboard.theme.KeyboardColors

class PreeditPinner(context: Context) : IPinner {

    override val view: PreeditPinnerView = PreeditPinnerView(context)

    init {
        refreshTheme(context)
    }

    override fun refreshTheme(context: Context) {
        val pinner = KeyboardColors.resolve(context).pinner
        val textSize = 15f * context.resources.displayMetrics.density
        view.applyTheme(pinner.background, pinner.textColor, textSize)
    }

    override fun updateText(text: String?) {
        view.preeditText = text
    }
}
