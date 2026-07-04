package com.ninthsoft.ime.input.keyboard

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.content.res.Resources
import android.graphics.PixelFormat
import android.util.TypedValue
import android.view.Gravity
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.FrameLayout
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isGone
import com.ninthsoft.ime.data.keyboard.theme.KeyboardColors
import com.ninthsoft.ime.data.theme.ThemeManager
import com.ninthsoft.ime.engine.data.EngineMessage
import com.ninthsoft.ime.input.keyboard.key.KeyActionListener
import com.ninthsoft.ime.input.panel.IPanel
import com.ninthsoft.ime.input.panel.KawaiiPanel
import com.ninthsoft.ime.input.pinner.PreeditPinner
import kotlin.math.max
import kotlin.math.roundToInt

@SuppressLint("ViewConstructor")
class KeyboardWindowView(
    context: Context,
    onCandidateSelected: ((EngineMessage.Candidate) -> Unit)? = null,
    onRerankedSelected: ((String) -> Unit)? = null,
) : FrameLayout(context) {

    private val fallbackNavBarHeight = 48

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
        onRerankedSelected = onRerankedSelected,
    )

    private val preeditPinner = PreeditPinner(context)

    private val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    private var pinnerShown = false

    private val prefs: SharedPreferences =
        context.getSharedPreferences(ThemeManager.PREFS_NAME, Context.MODE_PRIVATE)

    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        when (key) {
            "keyboard.height", "keyboard.padding.horizontal", "keyboard.padding.bottom", "keyboard.ignore_insets" -> post { requestLayout() }
            "keyboard.key_radius", "keyboard.theme", "keyboard.gap.horizontal", "keyboard.gap.vertical" -> post { refreshColors() }
            "keyboard.ripple_effect" -> post {
                val enabled = ThemeManager.Keyboard.RippleEffect.isEnabled(context)
                for (kb in keyboards.values) {
                    kb.setRippleEnabled(enabled)
                }
            }
            "keyboard.key_border_stroke" -> post { refreshColors() }
        }
    }

    private val panelHeight: Int
        get() = (48 * resources.displayMetrics.density).roundToInt()

    init {
        prefs.registerOnSharedPreferenceChangeListener(prefsListener)
        ViewCompat.setOnApplyWindowInsetsListener(this) { view, insets ->
            view.requestLayout()
            insets
        }
        setBackgroundColor(cachedColors.background)

        addView(panel.view, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        createKeyboard(NormalKeyboard.NAME) { NormalKeyboard(context, cachedColors) }
        createKeyboard(T9Keyboard.NAME) { T9Keyboard(context, cachedColors) }
        createKeyboard(SymbolKeyboard.NAME) { SymbolKeyboard(context, cachedColors) }
        attachKeyboard(NormalKeyboard.NAME)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        ViewCompat.requestApplyInsets(this)
    }

    override fun onDetachedFromWindow() {
        removePinnerWindow()
        detachCurrentKeyboard()
        prefs.unregisterOnSharedPreferenceChangeListener(prefsListener)
        super.onDetachedFromWindow()
    }

    private fun contentHeight(): Int {
        val percent = ThemeManager.Keyboard.getHeightPercent(context)
        return (resources.displayMetrics.heightPixels * percent / 100).coerceAtLeast(minimumHeight)
    }

    private fun dpToPx(dp: Int): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, dp.toFloat(), resources.displayMetrics
        ).toInt()
    }

    @SuppressLint("DiscouragedApi")
    private fun navbarFrameHeight(): Int {
        val resId = resources.getIdentifier("navigation_bar_frame_height", "dimen", "android")
        return try {
            resources.getDimensionPixelSize(resId)
        } catch (_: Resources.NotFoundException) {
            (fallbackNavBarHeight * resources.displayMetrics.density).toInt()
        }
    }

    private fun resolveBottomInset(): Int {
        if (ThemeManager.Keyboard.getIgnoreInsets(context)) return 0
        val insets = ViewCompat.getRootWindowInsets(this) ?: return 0
        val navBars = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
        val mandatory = insets.getInsets(WindowInsetsCompat.Type.mandatorySystemGestures())
        var insetsBottom = max(navBars.bottom, mandatory.bottom)
        if (insetsBottom <= 0) {
            val gesturesBottom = insets.getInsets(WindowInsetsCompat.Type.systemGestures()).bottom
            if (gesturesBottom > 0) {
                insetsBottom = max(gesturesBottom, navbarFrameHeight())
            }
        }
        return insetsBottom
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val density = resources.displayMetrics.density
        val hPad = dpToPx(ThemeManager.Keyboard.Padding.getHorizontalDp(context))
        val bPad = dpToPx(ThemeManager.Keyboard.Padding.getBottomDp(context))
        val cHeight = contentHeight()
        val totalWidth = MeasureSpec.getSize(widthMeasureSpec)
        val bottomInset = resolveBottomInset()
        val barH = panelHeight
        val contentW = (totalWidth - 2 * hPad).coerceAtLeast(0)

        panel.view.measure(
            MeasureSpec.makeMeasureSpec(contentW, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(barH, MeasureSpec.EXACTLY),
        )

        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child === panel.view || child.isGone) continue
            child.measure(
                MeasureSpec.makeMeasureSpec(contentW, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(cHeight, MeasureSpec.EXACTLY),
            )
        }

        val totalHeight = barH + cHeight + bPad + bottomInset
        setMeasuredDimension(totalWidth, totalHeight)
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        val hPad = dpToPx(ThemeManager.Keyboard.Padding.getHorizontalDp(context))
        val cHeight = contentHeight()
        val contentW = right - left - 2 * hPad
        val barH = panelHeight

        panel.view.layout(hPad, 0, hPad + contentW, barH)
        var y = barH

        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child === panel.view || child.isGone) continue
            child.layout(hPad, y, hPad + contentW, y + cHeight)
        }
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
        rebuildKeyboards()
    }

    private fun rebuildKeyboards() {
        val currentName = currentKeyboardName
        detachCurrentKeyboard()
        keyboards.clear()
        createKeyboard(NormalKeyboard.NAME) { NormalKeyboard(context, cachedColors) }
        createKeyboard(T9Keyboard.NAME) { T9Keyboard(context, cachedColors) }
        createKeyboard(SymbolKeyboard.NAME) { SymbolKeyboard(context, cachedColors) }
        if (currentName.isNotEmpty()) attachKeyboard(currentName)
    }

    private fun createKeyboard(name: String, factory: () -> BaseKeyboard) {
        val kb = factory()
        kb.setRippleEnabled(ThemeManager.Keyboard.RippleEffect.isEnabled(context))
        keyboards[name] = kb
    }

    fun refreshLayout() {
        requestLayout()
    }

    fun setCandidates(list: List<EngineMessage.Candidate>) {
        panel.setCandidates(list)
    }

    fun setRerankedCandidate(candidate: EngineMessage.Candidate) {
        panel.setRerankedCandidate(candidate)
    }

    fun updateSidePanel(items: List<com.ninthsoft.ime.input.keyboard.key.KeyDef>) {
        (getCurrentKeyboard() as? T9Keyboard)?.updateSidePanel(items)
    }

    fun setSidePanelItemListener(listener: (com.ninthsoft.ime.input.keyboard.key.KeyAction) -> Unit) {
        (getCurrentKeyboard() as? T9Keyboard)?.setSidePanelItemListener(listener)
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
        val hPad = dpToPx(ThemeManager.Keyboard.Padding.getHorizontalDp(context))

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
            flags =
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
            token = panel.view.windowToken
            type = WindowManager.LayoutParams.TYPE_APPLICATION_PANEL
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
            addView(it, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
            it.onAttach()
        }
    }
}
