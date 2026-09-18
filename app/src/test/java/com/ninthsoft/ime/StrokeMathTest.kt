package com.ninthsoft.ime

import com.ninthsoft.ime.input.handwriting.HwStroke
import com.ninthsoft.ime.input.handwriting.StrokeMath
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [StrokeMath] 的回归测试。
 *
 * 这些判定看着简单，但错在真机上很难查：去重过严会让笔画缺头少尾
 * （一笔的第一个点被吃掉），过松则点数虚高、识别变慢。
 *
 * 这里原本还有 `flatten` 与 `fitToArea` 两组用例。两者都是为已移除的 zinnia 引擎
 * 服务的（前者为「一次 JNI 传完所有点」、后者为「铺满画布对齐训练分布」），
 * 函数本身已删除，用例随之删除 —— 否则会留下「还在用的约定」的错觉。
 * 现在承担同类职责的是 [com.ninthsoft.ime.input.handwriting.ochwpro.OchwproPreprocess]，
 * 它有自己的用例。
 */
class StrokeMathTest {

    // ---------------- shouldKeepPoint ----------------

    @Test
    fun `一笔的第一个点无条件保留`() {
        // 即使上一个点在同一位置，第一个点也必须留下，否则笔画起点丢失
        assertTrue(StrokeMath.shouldKeepPoint(false, 100f, 100f, 100f, 100f))
    }

    @Test
    fun `完全重合的点被丢弃`() {
        assertFalse(StrokeMath.shouldKeepPoint(true, 50f, 50f, 50f, 50f))
    }

    @Test
    fun `距离小于阈值被丢弃`() {
        // 偏移 0.5px，小于默认 1px
        assertFalse(StrokeMath.shouldKeepPoint(true, 10f, 10f, 10.5f, 10f))
        assertFalse(StrokeMath.shouldKeepPoint(true, 10f, 10f, 10f, 10.9f))
    }

    @Test
    fun `距离刚好等于阈值时保留`() {
        assertTrue(StrokeMath.shouldKeepPoint(true, 10f, 10f, 11f, 10f))
        assertTrue(StrokeMath.shouldKeepPoint(true, 10f, 10f, 10f, 9f))
    }

    @Test
    fun `斜向距离按欧氏距离判定而不是按单个分量`() {
        // (3,4) 的欧氏距离是 5；两个分量都只有 3 和 4，若只比某个分量会误判
        assertTrue(StrokeMath.shouldKeepPoint(true, 0f, 0f, 3f, 4f, minDistance = 5f))
        // 距离 5 但不满足 5：用 4.9 的偏移点验证「分量大而距离小」不会放行
        assertFalse(StrokeMath.shouldKeepPoint(true, 0f, 0f, 3.4f, 3.4f, minDistance = 5f))
    }

    @Test
    fun `阈值可调且为 0 时任何点都保留`() {
        assertTrue(StrokeMath.shouldKeepPoint(true, 0f, 0f, 0f, 0f, minDistance = 0f))
        assertFalse(StrokeMath.shouldKeepPoint(true, 0f, 0f, 0.01f, 0f, minDistance = 1f))
    }

    @Test
    fun `负坐标同样正确`() {
        assertTrue(StrokeMath.shouldKeepPoint(true, -10f, -10f, -11f, -10f))
        assertFalse(StrokeMath.shouldKeepPoint(true, -10f, -10f, -10.4f, -10f))
    }

    // ---------------- isEmpty ----------------

    @Test
    fun `isEmpty 的各种情况`() {
        assertTrue(StrokeMath.isEmpty(emptyList()))
        assertTrue(StrokeMath.isEmpty(listOf(HwStroke(floatArrayOf()))))
        assertTrue(StrokeMath.isEmpty(listOf(HwStroke(floatArrayOf()), HwStroke(floatArrayOf()))))
        assertFalse(StrokeMath.isEmpty(listOf(HwStroke(floatArrayOf(1f, 1f)))))
        assertFalse(
            StrokeMath.isEmpty(
                listOf(HwStroke(floatArrayOf()), HwStroke(floatArrayOf(1f, 1f)))
            )
        )
    }

    // ---------------- HwStroke ----------------

    @Test
    fun `HwStroke 的点数按坐标个数折半计算`() {
        assertEquals(0, HwStroke(floatArrayOf()).pointCount)
        assertEquals(2, HwStroke(floatArrayOf(1f, 1f, 2f, 2f)).pointCount)
    }

    @Test
    fun `HwStroke 的时间戳可以为空`() {
        assertEquals(null, HwStroke(floatArrayOf(1f, 1f)).times)
        assertEquals(2, HwStroke(floatArrayOf(1f, 1f, 2f, 2f), longArrayOf(1L, 2L)).times?.size)
    }
}
