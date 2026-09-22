package com.ninthsoft.ime.base.neural

import kotlin.math.exp

/**
 * 词表上的 softmax 分布（只保留算概率所需的两项，不落地整个概率数组）。
 *
 * 一次前向的 logits 是 3 万个数（≈120 KB）。要把它当「候选得分」用，
 * 关心两个量就够：**最大 logit**（数值稳定用的平移量）与 **exp 之和**（归一化分母）。
 * 这样任意一个候选（包括引擎给出、而神经模型没排进 top-k 的词）都能用词表 id
 * 直接取到概率，不需要为每个候选扫一遍词表。
 *
 * 平移用的是 `exp(l - max)`：logits 量级在 ±30，直接 exp 会溢出到 inf。
 */
class NeuralDistribution private constructor(
    val maxLogit: Float,
    private val expSum: Double,
) {
    /** 该 logit 对应的 softmax 概率。退化输入（空数组 / 全 -inf）一致返回 0。 */
    fun probability(logit: Float): Double {
        if (logit.isNaN() || logit == Float.NEGATIVE_INFINITY) return 0.0
        if (expSum <= 0.0 || !expSum.isFinite()) return 0.0
        return (exp((logit - maxLogit).toDouble()) / expSum).coerceIn(0.0, 1.0)
    }

    companion object {
        fun of(logits: FloatArray): NeuralDistribution {
            var max = Float.NEGATIVE_INFINITY
            for (value in logits) {
                if (!value.isNaN() && value > max) max = value
            }
            // 没有有限值：expSum 留 0，probability() 会走退化分支
            if (max == Float.NEGATIVE_INFINITY) return NeuralDistribution(0f, 0.0)

            var sum = 0.0
            for (value in logits) {
                if (value.isNaN() || value == Float.NEGATIVE_INFINITY) continue
                sum += exp((value - max).toDouble())
            }
            return NeuralDistribution(max, sum)
        }
    }
}
