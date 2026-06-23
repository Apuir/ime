package com.ninthsoft.ime.input.keyboard.key

import com.ninthsoft.ime.engine.data.KeyEvent

sealed class KeyAction {

    data class PressKeyAction(
        val code: Int, val modifiers: Int
    ) : KeyAction()

    data class CommitAction(val text: String) : KeyAction()

    data class CapsAction(val lock: Boolean) : KeyAction()

    data class LayoutSwitchAction(val target: String) : KeyAction()

    data object BackspaceAction : KeyAction()

    data object ReturnAction : KeyAction()

    data object SpaceAction : KeyAction()

    data object LangSwitchAction : KeyAction()

    data object ShowInputMethodPickerAction : KeyAction()
}

fun KeyAction.PressKeyAction.asKeyEvent(isVirtual: Boolean): KeyEvent {
    return KeyEvent(this.code, this.modifiers, isVirtual = isVirtual)
}