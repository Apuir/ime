package com.ninthsoft.ime.engine.manager

import android.content.Context
import com.ninthsoft.ime.base.ngram.GramDb
import com.ninthsoft.ime.base.priority.CandidateFeature
import com.ninthsoft.ime.base.priority.PreferenceScorer
import com.ninthsoft.ime.base.priority.PriorityCalculator
import com.ninthsoft.ime.base.priority.WeightConfig
import com.ninthsoft.ime.data.database.AppDatabase
import com.ninthsoft.ime.engine.data.EngineMessage.Candidate
import timber.log.Timber

/**
 * 候选重排：在**引擎给出的顺序之上**做有依据的调整。
 *
 * 设计取向（改造后的重点）：
 *
 * 1. **第 0 个候选不动**。它是引擎的首选，也是「按空格上屏」的目标；让它随
 *    用户统计漂移会让肌肉记忆失效。误选到第 0 个的情况交给引擎侧的原生机制
 *    （万象 `enable_fallback_reorder`：「同码回删再输首次交换」）处理。
 * 2. **保留引擎的位次信息**（[CandidateFeature.rank]），它是一条独立信号，
 *    不能被词长或点击统计整段盖掉。
 * 3. **语法模型真的接进来**（[gramDb]）—— 改造前调用方恒传 `null`，
 *    导致权重最大的 `baseScore` 项永远为 0。
 * 4. 用户偏好可正可负（[PreferenceScorer]），支持「误选后降权」。
 */
class CandidateRerankManager(private val context: Context) {
    private val calculator = PriorityCalculator()
    private val weights = WeightConfig()

    suspend fun rerank(
        candidates: List<Candidate>,
        inputContext: String,
        gramDb: GramDb?,
        now: Long = System.currentTimeMillis(),
    ): List<Candidate> {
        val start = FIRST_EDITABLE_INDEX
        val end = minOf(RERANK_WINDOW_END, candidates.size)
        if (end - start <= 1) return candidates

        val window = candidates.subList(start, end)
        val prefers = AppDatabase.getInstance(context).candidatePreferDao()
            .getAllByTextIn(window.map { it.text })
            .associateBy { it.text }

        val span = end - start
        val scored = window.mapIndexed { offset, candidate ->
            val prefer = prefers[candidate.text]
            val feature = CandidateFeature(
                baseScore = if (inputContext.isEmpty()) {
                    0.0
                } else {
                    gramDb?.query(inputContext, candidate.text) ?: 0.0
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
                wordLength = candidate.text.codePointCount(0, candidate.text.length),
                rank = offset,
                rankSpan = span,
            )
            candidate.copy(score = calculator.calculate(feature, weights))
        }

        // 同分时按引擎原始位次兜底，结果是确定的（不依赖排序算法的稳定性）
        val ordered = scored.sortedWith(
            compareByDescending<Candidate> { it.score }.thenBy { it.index }
        )

        if (Timber.treeCount > 0) {
            Timber.d(
                "rerank: gram=%s input=%s top=%s",
                if (gramDb == null) "none" else "on",
                inputContext,
                ordered.take(3).joinToString(" ") { "${it.text}(${it.index})" },
            )
        }

        return buildList(candidates.size) {
            add(candidates[0])
            addAll(ordered)
            for (index in end until candidates.size) {
                add(candidates[index])
            }
        }
    }

    companion object {
        /** 从第 1 个开始参与重排；第 0 个固定。 */
        const val FIRST_EDITABLE_INDEX = 1

        /** 重排窗口上界（不含）。第 25 个之后保持引擎顺序。 */
        private const val RERANK_WINDOW_END = 25
    }
}
