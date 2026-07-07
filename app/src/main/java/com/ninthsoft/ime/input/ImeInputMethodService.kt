package com.ninthsoft.ime.input

import android.inputmethodservice.InputMethodService
import android.view.View
import android.view.inputmethod.EditorInfo
import com.ninthsoft.ime.engine.EngineFactory
import com.ninthsoft.ime.engine.IEngine
import com.ninthsoft.ime.input.keyboard.window.KeyboardWindow
import com.ninthsoft.ime.input.panel.KawaiiPanel.Action.CloseKeyboard
import com.ninthsoft.ime.input.panel.KawaiiPanel.Action.SwitchKeyboard
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

class ImeInputMethodService : InputMethodService() {
    private val engine: IEngine? = EngineFactory.current()
    private var keyboardWindow: KeyboardWindow? = null

    private lateinit var keyActionListener: KeyActionListener

    var scope: CoroutineScope? = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        keyActionListener = KeyActionListener(
            service = this,
            engine = engine,
            onSwitchLayout = { target -> keyboardWindow?.switchLayout(target) },
        )
        engine?.observe(scope!!) { keyboardWindow?.handleEngineMessage(it) }
    }

    override fun onCreateInputView(): View {
        keyboardWindow = KeyboardWindow(
            service = this,
            onCandidateSelected = { candidate ->
                engine?.selectCandidate(candidate.index)
            },
            onRerankedSelected = { text ->
                currentInputConnection?.commitText(text, 1)
            },
            onToolbarAction = { action ->
                when (action) {
                    CloseKeyboard -> requestHideSelf(0)
                    SwitchKeyboard -> keyboardWindow?.view?.toggleMenu()
                    else -> {}
                }
            },
        ).apply { setKeyActionListener(keyActionListener) }
        return keyboardWindow!!.view
    }

    override fun onStartInputView(info: EditorInfo, restarting: Boolean) {
        keyboardWindow?.onStartInputView(info, restarting)
        super.onStartInputView(info, restarting)
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        engine?.resetComposition()
        keyboardWindow?.onFinishInputView(finishingInput)
        super.onFinishInputView(finishingInput)
    }

    override fun onWindowHidden() {
        keyboardWindow?.onDetach()
        super.onWindowHidden()
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
