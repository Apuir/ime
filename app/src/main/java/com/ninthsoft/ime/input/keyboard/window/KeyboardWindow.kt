package com.ninthsoft.ime.input.keyboard.window

import android.view.inputmethod.EditorInfo
import com.ninthsoft.ime.data.keyboard.theme.KeyboardColors
import com.ninthsoft.ime.data.manager.ClipboardManager
import com.ninthsoft.ime.engine.data.CandidatePinYin
import com.ninthsoft.ime.engine.data.EngineMessage
import com.ninthsoft.ime.input.ImeInputMethodService
import com.ninthsoft.ime.input.keyboard.key.KeyActionListener
import com.ninthsoft.ime.input.panel.IPanel
import com.ninthsoft.ime.input.panel.KawaiiPanel
import com.ninthsoft.ime.input.panel.component.TextEditView

class KeyboardWindow(
    service: ImeInputMethodService,
    keyboardStateManager: KeyboardStateManager,
    onCandidateSelected: ((EngineMessage.Candidate) -> Unit)? = null,
    onToolbarAction: ((KawaiiPanel.Action) -> Unit)? = null,
    onSidePanelAction: ((com.ninthsoft.ime.input.keyboard.key.KeyboardAction) -> Unit)? = null,
    onTextEditingAction: ((TextEditView.Action) -> Unit)? = null,
    onClipboardItemClick: ((ClipboardManager.Entry) -> Unit)? = null,
    onClipboardClear: (() -> Unit)? = null,
    onClipboardItemDelete: ((ClipboardManager.Entry) -> Unit)? = null,
    onCopyTextCommit: ((String) -> Unit)? = null,
    onCandidateGridDragComplete: ((List<EngineMessage.Candidate>) -> Unit)? = null,
    onCandidateForget: ((EngineMessage.Candidate) -> Unit)? = null,
) {

    var currentEditorInfo: EditorInfo? = null

    val view: KeyboardWindowView = KeyboardWindowView(
        context = service,
        keyboardStateManager = keyboardStateManager,
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

    val colors: KeyboardColors.ColorScheme get() = KeyboardColors.resolve(view.context)

    val panel: IPanel get() = view.panel

    private val messageHandler = MessageHandler(service).also { it.attach(this) }

    fun setKeyActionListener(listener: KeyActionListener) {
        view.keyActionListener = listener
    }

    fun setCandidates(list: List<EngineMessage.Candidate>) {
        view.setCandidates(list)
    }

    fun onPossibleCandidatePinYin(pinyins: List<CandidatePinYin>) {
        view.onPossibleCandidatePinYin(pinyins)
    }

    fun updateDynamicPreedit(items: List<EngineMessage.DynamicPreedit.DynamicPreeditItem>) {
        view.updateDynamicPreedit(items)
    }

    fun onSelectionUpdate(start: Int, end: Int) {
        view.panel.onSelectionUpdate(start, end)
    }

    suspend fun handleEngineMessage(message: EngineMessage) {
        messageHandler.handle(message)
    }

    fun onStartInputView(info: EditorInfo, restarting: Boolean) {
        currentEditorInfo = info
        view.onStartInput(info)
        view.refreshLayout()
    }

    fun onFinishInputView(finishingInput: Boolean) {
        panel.onFinishInputView(finishingInput)
    }

    fun onWindowShown() = view.onAttach()

    fun onWindowHidden() = view.onDetach()

    fun onConfigChanged(key: String) = view.onConfigChanged(key)

    fun toggleVoiceLocked() = view.toggleVoiceLocked()

    fun onInputChanged(text: String) = view.onInputChanged(currentEditorInfo, text)
}
