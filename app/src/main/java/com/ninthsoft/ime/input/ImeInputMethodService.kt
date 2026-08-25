package com.ninthsoft.ime.input

import android.content.SharedPreferences
import android.content.res.Configuration
import android.inputmethodservice.InputMethodService
import android.os.SystemClock
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import com.ninthsoft.ime.ImeApplication
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
import timber.log.Timber

class ImeInputMethodService : InputMethodService() {
    internal val engine: IEngine? get() = EngineFactory.current()
    internal var keyboardWindow: KeyboardWindow? = null
    internal lateinit var keyActionListener: KeyActionListener
    var scope: CoroutineScope? = null
    var messageObserveJob: Job? = null
    private var showingDialog: android.app.Dialog? = null
    private var lastSelectionStart = 0
    private var lastSelectionEnd = 0
    private val themePrefs: SharedPreferences by lazy {
        getSharedPreferences(KeyboardManager.PREFS_NAME, MODE_PRIVATE)
    }

    private val schemaPrefs: SharedPreferences by lazy {
        getSharedPreferences(SchemaManager.PREFS_NAME, MODE_PRIVATE)
    }

    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        keyboardWindow?.onConfigChanged(key.orEmpty())
    }

    override fun onCreate() {
        super.onCreate()
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
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        keyboardWindow?.view?.refreshColors()
    }

    override fun onCreateInputView(): View {
        keyboardWindow?.let {
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
        return window.view
    }

    override fun onStartInputView(info: EditorInfo, restarting: Boolean) {
        keyboardWindow?.onStartInputView(info, restarting)
        engine?.onStartInputView(currentInputConnection)
        notifyInputChanged()
        super.onStartInputView(info, restarting)
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        showingDialog?.dismiss()
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

    private fun sendCombinationKeyEvent(keyCode: Int, shift: Boolean) {
        val ic = currentInputConnection ?: return
        val downTime = SystemClock.uptimeMillis()
        val device = KeyCharacterMap.VIRTUAL_KEYBOARD
        val flags = KeyEvent.FLAG_SOFT_KEYBOARD or KeyEvent.FLAG_KEEP_TOUCH_MODE
        val metaShift = KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON

        fun down(code: Int, meta: Int = 0) {
            ic.sendKeyEvent(
                KeyEvent(downTime, downTime, KeyEvent.ACTION_DOWN, code, 0, meta, device, 0, flags)
            )
        }

        fun up(code: Int, meta: Int = 0) {
            ic.sendKeyEvent(
                KeyEvent(
                    downTime,
                    SystemClock.uptimeMillis(),
                    KeyEvent.ACTION_UP,
                    code,
                    0,
                    meta,
                    device,
                    0,
                    flags
                )
            )
        }

        if (shift) down(KeyEvent.KEYCODE_SHIFT_LEFT)
        down(keyCode, if (shift) metaShift else 0)
        up(keyCode, if (shift) metaShift else 0)
        if (shift) up(KeyEvent.KEYCODE_SHIFT_LEFT)
    }

    internal fun handleTextEditingAction(action: TextEditView.Action) {
        val ic = currentInputConnection
        when (action) {
            is TextEditView.Action.MoveLeft -> sendCombinationKeyEvent(
                KeyEvent.KEYCODE_DPAD_LEFT, action.shift
            )

            is TextEditView.Action.MoveRight -> sendCombinationKeyEvent(
                KeyEvent.KEYCODE_DPAD_RIGHT, action.shift
            )

            is TextEditView.Action.MoveUp -> sendCombinationKeyEvent(
                KeyEvent.KEYCODE_DPAD_UP, action.shift
            )

            is TextEditView.Action.MoveDown -> sendCombinationKeyEvent(
                KeyEvent.KEYCODE_DPAD_DOWN, action.shift
            )

            is TextEditView.Action.MoveHome -> sendCombinationKeyEvent(
                KeyEvent.KEYCODE_MOVE_HOME, action.shift
            )

            is TextEditView.Action.MoveEnd -> sendCombinationKeyEvent(
                KeyEvent.KEYCODE_MOVE_END, action.shift
            )

            TextEditView.Action.SelectToggle -> { /* local state toggle handled in view */
            }

            TextEditView.Action.CancelSelection -> {
                if (lastSelectionStart != lastSelectionEnd) {
                    ic?.setSelection(lastSelectionEnd, lastSelectionEnd)
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

    fun notifyInputChanged() {
        var text = currentInputConnection?.getTextBeforeCursor(1, 0)?.toString() ?: ""
        text += currentInputConnection.getTextAfterCursor(1, 0)?.toString() ?: ""
        keyboardWindow?.onInputChanged(text)
        if (text.isEmpty()) engine?.onInputCleared()
    }
}
