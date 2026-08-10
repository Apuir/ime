package com.ninthsoft.ime.input.keyboard.window

import android.annotation.SuppressLint
import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import android.util.TypedValue
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.FrameLayout
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isGone
import com.ninthsoft.ime.R
import com.ninthsoft.ime.data.keyboard.theme.KeyboardColors
import com.ninthsoft.ime.data.manager.ClipboardManager
import com.ninthsoft.ime.data.manager.SchemaManager
import com.ninthsoft.ime.data.manager.KeyboardManager
import com.ninthsoft.ime.engine.data.CandidatePinYin
import com.ninthsoft.ime.engine.data.EngineMessage
import com.ninthsoft.ime.input.keyboard.key.KeyActionListener
import com.ninthsoft.ime.input.keyboard.key.KeyboardAction
import com.ninthsoft.ime.input.panel.KawaiiPanel
import com.ninthsoft.ime.input.panel.component.TextEditView
import com.ninthsoft.ime.input.pinner.PreeditPinner
import com.ninthsoft.ime.input.speech.SpeechOverlayView
import com.ninthsoft.ime.base.speech.SherpaSpeechClient
import com.ninthsoft.ime.base.speech.SpeechUiBridge
import com.ninthsoft.ime.input.ImeInputMethodService
import com.ninthsoft.ime.input.dialog.SchemaPickerDialog
import kotlin.math.roundToInt

@SuppressLint("ViewConstructor")
class KeyboardWindowView(
    context: Context,
    onCandidateSelected: ((EngineMessage.Candidate) -> Unit)? = null,
    onToolbarAction: ((KawaiiPanel.Action) -> Unit)? = null,
    onSidePanelAction: ((KeyboardAction) -> Unit)? = null,
    onTextEditingAction: ((TextEditView.Action) -> Unit)? = null,
    onClipboardItemClick: ((ClipboardManager.Entry) -> Unit)? = null,
    onClipboardClear: (() -> Unit)? = null,
    onClipboardItemDelete: ((ClipboardManager.Entry) -> Unit)? = null,
    onCopyTextCommit: ((String) -> Unit)? = null,
    onCandidateGridDragComplete: ((List<EngineMessage.Candidate>) -> Unit)? = null,
    onCandidateForget: ((EngineMessage.Candidate) -> Unit)? = null,
) : FrameLayout(context), IManagedView {

    companion object {
        const val PANEL_HEIGHT_DP = 44
    }

    private var cachedColors: KeyboardColors.ColorScheme = KeyboardColors.resolve(context)

    private val keyboardManager = KeyboardManager(context, this)

    val panel = KawaiiPanel(
        context = context,
        onCandidateSelected = onCandidateSelected,
        onToolbarAction = onToolbarAction,
        onSidePanelAction = onSidePanelAction,
        onTextEditingAction = onTextEditingAction,
        onClipboardItemClick = onClipboardItemClick,
        onClipboardClear = onClipboardClear,
        onClipboardItemDelete = onClipboardItemDelete,
        onCopyTextCommit = onCopyTextCommit,
        onCandidateGridDragComplete = onCandidateGridDragComplete,
        onCandidateForget = onCandidateForget,
    )

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

    var keyActionListener: KeyActionListener
        get() = keyboardManager.keyActionListener
        set(value) {
            keyboardManager.keyActionListener = KeyActionListener { action ->
                transformed(action)?.let { value.onKeyAction(it) }
            }
        }

    fun transformed(action: KeyboardAction): KeyboardAction? {
        val transformed: KeyboardAction? = when (action) {
            is KeyboardAction.RotateSchema -> {
                val schemeId = keyboardManager.rotateSchema()
                return KeyboardAction.SelectSchema(schemeId)
            }

            is KeyboardAction.LayoutSwitchAction -> {
                keyboardManager.switchTo(action.target)
                null
            }

            is KeyboardAction.ResumeAction -> {
                keyboardManager.resume()
                null
            }

            is KeyboardAction.ShowInputMethodPickerAction -> {
                val dialog = SchemaPickerDialog.build(
                    context = context,
                    schemas = keyboardManager.getSchemas(),
                    currentSchemaId = keyboardManager.getCurrentSchema()?.id,
                    colors = cachedColors,
                    onSchemaSelected = { schemaId -> keyboardManager.selectSchema(schemaId) })
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
            SchemaManager.KEY_ENABLED_IDS -> keyboardManager.onConfigChanged(key)
            KeyboardManager.Keyboard.KEY_HEIGHT, KeyboardManager.Keyboard.KEY_HEIGHT_LANDSCAPE, KeyboardManager.Keyboard.Padding.KEY_HORIZONTAL, KeyboardManager.Keyboard.Padding.KEY_BOTTOM, KeyboardManager.Keyboard.KEY_IGNORE_INSETS -> post {
                panel.view.updateHorizontalPadding(
                    KeyboardManager.Keyboard.Padding.getHorizontalDp(context).toFloat()
                )
                requestLayout()
            }

            KeyboardManager.Keyboard.KeyRadius.KEY, KeyboardManager.Keyboard.KEY_THEME, KeyboardManager.Keyboard.Gap.KEY_HORIZONTAL, KeyboardManager.Keyboard.Gap.KEY_VERTICAL -> post { refreshColors() }

            KeyboardManager.Keyboard.RippleEffect.KEY -> post {
                keyboardManager.setRippleEnabled(
                    KeyboardManager.Keyboard.RippleEffect.isEnabled(context)
                )
            }

            KeyboardManager.Keyboard.KeyBorderStroke.KEY -> post { refreshColors() }
        }
    }

    private var cachedBottomInset = 0

    init {
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

        setBackgroundColor(cachedColors.background)

        addView(panel.view, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        addView(
            panel.candidateGrid, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
        )
        addView(panel.menuGrid, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        addView(
            panel.textEditingView,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
        )
        addView(
            panel.clipboardView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
        )
        addView(
            panel.confirmOverlay, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
        )
    }

    fun toggleMenu() {
        panel.toggleMenu()
    }

    fun showTextEditing() = panel.showTextEditing()

    fun hideTextEditing() = panel.hideTextEditing()

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
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
            if (child === panel.view || child === panel.menuGrid || child === panel.textEditingView || child === panel.clipboardView || child === panel.confirmOverlay || child.isGone) continue
            child.measure(
                MeasureSpec.makeMeasureSpec(contentW, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(cHeight, MeasureSpec.EXACTLY),
            )
        }

        panel.menuGrid.measure(
            MeasureSpec.makeMeasureSpec(contentW, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(cHeight, MeasureSpec.EXACTLY),
        )

        panel.textEditingView.measure(
            MeasureSpec.makeMeasureSpec(contentW, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(cHeight, MeasureSpec.EXACTLY),
        )

        panel.clipboardView.measure(
            MeasureSpec.makeMeasureSpec(contentW, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(cHeight, MeasureSpec.EXACTLY),
        )

        panel.confirmOverlay.measure(
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
            if (child === panel.view || child === panel.candidateGrid || child === panel.menuGrid || child === panel.textEditingView || child === panel.clipboardView || child === panel.confirmOverlay || child.isGone) continue
            child.layout(hPad, barH, hPad + contentW, barH + cHeight)
        }

        panel.candidateGrid.layout(hPad, barH, hPad + contentW, barH + cHeight)
        panel.menuGrid.layout(hPad, barH, hPad + contentW, barH + cHeight)
        panel.textEditingView.layout(hPad, barH, hPad + contentW, barH + cHeight)
        panel.clipboardView.layout(hPad, barH, hPad + contentW, barH + cHeight)
        panel.confirmOverlay.layout(hPad, barH, hPad + contentW, barH + cHeight)
    }


    fun onStartInput(info: EditorInfo) {
        panel.view.setExpanded(false)
        panel.onStartInputView()
        keyboardManager.startInput(info)
    }

    fun refreshColors() {
        panel.view.setExpanded(false)
        cachedColors = KeyboardColors.resolve(context)
        setBackgroundColor(cachedColors.background)
        panel.refreshTheme()
        preeditPinner.refreshTheme(context)
        keyboardManager.rebuild(cachedColors)
        voiceOverlay.applyColors(
            cachedColors.background,
            cachedColors.specialKeyBackground,
            cachedColors.specialKeyPressed,
            cachedColors.specialKeyText,
            cachedColors.accentKeyBackground,
            cachedColors.accentKeyText
        )
    }

    fun refreshLayout() = requestLayout()

    fun setCandidates(list: List<EngineMessage.Candidate>) = panel.setCandidates(list)

    fun onPossibleCandidatePinYin(pinyins: List<CandidatePinYin>) {
        panel.onPossibleCandidatePinYin(pinyins)
        keyboardManager.onPossibleCandidatePinYin(pinyins)
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

    override fun onAttach() {}

    override fun onDetach() {
        if (isVoiceRecording) {
            isVoiceRecording = false
            SherpaSpeechClient.stopHoldSession()
            voiceOverlay.hide()
        }
        keyboardManager.detachCurrent()
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
                val intent =
                    Intent(context, com.ninthsoft.ime.base.speech.SpeechPermissionActivity::class.java).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                runCatching { context.startActivity(intent) }
            },
            centerHorizontal = true,
            centerVertical = true,
        )
    }

    private fun startVoiceInput() {
        if (isVoiceRecording) return
        if (!ensureRecordAudioPermission()) return
        isVoiceRecording = true
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
        voiceOverlay.show()
        voiceOverlay.bringToFront()

        SpeechUiBridge.clear()
        SpeechUiBridge.onRecordingStarted = {
            // recording started
        }
        SpeechUiBridge.onAmplitude = { amp ->
            voiceOverlay.updateAmplitude(amp)
        }
        SpeechUiBridge.onDone = {
            isVoiceRecording = false
            if (!voiceOverlay.isLocked) {
                voiceOverlay.hide()
            }
        }

        SherpaSpeechClient.startHoldSession(context as ImeInputMethodService)
    }

    private fun stopVoiceInput() {
        if (!isVoiceRecording) return
        isVoiceRecording = false
        SherpaSpeechClient.stopHoldSession()
        voiceOverlay.hide()
    }

    fun switchWaveViewType(type: SpeechOverlayView.WaveViewType) {
        voiceOverlay.switchWaveView(type)
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
        voiceOverlay.show()
        voiceOverlay.bringToFront()
        voiceOverlay.setDragLocked()

        SpeechUiBridge.clear()
        SpeechUiBridge.onRecordingStarted = {}
        SpeechUiBridge.onAmplitude = { amp ->
            voiceOverlay.updateAmplitude(amp)
        }
        SpeechUiBridge.onDone = {
            isVoiceRecording = false
            if (!voiceOverlay.isLocked) {
                voiceOverlay.hide()
            }
        }

        SherpaSpeechClient.startHoldSession(context as ImeInputMethodService)
    }

    fun onInputChanged(info: EditorInfo?, text: String): Any {
        if (isVoiceRecording && text.isEmpty()) {
            SherpaSpeechClient.stopHoldSession()
            voiceOverlay.hide()
        }
        panel.onInputChanged(text)
        return keyboardManager.onInputChanged(info, text)
    }

    fun switchKeyboard(name: String) = keyboardManager.switchTo(name)
}
