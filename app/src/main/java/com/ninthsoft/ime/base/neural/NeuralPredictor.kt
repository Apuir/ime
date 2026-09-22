package com.ninthsoft.ime.base.neural

import android.os.SystemClock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber

/**
 * 神经下一词预测（NWP）的对外入口。
 *
 * 三件事在这里汇合，都是调研里定死的硬约束：
 *
 * 1. **绝不阻塞按键**：调用方把它跑在后台协程里，本类内部再加一道 450 ms 硬超时；
 *    连续超时 5 次直接熔断，本次会话不再尝试（回落 n-gram）。
 * 2. **取消要能回滚**：native 的前向调用是不可中断的（C 函数，没有取消点），
 *    所以「取消」只能是**丢弃结果 + 把缓存标记为不可信**，下次前向从头来。
 *    这一点不能靠猜，必须显式处理，否则下一次前向会带着被丢弃候选的残留。
 * 3. **增量优先**：缓存按「已预填的文本」记账，本次上下文是上次的延长时只补新增的字；
 *    上下文变了就回滚到公共前缀再补。这条把 350 ms 压到十几毫秒。
 *
 * 另外：所有 native 交互都串行化在一把锁后面。native 侧只有一份 KV cache，
 * 并发调用会直接把缓存搅乱。
 *
 * 做成**进程内单例**不是偷懒：JNI 侧的会话与 KV cache 都是全局的（`g_session`），
 * 所以进程里只能有一个实例，否则第二个实例 `nativeLoad` 会把第一个的会话顶掉。
 */
object NeuralPredictor {

    private val mutex = Mutex()
    private val breaker = NeuralCircuitBreaker()

    private var session: Session? = null
    private var cachedText = ""
    private var cachedLogits: FloatArray? = null
    private var resetNeeded = false

    /**
     * 上一次前向的耗时（毫秒），`null` 表示**这一步没跑前向**（上下文没变、直接复用缓存）。
     *
     * 为什么单独记账：450 ms 是硬预算，而 logcat 的行时间戳只到毫秒、还混着别的日志，
     * 光看时间戳估不出真实延迟；量化选型（int8 vs fp16）也要求两类模型的数字能直接对拍。
     * 用可空而不是 0：0 毫秒是一次很快的前向，和「压根没算」是两件事。
     */
    private var lastPrefillMs: Long? = null

    @Volatile
    private var loadError: String? = null

    @Volatile
    private var description = ""

    /**
     * 是否已经尝试过加载。模型没装时每次预测都重新读清单、找文件是白费力气；
     * 但用户装好模型后必须能重新触发 —— 那是设置页「校验并加载」（[load] 传 true）的事。
     */
    @Volatile
    private var loadAttempted = false

    private class Session(
        val manifest: NeuralModelManifest,
        val vocabulary: WordVocabulary,
        val chars: CharVocabulary,
    ) {
        /**
         * 实际喂给模型的字符预算 = **模型容量**与**端侧预算**的较小值。
         *
         * 两个数说的是两件事，不能混：
         * - `manifest.contextTokens`：模型能接受多少输入 id（超出它的位置编码范围就没意义）；
         * - [NeuralPrompt.MAX_CHARS]：端侧愿意喂多少（≈210 汉字，对应设计里「3–4 句」），
         *   这个数决定延迟与 cache 体积。
         *
         * KV cache 也按这个值预分配：按模型容量分配会白占几十 MB，
         * 因为我们永远不会喂到那么多。
         */
        val promptBudget: Int = minOf(manifest.contextTokens, NeuralPrompt.MAX_CHARS)
    }

    val isReady: Boolean
        get() = session != null

    val isTripped: Boolean
        get() = breaker.isTripped

    val lastError: String?
        get() = loadError

    /** 本次会话连续超时的次数，用于设置页说明「为什么神经联想不出了」。 */
    val consecutiveTimeouts: Int
        get() = breaker.consecutiveTimeouts

    /** native 侧的模型结构摘要，用于设置页显示与排障。 */
    val modelDescription: String
        get() = description

    /**
     * 加载模型。重复调用是安全的（已加载则直接返回 true）。
     *
     * [forceVerify] 会强制重算 sha256 并忽略「已经试过且失败」的记录，
     * 用于「用户刚把模型放进来」或「手动换了模型文件」。
     */
    suspend fun load(forceVerify: Boolean = false): Boolean {
        val loaded = mutex.withLock {
            if (session != null && !forceVerify) return@withLock true
            // 已经失败过就不再自动重试，免得每次预测都白跑一遍校验
            if (!forceVerify && loadAttempted && loadError != null) return@withLock false
            loadAttempted = true
            releaseLocked()
            loadError = null

            if (!NeuralOnnxNative.isAvailable) {
                loadError = "native 库不可用（${NeuralOnnxNative.loadFailedReason ?: "未知原因"}）"
                return@withLock false
            }

            val manifest = NeuralModelStore.readManifest()
            if (manifest == null) {
                loadError = "模型清单缺失"
                return@withLock false
            }
            val problem = NeuralModelStore.verify(manifest, forceVerify)
            if (problem != null) {
                loadError = problem
                return@withLock false
            }

            val vocabulary: WordVocabulary
            val chars: CharVocabulary
            try {
                vocabulary = withContext(Dispatchers.IO) {
                    NeuralModelStore.fileFor(manifest.wordVocab)
                        .useLines { WordVocabulary.parse(it) }
                }
                chars = withContext(Dispatchers.IO) {
                    CharVocabulary.parse(
                        NeuralModelStore.fileFor(manifest.charVocab).readText()
                    )
                }
            } catch (error: Exception) {
                loadError = "词表读取失败：${error.message}"
                Timber.e(error, "神经模型词表读取失败")
                return@withLock false
            }

            val modelFile = NeuralModelStore.fileFor(manifest.model)
            val promptBudget = minOf(manifest.contextTokens, NeuralPrompt.MAX_CHARS)
            val ok = withContext(Dispatchers.IO) {
                NeuralOnnxNative.nativeLoad(modelFile.absolutePath, promptBudget, intraOpThreads())
            }
            if (!ok) {
                loadError = "建立 ONNX 会话失败：${NeuralOnnxNative.nativeLastError()}"
                return@withLock false
            }

            session = Session(manifest, vocabulary, chars)
            description = NeuralOnnxNative.nativeDescribe()
            breaker.reset()
            invalidateLocked()
            Timber.i("神经联想模型已加载：%s", description)
            true
        }
        return loaded
    }

    fun destroy() {
        kotlinx.coroutines.runBlocking {
            mutex.withLock {
                releaseLocked()
                // 引擎重启后要能重新加载，否则「失败过就不再重试」会一直挡着
                loadAttempted = false
            }
        }
    }

    /**
     * 预测下一个词。返回 null 表示这一轮没有神经信号
     * （模型没装 / 开关关掉 / 熔断 / 上下文不可用 / 超时），调用方应当继续用 n-gram。
     */
    suspend fun predict(committed: String, topK: Int = DEFAULT_TOP_K): NeuralPrediction? {
        val current = session ?: return null
        if (!breaker.shouldAttempt()) return null

        val prompt = NeuralPrompt.build(committed)
        if (!NeuralPrompt.isUsable(prompt)) return null

        return mutex.withLock {
            // 每次都重新取：锁等待期间模型可能被卸载了
            val active = session ?: return@withLock null
            if (active !== current) return@withLock null
            try {
                val result = withContext(Dispatchers.Default) {
                    withTimeoutOrNull(NeuralCircuitBreaker.TIMEOUT_MS) {
                        runLocked(active, prompt, topK)
                    }
                }
                if (result == null) {
                    invalidateLocked()
                    breaker.recordTimeout()
                    if (breaker.isTripped) {
                        Timber.w(
                            "神经联想连续超时 %d 次，本次会话停用，回落 n-gram",
                            breaker.consecutiveTimeouts,
                        )
                    } else {
                        Timber.w("神经联想超时（第 %d 次）", breaker.consecutiveTimeouts)
                    }
                    null
                } else {
                    breaker.recordSuccess()
                    result
                }
            } catch (error: CancellationException) {
                // 用户继续打字导致的取消：不算超时，但缓存要作废重来
                invalidateLocked()
                throw error
            } catch (error: Exception) {
                invalidateLocked()
                loadError = error.message
                Timber.e(error, "神经联想推理失败")
                null
            }
        }
    }

    private suspend fun runLocked(current: Session, prompt: String, topK: Int): NeuralPrediction {
        prefillLocked(current, prompt)

        val logits = cachedLogits
            ?: throw IllegalStateException("没有可用的 logits")
        if (logits.size != current.vocabulary.size) {
            throw IllegalStateException(
                "logits 长度 ${logits.size} 与词表大小 ${current.vocabulary.size} 不一致"
            )
        }

        val distribution = NeuralDistribution.of(logits)
        val indices = NeuralTopK.topK(logits, maxOf(topK, CANDIDATE_POOL))
        val candidates = ArrayList<NeuralCandidate>(topK)
        for (index in indices) {
            val word = current.vocabulary[index]
            if (word.isEmpty() || word == WordVocabulary.UNKNOWN_TOKEN) continue
            val probability = distribution.probability(logits[index])
            if (probability < MIN_PROBABILITY) continue
            candidates.add(NeuralCandidate(word, probability))
            if (candidates.size >= topK) break
        }

        if (Timber.treeCount > 0) {
            Timber.d(
                "神经预测 ms=%s input=%s top=%s",
                lastPrefillMs?.toString() ?: "复用",
                prompt,
                candidates.take(5).joinToString(" ") { "${it.word}(${"%.3f".format(it.probability)})" },
            )
        }

        return NeuralPrediction(candidates, logits, distribution, current.vocabulary)
    }

    /**
     * 把 [prompt] 送进模型，使缓存以它结尾，并刷新 logits。
     *
     * 缓存复用规则：算出能保留的公共前缀长度 `keep`。
     * - 公共前缀就是上次的全部 → `keep` = 上次长度，只补新增的字（增量，目标十几毫秒）；
     * - 中途分叉（用户回删、切换输入框）→ 回滚到分叉点再补。
     *
     * `keep` **必须严格小于**本次上下文长度：缓存里已有的 logits 是对应「上一次上下文」
     * 最后一个位置的，上下文一旦变化就必须重新前向才能得到正确的 logits。
     * 少了这一步，文本变短时会拿着旧上下文算出的分布去打分 —— 不报错，只是结果悄悄错掉。
     */
    private fun prefillLocked(current: Session, prompt: String) {
        if (resetNeeded) {
            NeuralOnnxNative.nativeReset()
            cachedText = ""
            cachedLogits = null
            resetNeeded = false
        }

        // 模型的位置编码预算是有限的：先按实际字符预算截尾
        val effective = takeLastCodePoints(prompt, current.promptBudget)

        if (effective == cachedText && cachedLogits != null) {
            lastPrefillMs = null // 上下文没变：这一步没跑前向，延迟里不该算它
            return
        }

        val startedAt = SystemClock.elapsedRealtimeNanos()

        val effectiveCodePoints = effective.codePointCount(0, effective.length)
        val cachedCodePoints = cachedText.codePointCount(0, cachedText.length)
        var keep = minOf(
            effective.codePointCount(
                0,
                floorToCodePointBoundary(effective, commonPrefixLength(cachedText, effective)),
            ),
            cachedCodePoints,
            effectiveCodePoints,
        )
        if (keep >= effectiveCodePoints) keep = effectiveCodePoints - 1
        if (keep < 0) keep = 0

        if (keep < cachedCodePoints && !NeuralOnnxNative.nativeTruncate(keep)) {
            // 回滚失败说明缓存状态已经不可信，退化成整段重算
            Timber.w("回滚 KV cache 失败，改为整段重算：%s", NeuralOnnxNative.nativeLastError())
            NeuralOnnxNative.nativeReset()
            keep = 0
        }

        cachedText = effective.substring(0, effective.offsetByCodePoints(0, keep))
        cachedLogits = null

        val suffix = effective.substring(cachedText.length)
        val ids = current.chars.encode(suffix)
        if (ids.isEmpty()) {
            // 有效上下文为空（整段都是模型不认识的字符）：没有可前向的内容
            invalidateLocked()
            return
        }
        if (!NeuralOnnxNative.nativePrefill(ids)) {
            val reason = NeuralOnnxNative.nativeLastError()
            invalidateLocked()
            throw IllegalStateException("前向失败：$reason")
        }
        cachedText = effective

        val logits = FloatArray(current.vocabulary.size)
        if (!NeuralOnnxNative.nativeLogits(logits)) {
            val reason = NeuralOnnxNative.nativeLastError()
            invalidateLocked()
            throw IllegalStateException("读取 logits 失败：$reason")
        }
        cachedLogits = logits
        // 计时把 nativePrefill + nativeLogits 都算进来：两者都在缓存未命中时才会跑
        lastPrefillMs = (SystemClock.elapsedRealtimeNanos() - startedAt) / 1_000_000
    }

    /** 缓存不再可信：下次前向从零开始。**调用前必须已持有 [mutex]。** */
    private fun invalidateLocked() {
        cachedText = ""
        cachedLogits = null
        resetNeeded = true
    }

    /** **调用前必须已持有 [mutex]。** */
    private fun releaseLocked() {
        if (session != null) {
            NeuralOnnxNative.nativeClose()
        }
        session = null
        invalidateLocked()
        description = ""
    }

    private fun intraOpThreads(): Int {
        val cores = Runtime.getRuntime().availableProcessors()
        // 上限 4：再多的线程在 big.LITTLE 上会溢出到小核，反而更慢更耗电
        return (cores / 2).coerceIn(1, 4)
    }

    private fun commonPrefixLength(a: String, b: String): Int {
        val limit = minOf(a.length, b.length)
        var index = 0
        while (index < limit && a[index] == b[index]) index++
        return index
    }

    /** 把位置向前退到码点边界：`String.length` 落在代理对中间时截断会产出半个字符。 */
    private fun floorToCodePointBoundary(text: String, index: Int): Int {
        if (index <= 0) return 0
        if (index >= text.length) return text.length
        return if (Character.isHighSurrogate(text[index - 1]) &&
            Character.isLowSurrogate(text[index])
        ) {
            index - 1
        } else {
            index
        }
    }

    private fun takeLastCodePoints(text: String, count: Int): String {
        if (count <= 0) return ""
        val total = text.codePointCount(0, text.length)
        if (total <= count) return text
        return text.substring(text.offsetByCodePoints(0, total - count))
    }

    // Kotlin 不允许 object 里再嵌 companion，所以这些常量直接挂在 object 上
    /** 默认给出的神经候选条数。 */
    const val DEFAULT_TOP_K = 12

    /** 先多取一些再按概率过滤，避免「top-k 里混进 <unk>」导致候选变少。 */
    private const val CANDIDATE_POOL = 32

    /**
     * 概率下限。低于它的词在当前上下文里基本是噪声，
     * 放出去只会把候选栏挤满（设计里明确不要「AI 候选栏」塞垃圾）。
     */
    private const val MIN_PROBABILITY = 0.002
}

/**
 * 一次神经前向的结果。
 *
 * 除了 top-k 候选本身，还带着**完整 logits 与词表**：候选重排要对引擎给出的词
 * 打分，而那些词大多不在 top-k 里 —— 用词表 id 直接查 logits 即可，
 * 不需要为每个候选再扫一遍词表。
 */
class NeuralPrediction internal constructor(
    val candidates: List<NeuralCandidate>,
    private val logits: FloatArray,
    private val distribution: NeuralDistribution,
    private val vocabulary: WordVocabulary,
) {
    /** 词不在输出词表里时返回 0：调用方据此认为「没有神经信号」，而不是「概率很小」。 */
    fun probabilityOf(word: String): Double {
        val id = vocabulary.idOf(word)
        if (id < 0 || id >= logits.size) return 0.0
        return distribution.probability(logits[id])
    }
}

data class NeuralCandidate(val word: String, val probability: Double)
