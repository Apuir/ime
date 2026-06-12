package com.ninthsoft.ime.engine

import android.content.Context
import com.ninthsoft.ime.engine.data.EngineMessage
import com.ninthsoft.ime.engine.data.KeyEvent
import com.ninthsoft.ime.engine.rime.core.RimeKeyMapping
import com.ninthsoft.ime.engine.rime.core.RimeMessage
import com.ninthsoft.ime.engine.rime.daemon.RimeDaemon
import com.ninthsoft.ime.engine.rime.daemon.RimeSession
import com.ninthsoft.ime.engine.rime.daemon.launchOnReady
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import timber.log.Timber

class RimeEngine : IEngine {
    private val daemon by lazy { RimeDaemon }

    private var session: RimeSession? = null

    private val sessionName: String = this.javaClass.name

    override fun initialize(context: Context) {
        session = daemon.createSession(sessionName)
    }

    override fun finalize() {
        daemon.destroySession(sessionName)
        session = null
    }

    override fun processKey(key: KeyEvent): Unit? {
        val rimeKey = RimeKeyMapping.keyCodeToVal(key.code)
        return session?.launchOnReady {
            it.processKey(rimeKey, key.modifiers.toUInt(), key.isVirtual)
        }
    }

    override fun observeMessage(scope: CoroutineScope, on: suspend (EngineMessage) -> Unit) {
        scope.launch {
            daemon.observeMessages { msg ->
                Timber.d("ssssss ${msg.messageType}  ${msg.data.toString()}")
            }
        }
    }

    private fun RimeMessage<*>.EngineMessage(): EngineMessage = when (this) {
        is RimeMessage.CommitTextMessage -> {
            EngineMessage.Commit(data.text.orEmpty())
        }

        is RimeMessage.CompositionMessage -> {
            val preedit = data.preedit.orEmpty()
            val cursor = data.cursorPos

            if (preedit.isEmpty()) {
                EngineMessage.CompositionEnd
            } else {
                EngineMessage.Composition(preedit, cursor)
            }
        }

        is RimeMessage.CandidateListMessage -> {
            EngineMessage.Candidates(
                list = data.candidates.map {
                    EngineMessage.Candidate(
                        text = it.text,
                        comment = it.comment,
                    )
                },
                highlighted = data.highlighted,
                page = 0,
            )
        }

        is RimeMessage.StatusMessage -> {
            EngineMessage.Status(
                schemaName = data.schemaName,
                isAsciiMode = data.isAsciiMode,
            )
        }

        else -> EngineMessage.Unknown
    }
}
