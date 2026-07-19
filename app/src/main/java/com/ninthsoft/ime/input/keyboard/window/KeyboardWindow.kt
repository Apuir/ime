package com.ninthsoft.ime.input.keyboard.window

import android.view.inputmethod.EditorInfo
import com.ninthsoft.ime.data.keyboard.theme.KeyboardColors
import com.ninthsoft.ime.data.manager.ClipboardRepository
import com.ninthsoft.ime.engine.data.CandidatePinYin
import com.ninthsoft.ime.engine.data.EngineMessage
import com.ninthsoft.ime.input.ImeInputMethodService
import com.ninthsoft.ime.input.keyboard.key.KeyActionListener
import com.ninthsoft.ime.input.panel.IPanel
import com.ninthsoft.ime.input.panel.KawaiiPanel
import com.ninthsoft.ime.input.panel.component.TextEditView

class KeyboardWindow(
    service: ImeInputMethodService,
    onCandidateSelected: ((EngineMessage.Candidate) -> Unit)? = null,
    onRerankedSelected: ((String) -> Unit)? = null,
    onToolbarAction: ((KawaiiPanel.Action) -> Unit)? = null,
    onSidePanelAction: ((com.ninthsoft.ime.input.keyboard.key.KeyboardAction) -> Unit)? = null,
    onTextEditingAction: ((TextEditView.Action) -> Unit)? = null,
    onClipboardItemClick: ((ClipboardRepository.Entry) -> Unit)? = null,
    onClipboardClear: (() -> Unit)? = null,
    onClipboardItemDelete: ((ClipboardRepository.Entry) -> Unit)? = null,
) : IManagedView {

    val view: KeyboardWindowView = KeyboardWindowView(
        context = service,
        onCandidateSelected = onCandidateSelected,
        onRerankedSelected = onRerankedSelected,
        onToolbarAction = onToolbarAction,
        onSidePanelAction = onSidePanelAction,
        onTextEditingAction = onTextEditingAction,
        onClipboardItemClick = onClipboardItemClick,
        onClipboardClear = onClipboardClear,
        onClipboardItemDelete = onClipboardItemDelete,
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

    fun setRerankedCandidate(candidate: EngineMessage.Candidate) {
        view.setRerankedCandidate(candidate)
    }

    fun onPossibleCandidatePinYin(pinyins: Array<CandidatePinYin>) {
        view.onPossibleCandidatePinYin(pinyins)
    }

    fun updatePreedit(text: String?) {
        view.updatePreedit(text)
    }

    fun onSelectionUpdate(start: Int, end: Int) {
        view.panel.onSelectionUpdate(start, end)
    }

    suspend fun handleEngineMessage(message: EngineMessage) {
        messageHandler.handle(message)
    }

    fun onStartInputView(info: EditorInfo, restarting: Boolean) {
        view.onStartInput(info)
        view.refreshLayout()
    }

    fun onFinishInputView(finishingInput: Boolean) {
        panel.onFinishInputView(finishingInput)
    }

    override fun onAttach() = view.onAttach()

    override fun onDetach() = view.onDetach()

    fun onConfigChanged(key: String) = view.onConfigChanged(key)

    fun onSchemaChanged(schemaId: String) {
        view.onSchemaChanged(schemaId)
    }
}
