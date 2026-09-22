package com.ninthsoft.ime

import com.ninthsoft.ime.base.priority.CandidateFeature
import com.ninthsoft.ime.base.priority.PriorityCalculator
import com.ninthsoft.ime.base.priority.WeightConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 神经 / 短语两路信号融进既有打分的回归。
 *
 * 这组断言对应设计里两条硬约束，任何一条破了都会立刻体现在用户体感上：
 *
 * 1. **默认权重下必须与改动前逐位一致** —— 两个开关都关时，
 *    候选顺序不能有任何变化。做法是把新权重的默认值设成 0，
 *    这样「多出来的一项」对所有候选都加 0，排序与分数值都不变。
 * 2. **短语命中必须压过神经概率** —— 成语/诗句的匹配是确定性事实，
 *    神经网络给的是统计猜测。混用同一路特征就表达不了这个区别。
 */
class CandidateFusionTest {

    private val calculator = PriorityCalculator()

    private fun score(
        feature: CandidateFeature,
        weights: WeightConfig = WeightConfig(),
    ): Double = calculator.calculate(feature, weights)

    private fun feature(
        baseScore: Double = 0.0,
        preference: Double = 0.0,
        wordLength: Int = 2,
        rank: Int = 0,
        rankSpan: Int = 10,
        neural: Double = 0.0,
        phrase: Double = 0.0,
    ) = CandidateFeature(baseScore, preference, wordLength, rank, rankSpan, neural, phrase)

    /** 最重要的一条：新信号在默认权重下**完全不参与**，关掉开关即回到原行为。 */
    @Test
    fun defaultWeightsIgnoreTheNewSignals() {
        val plain = feature(baseScore = 1.5, preference = 1.0, rank = 3, wordLength = 2)
        val withSignals = plain.copy(neural = 0.9, phrase = 1.0)
        assertEquals(score(plain), score(withSignals), 1e-12)
    }

    /**
     * 更强的一条：即使把神经权重打开，只要没有候选带神经分数，
     * 任意两个候选之间的**分差**就不变 —— 顺序不可能被挪动。
     */
    @Test
    fun enablingNeuralWeightKeepsOrderWhenNoCandidateHasAScore() {
        val off = WeightConfig()
        val on = WeightConfig(neural = PriorityCalculator.NEURAL_WEIGHT)
        val strong = feature(baseScore = 0.5, preference = 1.0, rank = 0)
        val weak = feature(baseScore = 0.1, preference = 0.0, rank = 4)

        assertTrue(score(strong, off) > score(weak, off))
        assertTrue(score(strong, on) > score(weak, on))
        assertEquals(
            "没有神经信号时这一项对所有人都是 0，分差必须原样保留",
            score(strong, off) - score(weak, off),
            score(strong, on) - score(weak, on),
            1e-12,
        )
    }

    @Test
    fun neuralProbabilityRaisesTheScore() {
        val weights = WeightConfig(neural = PriorityCalculator.NEURAL_WEIGHT)
        val without = feature(baseScore = 0.0)
        val with = feature(baseScore = 0.0, neural = 0.5)
        assertTrue(score(with, weights) > score(without, weights))
        assertEquals(
            "高置信预测应当贡献权重 × 概率",
            0.5 * PriorityCalculator.NEURAL_WEIGHT,
            score(with, weights) - score(without, weights),
            1e-12,
        )
    }

    /** 短语是确定性匹配：即使神经模型给出 100% 概率，短语候选也必须排前面。 */
    @Test
    fun phraseMatchOutweighsMaximumNeuralConfidence() {
        val weights = WeightConfig(
            neural = PriorityCalculator.NEURAL_WEIGHT,
            phrase = PriorityCalculator.PHRASE_WEIGHT,
        )
        val confidentNeural = feature(neural = 1.0)
        val phraseHit = feature(phrase = 1.0)
        assertTrue(
            "短语命中(${score(phraseHit, weights)}) 必须高于满概率神经候选(${score(confidentNeural, weights)})",
            score(phraseHit, weights) > score(confidentNeural, weights),
        )
    }

    /** 两路信号可以叠加：既是短语命中又被神经模型预测，分数应更高。 */
    @Test
    fun signalsAreAdditive() {
        val weights = WeightConfig(
            neural = PriorityCalculator.NEURAL_WEIGHT,
            phrase = PriorityCalculator.PHRASE_WEIGHT,
        )
        val onlyPhrase = feature(phrase = 1.0)
        val both = feature(neural = 0.5, phrase = 1.0)
        assertTrue(score(both, weights) > score(onlyPhrase, weights))
    }

    /** 越界与 NaN 只能被夹紧，不能让打分变成 NaN 把整个列表搅乱。 */
    @Test
    fun outOfRangeSignalsAreClamped() {
        val weights = WeightConfig(
            neural = PriorityCalculator.NEURAL_WEIGHT,
            phrase = PriorityCalculator.PHRASE_WEIGHT,
        )
        val atLimit = feature(neural = 1.0, phrase = 1.0)
        assertEquals(
            "超过 1 的信号必须夹到 1",
            score(atLimit, weights),
            score(feature(neural = 5.0, phrase = 9.0), weights),
            1e-12,
        )
        // 与「信号为 0」的同一个候选逐位比较，避免把其它特征的贡献也写进期望值
        val noSignal = feature()
        assertEquals(
            "NaN 必须当成没有信号",
            score(noSignal, weights),
            score(feature(neural = Double.NaN), weights),
            1e-12,
        )
        assertEquals(
            "负值必须当成没有信号",
            score(noSignal, weights),
            score(feature(neural = -3.0, phrase = -1.0), weights),
            1e-12,
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun negativeWeightsAreRejected() {
        WeightConfig(neural = -0.1)
    }

    @Test(expected = IllegalArgumentException::class)
    fun negativePhraseWeightIsRejected() {
        WeightConfig(phrase = -0.1)
    }

    /** 权重全为 0 是没有意义的配置，构造时就该拦下来。 */
    @Test(expected = IllegalArgumentException::class)
    fun allZeroWeightsAreRejected() {
        WeightConfig(
            baseScore = 0.0, preference = 0.0, rankPrior = 0.0, wordLength = 0.0,
            neural = 0.0, phrase = 0.0,
        )
    }
}
