package com.ninthsoft.ime.input

import android.annotation.SuppressLint
import android.inputmethodservice.InputMethodService
import android.view.inputmethod.EditorInfo
import com.ninthsoft.ime.engine.EngineFactory
import com.ninthsoft.ime.engine.IEngine
import com.ninthsoft.ime.engine.event.KeyEvent
import com.ninthsoft.ime.engine.event.KeyModifiers
import com.ninthsoft.ime.input.keyboard.key.KeyboardAction
import com.ninthsoft.ime.input.keyboard.key.KeyActionListener
import com.ninthsoft.ime.input.ImeInputMethodService
import com.ninthsoft.ime.input.keyboard.window.KeyboardStateManager

class KeyActionListener(
    private val service: InputMethodService,
) : KeyActionListener {

    private val engine: IEngine? get() = EngineFactory.current()

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
                engine?.commit(action.text)
            }

            is KeyboardAction.CommitPairAction -> {
                // 提交「前半+后半」，并让光标停在后半之前
                engine?.commit(action.open + action.close, action.close.length)
            }

            is KeyboardAction.BackspaceAction, KeyboardAction.SpaceAction -> {
                val character = if (action is KeyboardAction.BackspaceAction) "DEL" else "SPACE"
                engine?.processKey(
                    service, KeyEvent.CodeEvent(
                        KeyEvent.CodeEvent.keyCode(character), KeyModifiers.Empty
                    )
                )
            }

            is KeyboardAction.ReturnAction -> handleReturn(action.force)

            is KeyboardAction.LangSwitchAction -> {
                @SuppressLint("NewApi") service.switchToNextInputMethod(false)
            }

            is KeyboardAction.SelectSchema -> {
                // 切换方案会重置引擎组合；按设置决定已上屏的预览内容留还是丢。
                (service as? ImeInputMethodService)?.livePreview?.finalizeForKeyboardSwitch()
                engine?.selectSchema(action.schemaId)
            }

            is KeyboardAction.ShowInputMethodPickerAction -> {
                @SuppressLint("NewApi") service.requestShowSelf(0)
            }

            is KeyboardAction.SelectCandidatePinYin -> {
                engine?.selectCandidatePinYin(action.pinYin)
            }

            is KeyboardAction.MultiReturnAction -> {
                val actionCode = when (action.text) {
                    "GO" -> EditorInfo.IME_ACTION_GO
                    "SEND" -> EditorInfo.IME_ACTION_SEND
                    "SEARCH" -> EditorInfo.IME_ACTION_SEARCH
                    "NEXT" -> EditorInfo.IME_ACTION_NEXT
                    "PREVIOUS" -> EditorInfo.IME_ACTION_PREVIOUS
                    "DONE" -> EditorInfo.IME_ACTION_DONE
                    else -> EditorInfo.IME_ACTION_NONE
                }
                (service as? ImeInputMethodService)?.submitEditorAction(actionCode)
            }


            else -> {}
        }
    }

    /**
     * 回车键。
     *
     * 分两种情况：
     *  - 正在打拼音（有组合）：交给 Rime，由方案自己决定（通常是提交原文 / 上屏候选）；
     *  - 没有组合：按「输入框语义」处理 —— 输入框声明了 editor action（搜索 / 发送 / 前往 /
     *    完成）就执行它，否则发一个真正的回车按键事件，让应用自己决定换行还是提交。
     *
     * 这里曾经在没有组合时直接 `commitText("\n")`。而单行输入框（搜索框、聊天输入框）会把
     * 换行显示成空格，于是「按回车不搜索也不换行，只多了一个空格」；而且空输入框时永远走不到
     * `performEditorAction`，搜索框里没打字时按回车根本不会触发搜索。
     */
    private fun handleReturn(force: Boolean) {
        if (!force && KeyboardStateManager.isComposingNow) {
            engine?.processKey(
                service,
                KeyEvent.CodeEvent(KeyEvent.CodeEvent.keyCode("ENTER"), KeyModifiers.Empty),
            )
            return
        }
        (service as? ImeInputMethodService)?.submitEditorActionOrEnter()
    }
}
