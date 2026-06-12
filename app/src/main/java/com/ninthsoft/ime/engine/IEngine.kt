package com.ninthsoft.ime.engine

import android.content.Context
import com.ninthsoft.ime.engine.data.EngineMessage
import com.ninthsoft.ime.engine.data.KeyEvent
import kotlinx.coroutines.CoroutineScope

interface IEngine {
    fun initialize(context: Context)

    fun finalize()

    fun processKey(key: KeyEvent): Unit?

    fun observeMessage(scope: CoroutineScope, on: suspend (EngineMessage) -> Unit)
}
