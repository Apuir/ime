package com.ninthsoft.ime.engine.manager

import android.content.Context
import com.ninthsoft.ime.base.marisa.Prediction
import com.ninthsoft.ime.base.neural.NeuralPredictor
import com.ninthsoft.ime.base.ngram.GramDb
import com.ninthsoft.ime.base.phrase.PhraseIndex
import com.ninthsoft.ime.base.phrase.PhraseIndexStore
import com.ninthsoft.ime.base.priority.CandidateFeature
import com.ninthsoft.ime.base.priority.PreferenceScorer
import com.ninthsoft.ime.base.priority.PriorityCalculator
import com.ninthsoft.ime.base.priority.WeightConfig
import com.ninthsoft.ime.base.util.TextUtil
import com.ninthsoft.ime.data.database.AppDatabase
import com.ninthsoft.ime.data.manager.CandidateManager
import com.ninthsoft.ime.engine.data.EngineMessage.Candidate
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import timber.log.Timber
import java.io.File

/**
 * 「下一个词」的候选来源汇总。
 *
 * 现在这里有**三路信号**，性质完全不同，别把它们当成一件事：
 *
 * | 来源 | 依据 | 成本 |
 * |---|---|---|
 * | n-gram（`predict.marisa`） | 词级共现统计 | 微秒级，纯查表 |
 * | 短语索引（`phrase_index.tsv.gz`） | 上下文尾部 == 成语/上句，**精确匹配** | 微秒级，零功耗 |
 * | 神经 NWP（ONNX） | 一整段上下文的统计预测 | 十几到几百毫秒，必须异步 |
 *
 * 三路的分工是调研定死的：成语/诗句交给前缀匹配（100% 准确，让模型去生成只会更差），
 * 神经模型负责「n-gram 吃不到的长上下文」，n-gram 继续当兜底。
 *
 * **排序仍然只有一套**：都走 [PriorityCalculator] 的加权求和，只是给神经与短语
 * 各加了一路特征（见 [WeightConfig.neural] / [WeightConfig.phrase]）。
 * 不另开「AI 候选栏」—— 那是设计里明确排除的做法。
 *
 * 两个开关都关时，[makePredictions] 走 [legacyPredictions] 原样返回，
 * 与引入本功能之前逐位一致。
 */
class PredictionManager(private val context: Context) {
    private var prediction: Prediction? = null

    /**
     * 语法模型（`.gram` / octagram）。
     *
     * 对 [CandidateRerankManager] 暴露只读访问：候选重排要用同一份模型，
     * 避免两个组件各持一份 mmap。改造前重排的 `gramDb` 恒传 `null`，
     * 语法模型白加载了。跨线程访问（加载在 rime-main，读取在默认调度器），
     * 所以标 `@Volatile`。
     */
    @Volatile
    var gramDb: GramDb? = null
        private set

    @Volatile
    private var phraseIndex: PhraseIndex? = null

    private val calculator = PriorityCalculator()
    private val mutex = Mutex()

    suspend fun loadModels(modelDir: File, sharedDataDir: File, language: String?) =
        mutex.withLock {
            destroyLocked()

            //预测模型
            val predictGram = File(modelDir, "predict.marisa")
            if (predictGram.isFile) {
                prediction = Prediction(predictGram).apply { load() }
            }

            // 短语索引是资产、与输入方案无关，所以放在 language 判断之前
            phraseIndex = PhraseIndexStore.load(context)

            if (language.isNullOrEmpty()) {
                return@withLock
            }
            val gram = File(sharedDataDir, "$language.gram")
            if (gram.isFile) {
                gramDb = GramDb(gram.absolutePath)
            }
        }

    fun destroy() {
        // 同步锁保护销毁过程
        kotlinx.coroutines.runBlocking {
            mutex.withLock {
                destroyLocked()
            }
        }
    }

    // 内部私有的安全销毁方法
    private fun destroyLocked() {
        prediction?.destroy()
        prediction = null
        gramDb = null
        phraseIndex = null
        NeuralPredictor.destroy()
    }

    suspend fun makePredictions(inputContext: String): List<Candidate> {
        val neuralEnabled = CandidateManager.isNeuralPredictionEnabled(context)
        val phraseEnabled = CandidateManager.isPhraseCompletionEnabled(context)
        if (!neuralEnabled && !phraseEnabled) {
            return legacyPredictions(inputContext)
        }
        return mergedPredictions(inputContext, neuralEnabled, phraseEnabled)
    }

    /** 引入神经/短语之前的原始实现。**不要改**，它是「两个开关都关」时的行为基线。 */
    private suspend fun legacyPredictions(inputContext: String): List<Candidate> {
        val pred = prediction ?: return emptyList()
        val possiables = TextUtil.contextSubstrings(inputContext)
        val cfg = WeightConfig()

        for (contextStr in possiables) {
            if (contextStr.isEmpty()) continue
            val words = pred.predictNextWords(contextStr)
            if (words.size >= 5) {
                val texts = words.map { it.word }
                val prefers =
                    AppDatabase.getInstance(context).candidatePreferDao().getAllByTextIn(texts)
                        .associate { it.text to it.count }

                val candidates = words.mapIndexed { index, it ->
                    val gramScore = gramDb?.query(inputContext, it.word) ?: 0.0
                    val preferCount = prefers[it.word] ?: 0
                    val textLen = it.word.codePointCount(0, it.word.length)
                    val score = calculator.calculate(
                        CandidateFeature(
                            baseScore = gramScore,
                            preference = preferCount.toDouble(),
                            wordLength = textLen,
                            rank = index,
                            rankSpan = words.size,
                        ), cfg
                    )
                    Candidate(
                        index = index,
                        text = it.word,
                        type = Candidate.TYPE_IME_PREDICTION,
                        score = score
                    )
                }.sortedByDescending { it.score }

                return candidates.take(MAX_RESULTS)
            }
        }
        return emptyList()
    }

    /**
     * 三路候选合并。
     *
     * 合并是**按文本去重后仍保留各路信号**的：同一个词既被神经模型预测、又命中短语索引时，
     * 两个特征都会带上，而不是先到先得地丢掉一路。
     *
     * 顺序上 `rankPrior` 归零：这份列表是 app 自己合成的，`rank` 没有「引擎位次」的含义
     * （位次先验是给 [CandidateRerankManager] 保留引擎排序用的）。同分时靠稳定排序
     * 维持「短语 → 神经 → n-gram」的来源次序。
     */
    private suspend fun mergedPredictions(
        inputContext: String,
        neuralEnabled: Boolean,
        phraseEnabled: Boolean,
    ): List<Candidate> {
        val pool = LinkedHashMap<String, Signals>()

        if (phraseEnabled) {
            val suggestions = phraseIndex?.query(inputContext).orEmpty()
            for (suggestion in suggestions) {
                pool.getOrPut(suggestion.value) { Signals(suggestion.value) }.phrase = 1.0
            }
        }

        if (neuralEnabled) {
            if (!NeuralPredictor.isReady) NeuralPredictor.load()
            val prediction = NeuralPredictor.predict(inputContext)
            if (prediction != null) {
                for (candidate in prediction.candidates) {
                    pool.getOrPut(candidate.word) { Signals(candidate.word) }.neural =
                        candidate.probability
                }
            }
        }

        // n-gram 排在最后：它最便宜、覆盖面最广，前两路没有结论时它来兜底
        for (candidate in legacyPredictions(inputContext)) {
            pool.getOrPut(candidate.text) { Signals(candidate.text) }
        }

        if (pool.isEmpty()) return emptyList()

        val weights = WeightConfig(
            baseScore = BASE_SCORE_WEIGHT,
            preference = PREFERENCE_WEIGHT,
            rankPrior = 0.0,
            wordLength = WORD_LENGTH_WEIGHT,
            neural = if (neuralEnabled) PriorityCalculator.NEURAL_WEIGHT else 0.0,
            phrase = if (phraseEnabled) PriorityCalculator.PHRASE_WEIGHT else 0.0,
        )

        val entries = pool.values.toList()
        val prefers = AppDatabase.getInstance(context).candidatePreferDao()
            .getAllByTextIn(entries.map { it.text })
            .associateBy { it.text }
        val now = System.currentTimeMillis()
        val span = entries.size

        val scored = entries.mapIndexed { index, signals ->
            val prefer = prefers[signals.text]
            Candidate(
                index = index,
                text = signals.text,
                type = Candidate.TYPE_IME_PREDICTION,
                score = calculator.calculate(
                    CandidateFeature(
                        baseScore = if (inputContext.isEmpty()) {
                            0.0
                        } else {
                            gramDb?.query(inputContext, signals.text) ?: 0.0
                        },
                        preference = if (prefer == null) {
                            0.0
                        } else {
                            PreferenceScorer.score(
                                clickCount = prefer.count,
                                lastGoodAt = prefer.updatedAt,
                                badCount = prefer.badCount,
                                lastBadAt = prefer.lastBadAt,
                                now = now,
                            )
                        },
                        wordLength = signals.text.codePointCount(0, signals.text.length),
                        rank = index,
                        rankSpan = span,
                        neural = signals.neural,
                        phrase = signals.phrase,
                    ),
                    weights,
                ),
            )
        }.sortedByDescending { it.score }

        if (Timber.treeCount > 0) {
            Timber.d(
                "prediction-merged input=%s phrase=%d neural=%d ngram=%d top=%s",
                inputContext,
                entries.count { it.phrase > 0.0 },
                entries.count { it.neural > 0.0 },
                entries.count { it.phrase <= 0.0 && it.neural <= 0.0 },
                scored.take(5).joinToString(" ") { it.text },
            )
        }

        return scored.take(MAX_RESULTS)
    }

    /** 合并阶段每个词累积的信号，值都是「有就记上」，不互相覆盖。 */
    private class Signals(val text: String) {
        var neural = 0.0
        var phrase = 0.0
    }

    companion object {
        private const val MAX_RESULTS = 25

        // 与 CandidateRerankManager 的默认权重保持一致，只是把 rankPrior 让给了新特征
        private const val BASE_SCORE_WEIGHT = 0.20
        private const val PREFERENCE_WEIGHT = 0.30
        private const val WORD_LENGTH_WEIGHT = 0.10
    }
}
