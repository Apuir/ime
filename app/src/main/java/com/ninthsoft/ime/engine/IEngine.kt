package com.ninthsoft.ime.engine

import android.content.Context
import android.inputmethodservice.InputMethodService
import com.ninthsoft.ime.engine.data.EngineMessage
import com.ninthsoft.ime.engine.data.KeyEvent
import kotlinx.coroutines.CoroutineScope

interface IEngine {
    fun initialize(context: Context)
    fun observe(scope: CoroutineScope, on: suspend (EngineMessage) -> Unit)
    fun resetState()
    fun postProcessKey(
        service: InputMethodService, key: KeyEvent, on: (Boolean) -> Unit = {}
    ): Unit?

    fun postSelectCandidate(index: Int, on: (Boolean) -> Unit = {})
    fun postSchemeList(on: (List<EngineMessage.Schema>) -> Unit)
    fun postClear(service: InputMethodService, on: (Boolean) -> Unit = {})
    fun finalize()
}
