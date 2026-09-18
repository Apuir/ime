package com.ninthsoft.ime.input.panel

import com.ninthsoft.ime.data.manager.ClipboardManager
import com.ninthsoft.ime.data.manager.PhraseManager
import com.ninthsoft.ime.engine.data.EngineMessage
import com.ninthsoft.ime.input.keyboard.key.KeyboardAction
import com.ninthsoft.ime.input.panel.component.TextEditView

interface PanelListener {
    fun onCandidateSelected(candidate: EngineMessage.Candidate) {}

    /**
     * 用户点了**手写**候选（顶栏里那条）。
     *
     * 与 [onCandidateSelected] 分开是必须的：那个走的是 Rime 的选词
     * （`engine.selectCandidate`），而手写候选不属于任何方案，只能替换手写的组合文本。
     *
     * 手写候选现在没有「首候选已经上屏、点别的要替换」这回事了：首候选本身就在组合区里，
     * 点第 2..N 个候选只是把组合文本换成它（跟拼音点候选一样），所以只需要文本，不需要位次。
     */
    fun onHandwritingCandidateSelected(text: String) {}

    fun onToolbarAction(action: PanelAction) {}

    fun onSidePanelAction(action: KeyboardAction) {}

    fun onTextEditingAction(action: TextEditView.Action) {}

    fun onClipboardItemClick(entry: ClipboardManager.Entry) {}

    fun onPhraseClick(phrase: PhraseManager.Phrase) {}

    fun onClipboardClear() {}

    fun onClipboardItemDelete(entry: ClipboardManager.Entry) {}

    fun onCopyTextCommit(text: String) {}

    fun onCandidateGridDragComplete(candidates: List<EngineMessage.Candidate>) {}

    fun onCandidateForget(candidate: EngineMessage.Candidate) {}

    /** 用户点了联想候选右侧的「叉」，要求取消这次联想。 */
    fun onCancelPrediction() {}

    fun onEnterAddPhraseMode() {}

    fun onAddPhraseSave(text: String) {}

    fun onAddPhraseCancel() {}
}