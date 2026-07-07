package com.ninthsoft.ime.engine

import android.content.Context
import android.inputmethodservice.InputMethodService
import com.ninthsoft.ime.engine.data.EngineMessage
import com.ninthsoft.ime.engine.event.KeyEvent
import com.ninthsoft.ime.engine.rime.hosted.BehaviorHosted
import com.ninthsoft.ime.engine.IBehaviorHosted.Behavior.Deletion
import com.ninthsoft.ime.engine.IBehaviorHosted.Behavior.Input
import com.ninthsoft.ime.engine.IBehaviorHosted.Behavior.Reset
import com.ninthsoft.ime.engine.IBehaviorHosted.Behavior.Selection
import com.ninthsoft.ime.engine.rime.core.EngineMessage
import com.ninthsoft.ime.engine.rime.core.KeyMapping
import com.ninthsoft.ime.engine.rime.core.RimeApi
import com.ninthsoft.ime.engine.rime.core.RimeMessage
import com.ninthsoft.ime.engine.rime.daemon.RimeDaemon
import com.ninthsoft.ime.engine.rime.daemon.RimeSession
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

class RimeEngine : IEngine, IJob {
    private val daemon by lazy { RimeDaemon }
    private val session: RimeSession = daemon.createSession(javaClass.name)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val jobs = Channel<suspend RimeApi.() -> Unit>(Channel.UNLIMITED)
    private val behaviorHosted = BehaviorHosted()

    override fun initialize(context: Context) {
        scope.launch {
            for (job in jobs) {
                session.runOnReady(job)
            }
        }
    }

    override fun finalize() {
        jobs.close()
        scope.cancel()
        daemon.destroySession(javaClass.name)
    }

    override fun processKey(service: InputMethodService, key: KeyEvent, on: (Boolean) -> Unit) {
        sendRimeJob {
            when (key) {
                is KeyEvent.SequenceEvent -> {
                    behaviorHosted.process(Input(key.sequence))
                }

                is KeyEvent.CodeEvent -> {
                    var value = KeyMapping.keyCodeToVal(key.keyCode)
                    when (value) {
                        KeyMapping.Key_space -> {
                            if (getRawInput().isEmpty()) {
                                service.currentInputConnection.commitText(" ", 1)
                                return@sendRimeJob on(true)
                            }
                        }

                        KeyMapping.Key_BackSpace -> {
                            if (getRawInput().isEmpty()) {
                                service.currentInputConnection?.let { ic ->
                                    val before = ic.getTextBeforeCursor(1, 0)
                                    if (!before.isNullOrEmpty()) {
                                        ic.deleteSurroundingText(1, 0)
                                    } else {
                                        ic.deleteSurroundingText(0, 1)
                                    }
                                }
                                return@sendRimeJob on(true)
                            }
                            value = KeyMapping.keyCodeToVal(android.view.KeyEvent.KEYCODE_DEL)
                            behaviorHosted.process(Deletion)
                        }

                        KeyMapping.Key_Return -> {
                            if (getRawInput().isEmpty()) {
                                service.currentInputConnection.commitText("\n", 1)
                                return@sendRimeJob on(true)
                            }
                            value = KeyMapping.keyCodeToVal(android.view.KeyEvent.KEYCODE_ENTER)
                            behaviorHosted.process(Reset)
                        }
                    }
                    return@sendRimeJob on(processKey(value, key.modifiers.toUInt(), key.isVirtual))
                }
            }
        }
    }

    override fun selectCandidate(index: Int, on: (Boolean) -> Unit) {
        sendRimeJob {
            on(selectCandidate(index, global = true))
            behaviorHosted.process(Selection)
        }
    }

    override fun resetComposition() {
        sendRimeJob {
            clearComposition()
            behaviorHosted.process(Reset)
        }
    }

    private suspend fun possibleCandidatePinYin(): EngineMessage.PossibleCandidatePinYin {
        return awaitJob(EngineMessage.PossibleCandidatePinYin(emptyArray())) {
            EngineMessage.PossibleCandidatePinYin(behaviorHosted.possiblePinYin())
        }
    }

    override fun observe(scope: CoroutineScope, on: suspend (EngineMessage) -> Unit) {
        scope.launch {
            daemon.observeMessages { msg ->
                if (msg is RimeMessage.InlinePreeditMessage) {
                    if (msg.preedit.isEmpty()) {
                        sendJob { behaviorHosted.process(Reset) }
                    }
                    on(possibleCandidatePinYin())
                }
                on(msg.EngineMessage())
            }
        }
    }

    override fun schemeList(on: (List<EngineMessage.Schema>) -> Unit) {
        sendRimeJob {
            val schemas = availableSchemata()
            on(schemas.map {
                EngineMessage.Schema(it.id, it.name)
            })
        }
    }

    override fun clear(service: InputMethodService, on: (Boolean) -> Unit) {
        sendRimeJob {
            if (compositionCached.preedit?.isNotEmpty() ?: false) {
                clearComposition()
                behaviorHosted.process(Reset)
            } else {
                service.currentInputConnection?.let {
                    it.finishComposingText()
                    it.performContextMenuAction(android.R.id.selectAll)
                    it.commitText("", 1)
                }
            }
            on(true)
        }
    }

    private fun sendRimeJob(block: suspend RimeApi.() -> Unit) {
        jobs.trySend(block)
    }

    override fun sendJob(block: suspend () -> Unit) {
        sendRimeJob { block() }
    }

    override suspend fun <T> awaitJob(defaultValue: T, block: suspend () -> T): T {
        val deferred = CompletableDeferred<T>()
        val result = jobs.trySend {
            try {
                deferred.complete(block())
            } catch (_: Throwable) {
                deferred.complete(defaultValue)
            }
        }
        if (!result.isSuccess) {
            deferred.complete(defaultValue)
        }
        return deferred.await()
    }
}
