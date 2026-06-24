package com.ninthsoft.ime.engine

import android.content.Context
import android.inputmethodservice.InputMethodService
import androidx.lifecycle.lifecycleScope
import com.ninthsoft.ime.base.registry.SingletonRegistry
import com.ninthsoft.ime.base.speech.SherpaSpeechClient
import com.ninthsoft.ime.engine.data.EngineMessage
import com.ninthsoft.ime.engine.data.KeyEvent
import com.ninthsoft.ime.engine.ranking.IReranker
import com.ninthsoft.ime.engine.rime.core.KeyMapping
import com.ninthsoft.ime.engine.rime.core.RimeApi
import com.ninthsoft.ime.engine.rime.core.RimeMessage
import com.ninthsoft.ime.engine.rime.daemon.RimeDaemon
import com.ninthsoft.ime.engine.rime.daemon.RimeSession
import com.ninthsoft.ime.engine.rime.daemon.launchOnReady
import com.ninthsoft.ime.input.ImeInputMethodService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.consumeEach
import kotlinx.coroutines.channels.onFailure
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import timber.log.Timber

class RimeEngine : IEngine {
    private val daemon by lazy { RimeDaemon }
    private val session: RimeSession = daemon.createSession(javaClass.name)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val jobs = Channel<suspend RimeApi.() -> Unit>(Channel.UNLIMITED)
    private var messageCallback: (suspend (EngineMessage) -> Unit)? = null
    private var lastPreedit: String = ""
    private var rerankJob: Job? = null

    override fun onCreate(context: Context) {
        scope.launch {
            for (job in jobs) {
                session.runOnReady(job)
            }
        }
    }

    override fun onDestroy() {
        rerankJob?.cancel()
        jobs.close()
        scope.cancel()
        daemon.destroySession(javaClass.name)
    }

    override fun processKey(service: InputMethodService, key: KeyEvent): Unit {
        rerankJob?.cancel()
        val value = KeyMapping.keyCodeToVal(key.code)
        when (value) {
            KeyMapping.Key_BackSpace -> {
                postJob {
                    if (getRawInput().isNotEmpty()) {
                        val v = KeyMapping.keyCodeToVal(android.view.KeyEvent.KEYCODE_DEL)
                        processKey(v, 0u, true)
                        return@postJob
                    }
                    service.currentInputConnection?.let { ic ->
                        val before = ic.getTextBeforeCursor(1, 0)
                        if (!before.isNullOrEmpty()) {
                            ic.deleteSurroundingText(1, 0)
                        } else {
                            ic.deleteSurroundingText(0, 1)
                        }
                    }
                }
                return
            }

            KeyMapping.Key_Return -> {
                postJob {
                    if (compositionCached.preedit?.isNotEmpty() == true) {
                        val v = KeyMapping.keyCodeToVal(android.view.KeyEvent.KEYCODE_ENTER)
                        processKey(v, 0u, true)
                        return@postJob
                    }
                    service.currentInputConnection.commitText("\n", 1)
                }
                return
            }
        }
        postJob {
            processKey(value, key.modifiers.toUInt(), key.isVirtual)
        }
    }

    override fun selectCandidate(index: Int) {
        postJob { selectCandidate(index, global = true) }
    }

    override fun reset() {
        postJob { clearComposition() }
    }

    override fun observeMessage(scope: CoroutineScope, on: suspend (EngineMessage) -> Unit) {
        messageCallback = on
        scope.launch { daemon.observeMessages { msg -> on(msg.EngineMessage()) } }
    }

    private fun RimeMessage<*>.EngineMessage(): EngineMessage = when (this) {
        is RimeMessage.CommitTextMessage -> {
            lastPreedit += data.text.orEmpty()
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
            val candidates = data.candidates.mapIndexed { i, c ->
                EngineMessage.Candidate(
                    index = i,
                    text = c.text,
                    comment = c.comment,
                )
            }
            if (candidates.size >= 2 && lastPreedit.isNotEmpty()) {
                rerankJob?.cancel()
                rerankJob = scope.launch {
                    delay(1000)
                    triggerRerank(lastPreedit, candidates.take(5))
                }
            }
            EngineMessage.Candidates(
                list = candidates,
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

        is RimeMessage.KeyMessage -> {
            EngineMessage.Key(
                KeyEvent(
                    code = data.value.keyCode,
                    modifiers = data.modifiers.toInt(),
                    isVirtual = data.isVirtual
                )
            )
        }

        is RimeMessage.CandidateMenuMessage -> {
            EngineMessage.CandidateMenu(
                isLastPage = data.isLastPage,
                pageSize = data.pageSize,
                pageNumber = data.pageNumber,
                selectKeys = data.selectKeys,
                selectLabels = data.selectLabels,
                highlightedCandidateIndex = data.highlightedCandidateIndex,
                candidates = data.candidates.mapIndexed { index, item ->
                    EngineMessage.CandidateMenu.Candidate(
                        index = index,
                        text = item.text,
                        comment = item.comment,
                        label = item.label,
                    )
                }.toTypedArray()
            )
        }

        is RimeMessage.InlinePreeditMessage -> {
            EngineMessage.InlinePreedit(data)
        }

        is RimeMessage.SchemaMessage -> {
            EngineMessage.Schema(data.id, data.name)
        }

        else -> {
            Timber.d("EngineMessage.Unknown ${data.toString()}")
            EngineMessage.Unknown
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
