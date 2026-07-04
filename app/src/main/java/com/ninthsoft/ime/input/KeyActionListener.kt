package com.ninthsoft.ime.input

import android.annotation.SuppressLint
import android.inputmethodservice.InputMethodService
import com.ninthsoft.ime.engine.IEngine
import com.ninthsoft.ime.engine.data.KeyEvent
import com.ninthsoft.ime.input.keyboard.NormalKeyboard
import com.ninthsoft.ime.input.keyboard.key.KeyAction
import com.ninthsoft.ime.input.keyboard.key.KeyActionListener
import com.ninthsoft.ime.input.keyboard.key.asKeyEvent

class KeyActionListener(
    private val service: InputMethodService,
    private val engine: IEngine?,
    private val onSwitchLayout: (String) -> Unit,
    private val getCurrentKeyboard: () -> com.ninthsoft.ime.input.keyboard.BaseKeyboard?,
) : KeyActionListener {

    override fun onKeyAction(action: KeyAction) {
        when (action) {
            is KeyAction.PressKeyAction -> {
                engine?.postProcessKey(service, action.asKeyEvent(isVirtual = false))
            }

            is KeyAction.ClearAction -> {
                engine?.postClear(service)
            }

            is KeyAction.CommitAction -> {
                service.currentInputConnection?.commitText(action.text, 1)
            }

            is KeyAction.CapsAction -> {
                val kb = getCurrentKeyboard()
                if (kb is NormalKeyboard) {
                    when {
                        action.lock -> kb.syncCapsState(
                            when (kb.getCapsState()) {
                                NormalKeyboard.CapsState.Lock -> NormalKeyboard.CapsState.None
                                else -> NormalKeyboard.CapsState.Lock
                            }
                        )

                        else -> kb.syncCapsState(
                            when (kb.getCapsState()) {
                                NormalKeyboard.CapsState.None -> NormalKeyboard.CapsState.Once
                                else -> NormalKeyboard.CapsState.None
                            }
                        )
                    }
                }
            }

            is KeyAction.LayoutSwitchAction -> {
                onSwitchLayout(action.target)
            }

            is KeyAction.BackspaceAction, KeyAction.ReturnAction, KeyAction.SpaceAction -> {
                val character = when (action) {
                    KeyAction.BackspaceAction -> "DEL"
                    KeyAction.ReturnAction -> "ENTER"
                    KeyAction.SpaceAction -> "SPACE"
                }
                engine?.postProcessKey(service, KeyEvent(KeyEvent.code(character), 0, true))
            }

            is KeyAction.LangSwitchAction -> {
                @SuppressLint("NewApi") service.switchToNextInputMethod(false)
            }

            is KeyAction.ShowInputMethodPickerAction -> {
                @SuppressLint("NewApi") service.requestShowSelf(0)
            }
        }
    }
}
