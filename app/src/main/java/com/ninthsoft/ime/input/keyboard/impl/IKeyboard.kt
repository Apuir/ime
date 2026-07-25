package com.ninthsoft.ime.input.keyboard.impl

import android.view.inputmethod.EditorInfo
import com.ninthsoft.ime.data.Punctuation
import com.ninthsoft.ime.input.keyboard.key.KeyActionListener
import com.ninthsoft.ime.input.keyboard.window.IManagedView

interface IKeyboard : IManagedView {
    var keyActionListener: KeyActionListener?
    fun name(): String
    fun updateSpaceKeyText(text: String)
    fun updatePeriodKeyText(text: String)
    fun updatePunctuation(punctuation: Punctuation)
    fun updateEditorInfo(info: EditorInfo)
    fun setRippleEnabled(enabled: Boolean)
}
