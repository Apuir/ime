package com.ninthsoft.ime.input.keyboard

import android.content.Context
import com.ninthsoft.ime.data.keyboard.theme.KeyboardColors
import com.ninthsoft.ime.engine.data.EngineMessage
import com.ninthsoft.ime.input.keyboard.key.KeyActionListener
import com.ninthsoft.ime.input.panel.IPanel

class KeyboardWindow(
    context: Context,
    onCandidateSelected: ((EngineMessage.Candidate) -> Unit)? = null,
    onRerankedSelected: ((String) -> Unit)? = null,
) {
    val view: KeyboardWindowView = KeyboardWindowView(
        context = context,
        onCandidateSelected = onCandidateSelected,
        onRerankedSelected = onRerankedSelected,
    )

    val colors: KeyboardColors.ColorScheme get() = KeyboardColors.resolve(view.context)

    val panel: IPanel get() = view.panel

    fun setKeyActionListener(listener: KeyActionListener) {
        view.keyActionListener = listener
    }

    fun switchLayout(name: String) { view.switchKeyboard(name) }
    fun refreshTheme() { view.refreshColors() }
    fun refreshLayout() { view.refreshLayout() }
    fun isNormalKeyboard(): Boolean = view.isNormalKeyboard()
    fun getCurrentKeyboard(): BaseKeyboard? = view.getCurrentKeyboard()
    fun setCandidates(list: List<EngineMessage.Candidate>) { view.setCandidates(list) }
    fun setRerankedCandidate(candidate: EngineMessage.Candidate) { view.setRerankedCandidate(candidate) }
    fun updatePreedit(text: String?) { view.updatePreedit(text) }
    fun updateSidePanel(items: List<com.ninthsoft.ime.input.keyboard.key.KeyDef>) { view.updateSidePanel(items) }
    fun setSidePanelItemListener(listener: (com.ninthsoft.ime.input.keyboard.key.KeyAction) -> Unit) { view.setSidePanelItemListener(listener) }
}
