package com.ninthsoft.ime.engine

import android.content.Context
import android.inputmethodservice.InputMethodService
import android.os.SystemClock
import android.view.KeyEvent.*
import com.ninthsoft.ime.engine.behavior.IBehavior
import com.ninthsoft.ime.engine.rime.behavior.Segmentation
import com.ninthsoft.ime.data.database.AppDatabase
import com.ninthsoft.ime.data.database.CandidateSorting
import com.ninthsoft.ime.engine.data.EngineMessage
import com.ninthsoft.ime.engine.event.KeyEvent
import com.ninthsoft.ime.engine.rime.host.BehaviorHost
import com.ninthsoft.ime.engine.data.CandidatePinYin
import com.ninthsoft.ime.engine.rime.behavior.Backspace
import com.ninthsoft.ime.engine.rime.behavior.InputKey
import com.ninthsoft.ime.engine.rime.behavior.InputString
import com.ninthsoft.ime.engine.rime.behavior.Reset
import com.ninthsoft.ime.engine.rime.behavior.SelectPinYin
import com.ninthsoft.ime.engine.rime.behavior.Selection
import com.ninthsoft.ime.engine.rime.core.EngineMessage
import com.ninthsoft.ime.engine.rime.core.IRimeJob
import com.ninthsoft.ime.engine.rime.core.RimeApi
import com.ninthsoft.ime.engine.rime.daemon.RimeDaemon
import com.ninthsoft.ime.engine.rime.daemon.RimeSession
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

class RimeEngine : IEngine, IBehaviorHost, IRimeJob {
    private val daemon by lazy { RimeDaemon }
    private val session: RimeSession = daemon.createSession(javaClass.name)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val jobs = Channel<suspend RimeApi.() -> Unit>(Channel.UNLIMITED)
    private var behaviorHosted: BehaviorHost? = null
    private var context: Context? = null
    private var callback: suspend (EngineMessage) -> Unit = { }

    override fun initialize(context: Context) {
        this.context = context
        scope.launch {
            for (job in jobs) {
                session.runOnReady(job)
            }
        }
        behaviorHosted = BehaviorHost(this)
    }

    override fun finalize() {
        jobs.close()
        scope.cancel()
        daemon.destroySession(javaClass.name)
    }

    override fun processKey(service: InputMethodService, key: KeyEvent) {
        sendJob {
            when (key) {
                is KeyEvent.SequenceEvent -> {
                    this@RimeEngine.flowed(InputString(key.sequence))
                    return@sendJob
                }

                is KeyEvent.CodeEvent -> {
                    when (key.keyCode) {
                        KEYCODE_SPACE -> {
                            if (getRawInput().isEmpty()) {
                                service.currentInputConnection.commitText(" ", 1)
                                return@sendJob
                            }
                        }

                        KEYCODE_DEL -> {
                            if (getRawInput().isEmpty()) {
                                service.currentInputConnection?.let { ic ->
                                    val before = ic.getTextBeforeCursor(1, 0)
                                    if (!before.isNullOrEmpty()) {
                                        ic.deleteSurroundingText(1, 0)
                                    } else {
                                        ic.deleteSurroundingText(0, 1)
                                    }
                                }
                                return@sendJob
                            }
                            this@RimeEngine.flowed(Backspace())
                            return@sendJob
                        }

                        KEYCODE_APOSTROPHE -> {
                            this@RimeEngine.flowed(Segmentation())
                            return@sendJob
                        }

                        KEYCODE_ENTER -> {
                            if (getRawInput().isEmpty()) {
                                service.currentInputConnection.commitText("\n", 1)
                                return@sendJob
                            }
                        }
                    }
                    val modifiers = key.modifiers.toInt()
                    this@RimeEngine.flowed(InputKey(key.keyCode, modifiers, key.isVirtual))
                    return@sendJob
                }
            }
        }
    }

    override fun selectCandidate(index: Int) {
        this@RimeEngine.flowed(Selection(index))
    }

    override fun resetComposition() {
        this.flowed(Reset())
    }

    override fun selectCandidatePinYin(pinYin: CandidatePinYin) {
        this.flowed(SelectPinYin(pinYin))
    }

    override fun segement() {
        this.flowed(Segmentation())
    }

    override fun selectSchema(schemaId: String) {
        sendJob {
            this@RimeEngine.resetComposition()
            selectSchema(schemaId)
        }
    }

    override fun undo(service: InputMethodService) {
        sendCombinationKeyEvent(service, KEYCODE_Z, ctrl = true)
    }

    override fun redo(service: InputMethodService) {
        sendCombinationKeyEvent(service, KEYCODE_Z, ctrl = true, shift = true)
    }

    override fun resortCandidates(candidates: List<EngineMessage.Candidate>) {
        val ctx = context ?: return
        val db = AppDatabase.getInstance(ctx)
        sendJob {
            val preedit = getRawInput().replace("'", " ")
            if (preedit.isNotEmpty()) {
                val ids = candidates.map { it.index }
                db.candidateSortingDao().saveSorting(CandidateSorting(preedit, ids))
            }
        }
    }

    override fun deleteCandidate(index: Int) {
        sendJob {
            deleteCandidate(index, global = true)
        }
    }

    private fun sendCombinationKeyEvent(
        service: InputMethodService, keyCode: Int, ctrl: Boolean = false, shift: Boolean = false
    ) {
        val ic = service.currentInputConnection ?: return
        val now = SystemClock.uptimeMillis()
        var meta = 0
        if (ctrl) meta = meta or META_CTRL_ON or META_CTRL_LEFT_ON
        if (shift) meta = meta or META_SHIFT_ON or META_SHIFT_LEFT_ON
        if (ctrl) ic.sendKeyEvent(
            android.view.KeyEvent(
                now, now, ACTION_DOWN, KEYCODE_CTRL_LEFT, 0, 0
            )
        )
        if (shift) ic.sendKeyEvent(
            android.view.KeyEvent(
                now, now, ACTION_DOWN, KEYCODE_SHIFT_LEFT, 0, 0
            )
        )
        ic.sendKeyEvent(android.view.KeyEvent(now, now, ACTION_DOWN, keyCode, 0, meta))
        ic.sendKeyEvent(android.view.KeyEvent(now, now, ACTION_UP, keyCode, 0, meta))
        if (shift) ic.sendKeyEvent(
            android.view.KeyEvent(
                now, now, ACTION_UP, KEYCODE_SHIFT_LEFT, 0, 0
            )
        )
        if (ctrl) ic.sendKeyEvent(
            android.view.KeyEvent(
                now, now, ACTION_UP, KEYCODE_CTRL_LEFT, 0, 0
            )
        )
    }

    override fun flowed(behavior: IBehavior): Boolean {
        return behaviorHosted?.flowed(behavior) == true
    }

    override fun resetState() {
        behaviorHosted?.resetState()
    }

    private fun possibleCandidatePinYin() {
        sendJob {
            val currentInput = getRawInput()
            val confirmedLen = getInputConfirmedPosition()
            val pinYins = behaviorHosted?.possiblePinYin(currentInput, confirmedLen)
                ?: emptyArray<CandidatePinYin>()
            callback(EngineMessage.PossibleCandidatePinYin(pinYins))
        }
    }

    override fun observe(scope: CoroutineScope, on: suspend (EngineMessage) -> Unit) {
        callback = on
        scope.launch {
            daemon.observeMessages { message ->
                val msg = message.EngineMessage()
                when (msg) {
                    is EngineMessage.InlinePreedit -> {
                        if (msg.preedit.isEmpty()) {
                            behaviorHosted?.resetState()
                        }
                        possibleCandidatePinYin()
                    }

                    is EngineMessage.Candidates -> {
                        restoreCandidates(msg)
                        return@observeMessages
                    }

                    else -> {}
                }
                callback(msg)
            }
        }
    }

    override fun schemasList(): List<EngineMessage.Schema> = runBlocking {
        awaitJob(emptyList()) {
            enabledSchemata().map {
                EngineMessage.Schema(
                    it.id, it.name, it.layout, it.punctuation
                )
            }
        }
    }

    override fun clear(service: InputMethodService) {
        sendJob {
            if (compositionCached.preedit?.isNotEmpty() ?: false) {
                this@RimeEngine.resetComposition()
            } else {
                service.currentInputConnection?.let {
                    val p0 = it.getTextBeforeCursor(Int.MAX_VALUE, 0)?.length ?: 0
                    val p1 = it.getTextAfterCursor(Int.MAX_VALUE, 0)?.length ?: 0
                    it.deleteSurroundingTextInCodePoints(p0, p1)
                }
            }
        }
    }

    override fun sendJob(block: suspend RimeApi.() -> Unit) {
        jobs.trySend(block)
    }

    override suspend fun <T> awaitJob(defaultValue: T, block: suspend RimeApi.() -> T): T {
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

    private fun restoreCandidates(msg: EngineMessage.Candidates) {
        sendJob {
            val preedit = getRawInput().replace("'", " ")
            if (preedit.isEmpty()) {
                callback(msg)
                return@sendJob
            }
            val db = AppDatabase.getInstance(context!!)
            val sorting = db.candidateSortingDao().loadSorting(preedit)
            if (sorting != null && sorting.candidateIds.isNotEmpty()) {
                val reordered = sorting.candidateIds.mapNotNull { id ->
                    msg.list.find { it.index == id }
                }
                val remaining = msg.list.filter { it.index !in sorting.candidateIds }
                val result = reordered + remaining
                db.candidateSortingDao()
                    .saveSorting(CandidateSorting(preedit, result.map { it.index }))
                callback(EngineMessage.Candidates(result, msg.highlighted, msg.page))
                return@sendJob
            }
            callback(msg)
        }
    }
}
