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

    /**
     * 神经下一词模型给这个候选的概率，取值 `[0, 1]`；**0 表示没有神经信号**
     * （模型没装 / 开关关掉 / 该词不在输出词表里）。
     *
     * 之所以用概率而不是原始 logit：概率天然有界、跨上下文可比，
     * 不会像 logit 那样随上下文整体漂移，融进这套加权求和时不需要额外标定。
     */
    val neural: Double = 0.0,

    /**
     * 短语索引的精确命中强度，取值 `[0, 1]`；0 表示不是短语补全来的候选。
     *
     * 与 [neural] 分开是因为**两者性质不同**：神经概率是「像不像下一个词」的统计判断，
     * 短语命中是「上下文尾部就是这条成语/诗句的上句」的确定性匹配（100% 准确）。
     * 后者必须能稳定压过前者，混用同一个字段就表达不了这个区别。
     */
    val phrase: Double = 0.0,
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

    /**
     * 神经联想的权重。**默认 0**：关掉开关时必须与改动前的排序逐位一致，
     * 所以「有神经模型参与」这件事只能由调用方显式打开，不能是默认姿态。
     */
    val neural: Double = 0.0,

    /** 短语索引命中权重。默认 0，理由同 [neural]。 */
    val phrase: Double = 0.0,
) {
    init {
        require(baseScore >= 0.0)
        require(preference >= 0.0)
        require(rankPrior >= 0.0)
        require(wordLength >= 0.0)
        require(neural >= 0.0)
        require(phrase >= 0.0)
        require(baseScore + preference + rankPrior + wordLength + neural + phrase > 0.0)
    }
}

class PriorityCalculator {
    companion object {
        private const val WORD_LENGTH_SCALE = 4.0

        /** 线性归一的刻度：净偏好达到 ±[PREFERENCE_SCALE] 时打满 ±1。 */
        const val PREFERENCE_SCALE = 3.0

        /**
         * 神经概率的权重刻度。
         *
         * 直觉标定（不是调参结果）：重排窗口 24 项时一个位次步长
         * `0.40 / 24 ≈ 0.017`，而 0.35 的权重让一次高置信预测
         * （`p ≈ 0.3` → 贡献 `0.105`）大约相当于 6 个位次 ——
         * 足以把明显该出现的词顶上来，但顶不过用户的强偏好（权重 0.30）。
         */
        const val NEURAL_WEIGHT = 0.35

        /**
         * 短语命中的权重。
         *
         * 取 1.0 是有意的**压倒性**取值：其余每一项归一化后最多贡献 1.0 × 自己的权重
         * （合计不到 1.0），所以一次成语/诗句/歇后语的确定性命中必然排在统计预测之前。
         * 这就是「别把成语诗句交给神经网络」在打分上的体现 —— 那一路本来就该无条件优先，
         * 它错不了（上下文尾部确实等于那条上句）。
         */
        const val PHRASE_WEIGHT = 1.0
    }

    fun calculate(candidate: CandidateFeature, weights: WeightConfig = WeightConfig()): Double {
        val base = normalizeBaseScore(candidate.baseScore)
        val preference = normalizePreference(candidate.preference)
        val rank = normalizeRank(candidate.rank, candidate.rankSpan)
        val length = normalizeLength(candidate.wordLength, WORD_LENGTH_SCALE)
        val neural = normalizeNeural(candidate.neural)
        val phrase = normalizeNeural(candidate.phrase)
        return base * weights.baseScore +
            preference * weights.preference +
            rank * weights.rankPrior +
            length * weights.wordLength +
            neural * weights.neural +
            phrase * weights.phrase
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

    /**
     * 神经概率已经是 `[0, 1]`，线性使用即可。
     *
     * 不用对数压缩：概率小并不等于「不可能」，取对数会把 `0.001` 和 `0.0001`
     * 的差距放大成一条陡峭的斜坡，量化误差会被一并放大。夹紧只是防御
     * 训练侧的数值事故（NaN / 越界）传进来。
     */
    private fun normalizeNeural(value: Double): Double {
        if (value.isNaN() || value <= 0.0) return 0.0
        return value.coerceAtMost(1.0)
    }
}
