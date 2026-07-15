package com.ninthsoft.ime.engine

import android.content.Context
import android.inputmethodservice.InputMethodService
import com.ninthsoft.ime.engine.data.CandidatePinYin
import com.ninthsoft.ime.engine.data.EngineMessage
import com.ninthsoft.ime.engine.event.KeyEvent
import kotlinx.coroutines.CoroutineScope

interface IEngine {
    fun initialize(context: Context)
    fun observe(scope: CoroutineScope, on: suspend (EngineMessage) -> Unit)
    fun finalize()
    fun processKey(
        service: InputMethodService, key: KeyEvent
    ): Unit?

    fun selectCandidate(index: Int)
    fun schemeList(on: (List<EngineMessage.Schema>) -> Unit)
    fun clear(service: InputMethodService)
    fun resetComposition()
}
