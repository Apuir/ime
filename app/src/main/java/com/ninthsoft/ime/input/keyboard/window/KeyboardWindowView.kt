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
    }

    private var cachedColors: KeyboardColors.ColorScheme = KeyboardColors.resolve(context)

    // ==================== 横屏悬浮键盘 ====================

    /** 横屏悬浮开关（由 [com.ninthsoft.ime.input.ImeInputMethodService] 注入）。 */
    private var floatingEnabled: Boolean = false

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
            KeyboardManager.Keyboard.KEY_HEIGHT, KeyboardManager.Keyboard.KEY_HEIGHT_LANDSCAPE, KeyboardManager.Keyboard.Padding.KEY_HORIZONTAL, KeyboardManager.Keyboard.Padding.KEY_BOTTOM, KeyboardManager.Keyboard.KEY_IGNORE_INSETS -> post {
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

            // 位置被外部改动（例如设置里的「重置悬浮键盘位置」）时，丢弃内存中的拖动位置重新读设置。
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
        panel.onFinishInputView(true)
        preeditPinner.hide(wm)
        super.onDetachedFromWindow()
    }

    // ==================== 卡片布局几何 ====================

    private val isLandscape: Boolean
        get() = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    /** 悬浮卡片宽度百分比；未开启横屏悬浮时返回 100（即原来的贴底全宽布局）。 */
    private fun effectiveWidthPercent(): Int =
        if (floatingEnabled) KeyboardManager.Keyboard.Floating.getWidthPercent(context) else 100

    private fun currentPosXRatio(): Float =
        floatingXRatio ?: KeyboardManager.Keyboard.Floating.getPositionXRatio(context)

    private fun currentPosYRatio(): Float =
        floatingYRatio ?: KeyboardManager.Keyboard.Floating.getPositionYRatio(context)

    /** 是否使用「全屏透明窗口 + 悬浮卡片」布局。 */
    val usesOverlayLayout: Boolean
        get() = isLandscape && effectiveWidthPercent() < 100

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
            // 悬浮模式：本 View 占满整个 IME 窗口（背景透明），键盘绘制在一张固定大小的卡片里。
            // 应用是否需要缩放、哪些区域可触摸由 ImeInputMethodService.onComputeInsets 决定。
            val availHeight = measureSpecHeight(heightMeasureSpec)
            val cardW = (totalWidth * effectiveWidthPercent() / 100f)
                .roundToInt().coerceIn(1, totalWidth)
            // 卡片高度 = 手柄 + 候选栏 + 键盘内容 + 底部边距；内容不足时按窗口可用高度收窄。
            val maxContentH = (availHeight - bottomInset - handleH - barH - bPad)
                .coerceAtLeast(minimumHeight)
            val cardContentH = cHeight.coerceAtMost(maxContentH)
            val cardH = handleH + barH + cardContentH + bPad

            floatingAvailX = (totalWidth - cardW).coerceAtLeast(0)
            floatingAvailY = (availHeight - cardH - bottomInset).coerceAtLeast(0)
            val cardX = (currentPosXRatio() * floatingAvailX).roundToInt().coerceIn(0, floatingAvailX)
            val cardY = (currentPosYRatio() * floatingAvailY).roundToInt().coerceIn(0, floatingAvailY)
            floatingCard.set(cardX, cardY, cardX + cardW, cardY + cardH)
            floatingHandle.set(cardX, cardY, cardX + cardW, cardY + handleH)

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

            // 顶部拖动条的视觉提示
            val gripW = 40 * density
            val gripH = 4 * density
            val cx = floatingCard.exactCenterX()
            val cy = floatingHandle.exactCenterY()
            handleRectF.set(cx - gripW / 2f, cy - gripH / 2f, cx + gripW / 2f, cy + gripH / 2f)
            handlePaint.color = cachedColors.altText
            handlePaint.alpha = 110
            canvas.drawRoundRect(handleRectF, gripH / 2f, gripH / 2f, handlePaint)
        }
        super.dispatchDraw(canvas)
    }

    /**
     * 悬浮模式下卡片只有顶部手柄区域可拖动；卡片其余部分以及卡片外的区域行为保持不变
     * （卡片外的触摸会被系统穿透给下层应用）。
     */
    override fun onTouchEvent(event: MotionEvent): Boolean {
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
        KeyboardManager.Keyboard.Floating.setPosition(context, x, y)
    }

    /** 切换悬浮/普通模式（由 ImeInputMethodService 按横竖屏注入）。 */
    fun setFloatingMode(enabled: Boolean) {
        if (floatingEnabled == enabled) return
        floatingEnabled = enabled
        dragActive = false
        applyBackgroundTint()
        requestLayout()
    }


    /**
     * 悬浮模式下需要由输入法窗口接收触摸的区域。
     *
     * 返回的是 **IME 窗口坐标系** 下的矩形：`Insets.touchableRegion` 要求相对窗口原点，
     * 而本 View 在窗口里可能有偏移（框架的输入容器带 candidatesArea 等），因此统一加上
     * 本 View 在窗口内的位置，避免依赖具体布局。
     */
    fun floatingTouchableRegion(out: Rect) {
        if (addPhraseActive || isVoiceRecording) {
            // 添加常用语 / 语音悬浮条需要在整窗口范围内交互。
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
