package com.ninthsoft.ime.input.panel

import android.annotation.SuppressLint
import android.content.Context
import com.ninthsoft.ime.R
import com.ninthsoft.ime.data.keyboard.theme.KeyboardColors
import com.ninthsoft.ime.data.manager.ClipboardRepository
import com.ninthsoft.ime.data.manager.KeyboardManager
import com.ninthsoft.ime.engine.data.CandidatePinYin
import com.ninthsoft.ime.engine.data.EngineMessage
import com.ninthsoft.ime.input.panel.component.CandidateGridView
import com.ninthsoft.ime.input.panel.component.ClipboardView
import com.ninthsoft.ime.input.panel.component.ConfirmOverlay
import com.ninthsoft.ime.input.panel.component.MenuGridView
import com.ninthsoft.ime.input.panel.component.TextEditView
import com.ninthsoft.ime.input.panel.toolbar.IdleRenderer
import timber.log.Timber

class KawaiiPanel(
    val context: Context,
    var onCandidateSelected: ((EngineMessage.Candidate) -> Unit)? = null,
    var onRerankedSelected: ((String) -> Unit)? = null,
    var onToolbarAction: ((Action) -> Unit)? = null,
    var onSidePanelAction: ((com.ninthsoft.ime.input.keyboard.key.KeyboardAction) -> Unit)? = null,
    var onTextEditingAction: ((TextEditView.Action) -> Unit)? = null,
    var onClipboardItemClick: ((ClipboardRepository.Entry) -> Unit)? = null,
    var onClipboardClear: (() -> Unit)? = null,
    var onClipboardItemDelete: ((ClipboardRepository.Entry) -> Unit)? = null,
    var onCandidateForget: ((EngineMessage.Candidate) -> Unit)? = null,
    var onCopyTextCommit: ((String) -> Unit)? = null,
    var onCandidateGridDragComplete: ((List<EngineMessage.Candidate>) -> Unit)? = null,
) : IPanel {

    sealed class Action {
        data object SwitchKeyboard : Action()
        data object Clipboard : Action()
        data object ToggleVoice : Action()
        data object Settings : Action()
        data object Undo : Action()
        data object Redo : Action()
        data object Palette : Action()
        data object CursorMove : Action()
        data object CloseKeyboard : Action()
        data object ClearClipboard : Action()
    }

    sealed class TouchResult {
        data class ToolbarAction(
            val action: Action,
            val tapX: Float = Float.NaN,
            val tapY: Float = Float.NaN,
        ) : TouchResult()

        data class SelectCandidate(val candidate: EngineMessage.Candidate) : TouchResult()
        data class SelectRerankedCandidate(val text: String) : TouchResult()
        data object ExpandCandidates : TouchResult()
        data object CollapseCandidates : TouchResult()
    }

    sealed class State {
        data object Idle : State()
        data class Composing(val candidates: List<EngineMessage.Candidate>) : State()
        data object Menu : State()
        data object TextEditing : State()
        data object Clipboard : State()
        data object Copy : State()
    }

    private var state: State = State.Idle
        set(value) {
            if (field == value) return
            exit(field)
            field = value
            enter(field)
            if (field == State.Idle) checkPendingCopy()
        }

    private var copyText: String? = null
    private var clipboardCheckRunnable: Runnable? = null
    private var lastShownCopyTimestamp: Long = 0L
    private var lastShownCopyText: String? = null

    private fun exit(state: State) {
        confirmOverlay.dismiss()
        when (state) {
            is State.Composing -> candidateGrid.hide()
            State.Menu -> menuGrid.hide()
            State.TextEditing -> {
                textEditingView.hide()
                (view.currentRenderer as? IdleRenderer)?.textEditingMode = false
                view.invalidate()
            }

            State.Clipboard -> {
                clipboardView.hide()
                (view.currentRenderer as? IdleRenderer)?.clipboardMode = false
                view.invalidate()
            }

            State.Copy -> {
                (view.currentRenderer as? IdleRenderer)?.copyText = null
                (view.currentRenderer as? IdleRenderer)?.showArrow = false
                view.invalidate()
            }

            State.Idle -> {}
        }
    }

    private fun enter(state: State) {
        when (state) {
            is State.Composing -> candidateGrid.show(state.candidates)
            State.Menu -> menuGrid.show()
            State.TextEditing -> {
                Timber.d("enter TextEditing: setting renderer textEditingMode=true")
                (view.currentRenderer as? IdleRenderer)?.textEditingMode = true
                view.invalidate()
                textEditingView.show()
            }

            State.Clipboard -> {
                Timber.d("enter Clipboard: setting renderer clipboardMode=true")
                (view.currentRenderer as? IdleRenderer)?.clipboardMode = true
                view.invalidate()
                clipboardView.show(ClipboardRepository.getEntries(context))
            }

            State.Copy -> {
                val r = view.currentRenderer as? IdleRenderer
                r?.copyText = copyText
                view.invalidate()
            }

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
        onSidePanelAction = onSidePanelAction,
    ).apply {
        onWordForget = { candidate, x, y ->
            confirmOverlay.confirm(
                message = context.getString(
                    R.string.candidate_forget_confirm,
                    if (candidate.text.length > 5) candidate.text.take(5) + "..." else candidate.text
                ),
                onConfirm = { handleCandidateForget(candidate) },
                cardX = x,
                cardY = y,
            )
        }
        onDragComplete = { candidates ->
            (view.currentRenderer as? ComposingRenderer)?.candidates = candidates
            this@KawaiiPanel.onCandidateGridDragComplete?.invoke(candidates)
        }
    }

    val menuGrid = MenuGridView(
        context = context,
        colors = resolvedColors,
    ).apply {
        onAction = { action ->
            state = State.Idle
            view.onTap?.invoke(TouchResult.ToolbarAction(action))
        }
    }

    val textEditingView = TextEditView(
        context = context,
        colors = resolvedColors,
    ).apply {
        onAction = { action -> onTextEditingAction?.invoke(action) }
    }

    val confirmOverlay = ConfirmOverlay(
        context = context,
        colors = resolvedColors,
    )

    val clipboardView = ClipboardView(
        context = context,
        colors = resolvedColors,
    )

    init {
        clipboardView.onItemClick = { entry -> onClipboardItemClick?.invoke(entry) }
        clipboardView.onItemLongClick = { entry, x, y ->
            Timber.d("clipboard longClick: cardX=$x cardY=$y")
            confirmOverlay.confirm(
                message = context.getString(
                    R.string.clipboard_delete_confirm,
                    if (entry.text.length > 5) entry.text.take(5) + "..." else entry.text
                ),
                onConfirm = { handleClipboardDelete(entry) },
                cardX = x + 100,
                cardY = y + 100,
            )
        }

        ClipboardRepository.onNewEntry = { entry ->
            showCopyIfRecent(entry.text)
        }

        ClipboardRepository.onContentChanged = {
            if (state == State.Clipboard) {
                clipboardView.show(ClipboardRepository.getEntries(context))
            }
        }
    }

    private fun handleClipboardClear() {
        onClipboardClear?.invoke()
        clipboardView.show(ClipboardRepository.getEntries(context))
    }

    private fun handleClipboardDelete(entry: ClipboardRepository.Entry) {
        onClipboardItemDelete?.invoke(entry)
        clipboardView.show(ClipboardRepository.getEntries(context))
    }

    private fun handleCandidateForget(candidate: EngineMessage.Candidate) {
        onCandidateForget?.invoke(candidate)
    }

    fun onSelectionUpdate(start: Int, end: Int) {
        textEditingView.setSelection(start, end)
    }

    private fun showCopyIfRecent(text: String) {
        copyText = text
        val recentTime = ClipboardRepository.lastCopyTimestamp
        if (recentTime <= lastShownCopyTimestamp || System.currentTimeMillis() - recentTime >= 5 * 60 * 1000L) return
        if (text == lastShownCopyText) return
        lastShownCopyTimestamp = recentTime
        lastShownCopyText = text
        when (state) {
            State.Idle -> state = State.Copy
            is State.Copy -> {
                (view.currentRenderer as? IdleRenderer)?.copyText = copyText
                view.invalidate()
            }
            State.Clipboard -> clipboardView.show(ClipboardRepository.getEntries(context))
            else -> {}
        }
    }

    override val view: KawaiiPanelView = KawaiiPanelView(context).also { v ->
        v.onTap = { result ->
            when (result) {
                is TouchResult.ToolbarAction -> {
                    when (result.action) {
                        Action.CursorMove -> state = State.TextEditing
                        Action.Clipboard -> state = State.Clipboard
                        Action.ClearClipboard -> {
                            confirmOverlay.confirm(
                                message = context.getString(R.string.clipboard_clear_confirm_title),
                                onConfirm = { handleClipboardClear() },
                                cardX = Float.NaN,
                                cardY = 0f,
                            )
                        }

                        Action.SwitchKeyboard -> {
                            when (state) {
                                State.TextEditing, State.Clipboard, State.Copy -> state = State.Idle
                                else -> onToolbarAction?.invoke(result.action)
                            }
                        }

                        else -> onToolbarAction?.invoke(result.action)
                    }
                }

                is TouchResult.SelectCandidate -> {
                    onCandidateSelected?.invoke(result.candidate)
                }

                is TouchResult.SelectRerankedCandidate -> onRerankedSelected?.invoke(result.text)
                is TouchResult.ExpandCandidates -> v.setExpanded(true)
                is TouchResult.CollapseCandidates -> v.setExpanded(false)
                null -> {
                    if (state == State.Copy && copyText != null) {
                        onCopyTextCommit?.invoke(copyText ?: "")
                        state = State.Idle
                    }
                }
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

    fun showTextEditing() {
        view.setExpanded(false)
        state = State.TextEditing
    }

    fun hideTextEditing() {
        if (state == State.TextEditing) state = State.Idle
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        confirmOverlay.dismiss()
        view.removeCallbacks(clipboardCheckRunnable)
        state = State.Idle
    }

    fun onStartInputView() {
        if (clipboardCheckRunnable == null) {
            clipboardCheckRunnable = object : Runnable {
                override fun run() {
                    ClipboardRepository.checkCurrentClipboard(context)
                    checkPendingCopy()
                    view.postDelayed(this, 2000L)
                }
            }
        }
        ClipboardRepository.checkCurrentClipboard(context)
        checkPendingCopy()
        view.postDelayed({
            ClipboardRepository.checkCurrentClipboard(context)
            checkPendingCopy()
        }, 500L)
        view.removeCallbacks(clipboardCheckRunnable!!)
        view.postDelayed(clipboardCheckRunnable!!, 2000L)
    }

    private fun checkPendingCopy() {
        if (state != State.Idle) return
        val text = ClipboardRepository.lastCopyText ?: return
        if (text == lastShownCopyText) return
        val time = ClipboardRepository.lastCopyTimestamp
        if (time > lastShownCopyTimestamp && System.currentTimeMillis() - time < 5 * 60 * 1000L) {
            copyText = text
            lastShownCopyTimestamp = time
            lastShownCopyText = text
            state = State.Copy
        }
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
                context.getDrawable(R.drawable.ic_keyboard_trash),
                KeyboardManager.Keyboard.Padding.getHorizontalDp(context).toFloat(),
            ).also {
                it.textEditingMode = (state == State.TextEditing)
                it.clipboardMode = (state == State.Clipboard)
                it.copyText = if (state == State.Copy) copyText else null
            }
        } else {
            if (state == State.Copy) state = State.Idle
            view.scrollX = 0f
            view.currentRenderer = ComposingRenderer(
                list, context.getDrawable(R.drawable.ic_keyboard_expand_more),
                KeyboardManager.Keyboard.Padding.getHorizontalDp(context).toFloat(),
            )
            if (state is State.Composing) {
                candidateGrid.updateCandidates(list)
            }
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
