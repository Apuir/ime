package com.ninthsoft.ime.input.keyboard.key

import com.ninthsoft.ime.engine.event.KeyEvent
import com.ninthsoft.ime.engine.event.KeyModifiers

sealed class KeyboardAction {

    data class KeyCodeAction(
        val keyCode: Int,
        val modifiers: KeyModifiers = KeyModifiers.Empty,
        val isVirtual: Boolean = true
    ) : KeyboardAction() {
        fun asKeyEvent(): KeyEvent {
            return KeyEvent.CodeEvent(keyCode, modifiers, isVirtual = isVirtual)
        }
    }

    data class KeySequenceAction(val sequence: String) : KeyboardAction() {
        fun asKeyEvent(): KeyEvent {
            return KeyEvent.SequenceEvent(sequence)
        }
    }

    data object ClearAction : KeyboardAction()

    data class CommitAction(val text: String) : KeyboardAction()

    data object CapsAction : KeyboardAction()

    data class LayoutSwitchAction(val target: String) : KeyboardAction()

    data object BackspaceAction : KeyboardAction()

    data object ReturnAction : KeyboardAction()

    data object SpaceAction : KeyboardAction()

    data object LangSwitchAction : KeyboardAction()

    data object ShowInputMethodPickerAction : KeyboardAction()
}