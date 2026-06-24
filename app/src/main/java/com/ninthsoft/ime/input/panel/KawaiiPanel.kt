package com.ninthsoft.ime.input.panel

import android.content.Context
import com.ninthsoft.ime.engine.data.EngineMessage

class KawaiiPanel(
    context: Context,
    var onCandidateSelected: ((EngineMessage.Candidate) -> Unit)? = null,
    var onRerankedSelected: ((String) -> Unit)? = null,
    var onToolbarAction: ((Action) -> Unit)? = null,
) : IPanel {

    sealed class Action {
        data object SwitchKeyboard : Action()
        data object Clipboard : Action()
        data object ToggleVoice : Action()
        data object Settings : Action()
    }

    sealed class TouchResult {
        data class ToolbarAction(val action: Action) : TouchResult()
        data class SelectCandidate(val candidate: EngineMessage.Candidate) : TouchResult()
        data class SelectRerankedCandidate(val text: String) : TouchResult()
    }

    sealed class State {
        data object Idle : State()
        data class Composing(val candidates: List<EngineMessage.Candidate>) : State()
    }

    override val view: KawaiiPanelView = KawaiiPanelView(context).also { v ->
        v.onTap = { result ->
            when (result) {
                is TouchResult.ToolbarAction -> onToolbarAction?.invoke(result.action)
                is TouchResult.SelectCandidate -> onCandidateSelected?.invoke(result.candidate)
                is TouchResult.SelectRerankedCandidate -> onRerankedSelected?.invoke(result.text)
                null -> {}
            }
        }
    }

    override fun setCandidates(list: List<EngineMessage.Candidate>) {
        if (!view.isLaidOut) {
            view.post { setCandidates(list) }
            return
        }
        view.currentRenderer = when {
            list.isEmpty() -> {
                view.scrollX = 0f; KawaiiPanelView.IdleRenderer()
            }

            view.currentRenderer !is KawaiiPanelView.ComposingRenderer -> {
                view.scrollX = 0f; KawaiiPanelView.ComposingRenderer(list)
            }

            else -> KawaiiPanelView.ComposingRenderer(list)
        }
        view.invalidate()
    }

    override fun showRerankAnimation() {
        view.showRerankAnimation()
    }

    override fun setRerankedCandidate(candidate: EngineMessage.Candidate) {
        view.setRerankedCandidate(candidate)
    }

    override fun refreshTheme() {
        view.refreshTheme()
    }
}
