package com.ninthsoft.ime.input.keyboard.window

import android.annotation.SuppressLint
import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.os.Build
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.FrameLayout
import com.ninthsoft.ime.input.keyboard.impl.IKeyboard
import com.ninthsoft.ime.input.keyboard.impl.ISidePanelKeyboard
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isGone
import com.ninthsoft.ime.R
import com.ninthsoft.ime.data.keyboard.theme.KeyboardColors
import com.ninthsoft.ime.data.manager.CandidateManager
import com.ninthsoft.ime.data.manager.KeyboardKeyMapping
import com.ninthsoft.ime.data.manager.SchemaManager
import com.ninthsoft.ime.data.manager.KeyboardManager
import com.ninthsoft.ime.engine.data.CandidatePinYin
import com.ninthsoft.ime.engine.data.EngineMessage
import com.ninthsoft.ime.input.keyboard.impl.EmojiKeyboard
import com.ninthsoft.ime.input.keyboard.impl.NumberKeyboard
import com.ninthsoft.ime.input.keyboard.impl.QwertyKeyboard
import com.ninthsoft.ime.input.keyboard.impl.SymbolKeyboard
import com.ninthsoft.ime.input.keyboard.impl.T9Keyboard
import com.ninthsoft.ime.input.keyboard.key.KeyActionListener
import com.ninthsoft.ime.input.keyboard.key.KeyboardAction
import com.ninthsoft.ime.input.panel.KawaiiPanel
import com.ninthsoft.ime.input.pinner.PreeditPinner
import com.ninthsoft.ime.input.speech.SpeechOverlayView
import com.ninthsoft.ime.base.speech.SherpaSpeechClient
import com.ninthsoft.ime.base.speech.SpeechUiBridge
import com.ninthsoft.ime.input.ImeInputMethodService
import com.ninthsoft.ime.input.ImeInputConnection
import com.ninthsoft.ime.input.dialog.SchemaPickerDialog
import com.ninthsoft.ime.input.keyboard.impl.T15Keyboard
import com.ninthsoft.ime.input.panel.PanelListener
import kotlin.math.roundToInt

@SuppressLint("ViewConstructor")
class KeyboardWindowView(
    context: Context,
    private val keyboardStateManager: KeyboardStateManager,
    private val panelListener: PanelListener? = null,
) : FrameLayout(context), IManagedView {

    companion object {
        const val PANEL_HEIGHT_DP = 48

        /** 悬浮卡片顶部拖动手柄的高度（dp）。 */
        private const val FLOATING_HANDLE_DP = 18

        /** 悬浮卡片的圆角半径（dp）。 */
        private const val FLOATING_CORNER_DP = 16

        /** 尺寸编辑模式下控制条与手柄的尺寸（dp）。 */
        private const val RESIZE_BAR_DP = 40
        private const val RESIZE_HANDLE_LONG_DP = 72
        private const val RESIZE_HANDLE_THICK_DP = 6
        private const val RESIZE_HANDLE_TOUCH_DP = 30
        private const val RESIZE_BUTTON_H_DP = 30
        private const val RESIZE_TEXT_SP = 13f

        private const val ACCENT = 0xFF3B82F6.toInt()
        private const val OVERLAY_BAR_BG = 0xE6101114.toInt()
        private const val OVERLAY_TEXT = 0xFFFFFFFF.toInt()
    }

    private var cachedColors: KeyboardColors.ColorScheme = KeyboardColors.resolve(context)

    // ==================== 悬浮卡片布局与尺寸编辑 ====================

    /** 横屏悬浮开关（由 [com.ninthsoft.ime.input.ImeInputMethodService] 注入）。 */
    private var floatingEnabled: Boolean = false

    /** 是否处于「调整键盘大小」编辑模式。 */
    var isResizing: Boolean = false
        private set

    /** 进入编辑模式后，等下一次测量拿到真实窗口尺寸再初始化悬浮卡片。 */
    private var resizeInitPending = false

    /** 编辑模式拖动时的基准矩形（窗口坐标系）；实时矩形直接维护在 [floatingCard] 上。 */
    private val resizeStartCard = Rect()

    private var resizeHandle = ResizeHandle.NONE
    private var resizeStartRawX = 0f
    private var resizeStartRawY = 0f

    private enum class ResizeHandle { NONE, TOP, LEFT, RIGHT }

    /** 悬浮卡片在窗口坐标系中的位置与大小。 */
    private val floatingCard = Rect()

    /** 悬浮卡片的拖动手柄区域（窗口坐标系）。 */
    private val floatingHandle = Rect()

    /** 卡片可移动的水平/垂直余量，用于把拖动位置换算成比例保存。 */
    private var floatingAvailX = 0
    private var floatingAvailY = 0

    /**
     * 内存中的当前位置比例。拖动过程中不能依赖 SharedPreferences：写回是异步的，
     * 而 onMeasure 每次遍历都会重新读取，若读旧值会把卡片弹回原位。
     * 为 null 表示「未拖动过，直接读设置里的值」。
     */
    private var floatingXRatio: Float? = null
    private var floatingYRatio: Float? = null

    private val cardPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val cardShadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0x33000000
    }
    private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val cardRectF = RectF()
    private val cardShadowRectF = RectF()
    private val handleRectF = RectF()

    private val resizeBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = ACCENT
    }
    private val resizeHandlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = ACCENT
    }
    private val resizeBarBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = OVERLAY_BAR_BG
    }
    private val resizeTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = OVERLAY_TEXT
    }
    private val resizeButtonPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    private val resizeBarRect = Rect()
    private val resizeDoneRect = Rect()
    private val resizeResetRect = Rect()
    private val resizeTopTouchRect = Rect()
    private val resizeLeftTouchRect = Rect()
    private val resizeRightTouchRect = Rect()

    private var dragActive = false
    private var dragStartRawX = 0f
    private var dragStartRawY = 0f
    private var dragStartCardLeft = 0
    private var dragStartCardTop = 0

    val panel = KawaiiPanel(
        context = context,
        listener = panelListener,
    )

    init {
        panel.onRecordingStop = {
            isVoiceRecording = false
            panel.recording = false
            SherpaSpeechClient.stopHoldSession(discard = true)
            voiceOverlay.hide()
        }
    }

    private val preeditPinner = PreeditPinner(context)

    private val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    private val voiceOverlay = SpeechOverlayView(context).apply {
        onSpeechActionListener = object : SpeechOverlayView.OnSpeechActionListener {
            override fun onClose() {
                stopVoiceInput()
            }

            override fun onLockStateChanged(isLocked: Boolean) {}
        }
    }

    private var isVoiceRecording = false

    private val addPhraseLayer = InputBoxLayerView(context).apply {
        visibility = View.GONE
    }

    private val imeToastView = ImeToastView(context)

    var keyActionListener: KeyActionListener
        get() = keyboardStateManager.keyActionListener
        set(value) {
            keyboardStateManager.keyActionListener = KeyActionListener { action ->
                transformed(action)?.let { value.onKeyAction(it) }
            }
        }

    fun transformed(action: KeyboardAction): KeyboardAction? {
        val transformed: KeyboardAction? = when (action) {
            is KeyboardAction.RotateSchema -> {
                val schemeId = keyboardStateManager.rotateSchema()
                return KeyboardAction.SelectSchema(schemeId)
            }

            is KeyboardAction.LayoutSwitchAction -> {
                keyboardStateManager.pushTo(action.target)
                null
            }

            is KeyboardAction.ResumeAction -> {
                keyboardStateManager.resume()
                null
            }

            is KeyboardAction.CommitPairAction -> {
                // 先让路径回到「进入符号页前」的键盘，再把配对符号提交给输入框
                if (action.resume) keyboardStateManager.resume()
                action.copy(resume = false)
            }

            is KeyboardAction.ShowInputMethodPickerAction -> {
                val dialog = SchemaPickerDialog.build(
                    context = context,
                    schemas = keyboardStateManager.getSchemas(),
                    currentSchemaId = keyboardStateManager.getCurrentSchema()?.id,
                    colors = cachedColors,
                    onSchemaSelected = { schemaId ->
                        // 切换方案会重置引擎组合，按设置决定已上屏的预览内容留还是丢。
                        if (keyboardStateManager.getCurrentSchema()?.id != schemaId) {
                            (context as? ImeInputMethodService)?.livePreview
                                ?.finalizeForKeyboardSwitch()
                        }
                        keyboardStateManager.selectSchema(schemaId)
                    })
                (context as ImeInputMethodService).showDialog(dialog)
                null
            }

            is KeyboardAction.StopVoiceInputAction -> {
                stopVoiceInput()
                null
            }

            is KeyboardAction.VoiceDragPosition -> {
                if (isVoiceRecording) {
                    voiceOverlay.onDragPosition(action.rawX, action.rawY)
                }
                null
            }

            is KeyboardAction.VoiceDragUp -> {
                if (isVoiceRecording) {
                    when (voiceOverlay.currentDragTarget) {
                        SpeechOverlayView.DragTarget.CLOSE -> {
                            stopVoiceInput()
                        }

                        SpeechOverlayView.DragTarget.LOCK -> {
                            voiceOverlay.setDragLocked()
                        }

                        SpeechOverlayView.DragTarget.NONE -> {
                            stopVoiceInput()
                        }
                    }
                }
                null
            }

            is KeyboardAction.VoiceInputAction -> {
                if (isVoiceRecording) {
                    stopVoiceInput()
                } else {
                    startVoiceInput()
                }
                null
            }

            else -> action
        }
        return transformed
    }


    fun onConfigChanged(key: String) {
        when (key) {
            SchemaManager.KEY_ENABLED_IDS -> keyboardStateManager.onConfigChanged(key)
            KeyboardManager.Keyboard.KEY_HEIGHT, KeyboardManager.Keyboard.KEY_HEIGHT_LANDSCAPE,
            KeyboardManager.Keyboard.KEY_WIDTH,
            KeyboardManager.Keyboard.Padding.KEY_HORIZONTAL, KeyboardManager.Keyboard.Padding.KEY_BOTTOM, KeyboardManager.Keyboard.KEY_IGNORE_INSETS -> post {
                panel.view.updateHorizontalPadding(
                    KeyboardManager.Keyboard.Padding.getHorizontalDp(context).toFloat()
                )
                requestLayout()
            }

            KeyboardManager.Keyboard.Floating.KEY_ENABLED,
            KeyboardManager.Keyboard.Floating.KEY_WIDTH,
            -> post {
                setFloatingMode(KeyboardManager.Keyboard.Floating.shouldUseFloating(context))
                requestLayout()
            }

            // 位置被外部改动（例如设置里的「重置」）时，丢弃内存中的拖动位置重新读设置。
            KeyboardManager.Keyboard.KEY_POSITION_X,
            KeyboardManager.Keyboard.KEY_POSITION_Y,
            KeyboardManager.Keyboard.Floating.KEY_POSITION_X,
            KeyboardManager.Keyboard.Floating.KEY_POSITION_Y,
            -> post {
                floatingXRatio = null
                floatingYRatio = null
                requestLayout()
            }

            KeyboardManager.Keyboard.KeyRadius.KEY, KeyboardManager.Keyboard.KEY_THEME, KeyboardManager.Keyboard.KEY_FOLLOW_SYSTEM, KeyboardManager.Keyboard.KEY_LIGHT_THEME, KeyboardManager.Keyboard.KEY_DARK_THEME, KeyboardManager.Keyboard.Gap.KEY_HORIZONTAL, KeyboardManager.Keyboard.Gap.KEY_VERTICAL,
            KeyboardManager.Keyboard.GestureInput.KEY,
            -> post { refreshColors() }

            KeyboardManager.Keyboard.ToolbarTools.KEY -> post { panel.refreshToolbarConfig() }

            // 按键映射 / 气泡开关改的是 KeyDef 里的布局与行为，必须整块重建键盘才生效
            // （refreshColors 只重建视图、不会重新执行 buildLayout）。
            // 上滑触发距离同理：它是在 BaseKeyboard 构造期读进内存、再下发到每个 KeyView 的，
            // 不重建键盘就还是旧值 —— 表现成「滑块拖了没反应」。
            KeyboardKeyMapping.KEY_QWERTY,
            KeyboardKeyMapping.KEY_T9,
            KeyboardKeyMapping.KEY_BUBBLE_ENABLED,
            KeyboardManager.Keyboard.SwipeUp.KEY,
            KeyboardManager.Keyboard.SwipeUp.KEY_DIRECTION_TAN,
            -> post { keyboardStateManager.rebuild() }

            KeyboardManager.Keyboard.RippleEffect.KEY -> post {
                keyboardStateManager.setRippleEnabled(
                    KeyboardManager.Keyboard.RippleEffect.isEnabled(context)
                )
            }

            KeyboardManager.Keyboard.KeyBorderStroke.KEY,
            KeyboardManager.Keyboard.ExpandBorder.KEY,
            CandidateManager.KEY_BORDER,
            CandidateManager.KEY_SHOW_INDEX,
            CandidateManager.KEY_SHOW_COMMENT,
                -> post { refreshColors() }
        }
    }

    private var cachedBottomInset = 0

    fun addKeyboardView(keyboard: IKeyboard) {
        val view = keyboard as View
        (view.parent as? ViewGroup)?.removeView(view)
        if (view.parent == null) {
            addView(
                view, 0, FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
                )
            )
        }
    }

    fun removeKeyboardView(keyboard: IKeyboard) {
        val view = keyboard as View
        (view.parent as? ViewGroup)?.removeView(view)
    }

    private fun createKeyboard(name: String): IKeyboard {
        val b = when (name) {
            T15Keyboard.NAME -> T15Keyboard(context, cachedColors)
            T9Keyboard.NAME -> T9Keyboard(context, cachedColors)
            SymbolKeyboard.NAME -> SymbolKeyboard(context, cachedColors)
            EmojiKeyboard.NAME -> EmojiKeyboard(context, cachedColors)
            NumberKeyboard.NAME -> NumberKeyboard(context, cachedColors)
            else -> QwertyKeyboard(context, cachedColors)
        }
        b.setRippleEnabled(KeyboardManager.Keyboard.RippleEffect.isEnabled(context))
        return b
    }

    private val keyboardFactory: (String) -> IKeyboard = { name -> createKeyboard(name) }

    fun onShowKeyboard(keyboard: IKeyboard) {
        currentKeyboard = keyboard
        addKeyboardView(keyboard)
    }

    fun onHideKeyboard(keyboard: IKeyboard) {
        removeKeyboardView(keyboard)
        if (currentKeyboard === keyboard) currentKeyboard = null
    }

    fun onKeyboardChanged(keyboard: IKeyboard) {
        currentKeyboard = keyboard
        addKeyboardView(keyboard)
    }

    init {
        keyboardStateManager.setKeyboardFactory(keyboardFactory)
        ViewCompat.setOnApplyWindowInsetsListener(this) { view, insets ->
            val bottom = maxOf(
                insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom,
                insets.getInsets(WindowInsetsCompat.Type.mandatorySystemGestures()).bottom,
                insets.getInsets(WindowInsetsCompat.Type.systemGestures()).bottom,
            )
            if (bottom != cachedBottomInset) {
                cachedBottomInset = bottom
                view.requestLayout()
            }
            insets
        }

        voiceOverlay.applyColors(
            cachedColors.background,
            cachedColors.specialKeyBackground,
            cachedColors.specialKeyPressed,
            cachedColors.specialKeyText,
            cachedColors.accentKeyBackground,
            cachedColors.accentKeyText
        )

        applyBackgroundTint()

        addView(panel.view, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        addView(
            panel.candidateGrid, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
        )
        addView(
            panel.textEditingView,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
        )
        addView(
            panel.clipboardView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
        )
        addView(
            panel.menuGridView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
        )
        addView(
            panel.confirmOverlay, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
        )

        addView(
            addPhraseLayer, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
        )
        addView(imeToastView, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))

        addPhraseLayer.onConfirm = { panelListener?.onAddPhraseSave(it) }
        addPhraseLayer.onClose = { panelListener?.onAddPhraseCancel() }
    }

    fun toggleMenu() {
        panel.toggleMenu()
    }

    var addPhraseActive = false
        private set

    fun enterAddPhraseMode(buffer: ImeInputConnection) {
        addPhraseActive = true
        addPhraseLayer.refreshTheme(cachedColors)
        addPhraseLayer.title = context.getString(R.string.phrase_add_title)
        addPhraseLayer.hint = context.getString(R.string.phrase_input_hint)
        addPhraseLayer.bind(buffer)
        addPhraseLayer.show()
        requestLayout()
    }

    fun exitAddPhraseMode() {
        if (!addPhraseActive) return
        addPhraseActive = false
        addPhraseLayer.hide()
        requestLayout()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
    }

    override fun onDetachedFromWindow() {
        // 键盘被收起/重建时结束编辑模式，把当前尺寸落盘。
        if (isResizing) {
            isResizing = false
            resizeHandle = ResizeHandle.NONE
            persistResizeGeometry()
        }
        panel.onFinishInputView(true)
        preeditPinner.hide(wm)
        super.onDetachedFromWindow()
    }

    // ==================== 卡片布局几何 ====================

    private val isLandscape: Boolean
        get() = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    /** 当前方向下键盘应使用的宽度百分比（横屏受「横屏悬浮键盘」开关约束）。 */
    private fun effectiveWidthPercent(): Int = if (isLandscape) {
        if (floatingEnabled) KeyboardManager.Keyboard.Floating.getWidthPercent(context)
        else KeyboardManager.Keyboard.WIDTH_PERCENT_MAX
    } else {
        KeyboardManager.Keyboard.getWidthPercent(context)
    }

    private fun currentPosXRatio(): Float = floatingXRatio
        ?: if (isLandscape) KeyboardManager.Keyboard.Floating.getPositionXRatio(context)
        else KeyboardManager.Keyboard.getPositionXRatio(context)

    private fun currentPosYRatio(): Float = floatingYRatio
        ?: if (isLandscape) KeyboardManager.Keyboard.Floating.getPositionYRatio(context)
        else KeyboardManager.Keyboard.getPositionYRatio(context)

    /** 是否使用「全屏透明窗口 + 悬浮卡片」布局：编辑模式、或键盘宽度小于整屏。 */
    val usesOverlayLayout: Boolean
        get() = isResizing || effectiveWidthPercent() < KeyboardManager.Keyboard.WIDTH_PERCENT_MAX

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val density = resources.displayMetrics.density
        val hPad = dpToPx(KeyboardManager.Keyboard.Padding.getHorizontalDp(context))
        val bPad = dpToPx(KeyboardManager.Keyboard.Padding.getBottomDp(context))
        val barH = (PANEL_HEIGHT_DP * density).roundToInt()
        val handleH = (FLOATING_HANDLE_DP * density).roundToInt()
        val totalWidth = MeasureSpec.getSize(widthMeasureSpec).takeIf { it > 0 } ?: fullScreenWidth()
        val bottomInset = resolveBottomInset()
        val stripH = if (addPhraseActive) (fullScreenHeight() * 0.20f).roundToInt() else 0
        val cHeight = contentHeight()

        if (usesOverlayLayout) {
            // 悬浮卡片布局：本 View 占满整个 IME 窗口（背景透明），键盘绘制在一张卡片里。
            // 应用是否需要缩放、哪些区域可触摸由 ImeInputMethodService.onComputeInsets 决定。
            val availHeight = measureSpecHeight(heightMeasureSpec)
            val maxContentH = (availHeight - bottomInset - handleH - barH - bPad)
                .coerceAtLeast(minimumHeight)

            // 设置值对应的卡片几何；编辑模式会在此基础上保留用户正在拖动的矩形。
            val setCardW = (totalWidth * effectiveWidthPercent() / 100f)
                .roundToInt().coerceIn(1, totalWidth)
            val setContentH = cHeight.coerceAtMost(maxContentH)
            val setCardH = handleH + barH + setContentH + bPad
            val setAvailX = (totalWidth - setCardW).coerceAtLeast(0)
            val setAvailY = (availHeight - setCardH - bottomInset).coerceAtLeast(0)
            val setCardX = (currentPosXRatio() * setAvailX).roundToInt().coerceIn(0, setAvailX)
            val setCardY = (currentPosYRatio() * setAvailY).roundToInt().coerceIn(0, setAvailY)

            if (isResizing) {
                if (resizeInitPending) {
                    // 用当前设置值初始化编辑矩形，之后不再被设置覆盖。
                    resizeInitPending = false
                    floatingCard.set(setCardX, setCardY, setCardX + setCardW, setCardY + setCardH)
                }
                clampOverlayCard(totalWidth, availHeight, bottomInset, handleH, barH, bPad)
            } else {
                floatingCard.set(setCardX, setCardY, setCardX + setCardW, setCardY + setCardH)
                resizeInitPending = false
            }

            val cardW = floatingCard.width().coerceAtLeast(1)
            val cardH = floatingCard.height().coerceAtLeast(1)
            floatingAvailX = (totalWidth - cardW).coerceAtLeast(0)
            floatingAvailY = (availHeight - cardH - bottomInset).coerceAtLeast(0)
            floatingHandle.set(
                floatingCard.left, floatingCard.top, floatingCard.right, floatingCard.top + handleH
            )

            val cardContentH = (cardH - handleH - barH - bPad).coerceAtLeast(0)
            val cardContentW = (cardW - 2 * hPad).coerceAtLeast(0)
            measureContentChildren(
                barW = cardW, barH = barH,
                contentW = cardContentW, contentH = cardContentH,
                stripW = totalWidth, stripH = stripH,
            )
            rememberGeometry(
                barLeft = floatingCard.left,
                barTop = floatingCard.top + handleH,
                barW = cardW, barH = barH,
                contentLeft = floatingCard.left + hPad,
                contentTop = floatingCard.top + handleH + barH,
                contentW = cardContentW, contentH = cardContentH,
                stripW = totalWidth, stripH = stripH,
            )
            setMeasuredDimension(totalWidth, availHeight)
            return
        }

        val contentW = (totalWidth - 2 * hPad).coerceAtLeast(0)
        measureContentChildren(
            barW = totalWidth, barH = barH,
            contentW = contentW, contentH = cHeight,
            stripW = totalWidth, stripH = stripH,
        )
        rememberGeometry(
            barLeft = 0, barTop = stripH, barW = totalWidth, barH = barH,
            contentLeft = hPad, contentTop = stripH + barH,
            contentW = contentW, contentH = cHeight,
            stripW = totalWidth, stripH = stripH,
        )
        setMeasuredDimension(totalWidth, stripH + barH + cHeight + bPad + bottomInset)
    }

    /** 把编辑中的卡片限制在窗口内，并保证最小可用尺寸。 */
    private fun clampOverlayCard(
        totalWidth: Int, totalHeight: Int, bottomInset: Int, handleH: Int, barH: Int, bPad: Int,
    ) {
        val minW = (totalWidth * KeyboardManager.Keyboard.WIDTH_PERCENT_MIN / 100f).roundToInt()
        val minH = handleH + barH + dpToPx(56)
        val maxBottom = (totalHeight - bottomInset).coerceAtLeast(minH)

        if (floatingCard.width() < minW) {
            floatingCard.left = (floatingCard.right - minW).coerceAtLeast(0)
            floatingCard.right = floatingCard.left + minW
        }
        if (floatingCard.height() < minH) {
            floatingCard.top = (floatingCard.bottom - minH).coerceAtLeast(0)
            floatingCard.bottom = floatingCard.top + minH
        }
        if (floatingCard.left < 0) floatingCard.offsetTo(0, floatingCard.top)
        if (floatingCard.top < 0) floatingCard.offsetTo(floatingCard.left, 0)
        if (floatingCard.right > totalWidth) {
            floatingCard.offsetTo(totalWidth - floatingCard.width(), floatingCard.top)
        }
        if (floatingCard.bottom > maxBottom) {
            floatingCard.offsetTo(floatingCard.left, maxBottom - floatingCard.height())
        }
    }

    private fun measureSpecHeight(heightMeasureSpec: Int): Int {
        val size = MeasureSpec.getSize(heightMeasureSpec)
        return if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.UNSPECIFIED || size <= 0) {
            fullScreenHeight()
        } else {
            size
        }
    }

    private fun measureContentChildren(
        barW: Int, barH: Int, contentW: Int, contentH: Int, stripW: Int, stripH: Int,
    ) {
        panel.view.measure(
            MeasureSpec.makeMeasureSpec(barW, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(barH, MeasureSpec.EXACTLY),
        )

        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child === panel.view || child === panel.textEditingView || child === panel.clipboardView || child === panel.menuGridView || child === panel.confirmOverlay || child === addPhraseLayer || child === imeToastView || child.isGone) continue
            child.measure(
                MeasureSpec.makeMeasureSpec(contentW, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(contentH, MeasureSpec.EXACTLY),
            )
        }

        panel.textEditingView.measure(
            MeasureSpec.makeMeasureSpec(contentW, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(contentH, MeasureSpec.EXACTLY),
        )

        panel.clipboardView.measure(
            MeasureSpec.makeMeasureSpec(contentW, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(contentH, MeasureSpec.EXACTLY),
        )

        panel.menuGridView.measure(
            MeasureSpec.makeMeasureSpec(contentW, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(contentH, MeasureSpec.EXACTLY),
        )

        panel.confirmOverlay.measure(
            MeasureSpec.makeMeasureSpec(contentW, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(contentH, MeasureSpec.EXACTLY),
        )

        addPhraseLayer.measure(
            MeasureSpec.makeMeasureSpec(stripW, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(stripH, MeasureSpec.EXACTLY),
        )

        imeToastView.measure(
            MeasureSpec.makeMeasureSpec(contentW, MeasureSpec.AT_MOST),
            MeasureSpec.makeMeasureSpec(contentH, MeasureSpec.AT_MOST),
        )
    }

    // 把 onMeasure 算出的几何参数缓存下来，供 onLayout 使用（悬浮模式下内容高度会被收窄，
    // 不能再用 contentHeight() 重新推算，否则测量/布局会不一致）。
    private var geomBarLeft = 0
    private var geomBarTop = 0
    private var geomBarW = 0
    private var geomBarH = 0
    private var geomContentLeft = 0
    private var geomContentTop = 0
    private var geomContentW = 0
    private var geomContentH = 0
    private var geomStripW = 0
    private var geomStripH = 0

    private fun rememberGeometry(
        barLeft: Int, barTop: Int, barW: Int, barH: Int,
        contentLeft: Int, contentTop: Int, contentW: Int, contentH: Int,
        stripW: Int, stripH: Int,
    ) {
        geomBarLeft = barLeft
        geomBarTop = barTop
        geomBarW = barW
        geomBarH = barH
        geomContentLeft = contentLeft
        geomContentTop = contentTop
        geomContentW = contentW
        geomContentH = contentH
        geomStripW = stripW
        geomStripH = stripH
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        val contentW = geomContentW
        val contentH = geomContentH
        val y0 = geomContentTop

        panel.view.layout(geomBarLeft, geomBarTop, geomBarLeft + geomBarW, geomBarTop + geomBarH)

        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child === panel.view || child === panel.candidateGrid || child === panel.textEditingView || child === panel.clipboardView || child === panel.menuGridView || child === panel.confirmOverlay || child === addPhraseLayer || child === imeToastView || child.isGone) continue
            child.layout(geomContentLeft, y0, geomContentLeft + contentW, y0 + contentH)
        }

        panel.candidateGrid.layout(geomContentLeft, y0, geomContentLeft + contentW, y0 + contentH)
        panel.textEditingView.layout(geomContentLeft, y0, geomContentLeft + contentW, y0 + contentH)
        panel.clipboardView.layout(geomContentLeft, y0, geomContentLeft + contentW, y0 + contentH)
        panel.menuGridView.layout(geomContentLeft, y0, geomContentLeft + contentW, y0 + contentH)
        panel.confirmOverlay.layout(geomContentLeft, y0, geomContentLeft + contentW, y0 + contentH)
        addPhraseLayer.layout(0, 0, geomStripW, geomStripH)

        val toastLeft = geomBarLeft + (geomBarW - imeToastView.measuredWidth) / 2
        val toastBottom = if (usesOverlayLayout) {
            floatingCard.bottom - dpToPx(12)
        } else {
            bottom - top - dpToPx(KeyboardManager.Keyboard.Padding.getBottomDp(context)) -
                resolveBottomInset() - dpToPx(12)
        }
        imeToastView.layout(
            toastLeft,
            toastBottom - imeToastView.measuredHeight,
            toastLeft + imeToastView.measuredWidth,
            toastBottom,
        )
    }

    override fun dispatchDraw(canvas: Canvas) {
        if (usesOverlayLayout) {
            val density = resources.displayMetrics.density
            val radius = FLOATING_CORNER_DP * density
            val shadowOffset = 2 * density

            cardRectF.set(floatingCard)
            cardShadowRectF.set(floatingCard)
            cardShadowRectF.inset(-shadowOffset, -shadowOffset * 0.5f)
            cardShadowRectF.offset(0f, shadowOffset)
            canvas.drawRoundRect(cardShadowRectF, radius, radius, cardShadowPaint)

            cardPaint.color = cachedColors.background
            canvas.drawRoundRect(cardRectF, radius, radius, cardPaint)

            // 顶部拖动条的视觉提示（编辑模式下改为尺寸手柄，见 drawResizeDecorations）
            if (!isResizing) {
                val gripW = 40 * density
                val gripH = 4 * density
                val cx = floatingCard.exactCenterX()
                val cy = floatingHandle.exactCenterY()
                handleRectF.set(cx - gripW / 2f, cy - gripH / 2f, cx + gripW / 2f, cy + gripH / 2f)
                handlePaint.color = cachedColors.altText
                handlePaint.alpha = 110
                canvas.drawRoundRect(handleRectF, gripH / 2f, gripH / 2f, handlePaint)
            }
        }
        super.dispatchDraw(canvas)
        if (isResizing) {
            drawResizeDecorations(canvas)
        }
    }

    /**
     * 卡片只有顶部手柄区域可拖动；卡片其余部分以及卡片外的区域行为保持不变
     * （卡片外的触摸会被系统穿透给下层应用）。编辑模式下所有触摸都由本 View 处理。
     */
    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        // 编辑模式要屏蔽键盘按键，避免拖动边框时误触发输入。
        if (isResizing) return true
        return super.onInterceptTouchEvent(ev)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (isResizing) return handleResizeTouch(event)
        if (!usesOverlayLayout) return super.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (floatingHandle.contains(event.x.toInt(), event.y.toInt())) {
                    dragActive = true
                    dragStartRawX = event.rawX
                    dragStartRawY = event.rawY
                    dragStartCardLeft = floatingCard.left
                    dragStartCardTop = floatingCard.top
                    return true
                }
            }

            MotionEvent.ACTION_MOVE -> if (dragActive) {
                moveFloatingCard(
                    dragStartCardLeft + (event.rawX - dragStartRawX),
                    dragStartCardTop + (event.rawY - dragStartRawY),
                )
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> if (dragActive) {
                dragActive = false
                saveFloatingPosition()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun moveFloatingCard(newLeft: Float, newTop: Float) {
        val cardLeft = newLeft.roundToInt().coerceIn(0, floatingAvailX)
        val cardTop = newTop.roundToInt().coerceIn(0, floatingAvailY)
        if (cardLeft == floatingCard.left && cardTop == floatingCard.top) return
        floatingCard.offsetTo(cardLeft, cardTop)
        floatingHandle.offsetTo(cardLeft, cardTop)
        // 记到内存里，保证接下来的 onMeasure 不会用旧比例把卡片弹回去。
        floatingXRatio = if (floatingAvailX > 0) cardLeft.toFloat() / floatingAvailX else 0.5f
        floatingYRatio = if (floatingAvailY > 0) cardTop.toFloat() / floatingAvailY else 1f
        // requestLayout 会触发 ViewRootImpl 重新派发 onComputeInsets，从而同步可触摸区域。
        requestLayout()
        invalidate()
    }

    private fun saveFloatingPosition() {
        val x = floatingXRatio ?: return
        val y = floatingYRatio ?: return
        if (isLandscape) {
            KeyboardManager.Keyboard.Floating.setPosition(context, x, y)
        } else {
            KeyboardManager.Keyboard.setPosition(context, x, y)
        }
    }

    /** 切换悬浮/普通模式（由 ImeInputMethodService 按横竖屏注入）。 */
    fun setFloatingMode(enabled: Boolean) {
        if (floatingEnabled == enabled) return
        floatingEnabled = enabled
        dragActive = false
        applyBackgroundTint()
        requestLayout()
    }

    // ==================== 调整键盘大小（编辑模式） ====================

    /** 工具栏「调整键盘大小」按钮：进入/退出编辑模式。 */
    fun toggleResizeMode() {
        if (isResizing) exitResizeMode() else enterResizeMode()
    }

    fun enterResizeMode() {
        if (isResizing) return
        isResizing = true
        resizeInitPending = true
        dragActive = false
        applyBackgroundTint()
        requestLayout()
        invalidate()
    }

    fun exitResizeMode() {
        if (!isResizing) return
        isResizing = false
        resizeHandle = ResizeHandle.NONE
        persistResizeGeometry()
        applyBackgroundTint()
        requestLayout()
        invalidate()
    }

    /** 把编辑结果写回偏好：宽高百分比 + 位置比例。 */
    private fun persistResizeGeometry() {
        val density = resources.displayMetrics.density
        val bPad = dpToPx(KeyboardManager.Keyboard.Padding.getBottomDp(context))
        val barH = (PANEL_HEIGHT_DP * density).roundToInt()
        val handleH = (FLOATING_HANDLE_DP * density).roundToInt()
        val totalWidth = (if (width > 0) width else fullScreenWidth()).coerceAtLeast(1)
        val totalHeight = (if (height > 0) height else fullScreenHeight()).coerceAtLeast(1)
        val bottomInset = resolveBottomInset()

        val cardW = floatingCard.width().coerceAtLeast(1)
        val contentH = (floatingCard.height() - handleH - barH - bPad).coerceAtLeast(1)
        val widthPercent = (cardW * 100f / totalWidth).roundToInt()
            .coerceIn(KeyboardManager.Keyboard.WIDTH_PERCENT_MIN, KeyboardManager.Keyboard.WIDTH_PERCENT_MAX)
        val heightPercent = (contentH * 100f / fullScreenHeight()).roundToInt()
            .coerceIn(KeyboardManager.Keyboard.HEIGHT_PERCENT_MIN, KeyboardManager.Keyboard.HEIGHT_PERCENT_MAX)
        val availX = (totalWidth - cardW).coerceAtLeast(0)
        val availY = (totalHeight - floatingCard.height() - bottomInset).coerceAtLeast(0)
        val xRatio = if (availX > 0) floatingCard.left.toFloat() / availX else 0.5f
        val yRatio = if (availY > 0) floatingCard.top.toFloat() / availY else 1f

        if (isLandscape) {
            if (widthPercent < KeyboardManager.Keyboard.WIDTH_PERCENT_MAX) {
                // 横屏缩窄即等同于开启悬浮，否则下次测量会退回全宽。
                KeyboardManager.Keyboard.Floating.setEnabled(context, true)
                floatingEnabled = true
            }
            KeyboardManager.Keyboard.Floating.setWidthPercent(context, widthPercent)
            KeyboardManager.Keyboard.Floating.setPosition(context, xRatio, yRatio)
            KeyboardManager.Keyboard.setHeightPercentLandscape(context, heightPercent)
        } else {
            KeyboardManager.Keyboard.setWidthPercent(context, widthPercent)
            KeyboardManager.Keyboard.setPosition(context, xRatio, yRatio)
            KeyboardManager.Keyboard.setHeightPercent(context, heightPercent)
        }
        floatingXRatio = null
        floatingYRatio = null
    }

    /** 编辑模式里的「重置」：恢复当前方向的默认尺寸与位置。 */
    private fun resetResizeGeometry() {
        if (isLandscape) {
            KeyboardManager.Keyboard.Floating.resetWidth(context)
            KeyboardManager.Keyboard.Floating.resetPosition(context)
            KeyboardManager.Keyboard.resetHeightPercentLandscape(context)
        } else {
            KeyboardManager.Keyboard.resetLayout(context)
            KeyboardManager.Keyboard.resetHeightPercent(context)
        }
        floatingXRatio = null
        floatingYRatio = null
        resizeInitPending = true
        floatingCard.setEmpty()
        requestLayout()
        invalidate()
    }

    private fun handleResizeTouch(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                // 首次测量前卡片还是空的，先吞掉这次触摸，避免误判为「点在外面」直接退出。
                if (floatingCard.isEmpty()) return true
                updateResizeDecorations()
                val x = event.x.toInt()
                val y = event.y.toInt()
                if (resizeDoneRect.contains(x, y)) {
                    exitResizeMode()
                    return true
                }
                if (resizeResetRect.contains(x, y)) {
                    resetResizeGeometry()
                    return true
                }
                resizeHandle = hitResizeHandle(x, y)
                resizeStartCard.set(floatingCard)
                resizeStartRawX = event.rawX
                resizeStartRawY = event.rawY
                if (resizeHandle == ResizeHandle.NONE && !floatingCard.contains(x, y)) {
                    // 点击卡片外空白处也退出，避免用户被困在编辑模式。
                    exitResizeMode()
                }
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                if (resizeHandle != ResizeHandle.NONE) {
                    applyResize(
                        resizeHandle,
                        resizeStartCard,
                        event.rawX - resizeStartRawX,
                        event.rawY - resizeStartRawY,
                    )
                }
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                resizeHandle = ResizeHandle.NONE
                return true
            }
        }
        return true
    }

    private fun hitResizeHandle(x: Int, y: Int): ResizeHandle = when {
        resizeTopTouchRect.contains(x, y) -> ResizeHandle.TOP
        resizeLeftTouchRect.contains(x, y) -> ResizeHandle.LEFT
        resizeRightTouchRect.contains(x, y) -> ResizeHandle.RIGHT
        else -> ResizeHandle.NONE
    }

    private fun applyResize(handle: ResizeHandle, start: Rect, dx: Float, dy: Float) {
        val totalWidth = (if (width > 0) width else fullScreenWidth()).coerceAtLeast(1)
        val totalHeight = (if (height > 0) height else fullScreenHeight()).coerceAtLeast(1)
        val density = resources.displayMetrics.density
        val handleH = (FLOATING_HANDLE_DP * density).roundToInt()
        val barH = (PANEL_HEIGHT_DP * density).roundToInt()
        val minW = (totalWidth * KeyboardManager.Keyboard.WIDTH_PERCENT_MIN / 100f).roundToInt().coerceAtLeast(1)
        val minH = handleH + barH + dpToPx(56)
        val maxBottom = (totalHeight - resolveBottomInset()).coerceAtLeast(minH)

        when (handle) {
            ResizeHandle.TOP -> {
                floatingCard.top = (start.top + dy).roundToInt().coerceIn(0, maxBottom - minH)
                floatingCard.bottom = start.bottom
            }

            ResizeHandle.LEFT -> {
                floatingCard.left = (start.left + dx).roundToInt().coerceIn(0, start.right - minW)
                floatingCard.right = start.right
            }

            ResizeHandle.RIGHT -> {
                floatingCard.left = start.left
                floatingCard.right = (start.right + dx).roundToInt()
                    .coerceIn(start.left + minW, totalWidth)
            }

            ResizeHandle.NONE -> return
        }
        floatingHandle.set(
            floatingCard.left, floatingCard.top, floatingCard.right, floatingCard.top + handleH
        )
        requestLayout()
        invalidate()
    }

    private fun updateResizeDecorations() {
        val density = resources.displayMetrics.density
        val long = (RESIZE_HANDLE_LONG_DP * density).roundToInt()
        val touch = (RESIZE_HANDLE_TOUCH_DP * density).roundToInt()
        val barH = (RESIZE_BAR_DP * density).roundToInt()
        val gap = (8 * density).toInt()

        // 顶部手柄（高度）
        resizeTopTouchRect.set(
            floatingCard.centerX() - long / 2, floatingCard.top - touch / 2,
            floatingCard.centerX() + long / 2, floatingCard.top + touch / 2,
        )
        // 左右手柄（宽度）
        resizeLeftTouchRect.set(
            floatingCard.left - touch / 2, floatingCard.centerY() - long / 2,
            floatingCard.left + touch / 2, floatingCard.centerY() + long / 2,
        )
        resizeRightTouchRect.set(
            floatingCard.right - touch / 2, floatingCard.centerY() - long / 2,
            floatingCard.right + touch / 2, floatingCard.centerY() + long / 2,
        )

        // 控制条：优先放在卡片上方，空间不足时放在卡片内部顶端。
        val barTop = if (floatingCard.top >= barH + gap) {
            floatingCard.top - barH - gap / 2
        } else {
            floatingCard.top
        }
        resizeBarRect.set(floatingCard.left, barTop, floatingCard.right, barTop + barH)

        val buttonH = (RESIZE_BUTTON_H_DP * density).roundToInt()
        val buttonW = (60 * density).roundToInt()
        val buttonPad = (barH - buttonH) / 2
        resizeDoneRect.set(
            resizeBarRect.right - buttonW - buttonPad, resizeBarRect.top + buttonPad,
            resizeBarRect.right - buttonPad, resizeBarRect.top + buttonPad + buttonH,
        )
        resizeResetRect.set(
            resizeDoneRect.left - buttonW - buttonPad, resizeDoneRect.top,
            resizeDoneRect.left - buttonPad, resizeDoneRect.bottom,
        )
        resizeBorderPaint.strokeWidth = 2 * density
        resizeTextPaint.textSize = RESIZE_TEXT_SP *
            resources.configuration.fontScale * resources.displayMetrics.density
    }

    private fun drawResizeDecorations(canvas: Canvas) {
        if (floatingCard.isEmpty()) return
        updateResizeDecorations()
        val density = resources.displayMetrics.density
        val radius = 4 * density

        // 边框
        cardRectF.set(floatingCard)
        canvas.drawRoundRect(cardRectF, radius, radius, resizeBorderPaint)

        // 手柄
        val thick = (RESIZE_HANDLE_THICK_DP * density).roundToInt().coerceAtLeast(1)
        val long = (RESIZE_HANDLE_LONG_DP * density).roundToInt()
        canvas.drawRoundRect(
            RectF(
                resizeTopTouchRect.centerX() - long / 2f, resizeTopTouchRect.centerY() - thick / 2f,
                resizeTopTouchRect.centerX() + long / 2f, resizeTopTouchRect.centerY() + thick / 2f,
            ), thick / 2f, thick / 2f, resizeHandlePaint,
        )
        canvas.drawRoundRect(
            RectF(
                resizeLeftTouchRect.centerX() - thick / 2f, resizeLeftTouchRect.centerY() - long / 2f,
                resizeLeftTouchRect.centerX() + thick / 2f, resizeLeftTouchRect.centerY() + long / 2f,
            ), thick / 2f, thick / 2f, resizeHandlePaint,
        )
        canvas.drawRoundRect(
            RectF(
                resizeRightTouchRect.centerX() - thick / 2f, resizeRightTouchRect.centerY() - long / 2f,
                resizeRightTouchRect.centerX() + thick / 2f, resizeRightTouchRect.centerY() + long / 2f,
            ), thick / 2f, thick / 2f, resizeHandlePaint,
        )

        // 控制条
        cardRectF.set(resizeBarRect)
        canvas.drawRoundRect(cardRectF, radius, radius, resizeBarBgPaint)

        val densityForText = resources.displayMetrics.density
        val textY = resizeBarRect.centerY() + (resizeTextPaint.textSize / 3f)
        val textX = resizeBarRect.left + 12 * densityForText
        val liveWidth = (floatingCard.width() * 100f / width.coerceAtLeast(1)).roundToInt()
        val contentH = floatingCard.height() -
            ((FLOATING_HANDLE_DP + PANEL_HEIGHT_DP) * densityForText).roundToInt() -
            dpToPx(KeyboardManager.Keyboard.Padding.getBottomDp(context))
        val liveHeight = if (height > 0) {
            (contentH * 100f / fullScreenHeight()).roundToInt().coerceAtLeast(0)
        } else 0
        // 文字：裁剪到「重置」按钮左侧，避免窄卡片时与按钮重叠
        canvas.save()
        canvas.clipRect(
            resizeBarRect.left, resizeBarRect.top,
            (resizeResetRect.left - (4 * densityForText).toInt()).coerceAtLeast(resizeBarRect.left),
            resizeBarRect.bottom,
        )
        resizeTextPaint.color = OVERLAY_TEXT
        canvas.drawText(
            context.getString(R.string.resize_hint), textX, textY, resizeTextPaint
        )
        canvas.drawText(
            context.getString(R.string.resize_size_label, liveWidth, liveHeight),
            textX + resizeTextPaint.measureText(context.getString(R.string.resize_hint)) + 12 * densityForText,
            textY,
            resizeTextPaint,
        )
        canvas.restore()

        // 按钮：重置 / 完成
        drawResizeButton(canvas, resizeResetRect, context.getString(R.string.resize_reset), false)
        drawResizeButton(canvas, resizeDoneRect, context.getString(R.string.resize_done), true)
    }

    private fun drawResizeButton(canvas: Canvas, rect: Rect, label: String, primary: Boolean) {
        cardRectF.set(rect)
        val radius = rect.height() / 2f
        resizeButtonPaint.color = if (primary) ACCENT else 0x33FFFFFF
        canvas.drawRoundRect(cardRectF, radius, radius, resizeButtonPaint)
        resizeTextPaint.color = OVERLAY_TEXT
        val textWidth = resizeTextPaint.measureText(label)
        canvas.drawText(
            label,
            rect.centerX() - textWidth / 2f,
            rect.centerY() + resizeTextPaint.textSize / 3f,
            resizeTextPaint,
        )
    }

    /**
     * 悬浮模式下需要由输入法窗口接收触摸的区域。
     *
     * 返回的是 **IME 窗口坐标系** 下的矩形：`Insets.touchableRegion` 要求相对窗口原点，
     * 而本 View 在窗口里可能有偏移（框架的输入容器带 candidatesArea 等），因此统一加上
     * 本 View 在窗口内的位置，避免依赖具体布局。
     */
    fun floatingTouchableRegion(out: Rect) {
        if (isResizing || addPhraseActive || isVoiceRecording) {
            // 编辑模式 / 添加常用语 / 语音悬浮条需要在整窗口范围内交互。
            out.set(0, 0, width, height)
        } else {
            out.set(floatingCard)
        }
        val loc = locationInWindow()
        out.offset(loc[0], loc[1])
    }

    /**
     * IME 内容底边在窗口坐标系中的位置。悬浮模式下 `contentTopInsets` 取该值，
     * 使上报给下层应用的底部边衬为 0（应用不会被键盘顶起）。
     */
    fun contentBottomInWindowPx(): Int {
        val loc = locationInWindow()
        val h = if (height > 0) height else fullScreenHeight()
        return loc[1] + h
    }

    private fun locationInWindow(): IntArray {
        val loc = IntArray(2)
        getLocationInWindow(loc)
        return loc
    }

    private fun applyBackgroundTint() {
        // 悬浮卡片布局下窗口背景必须透明，卡片背景由 dispatchDraw 单独绘制。
        setBackgroundColor(if (usesOverlayLayout) Color.TRANSPARENT else cachedColors.background)
    }


    fun onStartInput(info: EditorInfo) {
        panel.view.setExpanded(false)
        panel.onStartInputView()
        keyboardStateManager.startInput(info)
    }

    fun refreshColors() {
        panel.view.setExpanded(false)
        cachedColors = KeyboardColors.resolve(context)
        applyBackgroundTint()
        panel.refreshTheme()
        addPhraseLayer.refreshTheme(cachedColors)
        imeToastView.refreshTheme(cachedColors)
        preeditPinner.refreshTheme(context)
        keyboardStateManager.rebuild()
        voiceOverlay.applyColors(
            cachedColors.background,
            cachedColors.specialKeyBackground,
            cachedColors.specialKeyPressed,
            cachedColors.specialKeyText,
            cachedColors.accentKeyBackground,
            cachedColors.accentKeyText
        )
    }

    // 仅当解析出的配色与当前缓存不一致时才全量刷新（主题/跟随系统深浅变化等场景）。
    fun refreshColorsIfChanged() {
        val resolved = KeyboardColors.resolve(context)
        if (resolved != cachedColors) {
            refreshColors()
        }
    }

    fun refreshLayout() = requestLayout()

    private var currentKeyboard: IKeyboard? = null

    fun setCandidates(list: List<EngineMessage.Candidate>) = panel.setCandidates(list)

    fun onPossibleCandidatePinYin(pinyins: List<CandidatePinYin>) {
        panel.onPossibleCandidatePinYin(pinyins)
        (currentKeyboard as? ISidePanelKeyboard)?.onPossibleCandidatePinYin(pinyins)
    }

    fun updateDynamicPreedit(items: List<EngineMessage.DynamicPreedit.DynamicPreeditItem>) {
        preeditPinner.updateDynamicPreedit(items)
        if (items.isEmpty()) {
            preeditPinner.hide(wm)
        } else {
            val hPad = dpToPx(KeyboardManager.Keyboard.Padding.getHorizontalDp(context))
            preeditPinner.show(context, wm, panel.view, hPad)
        }
    }

    private fun contentHeight(): Int {
        val isLandscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        val percent = if (isLandscape) {
            KeyboardManager.Keyboard.getHeightPercentLandscape(context)
        } else {
            KeyboardManager.Keyboard.getHeightPercent(context)
        }
        val fullHeight = fullScreenHeight()
        return (fullHeight * percent / 100).coerceAtLeast(minimumHeight)
    }

    private fun fullScreenHeight(): Int {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return wm.maximumWindowMetrics.bounds.height()
        }
        val dm = android.util.DisplayMetrics()
        @Suppress("DEPRECATION") (context.getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay.getRealMetrics(
            dm
        )
        return dm.heightPixels
    }

    private fun fullScreenWidth(): Int {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return wm.maximumWindowMetrics.bounds.width()
        }
        val dm = android.util.DisplayMetrics()
        @Suppress("DEPRECATION") (context.getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay.getRealMetrics(
            dm
        )
        return dm.widthPixels
    }

    private fun dpToPx(dp: Int): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, dp.toFloat(), resources.displayMetrics
        ).toInt()
    }

    private fun resolveBottomInset(): Int {
        if (KeyboardManager.Keyboard.getIgnoreInsets(context)) return 0
        if (cachedBottomInset > 0) return cachedBottomInset
        val computed = computeBottomInset()
        if (computed > 0) cachedBottomInset = computed
        return computed
    }

    @SuppressLint("DiscouragedApi", "InternalInsetResource")
    private fun computeBottomInset(): Int {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val insets = WindowInsetsCompat.toWindowInsetsCompat(
                wm.maximumWindowMetrics.windowInsets, this
            )
            val navBars = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
            val mandatory = insets.getInsets(WindowInsetsCompat.Type.mandatorySystemGestures())
            val systemGestures = insets.getInsets(WindowInsetsCompat.Type.systemGestures())
            return maxOf(navBars.bottom, mandatory.bottom, systemGestures.bottom)
        }
        val resId = resources.getIdentifier("navigation_bar_height", "dimen", "android")
        return if (resId > 0) resources.getDimensionPixelSize(resId) else 0
    }

    override fun onAttach() = keyboardStateManager.onAttach()

    override fun onDetach() {
        if (isVoiceRecording) {
            isVoiceRecording = false
            panel.recording = false
            SherpaSpeechClient.stopHoldSession(discard = true)
            voiceOverlay.hide()
        }
        keyboardStateManager.onDetach()
    }

    private fun ensureRecordAudioPermission(): Boolean {
        if (ContextCompat.checkSelfPermission(
                context, Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            return true
        }
        showMicPermissionPrompt()
        return false
    }

    private fun showMicPermissionPrompt() {
        panel.confirmOverlay.confirm(
            message = context.getString(R.string.voice_permission_message),
            onConfirm = {
                val intent = Intent(
                    context, com.ninthsoft.ime.base.speech.SpeechPermissionActivity::class.java
                ).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                runCatching { context.startActivity(intent) }
            },
            centerHorizontal = true,
            centerVertical = true,
        )
    }

    private fun showModelDownloadPrompt() {
        if (!isVoiceRecording) return
        panel.confirmOverlay.confirm(
            message = context.getString(R.string.voice_model_missing_message),
            onConfirm = {
                isVoiceRecording = false
                val intent = Intent(
                    context, com.ninthsoft.ime.ui.VoiceSettingsActivity::class.java
                ).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    putExtra(
                        com.ninthsoft.ime.ui.VoiceSettingsActivity.EXTRA_AUTO_DOWNLOAD,
                        true,
                    )
                }
                runCatching { context.startActivity(intent) }
            },
            onCancel = null,
            centerHorizontal = true,
            centerVertical = true,
        )
    }

    private fun startVoiceInput() {
        if (isVoiceRecording) return
        if (!ensureRecordAudioPermission()) return
        isVoiceRecording = true
        panel.recording = true
        voiceOverlay.unlock()
        voiceOverlay.applyColors(
            cachedColors.background,
            cachedColors.specialKeyBackground,
            cachedColors.specialKeyPressed,
            cachedColors.specialKeyText
        )
        if (voiceOverlay.parent == null) {
            addView(voiceOverlay)
        }

        SpeechUiBridge.clear()
        SpeechUiBridge.onRecordingStarted = {
            // 依赖就绪、录音真正开始后才展示动画，避免未就绪时一闪而过导致抖动
            voiceOverlay.show()
            voiceOverlay.bringToFront()
        }
        SpeechUiBridge.onAmplitude = { amp ->
            voiceOverlay.updateAmplitude(amp)
        }
        SpeechUiBridge.onDone = {
            isVoiceRecording = false
            panel.recording = false
            if (!voiceOverlay.isLocked) {
                voiceOverlay.hide()
            }
        }
        SpeechUiBridge.onFailed = {
            isVoiceRecording = false
            panel.recording = false
            voiceOverlay.hide()
        }
        SpeechUiBridge.onModelMissing = { _ -> showModelDownloadPrompt() }

        SherpaSpeechClient.startHoldSession(context as ImeInputMethodService)
    }

    private fun stopVoiceInput() {
        if (!isVoiceRecording) return
        isVoiceRecording = false
        panel.recording = false
        SherpaSpeechClient.stopHoldSession()
        voiceOverlay.hide()
    }


    fun toggleVoiceLocked() {
        if (isVoiceRecording) {
            stopVoiceInput()
        } else {
            startVoiceInputLocked()
        }
    }

    private fun startVoiceInputLocked() {
        if (isVoiceRecording) return
        if (!ensureRecordAudioPermission()) return
        isVoiceRecording = true
        panel.recording = true
        voiceOverlay.unlock()
        voiceOverlay.applyColors(
            cachedColors.background,
            cachedColors.specialKeyBackground,
            cachedColors.specialKeyPressed,
            cachedColors.specialKeyText
        )
        if (voiceOverlay.parent == null) {
            addView(voiceOverlay)
        }
        voiceOverlay.setDragLocked()

        SpeechUiBridge.clear()
        SpeechUiBridge.onRecordingStarted = {
            voiceOverlay.show()
            voiceOverlay.bringToFront()
        }
        SpeechUiBridge.onAmplitude = { amp ->
            voiceOverlay.updateAmplitude(amp)
        }
        SpeechUiBridge.onDone = {
            isVoiceRecording = false
            panel.recording = false
            if (!voiceOverlay.isLocked) {
                voiceOverlay.hide()
            }
        }
        SpeechUiBridge.onFailed = {
            isVoiceRecording = false
            panel.recording = false
            voiceOverlay.hide()
        }
        SpeechUiBridge.onModelMissing = { _ -> showModelDownloadPrompt() }

        SherpaSpeechClient.startHoldSession(context as ImeInputMethodService)
    }

    fun onInputChanged(
        info: EditorInfo?, text: String, virtualInputConnection: Boolean = false,
    ): Any {
        if (isVoiceRecording && text.isEmpty()) {
            isVoiceRecording = false
            panel.recording = false
            SherpaSpeechClient.stopHoldSession(discard = true)
            voiceOverlay.hide()
        }
        panel.onInputChanged(text)
        keyboardStateManager.onInputChanged(info, text, virtualInputConnection)
        return Unit
    }

    fun showImeToast(message: CharSequence) {
        imeToastView.showToast(message, cachedColors)
        imeToastView.bringToFront()
    }

    // 面板入口（emoji / 符号）也属于用户主动切换，走 pushTo 以便「返回」原路回退
    fun switchKeyboard(name: String) = keyboardStateManager.pushTo(name)
    fun onDepolyFinished() = keyboardStateManager.refreshSchemas()
}
