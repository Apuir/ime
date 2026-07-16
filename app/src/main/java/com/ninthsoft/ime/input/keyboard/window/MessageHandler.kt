package com.ninthsoft.ime.input.keyboard.window

import android.inputmethodservice.InputMethodService
import com.ninthsoft.ime.engine.data.EngineMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber

class MessageHandler(
    private val service: InputMethodService,
) {
    private var window: KeyboardWindow? = null

    fun attach(window: KeyboardWindow) {
        this.window = window
    }

    suspend fun handle(message: EngineMessage) {
        Timber.d("KeyboardMessageHandler.handle $message")
        when (message) {
            is EngineMessage.Commit -> {
                withContext(Dispatchers.Main) {
                    service.currentInputConnection?.commitText(message.text, 1)
                }
            }

            is EngineMessage.Composition -> {
                withContext(Dispatchers.Main) {
                    window?.updatePreedit(message.preedit)
                }
            }

            is EngineMessage.CompositionEnd -> {
                withContext(Dispatchers.Main) {
                    service.currentInputConnection?.finishComposingText()
                    window?.updatePreedit(null)
                }
            }

            is EngineMessage.Candidates -> {
                withContext(Dispatchers.Main) {
                    window?.setCandidates(message.list)
                }
            }

            is EngineMessage.RerankStarted -> {
                withContext(Dispatchers.Main) {
                    window?.panel?.showRerankAnimation()
                }
            }

            is EngineMessage.RerankedCandidate -> {
                withContext(Dispatchers.Main) {
                    window?.setRerankedCandidate(message.best)
                }
            }

            is EngineMessage.PossibleCandidatePinYin -> {
                withContext(Dispatchers.Main) {
                    window?.onPossibleCandidatePinYin(message.possibleCandidatePinYins)
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
                withContext(Dispatchers.Main) {
                    window?.onSchemaChanged(message.id, message.name, message.layout)
                }
            }

            is EngineMessage.Unknown -> {
                Timber.d("Unknown ${message.toString()}")
            }
        }
    }
}
