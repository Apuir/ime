package com.ninthsoft.ime

import com.ninthsoft.ime.input.handwriting.ochwpro.OchwproDecode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [OchwproDecode] 的回归测试。
 *
 * 这里的边界（k 大于类别数、含 NaN/-Inf、极值）在真机上只会表现为
 * 「候选莫名其妙」或「一个字都出不来」，从现象很难倒推原因。
 */
class OchwproDecodeTest {

    @Test
    fun `topK 按下标返回概率最高的前 k 个`() {
        // 下标 2 最大、下标 0 次之、下标 1 最小
        val logits = floatArrayOf(1.0f, -1.0f, 3.0f)
        val result = OchwproDecode.topK(logits, 3)
        assertEquals(3, result.size)
        assertEquals(2, result[0].index)
        assertEquals(0, result[1].index)
        assertEquals(1, result[2].index)
        // 概率必须单调不增
        assertTrue(result[0].probability > result[1].probability)
        assertTrue(result[1].probability > result[2].probability)
    }

    @Test
    fun `topK 的概率是真正的概率且和为 1`() {
        val logits = FloatArray(50) { it.toFloat() / 10f }
        val result = OchwproDecode.topK(logits, 10)
        val sum = result.sumOf { it.probability.toDouble() }
        assertEquals(1.0, sum, 1e-6)
        for (item in result) {
            assertTrue("概率应在 0..1：${item.probability}", item.probability in 0f..1f)
        }
    }

    @Test
    fun `topK 只取 k 个即使类别数更多`() {
        val result = OchwproDecode.topK(FloatArray(7356) { it.toFloat() }, 10)
        assertEquals(10, result.size)
        // 最大的十个：下标 7355 往下
        assertEquals(7355, result[0].index)
        assertEquals(7346, result[9].index)
    }

    @Test
    fun `topK 的 k 大于类别数时返回全部`() {
        val result = OchwproDecode.topK(floatArrayOf(1f, 2f, 3f), 10)
        assertEquals(3, result.size)
        assertEquals(2, result[0].index)
    }

    @Test
    fun `topK 在 k 非法或输入为空时返回空`() {
        assertTrue(OchwproDecode.topK(floatArrayOf(1f, 2f), 0).isEmpty())
        assertTrue(OchwproDecode.topK(floatArrayOf(1f, 2f), -1).isEmpty())
        assertTrue(OchwproDecode.topK(FloatArray(0), 5).isEmpty())
    }

    @Test
    fun `topK 跳过 NaN 而不是让它污染排序`() {
        // NaN 与任何值比较都是 false，混进排序表会让比较逻辑错位。
        // 这里让它出现在最大值的位置，验证它被忽略、其余候选仍然正常。
        val logits = floatArrayOf(1f, Float.NaN, 3f, 2f)
        val result = OchwproDecode.topK(logits, 4)
        assertEquals(3, result.size)
        val indices = result.map { it.index }
        assertTrue("NaN 所在下标 1 不应出现", 1 !in indices)
        assertEquals(2, result[0].index)
        assertEquals(3, result[1].index)
        assertEquals(0, result[2].index)
        assertTrue(result.none { it.probability.isNaN() })
    }

    @Test
    fun `topK 在全是 NaN 时返回空`() {
        val result = OchwproDecode.topK(floatArrayOf(Float.NaN, Float.NaN), 2)
        assertTrue(result.isEmpty())
    }

    @Test
    fun `topK 忽略负无穷而不产生 NaN`() {
        // 掩码后的 logits 常出现 -Inf（被屏蔽的位置）。它不该让整批候选变成 NaN。
        val logits = floatArrayOf(Float.NEGATIVE_INFINITY, 2f, Float.NEGATIVE_INFINITY)
        val result = OchwproDecode.topK(logits, 3)
        assertEquals(1, result.size)
        assertEquals(1, result[0].index)
        assertEquals(1f, result[0].probability, 1e-6f)
    }

    @Test
    fun `topK 在极大值上数值稳定`() {
        // 不减去最大值的 softmax 会在这里溢出成 Inf，进而得到 NaN
        val logits = floatArrayOf(1e30f, 1e30f - 1f, 1e30f - 2f)
        val result = OchwproDecode.topK(logits, 3)
        assertEquals(3, result.size)
        assertTrue("概率不应为 NaN", result.none { it.probability.isNaN() })
        assertEquals(1.0, result.sumOf { it.probability.toDouble() }, 1e-6)
    }

    @Test
    fun `topK 丢弃概率下溢到零的候选项`() {
        // 三项差距大到最小项下溢成 0：模型在说「不可能是这个」，不该占候选位
        val logits = floatArrayOf(1e30f, 0f, Float.NEGATIVE_INFINITY)
        val result = OchwproDecode.topK(logits, 3)
        assertEquals(1, result.size)
        assertEquals(0, result[0].index)
        assertEquals(1f, result[0].probability, 1e-6f)
    }

    @Test
    fun `topK 在同分时按下标顺序稳定返回`() {
        val result = OchwproDecode.topK(floatArrayOf(5f, 5f, 5f), 3)
        assertEquals(3, result.size)
        // 完全同分时概率均等
        for (item in result) {
            assertEquals(1f / 3f, item.probability, 1e-6f)
        }
        // 插入式排序是稳定的：同分不改变下标先后
        assertEquals(listOf(0, 1, 2), result.map { it.index })
    }

    @Test
    fun `topK 在类别数为一与 k 为一的时候不越界`() {
        val single = OchwproDecode.topK(floatArrayOf(0.5f), 1)
        assertEquals(1, single.size)
        assertEquals(1f, single[0].probability, 1e-6f)
    }
}
