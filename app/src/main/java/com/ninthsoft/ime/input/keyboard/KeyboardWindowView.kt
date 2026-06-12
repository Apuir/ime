package com.ninthsoft.ime.input.keyboard

import android.content.Context
import android.view.inputmethod.EditorInfo
import android.widget.FrameLayout
import com.ninthsoft.ime.data.keyboard.theme.KeyboardColors
import com.ninthsoft.ime.data.theme.ThemeManager
import com.ninthsoft.ime.input.keyboard.key.KeyActionListener
import splitties.views.dsl.core.add
import splitties.views.dsl.core.lParams
import splitties.views.dsl.core.matchParent

class KeyboardWindowView(context: Context) : FrameLayout(context) {

    private var cachedColors: KeyboardColors.ColorScheme = KeyboardColors.resolve(context)

    private val keyboards: MutableMap<String, BaseKeyboard> = hashMapOf()

    private var currentKeyboardName = ""

    var keyActionListener: KeyActionListener = KeyActionListener.Empty
        set(value) {
            field = value
            getCurrentKeyboard()?.keyActionListener = value
        }

    private val keyboardHeightPct: Int
        get() {
            val prefs = context.getSharedPreferences(ThemeManager.PREFS_NAME, Context.MODE_PRIVATE)
            return prefs.getInt("keyboard.height", 24)
        }

    init {
        layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT,
        )
        setBackgroundColor(cachedColors.background)

        keyboards[NormalKeyboard.NAME] = NormalKeyboard(context, cachedColors)
        attachKeyboard(NormalKeyboard.NAME)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val displayHeight = resources.displayMetrics.heightPixels
        val desiredHeight = displayHeight * keyboardHeightPct / 100
        super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(desiredHeight, MeasureSpec.EXACTLY))
    }

    fun switchKeyboard(name: String) {
        if (name == currentKeyboardName || !keyboards.containsKey(name)) return
        detachCurrentKeyboard()
        attachKeyboard(name)
    }

    fun getCurrentKeyboardName(): String = currentKeyboardName
    fun getCurrentKeyboard(): BaseKeyboard? = keyboards[currentKeyboardName]
    fun isNormalKeyboard(): Boolean = currentKeyboardName == NormalKeyboard.NAME

    fun onStartInput(info: EditorInfo) {
        switchKeyboard(
            when (info.inputType and android.text.InputType.TYPE_MASK_CLASS) {
                android.text.InputType.TYPE_CLASS_NUMBER, android.text.InputType.TYPE_CLASS_PHONE -> SymbolKeyboard.NAME
                else -> NormalKeyboard.NAME
            }
        )
    }

    fun refreshColors() {
        cachedColors = KeyboardColors.resolve(context)
        setBackgroundColor(cachedColors.background)
    }

    private fun detachCurrentKeyboard() {
        keyboards[currentKeyboardName]?.also {
            it.onDetach()
            it.keyActionListener = null
            removeView(it)
        }
    }

    private fun attachKeyboard(name: String) {
        currentKeyboardName = name
        keyboards[name]?.let {
            it.keyActionListener = keyActionListener
            add(it, lParams(matchParent, matchParent))
            it.onAttach()
        }
    }

    override fun onDetachedFromWindow() {
        detachCurrentKeyboard()
        super.onDetachedFromWindow()
    }
}
