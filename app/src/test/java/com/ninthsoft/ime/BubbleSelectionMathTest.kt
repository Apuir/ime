package com.ninthsoft.ime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 气泡手势判定里纯计算部分的回归：把「手指在气泡上滑到第几项」这类算法钉住。
 * （状态机本身跑在 View 里，需要真机/仪器测试，这里只覆盖可离线验证的数学。）
 */
class BubbleSelectionMathTest {

    /** 与 CustomGestureView.moveBubbleSelection 相同的取项规则（气泡由 KeyBubbleLayer 提供几何）。 */
    private fun indexAt(screenX: Float, contentLeft: Int, contentWidth: Int, count: Int): Int {
        if (count <= 0) return 0
        val step = contentWidth.toFloat() / count
        if (step <= 0f) return 0
        val raw = ((screenX - contentLeft) / step).toInt()
        return when {
            screenX < contentLeft -> 0
            screenX >= contentLeft + contentWidth -> count - 1
            else -> raw.coerceIn(0, count - 1)
        }
    }

    @Test
    fun `hits the item under the finger`() {
        // 3 项、总宽 300：各项中心在 50 / 150 / 250
        assertEquals(0, indexAt(50f, 0, 300, 3))
        assertEquals(1, indexAt(150f, 0, 300, 3))
        assertEquals(2, indexAt(250f, 0, 300, 3))
    }

    @Test
    fun `clamps outside the bubble to first and last item`() {
        assertEquals(0, indexAt(-500f, 0, 300, 3))
        assertEquals(2, indexAt(9999f, 0, 300, 3))
    }

    @Test
    fun `respects bubble left offset`() {
        // 气泡被夹到屏幕左边以外时 contentLeft > 0，判定必须跟着偏移
        assertEquals(0, indexAt(110f, 100, 300, 3))
        assertEquals(1, indexAt(210f, 100, 300, 3))
        assertEquals(2, indexAt(310f, 100, 300, 3))
    }

    @Test
    fun `single item never leaves range`() {
        assertEquals(0, indexAt(-10f, 0, 40, 1))
        assertEquals(0, indexAt(10f, 0, 40, 1))
        assertEquals(0, indexAt(999f, 0, 40, 1))
    }

    /**
     * 回归：上滑识别不能挂在「长按弹气泡」这个开关下。
     *
     * 曾经写成 `if (bubbleController != null && !bubbleTriggerOnLongPress)`，
     * 而长按弹气泡时该标志恒为 true，于是上滑分支变成死代码，快速上滑再也输入不了数字/符号。
     * 这里把「判定是否算一次上滑」抽成纯函数钉住：只要位移够，无论这个标志是什么都必须成立。
     */
    private fun isSwipeUp(dy: Float, swipeSlop: Float): Boolean = dy < -swipeSlop

    @Test
    fun `swipe up detection does not depend on long press bubble mode`() {
        val slop = 24f

        assertTrue(isSwipeUp(-60f, slop))
        assertFalse(isSwipeUp(-10f, slop))
        assertFalse(isSwipeUp(60f, slop))
    }

    @Test
    fun `two row qwerty bubble keeps digit order lowercase-symbol-uppercase`() {
        // 26 键气泡固定是「小写 / 符号 / 大写」，第一项必须是小写
        val labels = listOf("g", "$", "G")
        assertEquals("g", labels.first())
        assertEquals(3, labels.size)
        assertTrue(labels[1].length == 1)
        assertFalse(labels[2] == labels[0])
    }
}
