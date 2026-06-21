package com.ninthsoft.ime.input.keyboard

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.view.Gravity
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.LinearLayout
import com.ninthsoft.ime.data.keyboard.theme.KeyboardColors
import com.ninthsoft.ime.data.theme.ThemeManager
import com.ninthsoft.ime.engine.data.EngineMessage
import com.ninthsoft.ime.input.keyboard.key.KeyActionListener
import com.ninthsoft.ime.input.panel.IPanel
import com.ninthsoft.ime.input.panel.KawaiiPanel
import com.ninthsoft.ime.input.pinner.PreeditPinner
import kotlin.math.roundToInt

class KeyboardWindowView(
    context: Context,
    onCandidateSelected: ((EngineMessage.Candidate) -> Unit)? = null,
) : LinearLayout(context) {

    private var cachedColors: KeyboardColors.ColorScheme = KeyboardColors.resolve(context)

    private val keyboards: MutableMap<String, BaseKeyboard> = hashMapOf()

    private var currentKeyboardName = ""

    var keyActionListener: KeyActionListener = KeyActionListener.Empty
        set(value) {
            field = value
            getCurrentKeyboard()?.keyActionListener = value
        }

    val panel: IPanel = KawaiiPanel(
        context = context,
        onCandidateSelected = onCandidateSelected,
    )

    private val preeditPinner = PreeditPinner(context)

    private val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    private var pinnerShown = false

    private val keyboardHeightPct: Int
        get() {
            val prefs = context.getSharedPreferences(ThemeManager.PREFS_NAME, Context.MODE_PRIVATE)
            return prefs.getInt("keyboard.height", 24)
        }

    private val panelHeight: Int
        get() = (48 * resources.displayMetrics.density).roundToInt()

    private val keyboardHeight: Int
        get() = resources.displayMetrics.heightPixels * keyboardHeightPct / 100

    init {
        orientation = VERTICAL
        layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        setBackgroundColor(cachedColors.background)

        addView(panel.view, LayoutParams(LayoutParams.MATCH_PARENT, panelHeight))

        keyboards[NormalKeyboard.NAME] = NormalKeyboard(context, cachedColors)
        attachKeyboard(NormalKeyboard.NAME)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val totalHeight = panelHeight + keyboardHeight
        super.onMeasure(
            widthMeasureSpec,
            MeasureSpec.makeMeasureSpec(totalHeight, MeasureSpec.EXACTLY),
        )
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
            },
        )
    }

    fun refreshColors() {
        cachedColors = KeyboardColors.resolve(context)
        setBackgroundColor(cachedColors.background)
        panel.refreshTheme()
        preeditPinner.refreshTheme(context)
    }

    fun setCandidates(list: List<EngineMessage.Candidate>) {
        panel.setCandidates(list)
    }

    fun updatePreedit(text: String?) {
        preeditPinner.updateText(text)
        if (text.isNullOrBlank()) {
            removePinnerWindow()
        } else {
            showOrUpdatePinner()
        }
    }

    private fun showOrUpdatePinner() {
        val pinnerView = preeditPinner.view
        pinnerView.measure(
            MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED),
            MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED),
        )
        val pillW = pinnerView.measuredWidth
        val pillH = pinnerView.measuredHeight
        if (pillW <= 0 || pillH <= 0) return

        val density = resources.displayMetrics.density
        val hPad = (4 * density).roundToInt()

        val loc = IntArray(2)
        panel.view.getLocationOnScreen(loc)
        val x = loc[0] + hPad
        val y = loc[1] - pillH

        val params = WindowManager.LayoutParams().apply {
            this.width = pillW
            this.height = pillH
            this.x = x
            this.y = y
            gravity = Gravity.TOP or Gravity.START
            format = PixelFormat.TRANSLUCENT
            flags = WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
            token = panel.view.windowToken
            type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                WindowManager.LayoutParams.TYPE_APPLICATION_PANEL
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_APPLICATION_PANEL
            }
        }

        try {
            if (pinnerShown) {
                wm.updateViewLayout(pinnerView, params)
            } else {
                wm.addView(pinnerView, params)
                pinnerShown = true
            }
        } catch (_: Exception) {
        }
    }

    private fun removePinnerWindow() {
        if (!pinnerShown) return
        try {
            wm.removeView(preeditPinner.view)
        } catch (_: Exception) {
        }
        pinnerShown = false
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
            addView(it, LayoutParams(LayoutParams.MATCH_PARENT, keyboardHeight))
            it.onAttach()
        }
    }

    override fun onDetachedFromWindow() {
        removePinnerWindow()
        detachCurrentKeyboard()
        super.onDetachedFromWindow()
    }
}
