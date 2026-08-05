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
        when (message) {
            is EngineMessage.Commit -> {
                withContext(Dispatchers.Main) {
                    service.currentInputConnection?.commitText(message.text, 1)
                }
            }

            is EngineMessage.Candidates -> {
                withContext(Dispatchers.Main) {
                    window?.setCandidates(message.list)
                }
            }

            is EngineMessage.PossibleCandidatePinYin -> {
                withContext(Dispatchers.Main) {
                    window?.onPossibleCandidatePinYin(message.possibleCandidatePinYins)
                }
            }

            is EngineMessage.DynamicPreedit -> {
                Timber.d("ssss %s", message.preedits)
                withContext(Dispatchers.Main) {
                    window?.updateDynamicPreedit(message.preedits)
                }
            }
            else -> {}
        }
    }
}
