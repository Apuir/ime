package com.ninthsoft.ime.base.priority

import kotlin.math.ln1p
import kotlin.math.tanh

/**
 * 参与候选打分的一组特征。
 *
 * 打分要回答的问题是：**「同一批候选中，哪个应该排前面」**。
 * 所以这里每个字段都必须在候选之间**有区分度** —— 对所有候选都取同一个值的特征
 * （例如「这一批候选总共有多少个」）不该出现在这里：那只是给每一项加同一个常数，
 * 不影响排序。改造前那个恒传 0 的 `candidateCount` 就属于这一类，已移除。
 */
data class CandidateFeature(
    /** 语法模型（`.gram` / octagram）给出的得分，0 表示没有语法分。 */
    val baseScore: Double = 0.0,

    /**
     * 净用户偏好：**正** = 用户常选它，**负** = 用户选过之后又删掉（误选）。
     * 取值由 [PreferenceScorer] 按点击次数与时间衰减算出，量纲大致是「有效次数」。
     */
    val preference: Double = 0.0,

    /**
     * 候选文本的码点数（汉字数）。
     *
     * **只有「联想」下一词该传它**：下一个词本来就有长短之别。候选重排**不再传** ——
     * 展示顺序里的字长排序由 `CandidateGrouping` 显式负责（4 字 → 5 字以上 → 3 字 →
     * 2 字 → 1 字），分组之后重排看到的候选同组同长，词长在组内是常量，传了也不会
     * 改变任何一对候选的相对次序。
     */
    val wordLength: Int = 0,

    /** 候选在引擎原始列表里的位次（0 = 首选）。 */
    val rank: Int = 0,

    /** 参与位次归一化的跨度（一般取被重排的候选个数）。 */
    val rankSpan: Int = 1,
)

/**
 * 打分权重。
 *
 * 默认值的取向：**以引擎自己的排序为主**（[rankPrior] 最大），用户偏好可以明显
 * 撼动它（[preference]），语法模型与词长只做微调。
 *
 * 这条取向是踩过坑才定的：改造前 `baseScore` 权重 0.5 却恒传 0、`preference` 用
 * 裸点击数的对数，结果是「引擎排序被整段打乱」，而且用户很容易被自己误选的词永久顶住。
 */
data class WeightConfig(
    val baseScore: Double = 0.20,
    val preference: Double = 0.30,
    val rankPrior: Double = 0.40,
    val wordLength: Double = 0.10,
) {
    init {
        require(baseScore >= 0.0)
        require(preference >= 0.0)
        require(rankPrior >= 0.0)
        require(wordLength >= 0.0)
        require(baseScore + preference + rankPrior + wordLength > 0.0)
    }
}

class PriorityCalculator {
    companion object {
        private const val WORD_LENGTH_SCALE = 4.0

        /** 线性归一的刻度：净偏好达到 ±[PREFERENCE_SCALE] 时打满 ±1。 */
        const val PREFERENCE_SCALE = 3.0
    }

    fun calculate(candidate: CandidateFeature, weights: WeightConfig = WeightConfig()): Double {
        val base = normalizeBaseScore(candidate.baseScore)
        val preference = normalizePreference(candidate.preference)
        val rank = normalizeRank(candidate.rank, candidate.rankSpan)
        val length = normalizeLength(candidate.wordLength, WORD_LENGTH_SCALE)
        return base * weights.baseScore +
            preference * weights.preference +
            rank * weights.rankPrior +
            length * weights.wordLength
    }

    private fun normalizeBaseScore(value: Double): Double {
        if (value <= 0.0) return 0.0
        return ln1p(value)
    }

    /**
     * 把「有效点击次数」压到 `[-1, 1]`。
     *
     * 用**线性**而不是对数：降权（负值）必须立刻见效 —— 用户误选一次、删掉再打，
     * 就该看到那个词往下掉；对数会让头几次惩罚几乎看不出来。
     */
    private fun normalizePreference(value: Double): Double {
        if (value == 0.0) return 0.0
        return (value / PREFERENCE_SCALE).coerceIn(-1.0, 1.0)
    }

    /** 位次越靠前越接近 1；[rankSpan] 为 0 时视为 1，避免除零。 */
    private fun normalizeRank(rank: Int, rankSpan: Int): Double {
        val span = if (rankSpan > 0) rankSpan else 1
        val clamped = rank.coerceIn(0, span)
        return (span - clamped).toDouble() / span
    }

    private fun normalizeLength(value: Int, scale: Double): Double {
        if (value <= 0) return 0.0
        return tanh(value / scale)
    }
}
