package com.ninthsoft.ime.input

import android.content.SharedPreferences
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.inputmethodservice.InputMethodService
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import com.ninthsoft.ime.ImeApplication
import com.ninthsoft.ime.base.util.InputConnectionUtil
import com.ninthsoft.ime.data.manager.CandidateManager
import com.ninthsoft.ime.data.manager.ClipboardManager
import com.ninthsoft.ime.data.manager.KeyboardManager
import com.ninthsoft.ime.data.manager.SchemaManager
import com.ninthsoft.ime.engine.EngineFactory
import com.ninthsoft.ime.engine.IEngine
import com.ninthsoft.ime.input.keyboard.window.KeyboardWindow
import com.ninthsoft.ime.input.keyboard.window.KeyboardStateManager
import com.ninthsoft.ime.input.panel.component.TextEditView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class ImeInputMethodService : InputMethodService() {
    internal val engine: IEngine? get() = EngineFactory.current()
    internal var keyboardWindow: KeyboardWindow? = null
    internal lateinit var keyActionListener: KeyActionListener

    /** 桥接模式：添加常用语时为真，所有提交/删除/语音输出写入 [virtualInputConnection] 而非真实编辑器。 */
    var phraseAddBridgeActive = false
    val virtualInputConnection = ImeInputConnection(this)

    /** 输入框实时上屏（预览）控制器。 */
    val livePreview = LivePreviewController(this)

    fun activeInputConnection(): android.view.inputmethod.InputConnection? =
        if (phraseAddBridgeActive) virtualInputConnection else currentInputConnection
    var scope: CoroutineScope? = null
    var messageObserveJob: Job? = null
    private var showingDialog: android.app.Dialog? = null
    private var lastSelectionStart = 0
    private var lastSelectionEnd = 0

    /** 复用缓冲区，避免 onComputeInsets 每次分配（该方法在一次布局中可能被多次调用）。 */
    private val insetsRegion = Rect()

    /** 进入悬浮模式前的 IME 窗口背景，离开时还原。 */
    private var originalImeWindowBackground: Drawable? = null
    private var imeWindowBackgroundCleared = false
    private val themePrefs: SharedPreferences by lazy {
        getSharedPreferences(KeyboardManager.PREFS_NAME, MODE_PRIVATE)
    }

    private val schemaPrefs: SharedPreferences by lazy {
        getSharedPreferences(SchemaManager.PREFS_NAME, MODE_PRIVATE)
    }

    private val candidatePrefs: SharedPreferences by lazy {
        getSharedPreferences(CandidateManager.PREFS_NAME, MODE_PRIVATE)
    }

    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        // 上屏模式变化后立即刷新当前预览，无需重新输入。
        if (key == CandidateManager.KEY_PREVIEW_MODE) {
            livePreview.onPreviewModeChanged()
        }
        // 悬浮开关可能是在键盘收起时改的，这里同步一次窗口背景，避免窗口全屏却不透明。
        if (key == KeyboardManager.Keyboard.Floating.KEY_ENABLED) {
            applyFloatingMode(KeyboardManager.Keyboard.Floating.shouldUseFloating(this))
        }
        keyboardWindow?.onConfigChanged(key.orEmpty())
    }

    override fun onCreate() {
        super.onCreate()
        virtualInputConnection.addOnChangeListener {
            if (phraseAddBridgeActive) syncActiveInputState()
        }
        //service scope & message subscribe
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main).apply {
            launch {
                val app = applicationContext as? ImeApplication
                // 监听应用状态，当引擎启动的时候时开始绑定消息
                app?.state?.collectLatest { state ->
                    if (state == ImeApplication.AppState.EngineStarting) {
                        messageObserveJob = engine?.observeMessages(this) { message ->
                            keyboardWindow?.handleEngineMessage(message)
                        }
                    }
                }
            }
        }
        // KeyActionListener & prefrece change listenter
        keyActionListener = KeyActionListener(service = this)
        themePrefs.registerOnSharedPreferenceChangeListener(prefsListener)
        schemaPrefs.registerOnSharedPreferenceChangeListener(prefsListener)
        candidatePrefs.registerOnSharedPreferenceChangeListener(prefsListener)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // 横竖屏切换时框架会重建输入视图，这里同步一次悬浮模式，保证窗口尺寸立即跟随方向变化。
        applyFloatingMode(KeyboardManager.Keyboard.Floating.shouldUseFloating(this))
        keyboardWindow?.view?.refreshColors()
    }

    override fun onCreateInputView(): View {
        val floating = KeyboardManager.Keyboard.Floating.shouldUseFloating(this)
        keyboardWindow?.let {
            applyFloatingMode(floating)
            // InputMethodService adds the returned root to its own container.
            // Detach it first when the same window instance is requested again.
            (it.view.parent as? ViewGroup)?.removeView(it.view)
            return it.view
        }
        val window = KeyboardWindow(
            service = this,
            keyboardStateManager = KeyboardStateManager,
            panelActionListener = PanelActionListener(this),
        )
        keyboardWindow = window
        window.setKeyActionListener(keyActionListener)
        applyFloatingMode(floating)
        // KeyboardStateManager 是进程级单例，其键盤注册表可能残留上一实例（旧配色）的键盘；
        // 新建窗口（销毁重建路径）时重建一次，让键盘用本次实例解析出的新配色生成。
        KeyboardStateManager.rebuild()
        return window.view
    }

    /**
     * 切换悬浮模式：除了通知键盘视图，还要同步 IME 窗口自身的背景。
     *
     * 悬浮模式下 IME 窗口会占满整个屏幕，如果窗口背景不透明（例如默认的
     * `Theme.DeviceDefault.InputMethod` / 应用主题的 `windowBackground`）就会把下层应用整个挡住，
     * 因此悬浮时把窗口背景换成透明，离开悬浮模式时还原。
     */
    private fun applyFloatingMode(floating: Boolean) {
        keyboardWindow?.setFloatingMode(floating)
        val win = window?.window ?: return
        if (floating) {
            if (!imeWindowBackgroundCleared) {
                originalImeWindowBackground = win.decorView.background
                imeWindowBackgroundCleared = true
            }
            win.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        } else if (imeWindowBackgroundCleared) {
            win.setBackgroundDrawable(originalImeWindowBackground)
            imeWindowBackgroundCleared = false
        }
    }

    /**
     * 悬浮键盘模式下 IME 窗口占满整个屏幕但背景透明，键盘只是窗口内的一张卡片。
     *
     * 这里把 `contentTopInsets` / `visibleTopInsets` 设为窗口底部，使 IME 上报给下层应用的
     * 内容边衬为零（应用不会被顶起），同时通过 `touchableRegion` 声明只有卡片区域接收触摸，
     * 卡片之外的手势会穿透到下层应用。这正是主流输入法「悬浮键盘」的实现方式。
     */
    override fun onComputeInsets(outInsets: InputMethodService.Insets?) {
        val view = keyboardWindow?.view
        if (outInsets == null || view == null || !view.usesOverlayLayout) {
            super.onComputeInsets(outInsets)
            return
        }
        val contentBottom = view.contentBottomInWindowPx()
        view.floatingTouchableRegion(insetsRegion)
        outInsets.contentTopInsets = contentBottom
        outInsets.visibleTopInsets = contentBottom
        outInsets.touchableRegion.set(insetsRegion)
        outInsets.touchableInsets = InputMethodService.Insets.TOUCHABLE_INSETS_REGION
    }

    override fun onStartInputView(info: EditorInfo, restarting: Boolean) {
        keyboardWindow?.onStartInputView(info, restarting)
        engine?.onStartInputView(currentInputConnection)
        notifyInputChanged()
        super.onStartInputView(info, restarting)
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        showingDialog?.dismiss()
        // 收起键盘前按设置决定是否保留已上屏内容，再重置引擎组合。
        livePreview.finalizeForKeyboardSwitch()
        engine?.resetComposition()
        keyboardWindow?.onFinishInputView(finishingInput)
        engine?.onFinishInputView()
        super.onFinishInputView(finishingInput)
    }

    override fun onWindowHidden() {
        keyboardWindow?.onWindowHidden()
        ClipboardManager.stopMonitoring(this)
        super.onWindowHidden()
    }

    override fun onWindowShown() {
        super.onWindowShown()
        // 每次显示都对齐一次悬浮模式：键盘可能是收起期间被改了设置，或输入视图被框架复用。
        applyFloatingMode(KeyboardManager.Keyboard.Floating.shouldUseFloating(this))
        keyboardWindow?.onWindowShown()
        ClipboardManager.startMonitoring(this)
        if (messageObserveJob == null) {
            messageObserveJob = scope?.let { scope ->
                engine?.observeMessages(scope) {
                    keyboardWindow?.handleEngineMessage(it)
                }
            }
        }
    }

    override fun onDestroy() {
        messageObserveJob?.cancel()
        scope?.cancel()
        scope = null
        keyboardWindow = null

        ClipboardManager.stopMonitoring(this)
        themePrefs.unregisterOnSharedPreferenceChangeListener(prefsListener)
        schemaPrefs.unregisterOnSharedPreferenceChangeListener(prefsListener)
        candidatePrefs.unregisterOnSharedPreferenceChangeListener(prefsListener)
        super.onDestroy()
    }

    override fun onEvaluateFullscreenMode(): Boolean = false

    override fun onEvaluateInputViewShown(): Boolean {
        super.onEvaluateInputViewShown()
        return true
    }

    fun showDialog(dialog: android.app.Dialog) {
        showingDialog?.dismiss()
        val tokenView = keyboardWindow?.view ?: return
        dialog.window?.apply {
            attributes.token = tokenView.windowToken
            attributes.type = WindowManager.LayoutParams.TYPE_APPLICATION_ATTACHED_DIALOG
            addFlags(
                WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM or WindowManager.LayoutParams.FLAG_DIM_BEHIND
            )
            setDimAmount(0.5f)
        }
        dialog.setOnDismissListener { showingDialog = null }
        dialog.show()
        showingDialog = dialog
    }

    internal fun handleTextEditingAction(action: TextEditView.Action) {
        val ic = activeInputConnection()
        when (action) {
            is TextEditView.Action.MoveLeft -> sendCombinationKeyEvent(
                ic, KeyEvent.KEYCODE_DPAD_LEFT, action.shift
            )

            is TextEditView.Action.MoveRight -> sendCombinationKeyEvent(
                ic, KeyEvent.KEYCODE_DPAD_RIGHT, action.shift
            )

            is TextEditView.Action.MoveUp -> sendCombinationKeyEvent(
                ic, KeyEvent.KEYCODE_DPAD_UP, action.shift
            )

            is TextEditView.Action.MoveDown -> sendCombinationKeyEvent(
                ic, KeyEvent.KEYCODE_DPAD_DOWN, action.shift
            )

            is TextEditView.Action.MoveHome -> sendCombinationKeyEvent(
                ic, KeyEvent.KEYCODE_MOVE_HOME, action.shift
            )

            is TextEditView.Action.MoveEnd -> sendCombinationKeyEvent(
                ic, KeyEvent.KEYCODE_MOVE_END, action.shift
            )

            TextEditView.Action.SelectToggle -> { /* local state toggle handled in view */
            }

            TextEditView.Action.CancelSelection -> {
                if (ic != null) {
                    val selection = if (phraseAddBridgeActive) virtualInputConnection.selection
                    else lastSelectionStart to lastSelectionEnd
                    if (selection.first != selection.second) {
                        ic.setSelection(selection.second, selection.second)
                    }
                }
            }

            TextEditView.Action.SelectAll -> ic?.performContextMenuAction(android.R.id.selectAll)
            TextEditView.Action.Cut -> ic?.performContextMenuAction(android.R.id.cut)
            TextEditView.Action.Copy -> ic?.performContextMenuAction(android.R.id.copy)
            TextEditView.Action.Paste -> ic?.performContextMenuAction(android.R.id.paste)

            TextEditView.Action.Backspace -> engine?.processKey(
                this, com.ninthsoft.ime.engine.event.KeyEvent.CodeEvent(
                    KeyEvent.KEYCODE_DEL, com.ninthsoft.ime.engine.event.KeyModifiers.Empty
                )
            )
        }
    }

    private fun sendCombinationKeyEvent(
        ic: android.view.inputmethod.InputConnection?, keyCode: Int, shift: Boolean,
    ) {
        InputConnectionUtil.sendCombinationKeyEvent(ic, keyCode, shift = shift)
    }

    /**
     * 回车键在「没有拼音组合」时的处理：按输入框声明的语义执行。
     *
     * 声明了 editor action（搜索 / 发送 / 前往 / 完成…）就执行它；没声明就发一个真正的
     * 回车按键事件交给应用自己决定换行还是提交。
     *
     * **不要用 `commitText("\n")` 代替换行** —— 单行输入框会把换行显示成空格，
     * 用户看到的就是「按回车只会多一个空格」，而搜索框根本收不到搜索动作。
     */
    internal fun submitEditorActionOrEnter() {
        val action = declaredEditorAction()
        if (action == EditorInfo.IME_ACTION_NONE || action == EditorInfo.IME_ACTION_UNSPECIFIED) {
            val ic = prepareForCommit() ?: return
            InputConnectionUtil.sendKeyEvent(ic, KeyEvent.KEYCODE_ENTER)
        } else {
            submitEditorAction(action)
        }
    }

    /** 执行指定的 editor action（输入框声明的，或工具栏按钮显式指定的）。 */
    internal fun submitEditorAction(action: Int) {
        val ic = prepareForCommit() ?: return
        ic.performEditorAction(action)
    }

    /** 当前输入框声明的 editor action；未声明时返回 [EditorInfo.IME_ACTION_NONE]。 */
    private fun declaredEditorAction(): Int =
        (keyboardWindow?.currentEditorInfo?.imeOptions ?: 0) and EditorInfo.IME_MASK_ACTION

    /**
     * 提交类动作（回车 / editor action）前的收尾：结束输入框里的预览 composing，
     * 并清空引擎组合 —— 否则动作执行完可能又冒出一段未完成的输入。
     */
    private fun prepareForCommit(): android.view.inputmethod.InputConnection? {
        val ic = activeInputConnection() ?: return null
        livePreview.finalizeForKeyboardSwitch()
        engine?.resetComposition()
        return ic
    }

    override fun onUpdateSelection(
        oldSelStart: Int, oldSelEnd: Int,
        newSelStart: Int, newSelEnd: Int,
        candidatesStart: Int, candidatesEnd: Int,
    ) {
        super.onUpdateSelection(
            oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd
        )
        lastSelectionStart = newSelStart
        lastSelectionEnd = newSelEnd
        keyboardWindow?.onSelectionUpdate(newSelStart, newSelEnd)
        notifyInputChanged()
    }

    internal fun syncActiveInputState() {
        val ic = activeInputConnection() ?: return
        val selection = if (phraseAddBridgeActive) virtualInputConnection.selection
        else lastSelectionStart to lastSelectionEnd
        keyboardWindow?.onSelectionUpdate(selection.first, selection.second)
        keyboardWindow?.onInputChanged(
            ic.getTextBeforeCursor(Int.MAX_VALUE, 0)?.toString().orEmpty() +
                ic.getTextAfterCursor(Int.MAX_VALUE, 0)?.toString().orEmpty(),
            virtualInputConnection = phraseAddBridgeActive,
        )
    }

    fun notifyInputChanged() {
        val ic = activeInputConnection() ?: return
        var text = ic.getTextBeforeCursor(1, 0)?.toString() ?: ""
        text += ic.getTextAfterCursor(1, 0)?.toString() ?: ""
        keyboardWindow?.onInputChanged(text, virtualInputConnection = phraseAddBridgeActive)
        if (text.isEmpty()) engine?.onInputCleared()
    }
}
