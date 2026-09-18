package com.ninthsoft.ime.input.handwriting

import android.content.Context
import android.os.Handler
import android.os.Looper
import timber.log.Timber
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * 手写引擎的持有者：**唯一**负责「什么时候加载哪个引擎、在哪个线程上跑」的地方。
 *
 * 三条硬约束（违反会直接表现为 ANR 或首字卡顿）：
 *
 * 1. **加载与识别都在单线程执行器上**。IME 主线程卡顿会直接 ANR，而模型加载与推理
 *    都是几十毫秒量级的工作，绝不能放在主线程。串行执行还顺带让
 *    [OnnxEngine] 的输出缓冲复用成为安全操作。
 * 2. **回调一律回主线程**。UI 更新必须如此，且调用方（手写面板）不该关心线程问题。
 * 3. **识别请求可取消**。连续书写会在前一拍还在推理时投来新请求；
 *    用序号丢弃过期结果，否则先发起、后返回的旧结果会覆盖掉新候选
 *    （表现为「写完最后一笔，候选却是上一个字的」）。
 *
 * 引擎解析的三态探测与降级决策在 [HandwritingEngineResolver] 里（纯逻辑、有单测），
 * 这里只负责「探测需要的外部事实」与副作用。
 */
object HandwritingEngineHolder {

    /** 单线程执行器。命名便于在 Profiler / ANR trace 里一眼认出。 */
    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "handwriting")
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    /** 识别请求序号，用于丢弃过期结果。 */
    private val sequence = AtomicInteger(0)

    private var localEngine: OnnxEngine? = null
    private var googleEngine: MlKitEngine? = null

    @Volatile
    private var active: HandwritingEngine? = null

    /** 当前 [active] 是按哪个模式解析出来的；模式变了要重新解析。 */
    @Volatile
    private var loadedMode: HwEngineMode? = null

    @Volatile
    private var lastError: String? = null

    /** Google 不可用的原因，供 UI 给出「切换到本地引擎」的提示。 */
    @Volatile
    private var googleUnavailableReason: String? = null

    /**
     * 本次进程内是否已尝试过下载 Google 模型并失败。
     *
     * 只放在内存里、不落偏好：下载超时属于临时故障，网络恢复后本该重试；
     * 落盘会让用户永远用不上 Google。放在内存里则「同一次使用中不反复触发下载」，
     * 重启输入法后再给一次机会。
     */
    @Volatile
    private var downloadAttemptedAndFailed = false

    /** 当前生效引擎的显示名（`Google` / `本地`），未加载时为 null。 */
    @Volatile
    var engineName: String? = null
        private set

    /** 当前是否可用（面板据此决定要不要显示「识别」入口）。 */
    val isReady: Boolean get() = active != null

    /** Google 不可用时的说明；可用时为 null。 */
    val googleHint: String? get() = googleUnavailableReason

    /**
     * 确保引擎就绪。**可在主线程调用**（实际工作在后台执行）。
     *
     * [onReady] 在主线程回调：引擎显示名（失败为 null）与错误原因。
     * 重复调用是廉价的：模式没变且引擎已就绪时直接回调，不会重复加载。
     */
    fun ensureReady(context: Context, onReady: (String?, String?) -> Unit) {
        val app = context.applicationContext
        executor.execute {
            val mode = HandwritingManager.engineMode(app)
            val alreadyReady = active?.isAvailable() == true && loadedMode == mode
            val ok = if (alreadyReady) true else resolveAndLoadLocked(app, mode)
            val name = active?.displayName
            val error = lastError
            mainHandler.post { onReady(if (ok) name else null, error) }
        }
    }

    /**
     * 识别。**可在主线程调用**。[onResult] 在主线程回调（结果、失败原因）。
     *
     * 传进来的 [strokes] 只被读取，不修改；调用方可以随后清空画布。
     */
    fun recognize(
        strokes: List<HwStroke>,
        writingAreaWidth: Int,
        writingAreaHeight: Int,
        nbest: Int,
        onResult: (List<HwCandidate>, String?) -> Unit,
    ) {
        val token = sequence.incrementAndGet()
        executor.execute {
            val engine = active
            if (engine == null || !engine.isAvailable()) {
                val message = lastError ?: "手写引擎未就绪"
                mainHandler.post { onResult(emptyList(), message) }
                return@execute
            }

            val candidates = engine.recognize(strokes, writingAreaWidth, writingAreaHeight, nbest)
            val error = engine.lastError

            // 过期请求直接丢弃：它的结果对应的是已经被清掉或改写的笔迹
            if (token != sequence.get()) {
                Timber.d("丢弃过期的手写识别结果（seq=%d, latest=%d）", token, sequence.get())
                return@execute
            }
            mainHandler.post { onResult(candidates, error) }
        }
    }

    /**
     * 取消尚未返回的识别请求。
     *
     * 面板关闭 / 清空画布时调用：不是必须的（序号已经能挡住过期结果），
     * 但能让「关掉面板后回调仍然打进来」这件事干净地不发生。
     */
    fun cancelPending() {
        sequence.incrementAndGet()
    }

    /**
     * 用户显式切到本地引擎（Google 不可用时 UI 的一键按钮）。
     *
     * 会写入偏好 —— 这与「AUTO 的静默降级」不同：那是临时的，
     * 而用户点这个按钮是**明确选择了本地**，应当被记住。
     */
    fun switchToLocal(context: Context) {
        val app = context.applicationContext
        HandwritingManager.setEngineMode(app, HwEngineMode.LOCAL)
        reset()
    }

    /** 清除 Google 可用性探测缓存并重新解析（设置页的「重新检测」）。 */
    fun resetGoogleProbe(context: Context) {
        val app = context.applicationContext
        HandwritingManager.setGoogleUsable(app, null)
        downloadAttemptedAndFailed = false
        reset()
    }

    /** 丢弃当前引擎状态，下次 [ensureReady] 会重新解析。 */
    fun reset() {
        loadedMode = null
        active = null
        engineName = null
    }

    /** 释放引擎句柄（native 会话）。设置页切换引擎后调用是安全的，之后可重新加载。 */
    fun close() {
        executor.execute {
            cancelPendingInternal()
            runCatching { googleEngine?.close() }
            runCatching { localEngine?.close() }
            googleEngine = null
            localEngine = null
            active = null
            engineName = null
            loadedMode = null
        }
    }

    // ------------------------------------------------------------------
    // 以下都在 executor 线程上执行
    // ------------------------------------------------------------------

    private fun cancelPendingInternal() {
        sequence.incrementAndGet()
    }

    /**
     * 解析并加载。**必须在执行器线程上调用。**
     *
     * 顺序刻意如此：先把「不需要联网就能确定」的事实查完，再决定要不要下载。
     */
    private fun resolveAndLoadLocked(context: Context, mode: HwEngineMode): Boolean {
        lastError = null
        googleUnavailableReason = null

        // LOCAL：用户点名要本地，不做任何 Google 探测
        if (mode == HwEngineMode.LOCAL) return loadLocalLocked(context)

        // 第一态：GMS。没有它后面全都不用看 —— 这是「硬结论」，可以缓存
        if (!MlKitSupport.isPlayServicesAvailable(context)) {
            HandwritingManager.setGoogleUsable(context, false)
            return fallbackOrFailLocked(context, mode, "设备上没有可用的 Google Play 服务")
        }

        // 第二态：模型标记；第三态：自检（只在标记为已下载时才值得跑）
        val downloaded = MlKitSupport.isModelDownloaded()
        val verified = if (!downloaded) {
            false
        } else if (HandwritingManager.googleUsable(context) == true) {
            // 上次已经自检通过，不必每次开面板都再跑一遍识别
            true
        } else {
            verifyGoogleLocked(context)
        }

        return when (HandwritingEngineResolver.decide(mode, true, downloaded, verified)) {
            HwEngineChoice.USE_GOOGLE -> useGoogleLocked()

            HwEngineChoice.USE_LOCAL -> loadLocalLocked(context)

            HwEngineChoice.UNAVAILABLE -> fallbackOrFailLocked(
                context,
                mode,
                googleUnavailableReason ?: "Google 手写模型当前不可用",
            )

            HwEngineChoice.DOWNLOAD_GOOGLE -> {
                if (downloadAttemptedAndFailed) {
                    return fallbackOrFailLocked(
                        context, mode,
                        "Google 手写模型本次未下载完成（已在后台继续，稍后可重试）",
                    )
                }
                val ok = downloadAndVerifyLocked(context)
                if (!ok) downloadAttemptedAndFailed = true
                when (HandwritingEngineResolver.afterDownload(mode, ok)) {
                    HwEngineChoice.USE_GOOGLE -> useGoogleLocked()
                    HwEngineChoice.USE_LOCAL -> loadLocalLocked(context)
                    else -> fallbackOrFailLocked(
                        context, mode,
                        "Google 手写模型下载或自检未通过",
                    )
                }
            }
        }
    }

    /** 跑一次真实自检识别，确认「标记为已下载」的模型真的能用。 */
    private fun verifyGoogleLocked(context: Context): Boolean {
        val engine = googleEngine ?: MlKitEngine().also { googleEngine = it }
        if (engine.isAvailable()) return true
        val ok = engine.load(context)
        if (ok) {
            HandwritingManager.setGoogleUsable(context, true)
        } else {
            // 自检不过**不写缓存**：残缺模型可以通过「删除后重新下载」修好，
            // 写死 false 会让设置页的重检失去意义。
            googleUnavailableReason = engine.lastError
            runCatching { engine.close() }
        }
        return ok
    }

    /** 触发下载并立刻自检；返回「下载 + 自检都过」。 */
    private fun downloadAndVerifyLocked(context: Context): Boolean {
        Timber.i("尝试下载 Google 手写模型（超时 %ds）", MlKitSupport.PROBE_TIMEOUT_SECONDS)
        val downloaded = MlKitSupport.downloadModel(MlKitSupport.PROBE_TIMEOUT_SECONDS)
        if (!downloaded) {
            googleUnavailableReason = "Google 手写模型未能在 ${MlKitSupport.PROBE_TIMEOUT_SECONDS}s 内下载完成"
            return false
        }
        // 下载返回成功不等于模型完整，必须再自检一次
        val engine = googleEngine ?: MlKitEngine().also { googleEngine = it }
        runCatching { engine.close() }
        return if (engine.load(context)) {
            HandwritingManager.setGoogleUsable(context, true)
            true
        } else {
            googleUnavailableReason = engine.lastError
            runCatching { engine.close() }
            false
        }
    }

    private fun useGoogleLocked(): Boolean {
        val engine = googleEngine ?: return false
        active = engine
        engineName = engine.displayName
        lastError = null
        Timber.i("手写引擎：%s", engineName)
        return true
    }

    private fun loadLocalLocked(context: Context): Boolean {
        val engine = localEngine ?: OnnxEngine().also { localEngine = it }
        val ok = engine.isAvailable() || engine.load(context)
        if (ok) {
            active = engine
            engineName = engine.displayName
            lastError = null
            Timber.i("手写引擎：%s（字符数 %d）", engineName, engine.charCount)
        } else {
            lastError = engine.lastError ?: "本地手写引擎不可用"
            Timber.e("本地手写引擎加载失败：%s", lastError)
        }
        return ok
    }

    /**
     * 落不了 Google 时的处置：AUTO 静默降级到本地，显式 GOOGLE 则如实报不可用。
     *
     * 这个分叉是整个降级语义的核心 —— 也正因为它是「两种模式两种行为」，
     * 才必须写在一处并配上注释，否则很容易被改成「一律降级」而失去用户选择的语义。
     */
    private fun fallbackOrFailLocked(
        context: Context,
        mode: HwEngineMode,
        reason: String?,
    ): Boolean {
        googleUnavailableReason = reason
        if (mode == HwEngineMode.AUTO) {
            Timber.i("Google 手写不可用（%s），静默降级到本地引擎", reason)
            return loadLocalLocked(context)
        }
        lastError = reason ?: "Google 手写引擎不可用"
        active = null
        engineName = null
        return false
    }
}
