package com.ninthsoft.ime.input.panel

import android.annotation.SuppressLint
import android.content.Context
import com.ninthsoft.ime.R
import com.ninthsoft.ime.base.feedback.InputFeedbacks
import com.ninthsoft.ime.data.keyboard.theme.KeyboardColors
import com.ninthsoft.ime.data.manager.ClipboardManager
import com.ninthsoft.ime.data.manager.KeyboardManager
import com.ninthsoft.ime.data.manager.CandidateManager
import com.ninthsoft.ime.engine.data.CandidatePinYin
import com.ninthsoft.ime.engine.data.EngineMessage
import com.ninthsoft.ime.input.panel.component.CandidateGridView
import com.ninthsoft.ime.input.panel.component.ClipboardView
import com.ninthsoft.ime.input.panel.component.ConfirmOverlay
import com.ninthsoft.ime.input.panel.component.MenuGridView
import com.ninthsoft.ime.input.panel.component.TextEditView
import com.ninthsoft.ime.input.keyboard.key.KeyboardAction
import com.ninthsoft.ime.input.panel.toolbar.IdleRenderer
import timber.log.Timber

class KawaiiPanel(
    val context: Context,
    var listener: Listener? = null,
) : IPanel {

    interface Listener {
        fun onCandidateSelected(candidate: EngineMessage.Candidate) {}

        fun onToolbarAction(action: Action) {}

        fun onSidePanelAction(action: KeyboardAction) {}

        fun onTextEditingAction(action: TextEditView.Action) {}

        fun onClipboardItemClick(entry: ClipboardManager.Entry) {}

        fun onClipboardClear() {}

        fun onClipboardItemDelete(entry: ClipboardManager.Entry) {}

        fun onCopyTextCommit(text: String) {}

        fun onCandidateGridDragComplete(candidates: List<EngineMessage.Candidate>) {}

        fun onCandidateForget(candidate: EngineMessage.Candidate) {}
    }

    sealed class Action {
        data object SwitchKeyboard : Action()
        data object EmojiKeyboard : Action()
        data object Clipboard : Action()
        data object ToggleVoice : Action()
        data object Settings : Action()
        data object SchemaSettings : Action()
        data object About : Action()
        data object ReloadEngine : Action()
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
        data object ExpandCandidates : TouchResult()
        data object CollapseCandidates : TouchResult()
        data object LongPressExpand : TouchResult()
    }

    sealed class State {
        data object Idle : State()
        data class Composing(val candidates: List<EngineMessage.Candidate>) : State()
        data class Prediction(val candidates: List<EngineMessage.Candidate>) : State()
        data object Menu : State()
        data object TextEditing : State()
        data object Clipboard : State()
        data object Copy : State()
    }

    private var state: State = State.Idle
        set(value) {
            if (field == value) return
            if (field is State.Composing && value is State.Composing && view.isExpanded) {
                candidateGrid.updateCandidates((value as State.Composing).candidates)
                field = value
                return
            }
            if (field is State.Prediction && value is State.Prediction && view.isExpanded) {
                candidateGrid.updateCandidates((value as State.Prediction).candidates)
                field = value
                return
            }
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
            is State.Composing, is State.Prediction -> candidateGrid.hide()
            State.Menu -> {
                menuGrid.hide()
                (view.currentRenderer as? IdleRenderer)?.showArrow = false
                view.invalidate()
            }

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
            is State.Composing -> {
                if (view.isExpanded) candidateGrid.show(state.candidates)
            }

            is State.Prediction -> {
                if (view.isExpanded) view.setExpanded(false)
            }

            State.Menu -> {
                menuGrid.show()
                (view.currentRenderer as? IdleRenderer)?.showArrow = true
                view.invalidate()
            }

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
                clipboardView.show(ClipboardManager.getEntries(context))
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
        onSidePanelAction = { listener?.onSidePanelAction(it) },
    ).apply {
        /*onWordForget = { candidate, x, y ->
            confirmOverlay.confirm(
                message = context.getString(
                    R.string.candidate_forget_confirm,
                    if (candidate.text.length > 5) candidate.text.take(5) + "..." else candidate.text
                ),
                onConfirm = { handleCandidateForget(candidate) },
                cardX = x,
                cardY = y,
            )
        }*/
        onDragComplete = { candidates ->
            (view.currentRenderer as? ComposingRenderer)?.candidates = candidates
            this@KawaiiPanel.listener?.onCandidateGridDragComplete(candidates)
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
        onAction = { action -> listener?.onTextEditingAction(action) }
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
        clipboardView.onItemClick = { entry -> listener?.onClipboardItemClick(entry) }
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

        ClipboardManager.onNewEntry = { entry ->
            showCopyIfRecent(entry.text)
        }

        ClipboardManager.onContentChanged = {
            if (state == State.Clipboard) {
                clipboardView.show(ClipboardManager.getEntries(context))
            }
        }
    }

    private fun handleClipboardClear() {
        listener?.onClipboardClear()
        clipboardView.show(ClipboardManager.getEntries(context))
    }

    private fun handleClipboardDelete(entry: ClipboardManager.Entry) {
        listener?.onClipboardItemDelete(entry)
        clipboardView.show(ClipboardManager.getEntries(context))
    }

    private fun handleCandidateForget(candidate: EngineMessage.Candidate) {
        listener?.onCandidateForget(candidate)
    }

    fun onSelectionUpdate(start: Int, end: Int) {
        textEditingView.setSelection(start, end)
    }

    fun onInputChanged(text: String) {
        textEditingView.onInputChanged(text)
    }

    private fun showCopyIfRecent(text: String) {
        copyText = text
        val recentTime = ClipboardManager.lastCopyTimestamp
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

            State.Clipboard -> clipboardView.show(ClipboardManager.getEntries(context))
            else -> {}
        }
    }

    override val view: KawaiiPanelView = KawaiiPanelView(context).also { v ->
        v.onTap = { result ->
            when (result) {
                is TouchResult.ToolbarAction -> {
                    InputFeedbacks.hapticFeedback(view)
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
                                else -> listener?.onToolbarAction(result.action)
                            }
                        }

                        else -> listener?.onToolbarAction(result.action)
                    }
                }

                is TouchResult.SelectCandidate -> {
                    InputFeedbacks.hapticFeedback(view)
                    InputFeedbacks.soundEffect(context, InputFeedbacks.SoundEffect.Standard)
                    listener?.onCandidateSelected(result.candidate)
                }

                is TouchResult.ExpandCandidates -> v.setExpanded(true)
                is TouchResult.CollapseCandidates -> v.setExpanded(false)
                is TouchResult.LongPressExpand -> {
                    if (state is State.Prediction) {
                        val candidates = (state as State.Prediction).candidates
                        var predictions = true
                        candidates.forEach {
                            if (it.type != EngineMessage.Candidate.CandidateType.Prediction) {
                                predictions = false
                                return@forEach
                            }
                        }
                        if (predictions) setCandidates(emptyList()) else v.setExpanded(true)
                    }
                }

                null -> {
                    if (state == State.Copy && copyText != null) {
                        listener?.onCopyTextCommit(copyText ?: "")
                        state = State.Idle
                    }
                }
            }
        }

        v.onExpandChanged = { expanded, candidates ->
            if (expanded) {
                candidateGrid.show(candidates)
            } else {
                candidateGrid.hide()
            }
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
                    ClipboardManager.checkCurrentClipboard(context)
                    checkPendingCopy()
                    view.postDelayed(this, 2000L)
                }
            }
        }
        ClipboardManager.checkCurrentClipboard(context)
        checkPendingCopy()
        view.postDelayed({
            ClipboardManager.checkCurrentClipboard(context)
            checkPendingCopy()
        }, 500L)
        view.removeCallbacks(clipboardCheckRunnable!!)
        view.postDelayed(clipboardCheckRunnable!!, 2000L)
    }

    private fun checkPendingCopy() {
        if (state != State.Idle) return
        val text = ClipboardManager.lastCopyText ?: return
        if (text == lastShownCopyText) return
        val time = ClipboardManager.lastCopyTimestamp
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
            state = State.Idle
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
                showIndex = CandidateManager.isShowIndex(context),
                showComment = CandidateManager.isShowComment(context),
                borderless = CandidateManager.isBorderless(context),
                expandBorderless = KeyboardManager.Keyboard.ExpandBorderless.isEnabled(context),
            )
            var predictions = true
            list.forEach {
                if (it.type != EngineMessage.Candidate.CandidateType.Prediction) {
                    predictions = false
                    return@forEach
                }
            }
            state = if (predictions) State.Prediction(list) else State.Composing(list)

            if (view.isExpanded) {
                candidateGrid.updateCandidates(list)
            }
        }
        view.invalidate()
    }

    override fun onPossibleCandidatePinYin(pinyins: List<CandidatePinYin>) {
        candidateGrid.onPossibleCandidatePinYin(pinyins)
    }

    override fun refreshTheme() {
        view.refreshTheme()
        candidateGrid.refreshTheme(context)
        menuGrid.refreshTheme(KeyboardColors.resolve(context))
        confirmOverlay.refreshTheme(KeyboardColors.resolve(context))
    }
}
