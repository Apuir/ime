package com.ninthsoft.ime.input

import android.content.DialogInterface
import android.content.Intent
import android.content.SharedPreferences
import android.inputmethodservice.InputMethodService
import android.os.SystemClock
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import com.ninthsoft.ime.data.manager.ClipboardRepository
import com.ninthsoft.ime.data.manager.KeyboardManager
import com.ninthsoft.ime.data.manager.SchemaManager
import com.ninthsoft.ime.engine.EngineFactory
import com.ninthsoft.ime.engine.IEngine
import com.ninthsoft.ime.engine.RimeEngine
import com.ninthsoft.ime.input.keyboard.impl.EmojiKeyboard
import com.ninthsoft.ime.input.keyboard.window.KeyboardWindow
import com.ninthsoft.ime.input.panel.KawaiiPanel
import com.ninthsoft.ime.input.panel.KawaiiPanel.Action.CloseKeyboard
import com.ninthsoft.ime.input.panel.KawaiiPanel.Action.Palette
import com.ninthsoft.ime.input.panel.KawaiiPanel.Action.Redo
import com.ninthsoft.ime.input.panel.KawaiiPanel.Action.SwitchKeyboard
import com.ninthsoft.ime.input.panel.KawaiiPanel.Action.ToggleVoice
import com.ninthsoft.ime.input.panel.KawaiiPanel.Action.Undo
import com.ninthsoft.ime.input.panel.component.TextEditView
import com.ninthsoft.ime.ui.AboutActivity
import com.ninthsoft.ime.ui.KeyboardSettingsActivity
import com.ninthsoft.ime.ui.MainActivity
import com.ninthsoft.ime.ui.SchemaSettingsActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

class ImeInputMethodService : InputMethodService() {
    private var engine: IEngine? = null
    private var keyboardWindow: KeyboardWindow? = null
    private lateinit var keyActionListener: KeyActionListener
    var scope: CoroutineScope? = CoroutineScope(SupervisorJob() + Dispatchers.IO)

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
        engine = EngineFactory.switchTo(this, RimeEngine::class)
        themePrefs.registerOnSharedPreferenceChangeListener(prefsListener)
        schemaPrefs.registerOnSharedPreferenceChangeListener(prefsListener)
        keyActionListener = KeyActionListener(
            service = this,
            engine = engine,
        )
        engine?.observe(scope!!) { keyboardWindow?.handleEngineMessage(it) }
        ClipboardRepository.startMonitoring(this)
    }

    override fun onCreateInputView(): View {
        keyboardWindow = KeyboardWindow(
            service = this,
            onCandidateSelected = { candidate -> engine?.selectCandidate(candidate.index) },
            onToolbarAction = { action ->
                when (action) {
                    CloseKeyboard -> requestHideSelf(0)
                    SwitchKeyboard -> keyboardWindow?.view?.toggleMenu()
                    KawaiiPanel.Action.EmojiKeyboard -> keyboardWindow?.view?.switchKeyboard(
                        EmojiKeyboard.NAME
                    )

                    Undo -> engine?.undo(this)
                    Redo -> engine?.redo(this)

                    Palette -> startActivity(
                        Intent(
                            this, KeyboardSettingsActivity::class.java
                        ).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        })

                    ToggleVoice -> keyboardWindow?.toggleVoiceLocked()

                    KawaiiPanel.Action.Settings -> startActivity(
                        Intent(
                            this, MainActivity::class.java
                        ).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        })

                    KawaiiPanel.Action.SchemaSettings -> startActivity(
                        Intent(
                            this, SchemaSettingsActivity::class.java
                        ).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        })

                    KawaiiPanel.Action.About -> startActivity(
                        Intent(
                            this, AboutActivity::class.java
                        ).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        })

                    else -> {}
                }
            },
            onSidePanelAction = { action -> keyActionListener.onKeyAction(action) },
            onTextEditingAction = { action -> handleTextEditingAction(action) },
            onClipboardItemClick = { entry -> currentInputConnection?.commitText(entry.text, 1) },
            onClipboardClear = { ClipboardRepository.clearAll(this) },
            onClipboardItemDelete = { entry -> ClipboardRepository.removeEntry(this, entry.text) },
            onCopyTextCommit = { text -> currentInputConnection?.commitText(text, 1) },
            onCandidateGridDragComplete = { candidates ->
                engine?.resortCandidates(candidates)
            },
            onCandidateForget = { candidate ->
                engine?.deleteCandidate(candidate.index)
            },
        ).apply { setKeyActionListener(keyActionListener) }
        return keyboardWindow!!.view
    }

    override fun onStartInputView(info: EditorInfo, restarting: Boolean) {
        keyboardWindow?.onStartInputView(info, restarting)
        notifyInputChanged()
        super.onStartInputView(info, restarting)
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        showingDialog?.dismiss()
        engine?.resetComposition()
        keyboardWindow?.onFinishInputView(finishingInput)
        super.onFinishInputView(finishingInput)
    }

    override fun onWindowHidden() {
        keyboardWindow?.onDetach()
        super.onWindowHidden()
    }

    override fun onDestroy() {
        themePrefs.unregisterOnSharedPreferenceChangeListener(prefsListener)
        schemaPrefs.unregisterOnSharedPreferenceChangeListener(prefsListener)
        scope?.cancel()
        scope = null
        keyboardWindow = null
        ClipboardRepository.stopMonitoring(this)
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

    private fun handleTextEditingAction(action: TextEditView.Action) {
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
    }
}
