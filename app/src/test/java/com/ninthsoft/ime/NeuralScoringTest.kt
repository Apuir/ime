package com.ninthsoft.ime

import com.ninthsoft.ime.base.neural.NeuralDistribution
import com.ninthsoft.ime.base.neural.NeuralTopK
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * logits → 概率 / top-k 的纯逻辑回归。
 *
 * 把 top-k 放在 Kotlin 而不是 ONNX 图里是设计决定（图里加 TopK/ArgMax 会让导出复杂化），
 * 所以这段数学必须自己保证正确。两个容易出错的地方：
 *
 * 1. **数值稳定**：logits 量级 ±30，直接 `exp` 会溢出成 inf，必须减去最大值；
 * 2. **非有限值**：动态范围 int8 在极端输入下会产出 NaN/-inf，
 *    把它们当候选会让概率变成 NaN，进而把整个候选列表的排序搞坏。
 */
class NeuralScoringTest {

    @Test
    fun probabilitiesSumToOne() {
        val logits = floatArrayOf(1.0f, 2.0f, 3.0f, 0.5f)
        val distribution = NeuralDistribution.of(logits)
        val sum = logits.sumOf { distribution.probability(it) }
        assertEquals(1.0, sum, 1e-9)
    }

    @Test
    fun higherLogitGetsHigherProbability() {
        val logits = floatArrayOf(-2.0f, 0.0f, 5.0f)
        val distribution = NeuralDistribution.of(logits)
        assertTrue(distribution.probability(5.0f) > distribution.probability(0.0f))
        assertTrue(distribution.probability(0.0f) > distribution.probability(-2.0f))
    }

    /** 大 logits 不能溢出：`exp(1000)` 是 inf，减去最大值之后才是安全的。 */
    @Test
    fun largeLogitsDoNotOverflow() {
        val logits = floatArrayOf(1000.0f, 1001.0f, 999.0f)
        val distribution = NeuralDistribution.of(logits)
        val sum = logits.sumOf { distribution.probability(it) }
        assertEquals(1.0, sum, 1e-9)
        assertTrue(distribution.probability(1001.0f) > 0.0)
    }

    @Test
    fun degenerateInputsGiveZeroProbability() {
        assertEquals(0.0, NeuralDistribution.of(FloatArray(0)).probability(1.0f), 1e-12)
        val allInfinite = NeuralDistribution.of(
            floatArrayOf(Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY)
        )
        assertEquals(0.0, allInfinite.probability(Float.NEGATIVE_INFINITY), 1e-12)
        // NaN 不能被当成一个大 logit 参与归一化
        val withNaN = NeuralDistribution.of(floatArrayOf(Float.NaN, 0.0f))
        assertEquals(1.0, withNaN.probability(0.0f), 1e-9)
        assertEquals(0.0, withNaN.probability(Float.NaN), 1e-12)
    }

    @Test
    fun topKReturnsIndicesInDescendingOrder() {
        val top = NeuralTopK.topK(floatArrayOf(0.1f, 5.0f, -1.0f, 3.0f), 2)
        assertEquals(2, top.size)
        assertEquals(1, top[0])
        assertEquals(3, top[1])
    }

    @Test
    fun topKClampsToVocabularySize() {
        val top = NeuralTopK.topK(floatArrayOf(1.0f, 2.0f), 10)
        assertEquals(2, top.size)
        assertEquals(1, top[0])
    }

    @Test
    fun topKHandlesDegenerateArguments() {
        assertEquals(0, NeuralTopK.topK(floatArrayOf(1.0f), 0).size)
        assertEquals(0, NeuralTopK.topK(floatArrayOf(1.0f), -3).size)
        assertEquals(0, NeuralTopK.topK(FloatArray(0), 5).size)
    }

    /** 非有限值必须被跳过，否则它们会占掉候选位。 */
    @Test
    fun topKIgnoresNonFiniteLogits() {
        val logits = floatArrayOf(Float.NaN, 1.0f, Float.NEGATIVE_INFINITY, 2.0f)
        val top = NeuralTopK.topK(logits, 4)
        assertEquals(2, top.size)
        assertEquals(3, top[0])
        assertEquals(1, top[1])
    }

    /** 同分时保留词表里更靠前的下标：结果必须确定，不能依赖排序实现。 */
    @Test
    fun topKIsStableForTies() {
        val top = NeuralTopK.topK(floatArrayOf(2.0f, 2.0f, 2.0f), 3)
        assertEquals(0, top[0])
        assertEquals(1, top[1])
        assertEquals(2, top[2])
    }

    /** 有界插入的实现容易在「刚好填满」的边界上错位，这里专门压一下。 */
    @Test
    fun topKIsCorrectAcrossInsertPositions() {
        val logits = FloatArray(64) { it.toFloat() }
        val top = NeuralTopK.topK(logits, 5)
        assertEquals(5, top.size)
        for (index in top.indices) {
            assertEquals("应由大到小", 63 - index, top[index])
        }
    }
}
