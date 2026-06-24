package com.ninthsoft.ime.input

import android.inputmethodservice.InputMethodService
import com.ninthsoft.ime.engine.data.EngineMessage
import com.ninthsoft.ime.input.keyboard.KeyboardWindow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber

class MessageHandler(
    private val service: InputMethodService,
) {
    private var keyboardWindow: KeyboardWindow? = null

    fun setKeyboardWindow(window: KeyboardWindow?) {
        keyboardWindow = window
    }

    suspend fun handle(message: EngineMessage) {
        Timber.d("MessageHandler.handle $message ${keyboardWindow.hashCode()}")
        when (message) {
            is EngineMessage.Commit -> {
                withContext(Dispatchers.Main) {
                    service.currentInputConnection?.commitText(message.text, 1)
                }
            }

            is EngineMessage.Composition -> {
                withContext(Dispatchers.Main) {
                    keyboardWindow?.updatePreedit(message.preedit)
                }
            }

            is EngineMessage.CompositionEnd -> {
                withContext(Dispatchers.Main) {
                    service.currentInputConnection?.finishComposingText()
                    keyboardWindow?.updatePreedit(null)
                }
            }

            is EngineMessage.Candidates -> {
                withContext(Dispatchers.Main) {
                    keyboardWindow?.setCandidates(message.list)
                }
            }

            is EngineMessage.RerankStarted -> {
                withContext(Dispatchers.Main) {
                    keyboardWindow?.panel?.showRerankAnimation()
                }
            }

            is EngineMessage.RerankedCandidate -> {
                withContext(Dispatchers.Main) {
                    keyboardWindow?.setRerankedCandidate(message.best)
                }
            }

            is EngineMessage.Status -> {
                Timber.d("status: schema=${message.schemaName}")
            }

            is EngineMessage.Key -> {
                Timber.d("key: key=${message.key}")
            }

            is EngineMessage.InlinePreedit -> {
                Timber.d("InlinePreedit: InlinePreedit=${message.preedit}")
            }

            is EngineMessage.CandidateMenu -> {
                Timber.d("CandidateMenu: CandidateMenu=${message}")
            }

            is EngineMessage.Schema -> {
                Timber.d("Schema: Schema=${message}")
            }

            is EngineMessage.Unknown -> {
                Timber.d("Unknown ${message.toString()}")
            }
        }
    }
}
