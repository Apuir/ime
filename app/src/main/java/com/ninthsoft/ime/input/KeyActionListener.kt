package com.ninthsoft.ime.input

import android.annotation.SuppressLint
import android.inputmethodservice.InputMethodService
import com.ninthsoft.ime.engine.IEngine
import com.ninthsoft.ime.engine.event.KeyEvent
import com.ninthsoft.ime.engine.event.KeyModifiers
import com.ninthsoft.ime.input.keyboard.key.KeyboardAction
import com.ninthsoft.ime.input.keyboard.key.KeyActionListener

class KeyActionListener(
    private val service: InputMethodService,
    private val engine: IEngine?,
) : KeyActionListener {

    override fun onKeyAction(action: KeyboardAction) {
        when (action) {
            is KeyboardAction.KeySequenceAction -> {
                engine?.processKey(service, action.asKeyEvent())
            }

            is KeyboardAction.KeyCodeAction -> {
                engine?.processKey(service, action.asKeyEvent())
            }

            is KeyboardAction.ClearAction -> {
                engine?.clear(service)
            }

            is KeyboardAction.CommitAction -> {
                service.currentInputConnection?.commitText(action.text, 1)
            }

            is KeyboardAction.BackspaceAction, KeyboardAction.ReturnAction, KeyboardAction.SpaceAction -> {
                val character = when (action) {
                    KeyboardAction.BackspaceAction -> "DEL"
                    KeyboardAction.ReturnAction -> "ENTER"
                    KeyboardAction.SpaceAction -> "SPACE"
                }
                engine?.processKey(
                    service, KeyEvent.CodeEvent(
                        KeyEvent.CodeEvent.keyCode(character), KeyModifiers.Empty
                    )
                )
            }

            is KeyboardAction.LangSwitchAction -> {
                @SuppressLint("NewApi") service.switchToNextInputMethod(false)
            }

            is KeyboardAction.SelectSchema -> {
                engine?.selectSchema(action.schemaId)
            }

            is KeyboardAction.ShowInputMethodPickerAction -> {
                @SuppressLint("NewApi") service.requestShowSelf(0)
            }

            else -> {}
        }
    }
}
