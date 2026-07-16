package com.ninthsoft.ime.input.keyboard.window

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Resources
import android.util.TypedValue
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.FrameLayout
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isGone
import com.ninthsoft.ime.data.keyboard.theme.KeyboardColors
import com.ninthsoft.ime.data.manager.SchemaManager
import com.ninthsoft.ime.data.manager.KeyboardManager
import com.ninthsoft.ime.engine.data.CandidatePinYin
import com.ninthsoft.ime.engine.data.EngineMessage
import com.ninthsoft.ime.input.keyboard.key.KeyActionListener
import com.ninthsoft.ime.input.keyboard.key.KeyboardAction
import com.ninthsoft.ime.input.panel.KawaiiPanel
import com.ninthsoft.ime.input.pinner.PreeditPinner
import kotlin.math.max
import kotlin.math.roundToInt

@SuppressLint("ViewConstructor")
class KeyboardWindowView(
    context: Context,
    onCandidateSelected: ((EngineMessage.Candidate) -> Unit)? = null,
    onRerankedSelected: ((String) -> Unit)? = null,
    onToolbarAction: ((KawaiiPanel.Action) -> Unit)? = null,
) : FrameLayout(context), IManagedView {

    companion object {
        const val PANEL_HEIGHT_DP = 44
    }

    private val fallbackNavBarHeight = 48

    private var cachedColors: KeyboardColors.ColorScheme = KeyboardColors.resolve(context)

    private val keyboardManager = KeyboardManager(context, this)

    val panel = KawaiiPanel(
        context = context,
        onCandidateSelected = onCandidateSelected,
        onRerankedSelected = onRerankedSelected,
        onToolbarAction = onToolbarAction,
    )

    private val preeditPinner = PreeditPinner(context)

    private val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    var keyActionListener: KeyActionListener
        get() = keyboardManager.keyActionListener
        set(value) {
            keyboardManager.keyActionListener = KeyActionListener { action ->
                transformed(action).let { value.onKeyAction(it) }
            }
        }

    fun transformed(action: KeyboardAction): KeyboardAction {
        if (action is KeyboardAction.RotateSchema) {
            val schemeId = keyboardManager.rotateSchema()
            return KeyboardAction.SelectSchema(schemeId)
        }
        if (action is KeyboardAction.LayoutSwitchAction) {
            keyboardManager.switchTo(action.target)
        }
        return action
    }


    fun updateSchemaLayout(schemaId: String, name: String, layout: String) {
        keyboardManager.updateSchemaLayout(layout)
        keyboardManager.currentKeyboard()?.updateSpaceKeyText(name)
    }


    fun onConfigChanged(key: String) {
        when (key) {
            SchemaManager.KEY_ENABLED_IDS -> keyboardManager.onConfigChanged(key)
            KeyboardManager.Keyboard.KEY_HEIGHT,
            KeyboardManager.Keyboard.Padding.KEY_HORIZONTAL,
            KeyboardManager.Keyboard.Padding.KEY_BOTTOM,
            KeyboardManager.Keyboard.KEY_IGNORE_INSETS -> post {
                panel.view.updateHorizontalPadding(
                    KeyboardManager.Keyboard.Padding.getHorizontalDp(context).toFloat()
                )
                requestLayout()
            }

            KeyboardManager.Keyboard.KeyRadius.KEY,
            KeyboardManager.Keyboard.KEY_THEME,
            KeyboardManager.Keyboard.Gap.KEY_HORIZONTAL,
            KeyboardManager.Keyboard.Gap.KEY_VERTICAL -> post { refreshColors() }
            KeyboardManager.Keyboard.RippleEffect.KEY -> post {
                keyboardManager.setRippleEnabled(
                    KeyboardManager.Keyboard.RippleEffect.isEnabled(context)
                )
            }

            KeyboardManager.Keyboard.KeyBorderStroke.KEY -> post { refreshColors() }
        }
    }

    init {
        ViewCompat.setOnApplyWindowInsetsListener(this) { view, insets ->
            view.requestLayout()
            insets
        }
        setBackgroundColor(cachedColors.background)

        addView(panel.view, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        addView(
            panel.candidateGrid, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
        )
        addView(panel.menuGrid, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
    }

    fun toggleMenu() {
        panel.toggleMenu()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        ViewCompat.requestApplyInsets(this)
    }

    override fun onDetachedFromWindow() {
        panel.onFinishInputView(true)
        preeditPinner.hide(wm)
        super.onDetachedFromWindow()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val density = resources.displayMetrics.density
        val hPad = dpToPx(KeyboardManager.Keyboard.Padding.getHorizontalDp(context))
        val bPad = dpToPx(KeyboardManager.Keyboard.Padding.getBottomDp(context))
        val barH = (PANEL_HEIGHT_DP * density).roundToInt()
        val cHeight = contentHeight()
        val totalWidth = MeasureSpec.getSize(widthMeasureSpec)
        val bottomInset = resolveBottomInset()
        val contentW = (totalWidth - 2 * hPad).coerceAtLeast(0)

        panel.view.measure(
            MeasureSpec.makeMeasureSpec(totalWidth, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(barH, MeasureSpec.EXACTLY),
        )

        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child === panel.view || child === panel.menuGrid || child.isGone) continue
            child.measure(
                MeasureSpec.makeMeasureSpec(contentW, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(cHeight, MeasureSpec.EXACTLY),
            )
        }

        panel.menuGrid.measure(
            MeasureSpec.makeMeasureSpec(contentW, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(cHeight, MeasureSpec.EXACTLY),
        )

        val totalHeight = barH + cHeight + bPad + bottomInset
        setMeasuredDimension(totalWidth, totalHeight)
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        val hPad = dpToPx(KeyboardManager.Keyboard.Padding.getHorizontalDp(context))
        val barH = (PANEL_HEIGHT_DP * resources.displayMetrics.density).roundToInt()
        val cHeight = contentHeight()
        val contentW = right - left - 2 * hPad

        panel.view.layout(0, 0, right - left, barH)

        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child === panel.view || child === panel.candidateGrid || child === panel.menuGrid || child.isGone) continue
            child.layout(hPad, barH, hPad + contentW, barH + cHeight)
        }

        panel.candidateGrid.layout(hPad, barH, hPad + contentW, barH + cHeight)
        panel.menuGrid.layout(hPad, barH, hPad + contentW, barH + cHeight)
    }


    fun onStartInput(info: EditorInfo) {
        panel.view.setExpanded(false)
        keyboardManager.startInput(info)
    }

    fun refreshColors() {
        panel.view.setExpanded(false)
        cachedColors = KeyboardColors.resolve(context)
        setBackgroundColor(cachedColors.background)
        panel.refreshTheme()
        preeditPinner.refreshTheme(context)
        keyboardManager.rebuild(cachedColors)
    }

    fun refreshLayout() = requestLayout()

    fun setCandidates(list: List<EngineMessage.Candidate>) = panel.setCandidates(list)
    fun setRerankedCandidate(candidate: EngineMessage.Candidate) =
        panel.setRerankedCandidate(candidate)

    fun onPossibleCandidatePinYin(pinyins: Array<CandidatePinYin>) {
        panel.onPossibleCandidatePinYin(pinyins)
        keyboardManager.onPossibleCandidatePinYin(pinyins)
    }

    fun updatePreedit(text: String?) {
        preeditPinner.updateText(text)
        if (text.isNullOrBlank()) {
            preeditPinner.hide(wm)
        } else {
            val hPad = dpToPx(KeyboardManager.Keyboard.Padding.getHorizontalDp(context))
            preeditPinner.show(context, wm, panel.view, hPad)
        }
    }

    private fun contentHeight(): Int {
        val percent = KeyboardManager.Keyboard.getHeightPercent(context)
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
        if (KeyboardManager.Keyboard.getIgnoreInsets(context)) return 0
        val insets = ViewCompat.getRootWindowInsets(this) ?: return navbarFrameHeight()
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

    override fun onAttach() {}

    override fun onDetach() = keyboardManager.detachCurrent()
}
