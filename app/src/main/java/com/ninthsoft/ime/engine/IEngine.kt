package com.ninthsoft.ime.engine

import android.content.Context
import android.inputmethodservice.InputMethodService
import com.ninthsoft.ime.engine.data.EngineMessage
import com.ninthsoft.ime.engine.data.KeyEvent
import kotlinx.coroutines.CoroutineScope

interface IEngine {
    fun onCreate(context: Context)

    fun onDestroy()

    fun processKey(service: InputMethodService, key: KeyEvent): Unit?

    fun selectCandidate(index: Int)

    fun observeMessage(
        scope: CoroutineScope,
        on: suspend (EngineMessage) -> Unit,
    )

    fun reset()
}
