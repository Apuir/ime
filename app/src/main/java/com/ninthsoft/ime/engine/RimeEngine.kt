package com.ninthsoft.ime.engine

import android.content.Context
import android.content.SharedPreferences
import android.inputmethodservice.InputMethodService
import android.view.KeyEvent.*
import android.view.inputmethod.InputConnection
import androidx.core.content.edit
import com.ninthsoft.ime.ImeApplication
import com.ninthsoft.ime.base.util.InputConnectionUtil
import com.ninthsoft.ime.base.util.PinYinUtil
import com.ninthsoft.ime.base.util.TextUtil
import com.ninthsoft.ime.engine.behavior.IBehavior
import com.ninthsoft.ime.engine.rime.behavior.Segmentation
import com.ninthsoft.ime.data.database.AppDatabase
import com.ninthsoft.ime.data.manager.CandidateManager
import com.ninthsoft.ime.data.manager.CandidateSortingManager
import com.ninthsoft.ime.data.manager.SchemaManager
import com.ninthsoft.ime.engine.event.KeyEvent
import com.ninthsoft.ime.engine.rime.host.BehaviorHost
import com.ninthsoft.ime.engine.data.CandidatePinYin
import com.ninthsoft.ime.engine.data.EngineMessage
import com.ninthsoft.ime.engine.data.EngineMessage.Candidate
import com.ninthsoft.ime.engine.rime.behavior.Backspace
import com.ninthsoft.ime.engine.rime.behavior.InputKey
import com.ninthsoft.ime.engine.rime.behavior.InputString
import com.ninthsoft.ime.engine.rime.behavior.Reset
import com.ninthsoft.ime.engine.rime.behavior.SelectPinYin
import com.ninthsoft.ime.engine.rime.behavior.Selection
import com.ninthsoft.ime.engine.rime.core.IRimeJob
import com.ninthsoft.ime.engine.rime.core.RimeApi
import com.ninthsoft.ime.engine.rime.daemon.RimeDaemon
import com.ninthsoft.ime.engine.rime.daemon.RimeSession
import com.ninthsoft.ime.engine.manager.CandidateRerankManager
import com.ninthsoft.ime.engine.manager.PredictionManager
import com.ninthsoft.ime.engine.rime.core.KeyMapping
import com.ninthsoft.ime.engine.rime.core.Rime.Companion.getCurrentSchema
import com.ninthsoft.ime.engine.rime.core.EngineMessageConverter
import com.ninthsoft.ime.engine.rime.core.RimeConfig
import com.ninthsoft.ime.engine.rime.core.RimeMessage
import com.ninthsoft.ime.engine.rime.core.RimeSchema
import com.ninthsoft.ime.data.App.modelDir
import com.ninthsoft.ime.engine.rime.data.DataManager.sharedDataDir
import com.ninthsoft.ime.base.util.TraditionalConverter
import com.ninthsoft.ime.engine.rime.util.OptionsApplier
import com.ninthsoft.ime.input.ImeInputMethodService
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber
import kotlin.lazy

class RimeEngine : IEngine, IBehaviorHost, IRimeJob {
    private data class EngineState(
        var initialized: Boolean = false,
        var predictionVisible: Boolean = false,
        var suppressNextEmptyCandidates: Boolean = false,
        var initHookTriggered: Boolean = false,
        var candidateRequestId: Long = 0L,
        var latestCandidateRequestId: Long = 0L,
        var predictionRequestId: Long = 0L,
        var latestPredictionRequestId: Long = 0L,
    )

    /**
     * 最近一次「从候选上屏」的记录，用来把「上屏后又删掉」判定为误选。
     *
     * 写在 rime-main 的 job 里、读在 actions 协程里，所以标 `@Volatile`。
     */
    private data class LastSelection(val text: String, val context: String, val at: Long)

    private sealed interface Action {
        data class ProcessKey(val service: InputMethodService, val key: KeyEvent) : Action
        data class Backspace(val rawInputEmpty: Boolean) : Action
        data class RimeMessage(val message: com.ninthsoft.ime.engine.rime.core.RimeMessage<*>) :
            Action

        data class Behavior(val behavior: IBehavior) : Action
        data class SelectCandidate(val candidate: Candidate) : Action
        data class Clear(val service: InputMethodService) : Action
        data class Predict(val commit: String) : Action
        data class PredictionReady(val requestId: Long, val candidates: List<Candidate>) : Action
        data class EmitMessage(val message: EngineMessage) : Action
        data class CandidatesReady(val requestId: Long, val message: EngineMessage.Candidates) :
            Action

        data class PossibleCandidatePinYinSnapshot(
            val candidatePinYinType: String,
            val currentInput: String,
            val confirmedLen: Int,
        ) : Action

        data object Reset : Action
        data class SelectCandidatePinYin(val pinYin: CandidatePinYin) : Action
        data object Segment : Action
        data class SelectSchema(val schemaId: String) : Action
        data class Commit(val text: String, val cursorOffset: Int = 0) : Action
        data object InputCleared : Action
        data object DismissPrediction : Action
        data object Reload : Action
    }

    private val daemon by lazy { RimeDaemon }
    private val scope by lazy { CoroutineScope(SupervisorJob() + Dispatchers.Default) }
    private val actions = Channel<Action>(Channel.UNLIMITED)
    private val jobs by lazy { Channel<suspend RimeApi.() -> Unit>(Channel.UNLIMITED) }
    private var session: RimeSession? = null
    private var behaviorHosted: BehaviorHost? = null
    private var context: Context? = null

    @Volatile
    private var inputConnection: InputConnection? = null
    private var serviceRef: ImeInputMethodService? = null

    //引擎相关配置监控
    private var prefs: SharedPreferences? = null
    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (OptionsApplier.isOptionDependency(key)) {
            sendJob { RimeSchema(getCurrentSchema()).applyOptions(this) }
        }
    }

    /** 桥接模式下返回虚拟连接，否则返回真实连接。 */
    private fun inputConnection(): InputConnection? =
        serviceRef?.activeInputConnection() ?: inputConnection

    private val rerankManager by lazy { context?.let { CandidateRerankManager(it) } }
    private val predictionManager by lazy { context?.let { PredictionManager(it) } }
    private val state = EngineState()
    private var predictionJob: Job? = null
    private var candidateRestoreJob: Job? = null

    @Volatile
    private var lastSelection: LastSelection? = null

    private val messages = MutableSharedFlow<EngineMessage>(
        replay = 0, extraBufferCapacity = 64
    )

    override fun initialize(context: Context) {
        val appContext = context.applicationContext
        this@RimeEngine.context = appContext

        val app = this@RimeEngine.context as ImeApplication
        app.notifyState(ImeApplication.AppState.EngineStarting)
        behaviorHosted = BehaviorHost(this)

        scope.launch {
            for (action in actions) reduce(action)
        }

        //observe engine Messages at first.
        scope.launch {
            daemon.observeMessages { actions.send(Action.RimeMessage(it)) }
        }
        session = daemon.createSession(javaClass.name)
        scope.launch {
            for (job in jobs) {
                session?.runOnReady(job)
            }
        }
        //监听引擎注册事件
        prefs = appContext.getSharedPreferences(
            CandidateManager.PREFS_NAME, Context.MODE_PRIVATE
        ).also {
            it.registerOnSharedPreferenceChangeListener(prefsListener)
        }
        //以结束为号
        sendJob {
            joinMaintenanceThread()
            actions.send(
                Action.RimeMessage(
                    RimeMessage.DeployMessage(RimeMessage.DeployMessage.State.Finish)
                )
            )
            RimeSchema(getCurrentSchema()).applyOptions(this)
        }
    }

    override fun finalize() {
        prefs?.unregisterOnSharedPreferenceChangeListener(prefsListener)
        prefs = null
        actions.close()
        jobs.close()
        scope.cancel()
        predictionManager?.destroy()
        daemon.destroySession(javaClass.name)
    }

    override fun processKey(service: InputMethodService, key: KeyEvent) {
        actions.trySend(Action.ProcessKey(service, key))
    }

    private fun processKeyInternal(key: KeyEvent) {
        if (!state.initialized) {
            return
        }
        sendJob {
            when (key) {
                is KeyEvent.SequenceEvent -> {
                    actions.send(Action.Behavior(InputString(key.sequence)))
                    return@sendJob
                }

                is KeyEvent.CodeEvent -> {
                    when (key.keyCode) {
                        KEYCODE_SPACE -> {
                            if (getRawInput().isEmpty()) {
                                actions.send(Action.EmitMessage(EngineMessage.Commit(" ")))
                                return@sendJob
                            }
                        }

                        KEYCODE_DEL -> {
                            actions.send(Action.Backspace(getRawInput().isEmpty()))
                            return@sendJob
                        }

                        KEYCODE_APOSTROPHE -> {
                            actions.send(Action.Behavior(Segmentation()))
                            return@sendJob
                        }

                        KEYCODE_ENTER -> {
                            // 没有组合时的回车不归引擎管：这里曾经 `Commit("\n")`，
                            // 而单行输入框会把换行显示成空格（搜索框也不认它），
                            // 用户看到的就是「按回车只多一个空格」。
                            // 交回宿主按输入框语义处理，见 KeyActionListener.handleReturn。
                            if (getRawInput().isEmpty()) {
                                return@sendJob
                            }
                        }
                    }
                    actions.send(
                        Action.Behavior(
                            InputKey(key.keyCode, key.modifiers.toInt(), key.isVirtual)
                        )
                    )
                    return@sendJob
                }
            }
        }
    }

    override fun selectCandidate(candidate: Candidate) {
        actions.trySend(Action.SelectCandidate(candidate))
    }

    private suspend fun selectCandidateInternal(candidate: Candidate) {
        if (candidate.type == Candidate.TYPE_IME_PREDICTION) {
            messages.emit(EngineMessage.Commit(candidate.text))
            requestPrediction(candidate.text)
            return
        }

        // Only Rime selection produces the empty candidate response that must be hidden.
        // Prediction candidates bypass Rime and must not arm this flag.
        state.suppressNextEmptyCandidates = true
        sendJob {
            val ctx = context ?: return@sendJob
            val inputContext = (inputConnection()?.getTextBeforeCursor(20, 0)?.toString() ?: "")
            AppDatabase.getInstance(ctx).candidatePreferDao().upsert(candidate.text, inputContext)
            // 记下这次上屏：若接下来用户马上把它删掉，就是「误选」信号。
            lastSelection = LastSelection(
                text = candidate.text,
                context = inputContext,
                at = System.currentTimeMillis(),
            )
        }
        flowBehavior(Selection(candidate.index))
    }

    override fun resetComposition() {
        actions.trySend(Action.Reset)
    }

    override fun selectCandidatePinYin(pinYin: CandidatePinYin) {
        actions.trySend(Action.SelectCandidatePinYin(pinYin))
    }

    override fun segement() {
        actions.trySend(Action.Segment)
    }

    override fun selectSchema(schemaId: String) {
        actions.trySend(Action.SelectSchema(schemaId))
    }

    override fun undo(service: InputMethodService) {
        InputConnectionUtil.sendCombinationKeyEvent(inputConnection(), KEYCODE_Z, ctrl = true)
    }

    override fun redo(service: InputMethodService) {
        InputConnectionUtil.sendCombinationKeyEvent(
            inputConnection(), KEYCODE_Z, ctrl = true, shift = true
        )
    }

    override fun resortCandidates(candidates: List<Candidate>) {
        val ctx = context ?: return
        if (candidates.isEmpty()) return
        val db = AppDatabase.getInstance(ctx)
        sendJob {
            CandidateSortingManager(db).save(candidates)
        }
    }

    override fun deleteCandidate(candidate: Candidate) {
        sendJob { deleteCandidate(candidate.index, global = true) }
        // 长按删除是用户显式的「我不想要这个词」，比回删更强的信号，一样记负反馈。
        demoteCandidate(candidate.text, reason = "forget", inputContext = "")
    }


    override fun flowed(behavior: IBehavior): Boolean {
        actions.trySend(Action.Behavior(behavior))
        return true
    }

    private fun flowBehavior(behavior: IBehavior): Boolean =
        behaviorHosted?.flowed(behavior) == true

    override fun resetState() {
        actions.trySend(Action.Reset)
    }

    private fun possibleCandidatePinYin() {
        sendJob {
            if (!PinYinUtil.isValidType(schemaCached.candidateKind)) {
                return@sendJob
            }
            val currentInput = getRawInput()
            val confirmedLen = getInputConfirmedPosition()
            actions.trySend(
                Action.PossibleCandidatePinYinSnapshot(
                    schemaCached.candidateKind, currentInput, confirmedLen
                )
            )
        }
    }

    private suspend fun reduce(action: Action) {
        when (action) {
            is Action.ProcessKey -> {
                serviceRef = action.service as? ImeInputMethodService
                processKeyInternal(action.key)
            }

            is Action.Backspace -> handleBackspace(action.rawInputEmpty)

            is Action.RimeMessage -> handleRimeMessage(action.message)
            is Action.Behavior -> flowBehavior(action.behavior)
            is Action.SelectCandidate -> selectCandidateInternal(action.candidate)
            is Action.Clear -> {
                serviceRef = action.service as? ImeInputMethodService
                clearInternal()
            }

            is Action.Predict -> requestPrediction(action.commit)
            is Action.PredictionReady -> {
                if (action.requestId == state.latestPredictionRequestId) {
                    state.predictionVisible = action.candidates.isNotEmpty()
                    messages.emit(EngineMessage.Candidates(action.candidates, 0, 0))
                }
            }

            is Action.EmitMessage -> messages.emit(action.message)

            is Action.PossibleCandidatePinYinSnapshot -> {
                val pinYins = behaviorHosted?.possiblePinYin(
                    action.candidatePinYinType, action.currentInput, action.confirmedLen
                ) ?: emptyList()
                messages.emit(EngineMessage.PossibleCandidatePinYin(pinYins))
            }

            is Action.CandidatesReady -> {
                if (action.requestId == state.latestCandidateRequestId) {
                    messages.emit(action.message)
                }
            }

            Action.Reset -> {
                flowBehavior(Reset())
            }

            is Action.SelectCandidatePinYin -> flowBehavior(SelectPinYin(action.pinYin))
            Action.Segment -> flowBehavior(Segmentation())
            is Action.SelectSchema -> {
                flowBehavior(Reset())
                sendJob { selectSchema(action.schemaId) }
            }

            is Action.Commit -> requestCommit(action.text, action.cursorOffset)
            Action.InputCleared -> {
                if (state.predictionVisible) {
                    state.predictionVisible = false
                    messages.emit(EngineMessage.Candidates(emptyList(), 0, 0))
                }
            }

            Action.DismissPrediction -> {
                // 用户主动取消这次联想。除了收起候选，还要让「正在跑」的预测结果失效：
                // 否则它会晚一步带着同一个 requestId 回来（PredictionReady 按 requestId 校验），
                // 候选面板会自己又弹出来。
                state.latestPredictionRequestId = ++state.predictionRequestId
                predictionJob?.cancel()
                if (state.predictionVisible) {
                    state.predictionVisible = false
                    messages.emit(EngineMessage.Candidates(emptyList(), 0, 0))
                }
            }

            Action.Reload -> {
                behaviorHosted?.resetState()
                sendJob {
                    deploy()
                    joinMaintenanceThread()
                    actions.send(
                        Action.RimeMessage(
                            RimeMessage.DeployMessage(RimeMessage.DeployMessage.State.Finish)
                        )
                    )
                }
            }
        }
    }

    private suspend fun handleBackspace(rawInputEmpty: Boolean) {
        if (!rawInputEmpty) {
            flowBehavior(Backspace())
            return
        }
        if (state.predictionVisible) {
            state.predictionVisible = false
            messages.emit(EngineMessage.Candidates(emptyList(), 0, 0))
            return
        }
        withContext(Dispatchers.Main.immediate) {
            val ic = inputConnection()
            val selected = ic?.getSelectedText(0)?.toString()
            if (!selected.isNullOrEmpty()) {
                onTextDeleted(selected)
                messages.emit(EngineMessage.Commit(""))
                return@withContext
            }
            // 一次读出光标前一段（而不是只读 1 个字符）：既判断「有没有内容可删」，
            // 也用来判断「删掉的是不是刚刚上屏的那几个字」。不额外增加 IPC 往返。
            val before = ic?.getTextBeforeCursor(UNDO_LOOKBACK_CHARS, 0)?.toString().orEmpty()
            if (before.isNotEmpty()) {
                ic?.deleteSurroundingText(1, 0)
                onTextDeleted(before)
            }
        }
    }

    /**
     * 删除动作发生前，光标前的内容是 [textBeforeCursor]；判断这次删除算不算「误选」。
     *
     * 判定两个条件同时成立：
     * 1. 距上次候选上屏不超过 [UNDO_WINDOW_MS]；
     * 2. 被删文本的**结尾**正好是刚刚上屏的那个候选 —— 用结尾匹配而不是全等，
     *    因为删除是逐字符发生的，删到该词第一个字时尾部就已经完整匹配。
     *
     * 判定成立后清掉记录，保证同一次上屏只降权一次。
     */
    private fun onTextDeleted(textBeforeCursor: String) {
        val selection = lastSelection ?: return
        if (System.currentTimeMillis() - selection.at > UNDO_WINDOW_MS) {
            lastSelection = null
            return
        }
        if (!textBeforeCursor.endsWith(selection.text)) return
        lastSelection = null
        demoteCandidate(selection.text, reason = "undo", inputContext = selection.context)
    }

    /** 记一条负反馈（误选降权）。只影响同码候选的排序，不会移除候选。 */
    private fun demoteCandidate(text: String, reason: String, inputContext: String) {
        if (text.isEmpty()) return
        val ctx = context ?: return
        Timber.d("demote candidate: '%s' reason=%s context='%s'", text, reason, inputContext)
        sendJob {
            AppDatabase.getInstance(ctx).candidatePreferDao()
                .demote(text, System.currentTimeMillis())
        }
    }

    private suspend fun handleRimeMessage(message: RimeMessage<*>) {
        val msg = EngineMessageConverter.convert(message)
        when (msg) {
            is EngineMessage.InlinePreedit -> {
                if (msg.preedit.isEmpty()) {
                    behaviorHosted?.resetState()
                }
                possibleCandidatePinYin()
                return
            }

            is EngineMessage.Commit -> {
                state.suppressNextEmptyCandidates = true
                requestPrediction(msg.text)
            }

            is EngineMessage.Candidates -> {
                if (msg.list.isNotEmpty()) {
                    // Rime candidates take over the panel from prediction candidates.
                    state.predictionVisible = false
                }
                if (state.suppressNextEmptyCandidates) {
                    state.suppressNextEmptyCandidates = false
                    if (msg.list.isEmpty()) {
                        return
                    }
                }
                logCandidateDiagnostics(msg.list)
                restoreCandidates(msg)
                return
            }

            is EngineMessage.Depoly -> {
                when (msg.state) {
                    EngineMessage.Depoly.State.Start -> state.initialized = false
                    EngineMessage.Depoly.State.Finish -> {
                        state.initialized = true
                        val triggerHook = !state.initHookTriggered
                        state.initHookTriggered = true
                        sendJob {
                            val prefs = context?.getSharedPreferences(
                                SchemaManager.PREFS_NAME, Context.MODE_PRIVATE
                            )
                            val enabledIds =
                                prefs?.getString(SchemaManager.KEY_ENABLED_IDS, "")?.split(",")
                                    ?.filter { it.isNotBlank() }
                            val schemas = enabledSchemata()
                            if (enabledIds.isNullOrEmpty()) {
                                val ids = schemas.joinToString(",") { it.id }
                                prefs?.edit { putString(SchemaManager.KEY_ENABLED_IDS, ids) }
                            }
                            val currentSchema = currentSchema()
                            RimeConfig.openSchema(currentSchema.schemaId).use { config ->
                                config.getString("grammar/language")?.let {
                                    Timber.d("predictionManager load model %s.gram", it)
                                    predictionManager?.loadModels(modelDir, sharedDataDir, it)
                                }
                            }

                            if (triggerHook) {
                                processKey(KeyMapping.Key_Delete, 0U, false)
                                context?.let { it as ImeApplication }
                                    ?.notifyState(ImeApplication.AppState.Finished)
                            }
                        }
                    }

                    else -> {}
                }
            }

            else -> {}
        }
        messages.emit(msg)
    }

    override fun observeMessages(
        scope: CoroutineScope, onMessage: suspend (EngineMessage) -> Unit
    ): Job {
        return scope.launch {
            messages.collect { message ->
                onMessage(message)
            }
        }
    }

    override fun schemasList(): List<EngineMessage.Schema> = runBlocking {
        awaitJob(emptyList()) {
            enabledSchemata().map {
                EngineMessage.Schema(
                    it.id, it.name, it.layout, it.punctuation, it.kind,
                    candidateKind = readCandidateKind(it.id),
                )
            }
        }
    }

    /**
     * 读方案声明的 candidateKind。
     *
     * native 的 `SchemaItem` 不带这个字段（改 native 不在本次范围内），而键盘槽要靠它
     * 判断「这个键盘配这套方案能不能用」，所以这里自己开一次方案配置取。
     * 只取一个字符串，比构造整个 [RimeSchema]（还会解析 switches / options / alphabet）轻得多。
     */
    private fun readCandidateKind(schemaId: String): String = runCatching {
        RimeConfig.openSchema(schemaId).use { it.getString("schema/candidateKind") ?: "" }
    }.getOrDefault("")


    override fun clear(service: InputMethodService) {
        actions.trySend(Action.Clear(service))
    }

    private suspend fun clearInternal() {
        val clearPredictions = state.predictionVisible
        state.predictionVisible = false
        if (clearPredictions) {
            messages.emit(EngineMessage.Candidates(emptyList(), 0, 0))
        }
        sendJob {
            if (compositionCached.preedit?.isNotEmpty() == true) {
                actions.send(Action.Reset)
            } else {
                withContext(Dispatchers.Main.immediate) {
                    inputConnection()?.deleteSurroundingText(Int.MAX_VALUE, Int.MAX_VALUE)
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
        return withTimeoutOrNull(2000L) { deferred.await() } ?: defaultValue
    }

    private fun restoreCandidates(msg: EngineMessage.Candidates) {
        val requestId = ++state.candidateRequestId
        state.latestCandidateRequestId = requestId
        candidateRestoreJob?.cancel()
        candidateRestoreJob = scope.launch {
            try {
                val ctx = context
                if (ctx == null) {
                    actions.send(Action.CandidatesReady(requestId, msg))
                    return@launch
                }

                val db = AppDatabase.getInstance(ctx)
                val rerankEnabled = CandidateManager.isRerankEnabled(ctx)
                if (rerankEnabled) {
                    // 开启重排：使用重排结果，不还原用户排序
                    val inputContext =
                        (inputConnection()?.getTextBeforeCursor(20, 0)?.toString() ?: "")
                    // gramDb 必须传真实的那一份：改造前这里恒传 null，
                    // 让权重最大的语法模型项永远为 0。
                    val sortedList = rerankManager?.rerank(
                        msg.list, inputContext, predictionManager?.gramDb
                    )
                    // 诊断：一眼看出「引擎给的顺序」与「应用侧重排后的顺序」差在哪。
                    // release 构建不装 Timber tree（treeCount == 0）时直接跳过，零开销。
                    if (Timber.treeCount > 0) {
                        Timber.d(
                            "diag-rerank engine=%s reranked=%s",
                            msg.list.take(5).joinToString(" ") { it.text },
                            sortedList?.take(5)?.joinToString(" ") { it.text } ?: "null",
                        )
                    }
                    actions.send(
                        Action.CandidatesReady(
                            requestId, EngineMessage.Candidates(sortedList ?: msg.list, 0, 0)
                        )
                    )
                } else {
                    // 关闭重排：还原用户拖拽保存的排序；无记录则原样展示
                    val savedIds = CandidateSortingManager(db).load(msg.list)
                    actions.send(
                        Action.CandidatesReady(
                            requestId, if (savedIds.isNullOrEmpty()) msg
                            else EngineMessage.Candidates(
                                restoreCandidateOrder(msg.list, savedIds), 0, 0
                            )
                        )
                    )
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Timber.e(error, "Failed to restore candidates; using original list")
                actions.send(Action.CandidatesReady(requestId, msg))
            }
        }
    }

    /** 按保存的原始序号顺序重排候选；不在保存列表中的候选保持原有相对顺序追加到末尾。 */
    private fun restoreCandidateOrder(
        list: List<Candidate>, savedIds: List<Int>
    ): List<Candidate> {
        val byId = list.associateBy { it.index }
        val savedSet = savedIds.toSet()
        val restored = ArrayList<Candidate>(list.size)
        for (id in savedIds) {
            byId[id]?.let { restored.add(it) }
        }
        for (c in list) {
            if (c.index !in savedSet) restored.add(c)
        }
        return restored
    }

    /**
     * 输入诊断日志（**只影响 debug 构建**）。
     *
     * 动机：简拼（如 `qryt` → 「杞人忧天」）这类问题在真机上排查时，光看候选面板
     * 说不清「是方案选错了、还是引擎没给出这个候选」。这里把**当前方案 id、
     * 原始输入码、候选总数与头几个候选**打进同一行，一次日志就能定位。
     *
     * 关掉的开销：release 构建里 `AppStartup.setupLogger` 不装 Timber tree，
     * [Timber.treeCount] 为 0 时直接返回，连读取 rawInput 的 job 都不会投递。
     */
    private fun logCandidateDiagnostics(list: List<Candidate>) {
        if (list.isEmpty() || Timber.treeCount == 0) return
        sendJob {
            val input = getRawInput()
            if (input.length < DIAG_MIN_INPUT_LENGTH) return@sendJob
            Timber.d(
                "diag schema=%s input=%s candidates=%d top=%s",
                schemaCached.schemaId,
                input,
                list.size,
                list.take(5).joinToString(" ") { it.text },
            )
        }
    }

    override fun onFinishInputView() {
        inputConnection = null
    }

    override fun onStartInputView(ic: InputConnection) {
        inputConnection = ic
    }

    override fun predict(commit: String) {
        actions.trySend(Action.Predict(commit))
    }

    private fun requestPrediction(commit: String) {
        // 预测模型基于简体训练；先转成简体再推导，以支持繁体输入下的候选预测。
        val inputContext = TraditionalConverter.toSimplified(
            (inputConnection()?.getTextBeforeCursor(20, 0)?.toString() ?: "") + commit
        )
        val requestId = ++state.predictionRequestId
        state.latestPredictionRequestId = requestId
        predictionJob?.cancel()
        predictionJob = scope.launch {
            try {
                if (context?.let { !CandidateManager.isPredictionEnabled(it) } == true) {
                    actions.send(Action.PredictionReady(requestId, emptyList()))
                    return@launch
                }
                var candidates: List<Candidate> = emptyList()
                if (inputContext.isNotEmpty() && !TextUtil.isSymbol(inputContext.last()) && !TextUtil.isAlphabet(
                        inputContext.last()
                    )
                ) {
                    candidates = predictionManager?.makePredictions(inputContext) ?: emptyList()
                }
                if (context?.let { CandidateManager.isTraditionalChineseEnabled(it) } == true) {
                    candidates = candidates.map {
                        it.copy(
                            text = TraditionalConverter.toTraditional(it.text),
                            comment = it.comment.takeIf(String::isNotEmpty)
                                ?.let(TraditionalConverter::toTraditional) ?: it.comment,
                        )
                    }
                }
                actions.send(Action.PredictionReady(requestId, candidates))
            } catch (error: CancellationException) {
                throw error
            }
        }
    }

    override fun reload() {
        actions.trySend(Action.Reload)
    }

    //前端提交
    override fun commit(text: String, cursorOffset: Int) {
        actions.trySend(Action.Commit(text, cursorOffset))
    }

    private fun requestCommit(text: String, cursorOffset: Int = 0) {
        sendJob {
            if (compositionCached.preedit?.isNotEmpty() == true) {
                // 有未上屏组合时先提交组合本身，再单独提交文本，
                // 这样成对符号也能拿到光标偏移（提交后向左回退）。
                commitCurrentSelection("")
                actions.send(Action.EmitMessage(EngineMessage.Commit(text, cursorOffset)))
                actions.send(Action.Predict(text))
                return@sendJob
            }
            actions.send(Action.EmitMessage(EngineMessage.Commit(text, cursorOffset)))
            actions.send(Action.Predict(text))
        }
    }

    override fun onInputCleared() {
        actions.trySend(Action.InputCleared)
    }

    override fun dismissPrediction() {
        actions.trySend(Action.DismissPrediction)
    }

    private companion object {
        /** 「上屏后回删」判定为误选的时间窗。超出窗口的删除不再算误选。 */
        const val UNDO_WINDOW_MS = 8_000L

        /** 判断删除目标时读回的光标前字符数，够覆盖常见候选词长度。 */
        const val UNDO_LOOKBACK_CHARS = 24

        /** 诊断日志的最小原始输入码长度（低于这个长度不记，避免刷屏）。 */
        const val DIAG_MIN_INPUT_LENGTH = 3
    }
}
