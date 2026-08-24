package com.ninthsoft.ime.engine

import android.content.Context
import android.inputmethodservice.InputMethodService
import android.os.SystemClock
import android.view.KeyEvent.*
import android.view.inputmethod.InputConnection
import com.ninthsoft.ime.ImeApplication
import com.ninthsoft.ime.base.util.TextUtil
import com.ninthsoft.ime.engine.behavior.IBehavior
import com.ninthsoft.ime.engine.rime.behavior.Segmentation
import com.ninthsoft.ime.data.database.AppDatabase
import com.ninthsoft.ime.data.database.CandidateSorting
import com.ninthsoft.ime.data.manager.CandidateManager
import com.ninthsoft.ime.engine.event.KeyEvent
import com.ninthsoft.ime.engine.rime.host.BehaviorHost
import com.ninthsoft.ime.engine.data.CandidatePinYin
import com.ninthsoft.ime.engine.data.EngineMessage
import com.ninthsoft.ime.engine.data.EngineMessage.Candidate
import com.ninthsoft.ime.engine.event.EngineEvent
import com.ninthsoft.ime.engine.event.EngineEvent.DepolyEvent.State.*
import com.ninthsoft.ime.engine.rime.behavior.Backspace
import com.ninthsoft.ime.engine.rime.behavior.InputKey
import com.ninthsoft.ime.engine.rime.behavior.InputString
import com.ninthsoft.ime.engine.rime.behavior.Reset
import com.ninthsoft.ime.engine.rime.behavior.SelectPinYin
import com.ninthsoft.ime.engine.rime.behavior.Selection
import com.ninthsoft.ime.engine.rime.core.EngineMessage
import com.ninthsoft.ime.engine.rime.core.IRimeJob
import com.ninthsoft.ime.engine.rime.core.KeyMapping
import com.ninthsoft.ime.engine.rime.core.RimeApi
import com.ninthsoft.ime.engine.rime.core.RimeConfig
import com.ninthsoft.ime.engine.rime.core.RimeMessage
import com.ninthsoft.ime.engine.rime.daemon.RimeDaemon
import com.ninthsoft.ime.engine.rime.daemon.RimeSession
import com.ninthsoft.ime.engine.rime.data.DataManager.modelDir
import com.ninthsoft.ime.engine.rime.data.DataManager.sharedDataDir
import com.ninthsoft.ime.engine.manager.CandidateRerankManager
import com.ninthsoft.ime.engine.manager.PredictionManager
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

class RimeEngine : IEngine, IBehaviorHost, IRimeJob {
    private val daemon by lazy { RimeDaemon }
    private val scope by lazy { CoroutineScope(SupervisorJob() + Dispatchers.Default) }
    private val msgJobs by lazy { Channel<suspend RimeApi.() -> Unit>(Channel.UNLIMITED) }
    private val eventJobs = Channel<EngineEvent>(Channel.UNLIMITED)
    private var session: RimeSession? = null
    private var behaviorHosted: BehaviorHost? = null
    private var context: Context? = null
    private var callback: suspend (EngineMessage) -> Unit = { }
    private var inited: Boolean = false
    private var inputConnection: InputConnection? = null
    private val rerankManager by lazy { CandidateRerankManager(context!!) }
    private val predictionManager by lazy { PredictionManager(context!!) }
    private var showPredictionCandidates = false
    private var notEmitNextEmptyCandidates = false

    override fun initialize(context: Context) {
        val appContext = context.applicationContext
        val app = appContext as ImeApplication
        this.context = appContext

        app.notifyState(ImeApplication.InitState.STARTING_ENGINE)
        // Install the collector before createSession() can start Rime. SharedFlow has no replay.
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            daemon.observeMessages { it ->
                if (it is RimeMessage.DeployMessage) {
                    if (it.state == RimeMessage.DeployMessage.State.Success) {
                        sendJob {
                            val ids = enabledSchemata().map { it.id }.toSet()
                            eventJobs.trySend(EngineEvent.DepolyEvent(Success, ids.toList()))

                            if (!this@RimeEngine.inited) {
                                val currentSchema = currentSchema()
                                RimeConfig.openSchema(currentSchema.schemaId).use { config ->
                                    config.getString("grammar/language")?.let {
                                        predictionManager.loadModels(modelDir, sharedDataDir, it)
                                    }
                                }
                                processKey(KeyMapping.Key_Delete, 0U, false)
                                app.notifyState(ImeApplication.InitState.DONE)
                                this@RimeEngine.inited = true
                            }
                        }
                    }
                    if (it.state == RimeMessage.DeployMessage.State.Failure) {
                        eventJobs.trySend(EngineEvent.DepolyEvent(Fail, emptyList()))
                    }
                }
            }
        }

        session = daemon.createSession(javaClass.name)
        scope.launch {
            for (job in msgJobs) {
                session?.runOnReady(job)
            }
        }
        behaviorHosted = BehaviorHost(this)
    }

    override fun finalize() {
        msgJobs.close()
        eventJobs.close()
        scope.cancel()
        predictionManager.destroy()
        daemon.destroySession(javaClass.name)
    }

    override fun processKey(service: InputMethodService, key: KeyEvent) {
        if (!this@RimeEngine.inited) return
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
                                callback(EngineMessage.Commit(" "))
                                return@sendJob
                            }
                        }

                        KEYCODE_DEL -> {
                            if (showPredictionCandidates) {
                                showPredictionCandidates = false
                                callback(EngineMessage.Candidates(emptyList(), 0, 0))
                                return@sendJob
                            }
                            val ic = service.currentInputConnection
                            if (getRawInput().isEmpty()) {
                                if (!ic?.getSelectedText(0).isNullOrEmpty()) {
                                    callback(EngineMessage.Commit(""))
                                    return@sendJob
                                }
                                ic?.let {
                                    if (!it.getTextBeforeCursor(1, 0).isNullOrEmpty()) {
                                        it.deleteSurroundingText(1, 0)
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
                                callback(EngineMessage.Commit("\n"))
                                return@sendJob
                            }
                        }
                    }
                    this@RimeEngine.flowed(
                        InputKey(
                            key.keyCode, key.modifiers.toInt(), key.isVirtual
                        )
                    )
                    return@sendJob
                }
            }
        }
    }

    override fun selectCandidate(candidate: Candidate) {
        sendJob {
            if (candidate.type == Candidate.CandidateType.Prediction) {
                callback(EngineMessage.Commit(candidate.text))
                this@RimeEngine.predict(candidate.text)
                return@sendJob
            }
            val ctx = context ?: return@sendJob
            val inputContext = (inputConnection?.getTextBeforeCursor(20, 0)?.toString() ?: "")
            AppDatabase.getInstance(ctx).candidatePreferDao().upsert(candidate.text, inputContext)
        }
        notEmitNextEmptyCandidates = true
        this@RimeEngine.flowed(Selection(candidate.index))
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

    override fun resortCandidates(candidates: List<Candidate>) {
        val ctx = context ?: return
        val db = AppDatabase.getInstance(ctx)
        sendJob {
            val preedit = getRawInput().replace("'", " ")
            if (preedit.isNotEmpty()) {
                db.candidateSortingDao()
                    .saveSorting(CandidateSorting(preedit, candidates.map { it.index }))
            }
        }
    }

    override fun deleteCandidate(index: Int) {
        sendJob { deleteCandidate(index, global = true) }
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

    override fun flowed(behavior: IBehavior): Boolean = behaviorHosted?.flowed(behavior) == true
    override fun resetState() {
        behaviorHosted?.resetState()
    }

    private fun possibleCandidatePinYin() {
        sendJob {
            val currentInput = getRawInput()
            val confirmedLen = getInputConfirmedPosition()
            val pinYins = behaviorHosted?.possiblePinYin(currentInput, confirmedLen) ?: emptyList()
            callback(EngineMessage.PossibleCandidatePinYin(pinYins))
        }
    }

    override fun observeEvent(scope: CoroutineScope, on: suspend (EngineEvent) -> Unit) {
        scope.launch {
            for (event in eventJobs) {
                on(event)
            }
        }
    }

    override fun observeMessage(scope: CoroutineScope, on: suspend (EngineMessage) -> Unit) {
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
                        return@observeMessages
                    }

                    is EngineMessage.Commit -> {
                        notEmitNextEmptyCandidates = true
                        this@RimeEngine.predict(msg.text)
                    }

                    is EngineMessage.Candidates -> {
                        if (notEmitNextEmptyCandidates && msg.list.isEmpty()) {
                            notEmitNextEmptyCandidates = false
                            return@observeMessages
                        }
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
            if (compositionCached.preedit?.isNotEmpty() == true) {
                this@RimeEngine.resetComposition()
            } else {
                if (showPredictionCandidates) {
                    showPredictionCandidates = false
                    callback(EngineMessage.Candidates(emptyList(), 0, 0))
                }
                service.currentInputConnection?.let {
                    val p0 = it.getTextBeforeCursor(Int.MAX_VALUE, 0)?.length ?: 0
                    val p1 = it.getTextAfterCursor(Int.MAX_VALUE, 0)?.length ?: 0
                    it.deleteSurroundingTextInCodePoints(p0, p1)
                }
            }
        }
    }

    override fun sendJob(block: suspend RimeApi.() -> Unit) {
        msgJobs.trySend(block)
    }

    override suspend fun <T> awaitJob(defaultValue: T, block: suspend RimeApi.() -> T): T {
        val deferred = CompletableDeferred<T>()
        val result = msgJobs.trySend {
            try {
                deferred.complete(block())
            } catch (_: Throwable) {
                deferred.complete(defaultValue)
            }
        }
        if (!result.isSuccess) {
            deferred.complete(defaultValue)
        }
        return withTimeoutOrNull(AWAIT_JOB_TIMEOUT_MS) { deferred.await() } ?: defaultValue
    }

    private companion object {
        const val AWAIT_JOB_TIMEOUT_MS = 2000L
    }

    private fun restoreCandidates(msg: EngineMessage.Candidates) {
        sendJob {
            val rerankEnabled = CandidateManager.isRerankEnabled(context!!)
            if (!rerankEnabled) {
                callback(msg)
                return@sendJob
            }
            val inputContext = (inputConnection?.getTextBeforeCursor(20, 0)?.toString() ?: "")
            val sortedList = rerankManager.rerank(msg.list, inputContext, null)
            callback(EngineMessage.Candidates(sortedList, 0, 0))
        }
    }

    override fun onFinishInputView() {
        inputConnection = null
    }

    override fun onStartInputView(ic: InputConnection) {
        inputConnection = ic
    }

    override fun predict(commit: String) {
        if (!CandidateManager.isPredictionEnabled(context!!)) return
        val inputContext = (inputConnection?.getTextBeforeCursor(20, 0)?.toString() ?: "") + commit
        sendJob {
            var candidates = emptyList<Candidate>()
            if (!inputContext.isEmpty() && !TextUtil.isSymbol(inputContext.last()) && !TextUtil.isAlphabet(
                    inputContext.last()
                )
            ) {
                candidates = predictionManager.makePredictions(inputContext)
            }
            showPredictionCandidates = candidates.isNotEmpty()
            callback(EngineMessage.Candidates(candidates, 0, 0))
        }
    }

    override fun reload() {
        resetState()
        sendJob {
            this@RimeEngine.inited = false
            deploy()
        }
    }

    //前端提交
    override fun commit(text: String) {
        sendJob {
            callback(EngineMessage.Commit(text))
            this@RimeEngine.predict(text)
        }
    }

    override fun onInputCleared() {
        if (showPredictionCandidates) {
            sendJob {
                showPredictionCandidates = false
                callback(EngineMessage.Candidates(emptyList(), 0, 0))
            }
        }
    }
}
