package com.ninthsoft.ime.input

import android.annotation.SuppressLint
import android.inputmethodservice.InputMethodService
import android.view.View
import android.view.inputmethod.EditorInfo
import com.ninthsoft.ime.engine.EngineFactory
import com.ninthsoft.ime.engine.IEngine
import com.ninthsoft.ime.engine.RimeEngine
import com.ninthsoft.ime.engine.data.EngineMessage
import com.ninthsoft.ime.input.keyboard.KeyboardWindow
import com.ninthsoft.ime.input.keyboard.NormalKeyboard
import com.ninthsoft.ime.input.keyboard.key.KeyAction
import com.ninthsoft.ime.input.keyboard.key.KeyActionListener
import com.ninthsoft.ime.input.keyboard.key.asKeyEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import timber.log.Timber

class ImeInputMethodService : InputMethodService() {
    private var engine: IEngine? = EngineFactory.getOrCreate(this, RimeEngine::class)
    private var keyboardWindow: KeyboardWindow? = null

    private val messageScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val keyActionListener = KeyActionListener { action ->
        when (action) {
            is KeyAction.NormalKeyAction -> {
                engine?.processKey(action.asKeyEvent(isVirtual = false))
            }

            is KeyAction.CommitAction -> {
                currentInputConnection?.commitText(action.text, 1)
            }

            is KeyAction.CapsAction -> {
                val kb = keyboardWindow?.getCurrentKeyboard()
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
                keyboardWindow?.switchLayout(action.target)
            }

            is KeyAction.BackspaceAction -> {
                val ic = currentInputConnection
                if (ic != null) {
                    val before = ic.getTextBeforeCursor(1, 0)
                    if (!before.isNullOrEmpty()) {
                        ic.deleteSurroundingText(1, 0)
                    } else {
                        ic.deleteSurroundingText(0, 1)
                    }
                }
            }

            is KeyAction.ReturnAction -> {
                runCatching { currentInputConnection?.commitText("\n", 1) }
            }

            is KeyAction.SpaceAction -> {
            }

            is KeyAction.LangSwitchAction -> {
                @SuppressLint("NewApi") switchToNextInputMethod(false)
            }

            is KeyAction.ShowInputMethodPickerAction -> {
                @SuppressLint("NewApi") requestShowSelf(0)
            }

            else -> {
                Timber.d("Unhandled action: $action")
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
    }

    override fun onCreateInputView(): View {
        keyboardWindow = KeyboardWindow(this).apply {
            setKeyActionListener(keyActionListener)
        }
        startObservingEngineMessage()
        return keyboardWindow!!.view
    }

    private fun startObservingEngineMessage() {
        engine?.observeMessage(messageScope) { message ->
            Timber.d("startObservingEngineMessages $message")
            when (message) {
                is EngineMessage.Commit -> {
                    currentInputConnection?.commitText(message.text, 1)
                }

                is EngineMessage.Composition -> {
                    currentInputConnection?.setComposingText(message.preedit, 1)
                }

                is EngineMessage.CompositionEnd -> {
                    currentInputConnection?.finishComposingText()
                }

                is EngineMessage.Candidates -> {
                    Timber.d("candidates: ${message.list.joinToString { it.text }}")
                }

                is EngineMessage.Status -> {
                    Timber.d("status: schema=${message.schemaName}")
                }
                is EngineMessage.Unknown->{
                    Timber.d("Unknown")
                }
            }
        }
    }

    override fun onStartInputView(info: EditorInfo, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        keyboardWindow?.view?.onStartInput(info)
    }

    override fun onDestroy() {
        messageScope.cancel()
        engine?.finalize()
        engine = null
        super.onDestroy()
    }

    override fun onEvaluateFullscreenMode(): Boolean = false

    override fun onEvaluateInputViewShown(): Boolean {
        super.onEvaluateInputViewShown()
        return true
    }
}
