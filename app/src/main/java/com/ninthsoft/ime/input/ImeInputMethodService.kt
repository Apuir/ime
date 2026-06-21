package com.ninthsoft.ime.input

import android.inputmethodservice.InputMethodService
import android.view.View
import android.view.inputmethod.EditorInfo
import com.ninthsoft.ime.engine.EngineFactory
import com.ninthsoft.ime.engine.IEngine
import com.ninthsoft.ime.input.keyboard.KeyboardWindow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

class ImeInputMethodService : InputMethodService() {
    private val engine: IEngine? = EngineFactory.current()
    private var keyboardWindow: KeyboardWindow? = null
    private var scope: CoroutineScope? = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var messageHandler: MessageHandler
    private lateinit var keyActionListener: KeyActionListener

    override fun onCreate() {
        super.onCreate()
        messageHandler = MessageHandler(this)
        keyActionListener = KeyActionListener(
            service = this,
            engine = engine,
            onSwitchLayout = { target -> keyboardWindow?.switchLayout(target) },
            getCurrentKeyboard = { keyboardWindow?.getCurrentKeyboard() },
        )
        engine?.observeMessage(scope!!) { messageHandler.handle(it) }
    }

    override fun onCreateInputView(): View {
        keyboardWindow = KeyboardWindow(
            context = this,
            onCandidateSelected = { candidate ->
                engine?.selectCandidate(candidate.index)
            },
        ).apply { setKeyActionListener(keyActionListener) }
        messageHandler.setKeyboardWindow(keyboardWindow)
        return keyboardWindow!!.view
    }

    override fun onStartInputView(info: EditorInfo, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        keyboardWindow?.view?.onStartInput(info)
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        engine?.reset()
        super.onFinishInputView(finishingInput)
    }

    override fun onDestroy() {
        scope?.cancel()
        scope = null
        keyboardWindow = null
        super.onDestroy()
    }

    override fun onEvaluateFullscreenMode(): Boolean = false

    override fun onEvaluateInputViewShown(): Boolean {
        super.onEvaluateInputViewShown()
        return true
    }
}
