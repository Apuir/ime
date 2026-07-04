package com.ninthsoft.ime.engine

import android.content.Context
import android.inputmethodservice.InputMethodService
import com.ninthsoft.ime.base.registry.SingletonRegistry
import com.ninthsoft.ime.engine.data.EngineMessage
import com.ninthsoft.ime.engine.data.KeyEvent
import com.ninthsoft.ime.engine.ranking.IReranker
import com.ninthsoft.ime.engine.rime.core.EngineMessage
import com.ninthsoft.ime.engine.rime.core.KeyMapping
import com.ninthsoft.ime.engine.rime.core.RimeApi
import com.ninthsoft.ime.engine.rime.daemon.RimeDaemon
import com.ninthsoft.ime.engine.rime.daemon.RimeSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import timber.log.Timber

class RimeEngine : IEngine {
    private val daemon by lazy { RimeDaemon }
    private val session: RimeSession = daemon.createSession(javaClass.name)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val jobs = Channel<suspend RimeApi.() -> Unit>(Channel.UNLIMITED)
    private var messageCallback: (suspend (EngineMessage) -> Unit)? = null
    private var rerankJob: Job? = null

    override fun initialize(context: Context) {
        scope.launch {
            for (job in jobs) {
                session.runOnReady(job)
            }
        }
    }

    override fun finalize() {
        rerankJob?.cancel()
        jobs.close()
        scope.cancel()
        daemon.destroySession(javaClass.name)
    }

    override fun postProcessKey(service: InputMethodService, key: KeyEvent, on: (Boolean) -> Unit) {
        rerankJob?.cancel()
        postJob {
            var value = KeyMapping.keyCodeToVal(key.code)
            when (value) {
                KeyMapping.Key_space ->{
                    if (getRawInput().isEmpty()) {
                        service.currentInputConnection.commitText(" ", 1)
                        on(true)
                        return@postJob
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
                            on(true)
                        }
                        return@postJob
                    }
                    value = KeyMapping.keyCodeToVal(android.view.KeyEvent.KEYCODE_DEL)
                }

                KeyMapping.Key_Return -> {
                    if (getRawInput().isEmpty()) {
                        service.currentInputConnection.commitText("\n", 1)
                        on(true)
                        return@postJob
                    }
                    value = KeyMapping.keyCodeToVal(android.view.KeyEvent.KEYCODE_ENTER)
                }
            }
            on(processKey(value, key.modifiers.toUInt(), key.isVirtual))
        }
    }

    override fun postSelectCandidate(index: Int, on: (Boolean) -> Unit) {
        postJob { on(selectCandidate(index, global = true)) }
    }

    override fun resetState() {
        postJob { clearComposition() }
    }

    override fun observe(scope: CoroutineScope, on: suspend (EngineMessage) -> Unit) {
        messageCallback = on
        scope.launch { daemon.observeMessages { msg -> on(msg.EngineMessage()) } }
    }

    override fun postSchemeList(on: (List<EngineMessage.Schema>) -> Unit) {
        postJob {
            val schemas = availableSchemata()
            on(schemas.map {
                EngineMessage.Schema(it.id, it.name)
            })
        }
    }

    override fun postClear(service: InputMethodService, on: (Boolean) -> Unit) {
        postJob {
            if (compositionCached.preedit?.isNotEmpty() ?: false) {
                resetState()
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

    fun postJob(
        block: suspend RimeApi.() -> Unit,
    ) {
        jobs.trySend(block)
    }

    private suspend fun triggerRerank(query: String, candidates: List<EngineMessage.Candidate>) {
        try {
            val reranker = SingletonRegistry.get<IReranker>()
            messageCallback?.invoke(EngineMessage.RerankStarted)
            val results = reranker.rerank(query, candidates.map { it.text })
            Timber.d("zzzzz $query")
            results.forEach {
                Timber.d("mmmmm ${it.document} ${it.score}")
            }
            if (results.isNotEmpty()) {
                val best = results.first()
                messageCallback?.invoke(
                    EngineMessage.RerankedCandidate(
                        EngineMessage.Candidate(index = 0, text = best.document)
                    )
                )
            }
        } catch (e: Exception) {
            Timber.e(e, "Rerank failed")
        }
    }
}
