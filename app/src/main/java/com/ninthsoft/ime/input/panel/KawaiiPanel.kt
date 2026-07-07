package com.ninthsoft.ime.input.panel

import android.annotation.SuppressLint
import android.content.Context
import android.view.View
import com.ninthsoft.ime.R
import com.ninthsoft.ime.data.keyboard.theme.KeyboardColors
import com.ninthsoft.ime.data.theme.ThemeManager
import com.ninthsoft.ime.engine.data.CandidatePinYin
import com.ninthsoft.ime.engine.data.EngineMessage
import com.ninthsoft.ime.input.panel.component.CandidateGridView
import com.ninthsoft.ime.input.panel.component.MenuGridView
import com.ninthsoft.ime.input.panel.toolbar.IdleRenderer

class KawaiiPanel(
    val context: Context,
    var onCandidateSelected: ((EngineMessage.Candidate) -> Unit)? = null,
    var onRerankedSelected: ((String) -> Unit)? = null,
    var onToolbarAction: ((Action) -> Unit)? = null,
) : IPanel {

    sealed class Action {
        data object SwitchKeyboard : Action()
        data object Clipboard : Action()
        data object ToggleVoice : Action()
        data object Settings : Action()
        data object RotateLeft : Action()
        data object RotateRight : Action()
        data object Palette : Action()
        data object CursorMove : Action()
        data object CloseKeyboard : Action()
    }

    sealed class TouchResult {
        data class ToolbarAction(val action: Action) : TouchResult()
        data class SelectCandidate(val candidate: EngineMessage.Candidate) : TouchResult()
        data class SelectRerankedCandidate(val text: String) : TouchResult()
        data object ExpandCandidates : TouchResult()
        data object CollapseCandidates : TouchResult()
    }

    sealed class State {
        data object Idle : State()
        data class Composing(val candidates: List<EngineMessage.Candidate>) : State()
        data object Menu : State()
    }

    private var state: State = State.Idle
        set(value) {
            if (field == value) return
            exit(field)
            field = value
            enter(field)
        }

    private fun exit(state: State) {
        when (state) {
            is State.Composing -> {
                candidateGrid.animate().cancel()
                candidateGrid.visibility = View.GONE
            }

            State.Menu -> {
                menuGrid.animate().cancel()
                menuGrid.visibility = View.GONE
            }

            State.Idle -> {}
        }
    }

    private fun enter(state: State) {
        when (state) {
            is State.Composing -> candidateGrid.show(state.candidates)
            State.Menu -> menuGrid.show()
            State.Idle -> {}
        }
    }

    private val resolvedColors: KeyboardColors.ColorScheme
        get() = KeyboardColors.resolve(context)

    val candidateGrid = CandidateGridView(
        context = context,
        colors = resolvedColors,
        onCandidateSelected = { candidate ->
            view.onTap?.invoke(TouchResult.SelectCandidate(candidate))
        },
    ).apply { visibility = View.GONE }

    val menuGrid = MenuGridView(
        context = context,
        colors = resolvedColors,
    ).apply {
        visibility = View.GONE
        onAction = { action ->
            state = State.Idle
            view.onTap?.invoke(TouchResult.ToolbarAction(action))
        }
    }

    override val view: KawaiiPanelView = KawaiiPanelView(context).also { v ->
        v.onTap = { result ->
            when (result) {
                is TouchResult.ToolbarAction -> onToolbarAction?.invoke(result.action)
                is TouchResult.SelectCandidate -> {
                    if (v.isExpanded) v.setExpanded(false)
                    onCandidateSelected?.invoke(result.candidate)
                }

                is TouchResult.SelectRerankedCandidate -> onRerankedSelected?.invoke(result.text)
                is TouchResult.ExpandCandidates -> v.setExpanded(true)
                is TouchResult.CollapseCandidates -> v.setExpanded(false)
                null -> {}
            }
        }

        v.onExpandChanged = { expanded, candidates ->
            state = if (expanded) State.Composing(candidates) else State.Idle
        }
    }

    fun toggleMenu() {
        if (state == State.Menu) {
            state = State.Idle
        } else {
            view.setExpanded(false)
            state = State.Menu
        }
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        state = State.Idle
    }

    @SuppressLint("UseCompatLoadingForDrawables")
    override fun setCandidates(list: List<EngineMessage.Candidate>) {
        if (!view.isLaidOut) {
            view.post { setCandidates(list) }
            return
        }
        if (list.isEmpty()) {
            view.setExpanded(false)
            view.scrollX = 0f
            view.currentRenderer = IdleRenderer(
                context.getDrawable(R.drawable.ic_keyboard_menu),
                context.getDrawable(R.drawable.ic_keyboard_arrow_back),
                context.getDrawable(R.drawable.ic_keyboard_clipboard),
                context.getDrawable(R.drawable.ic_keyboard_undo),
                context.getDrawable(R.drawable.ic_keyboard_redo),
                context.getDrawable(R.drawable.ic_keyboard_palette),
                context.getDrawable(R.drawable.ic_keyboard_cursor_move),
                context.getDrawable(R.drawable.ic_keyboard_keyboard_close),
                ThemeManager.Keyboard.Padding.getHorizontalDp(context).toFloat(),
            )
        } else {
            view.scrollX = 0f
            view.currentRenderer = ComposingRenderer(
                list, context.getDrawable(R.drawable.ic_keyboard_expand_more),
                ThemeManager.Keyboard.Padding.getHorizontalDp(context).toFloat(),
            )
        }
        view.invalidate()
    }

    override fun showRerankAnimation() {
        view.showRerankAnimation()
    }

    override fun setRerankedCandidate(candidate: EngineMessage.Candidate) {
        view.setRerankedCandidate(candidate)
    }

    override fun onPossibleCandidatePinYin(pinyins: Array<CandidatePinYin>) {
        candidateGrid.onPossibleCandidatePinYin(pinyins)
    }

    override fun refreshTheme() {
        view.refreshTheme()
        candidateGrid.refreshTheme(context)
    }
}
