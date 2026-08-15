package com.ninthsoft.ime.engine

import android.content.Context
import android.inputmethodservice.InputMethodService
import android.os.SystemClock
import android.view.KeyEvent.*
import android.view.inputmethod.InputConnection
import androidx.core.content.edit
import com.ninthsoft.ime.ImeApplication
import com.ninthsoft.ime.base.marisa.Prediction
import com.ninthsoft.ime.base.ngram.GramDb
import com.ninthsoft.ime.base.priority.CandidateFeature
import com.ninthsoft.ime.base.priority.PriorityCalculator
import com.ninthsoft.ime.base.priority.WeightConfig
import com.ninthsoft.ime.base.util.TextUtil
import com.ninthsoft.ime.engine.behavior.IBehavior
import com.ninthsoft.ime.engine.rime.behavior.Segmentation
import com.ninthsoft.ime.data.database.AppDatabase
import com.ninthsoft.ime.data.database.CandidateSorting
import com.ninthsoft.ime.data.manager.CandidateManager
import com.ninthsoft.ime.data.manager.SchemaManager
import com.ninthsoft.ime.engine.data.EngineMessage
import com.ninthsoft.ime.engine.event.KeyEvent
import com.ninthsoft.ime.engine.rime.host.BehaviorHost
import com.ninthsoft.ime.engine.data.CandidatePinYin
import com.ninthsoft.ime.engine.data.EngineMessage.Candidate
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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.File

class RimeEngine : IEngine, IBehaviorHost, IRimeJob {
    private val daemon by lazy { RimeDaemon }
    private val scope by lazy { CoroutineScope(SupervisorJob() + Dispatchers.Default) }
    private val boot by lazy { CoroutineScope(SupervisorJob() + Dispatchers.Default) }
    private val jobs by lazy { Channel<suspend RimeApi.() -> Unit>(Channel.UNLIMITED) }
    private var session: RimeSession? = null
    private var behaviorHosted: BehaviorHost? = null
    private var context: Context? = null
    private var callback: suspend (EngineMessage) -> Unit = { }
    private var inited: Boolean = false
    private var gramDb: GramDb? = null;
    private var inputContext: String = ""
    private var inputConnection: InputConnection? = null
    private var prediction: Prediction? = null
    private var lastCandidatesSize = 0
    private val calculator = PriorityCalculator()

    override fun initialize(context: Context) {
        this.context = context
        (context.applicationContext as ImeApplication).notifyState(ImeApplication.InitState.STARTING_ENGINE)
        boot.launch {
            daemon.observeMessages {
                if (it is RimeMessage.DeployMessage && it.state == RimeMessage.DeployMessage.State.Success) {
                    inited = true
                    boot.cancel()
                }
            }
        }
        session = daemon.createSession(javaClass.name)
        scope.launch {
            for (job in jobs) {
                session?.runOnReady(job)
            }
        }
        behaviorHosted = BehaviorHost(this)
        sendJob {
            val prefs = context.getSharedPreferences(SchemaManager.PREFS_NAME, Context.MODE_PRIVATE)
            val enabledIds = prefs.getString(SchemaManager.KEY_ENABLED_IDS, "")?.split(",")
                ?.filter { it.isNotBlank() }
            var index = 0
            while (index < 300) {
                index++
                if (!inited) {
                    Thread.sleep(1000)
                    continue
                }
                val schemas = enabledSchemata()
                if (enabledIds.isNullOrEmpty()) {
                    val ids = schemas.joinToString(",") { it.id }
                    prefs.edit { putString(SchemaManager.KEY_ENABLED_IDS, ids) }
                }
                break
            }
            val currentSchema = currentSchema()
            RimeConfig.openSchema(currentSchema.schemaId).use { config ->
                config.getString("grammar/language")?.let { initGramdb(it) }
            }
            //预热一下
            processKey(KeyMapping.Key_Delete, 0U, false)
            (context.applicationContext as ImeApplication).notifyState(ImeApplication.InitState.DONE)
        }
    }

    override fun finalize() {
        jobs.close()
        scope.cancel()
        prediction?.destroy()
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

    override fun selectCandidate(candidate: Candidate) {
        if (candidate.type == Candidate.CandidateType.Prediction) {
            sendJob {
                callback(EngineMessage.Commit(candidate.text))
            }
            return
        }
        sendJob {
            val ctx = context ?: return@sendJob
            val db = AppDatabase.getInstance(ctx)
            db.candidatePreferDao().upsert(candidate.text, inputContext)
        }
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
            val pinYins = behaviorHosted?.possiblePinYin(currentInput, confirmedLen) ?: emptyList()
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
                        return@observeMessages
                    }

                    is EngineMessage.Candidates -> {
                        val predictionEnabled = CandidateManager.isPredictionEnabled(context!!)
                        if (predictionEnabled && msg.list.isEmpty() && this@RimeEngine.lastCandidatesSize > 0) {
                            this@RimeEngine.lastCandidatesSize = 0
                            this@RimeEngine.onInputChanged()
                            return@observeMessages
                        }
                        this@RimeEngine.lastCandidatesSize = msg.list.size
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
            val rerankEnabled = CandidateManager.isRerankEnabled(context!!)
            if (!rerankEnabled) {
                callback(msg)
            }

            val restoreStart = 1
            val restoreEnd = minOf(25, msg.list.size)
            if (restoreEnd <= restoreStart) {
                callback(msg)
                return@sendJob
            }

            val texts = msg.list.subList(restoreStart, restoreEnd).map { it.text }
            val prefers =
                AppDatabase.getInstance(context!!).candidatePreferDao().getAllByTextIn(texts)
                    .associate { it.text to it.count }

            val cfg = WeightConfig()
            val restored = ArrayList<Candidate>(restoreEnd - restoreStart)

            for (index in restoreStart until restoreEnd) {
                val it = msg.list[index]
                var candidateCount = 0
                prediction?.predictNextWords(it.text, restoreEnd)?.forEach { prediction ->
                    candidateCount += prediction.count
                }

                val gramScore = if (inputContext.isNotEmpty()) {
                    gramDb?.query(inputContext, it.text) ?: 0.0
                } else {
                    0.0
                }

                val preferCount = prefers[it.text] ?: 0
                val textLen = it.text.codePointCount(0, it.text.length)
                val score = calculator.calculate(
                    CandidateFeature(
                        frequency = preferCount.toLong(),
                        wordLength = textLen,
                        candidateCount = candidateCount,
                        baseScore = gramScore
                    ), cfg
                )
                restored.add(
                    Candidate(
                        index = index,
                        text = it.text,
                        type = Candidate.CandidateType.Engine,
                        score = score
                    )
                )
            }
            restored.sortByDescending { it.score }

            val candidates = ArrayList<Candidate>(msg.list.size)
            candidates.add(msg.list[0])
            candidates.addAll(restored)
            for (index in restoreEnd until msg.list.size) {
                candidates.add(msg.list[index])
            }
            callback(EngineMessage.Candidates(candidates, 0, 0))
        }
    }

    private suspend fun initGramdb(language: String) {
        val gram = File(sharedDataDir, "$language.gram")
        if (gram.isFile) {
            gramDb = GramDb(gram.absolutePath)
            val predictGram = File(modelDir, "predict.marisa")
            if (predictGram.isFile) {
                prediction = Prediction(predictGram)
                prediction?.load()
            }
        }
    }

    override fun onFinishInputView() {
        inputConnection = null
    }

    override fun onStartInputView(ic: InputConnection) {
        inputConnection = ic
    }

    override fun onInputChanged() {
        if (!CandidateManager.isPredictionEnabled(context!!)) return
        inputContext = inputConnection?.getTextBeforeCursor(20, 0)?.toString() ?: ""
        sendJob {
            if (compositionCached.preedit?.isNotEmpty() == true) {
                return@sendJob
            }
            if (inputContext.isEmpty() || TextUtil.isSymbol(inputContext.last())) {
                callback(EngineMessage.Candidates(emptyList(), 0, 0))
                return@sendJob
            }
            val prediction = prediction ?: return@sendJob
            val possiables = TextUtil.contextSubstrings(inputContext)
            val cfg = WeightConfig()
            for (context in possiables) {
                if (context.isEmpty()) continue
                val words = prediction.predictNextWords(context)
                if (words.size >= 5) {
                    val texts = words.map { it.word }
                    val prefers =
                        AppDatabase.getInstance(this@RimeEngine.context!!).candidatePreferDao()
                            .getAllByTextIn(texts).associate { it.text to it.count }
                    val candidates = words.mapIndexed { index, it ->
                        val gramScore = gramDb?.query(inputContext, it.word) ?: 0.0
                        val preferCount = prefers[it.word] ?: 0
                        val textLen = it.word.codePointCount(0, it.word.length)
                        val score = calculator.calculate(
                            CandidateFeature(
                                frequency = preferCount.toLong(),
                                wordLength = textLen,
                                candidateCount = 1,
                                baseScore = gramScore
                            ), cfg
                        )
                        Candidate(
                            index = index,
                            text = it.word,
                            type = Candidate.CandidateType.Prediction,
                            score = score
                        )
                    }.sortedByDescending { it.score }
                    callback(EngineMessage.Candidates(candidates.take(25), 0, 0))
                    return@sendJob
                }
            }
        }
    }

    override fun reload() {
        resetState()
        sendJob {
            deploy()
        }
    }
}
