package com.ninthsoft.ime.input

import android.content.Intent
import com.ninthsoft.ime.data.manager.ClipboardManager
import com.ninthsoft.ime.engine.data.EngineMessage
import com.ninthsoft.ime.input.keyboard.impl.EmojiKeyboard
import com.ninthsoft.ime.input.keyboard.key.KeyboardAction
import com.ninthsoft.ime.input.panel.KawaiiPanel
import com.ninthsoft.ime.input.panel.component.TextEditView
import com.ninthsoft.ime.ui.AboutActivity
import com.ninthsoft.ime.ui.KeyboardSettingsActivity
import com.ninthsoft.ime.ui.MainActivity
import com.ninthsoft.ime.ui.SchemaSettingsActivity

class PanelActionListener(
    private val service: ImeInputMethodService,
) : KawaiiPanel.Listener {

    override fun onCandidateSelected(candidate: EngineMessage.Candidate) {
        service.engine?.selectCandidate(candidate)
    }

    override fun onToolbarAction(action: KawaiiPanel.Action) {
        when (action) {
            KawaiiPanel.Action.CloseKeyboard -> service.requestHideSelf(0)
            KawaiiPanel.Action.SwitchKeyboard -> service.keyboardWindow?.view?.toggleMenu()
            KawaiiPanel.Action.EmojiKeyboard -> service.keyboardWindow?.view?.switchKeyboard(
                EmojiKeyboard.NAME
            )

            KawaiiPanel.Action.ReloadEngine -> service.engine?.reload()
            KawaiiPanel.Action.Undo -> service.engine?.undo(service)
            KawaiiPanel.Action.Redo -> service.engine?.redo(service)

            KawaiiPanel.Action.Palette -> service.startActivity(
                Intent(service, KeyboardSettingsActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                })

            KawaiiPanel.Action.ToggleVoice -> service.keyboardWindow?.toggleVoiceLocked()

            KawaiiPanel.Action.Settings -> service.startActivity(
                Intent(service, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                })

            KawaiiPanel.Action.SchemaSettings -> service.startActivity(
                Intent(service, SchemaSettingsActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                })

            KawaiiPanel.Action.About -> service.startActivity(
                Intent(service, AboutActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                })

            else -> {}
        }
    }

    override fun onSidePanelAction(action: KeyboardAction) {
        service.keyActionListener.onKeyAction(action)
    }

    override fun onTextEditingAction(action: TextEditView.Action) {
        service.handleTextEditingAction(action)
    }

    override fun onClipboardItemClick(entry: ClipboardManager.Entry) {
        service.keyActionListener.onKeyAction(KeyboardAction.CommitAction(entry.text))
    }

    override fun onClipboardClear() {
        ClipboardManager.clearAll(service)
    }

    override fun onClipboardItemDelete(entry: ClipboardManager.Entry) {
        ClipboardManager.removeEntry(service, entry.text)
    }

    override fun onCopyTextCommit(text: String) {
        service.keyActionListener.onKeyAction(KeyboardAction.CommitAction(text))
    }

    override fun onCandidateGridDragComplete(candidates: List<EngineMessage.Candidate>) {
        service.engine?.resortCandidates(candidates)
    }

    override fun onCandidateForget(candidate: EngineMessage.Candidate) {
        service.engine?.deleteCandidate(candidate.index)
    }
}
