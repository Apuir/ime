package com.ninthsoft.ime.input.pinner

import android.content.Context
import android.view.View

interface IPinner {
    val view: View
    fun updateText(text: String?)
    fun refreshTheme(context: Context)
}
