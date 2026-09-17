package com.ninthsoft.ime

import com.ninthsoft.ime.input.keyboard.key.SwipeUpMath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「上滑输入」触发条件判定的回归：钉住**纯计算**那一层（`SwipeUpMath`）。
 *
 * 上滑的状态机本体跑在 `CustomGestureView` 里，需要真机（仪器测试）才能验；
 * 这里只覆盖「阈值怎么算、边界含不含、参数能不能真的起作用」这些可离线验证的部分，
 * 与 `BubbleSelectionMathTest` 是同一个思路。
 *
 * 触发条件有**两个**，都要满足：
 * 1. 距离 —— 纵向位移 ≥ 当前键高 × ratio（旧实现是 `2 × touchSlop` ≈ 16dp，
 *    表现为「稍微上滑一点点就出符号」）；
 * 2. 方向 —— 纵向位移 ≥ 横向位移 × directionTan，即纵向占主导
 *    （不加这条时，横滑带的纵向漂移也会被当成上滑）。
 */
class SwipeUpThresholdTest {

    /** 一个 100px 高的键配默认系数 → 距离阈值正好 100px，断言写起来直观。 */
    private val keyHeight = 100f

    /** 模拟 touchSlop = 8dp、density = 3 的机器：兜底下限 = 2 × 8dp × 3 = 48px。 */
    private val minDistance = 48f

    /** [dx] 默认 0（纯纵向），[directionTan] 默认用内置值。 */
    private fun isSwipeUp(
        dy: Float,
        dx: Float = 0f,
        ratio: Float = SwipeUpMath.DEFAULT_RATIO,
        directionTan: Float = SwipeUpMath.DEFAULT_DIRECTION_TAN,
        height: Float = keyHeight,
    ): Boolean = SwipeUpMath.isSwipeUp(dx, dy, height, ratio, directionTan, minDistance)

    // ------------------------------------------------------------ 距离边界

    @Test
    fun `差一点不算上滑`() {
        assertFalse("99px < 一个键高 100px，不该触发", isSwipeUp(99f))
    }

    @Test
    fun `正好滑够一个键高就算上滑`() {
        assertTrue("边界包含（>=），否则滑到刚好一个键高会没反应", isSwipeUp(100f))
    }

    @Test
    fun `滑过头当然也算`() {
        assertTrue(isSwipeUp(200f))
    }

    @Test
    fun `向下滑永远不算上滑`() {
        assertFalse(isSwipeUp(-500f))
    }

    @Test
    fun `原地不动不算上滑`() {
        assertFalse(isSwipeUp(0f))
    }

    // ------------------------------------------------------------ 距离系数确实生效

    @Test
    fun `距离系数调小后更容易触发 但仍受兜底下限约束`() {
        // 0.4 × 100 = 40px，被 48px 的下限顶上来 → 实际阈值 48px
        assertFalse(isSwipeUp(47f, ratio = SwipeUpMath.MIN_RATIO))
        assertTrue(isSwipeUp(48f, ratio = SwipeUpMath.MIN_RATIO))
        // 键更高（200px）时下限不再参与：0.4 × 200 = 80px
        assertFalse(isSwipeUp(79f, ratio = SwipeUpMath.MIN_RATIO, height = 200f))
        assertTrue(isSwipeUp(80f, ratio = SwipeUpMath.MIN_RATIO, height = 200f))
    }

    @Test
    fun `距离系数调大后更难触发`() {
        // 1.5 × 100 = 150px
        assertFalse(isSwipeUp(149f, ratio = SwipeUpMath.MAX_RATIO))
        assertTrue(isSwipeUp(150f, ratio = SwipeUpMath.MAX_RATIO))
    }

    // ------------------------------------------------------------ 键高适配（本方案的核心）

    @Test
    fun `键高不同则阈值不同 —— 26 键与九键各按自己的高度`() {
        val dy = 120f
        // 同样的手指位移：26 键（矮）算上滑，九键（高）不算 —— 这正是「不写死距离」要的效果
        assertTrue("26 键键高 100px ≤ 120px", isSwipeUp(dy, height = 100f))
        assertFalse("九键键高 160px > 120px", isSwipeUp(dy, height = 160f))
    }

    @Test
    fun `键盘调大调小阈值跟着走`() {
        val dy = 90f
        assertTrue(isSwipeUp(dy, height = 80f))
        assertFalse(isSwipeUp(dy, height = 140f))
    }

    // ------------------------------------------------------------ 方向约束

    @Test
    fun `纯纵向滑算上滑`() {
        assertTrue(isSwipeUp(120f, dx = 0f))
    }

    @Test
    fun `斜着滑只要纵向占主导也算上滑`() {
        // dy 120 ≥ dx 60 × 1.5 = 90，且距离 120 ≥ 100
        assertTrue("纵向是横向的两倍，属于正常的上滑手势", isSwipeUp(120f, dx = 60f))
    }

    @Test
    fun `横滑带出来的纵向漂移不算上滑`() {
        // 距离够了（110 ≥ 100），但横向滑了 140 —— 这是「从 q 划到 e」那种横滑，
        // 纵向只是顺带漂移出来的，旧实现（只看距离）会在这种场景误出符号。
        assertFalse(isSwipeUp(110f, dx = 140f))
        // 同样的纵向位移，横向为 0 时就是正常上滑
        assertTrue(isSwipeUp(110f, dx = 0f))
    }

    @Test
    fun `四十五度斜滑不算上滑`() {
        // dy = dx = 100 → 100 < 100 × 1.5
        assertFalse(isSwipeUp(100f, dx = 100f))
    }

    @Test
    fun `方向系数的边界含不含`() {
        // 默认 1.5：66 × 1.5 = 99 ≤ 100 通过；67 × 1.5 = 100.5 > 100 挡掉
        assertTrue(isSwipeUp(100f, dx = 66f))
        assertFalse(isSwipeUp(100f, dx = 67f))
    }

    @Test
    fun `方向系数可调 —— 放松后同一条轨迹就通过了`() {
        val dy = 100f
        val dx = 100f
        assertFalse("默认 1.5 挡掉 45°", isSwipeUp(dy, dx = dx))
        assertTrue("放到 0.5（≈63°）就放行", isSwipeUp(dy, dx = dx, directionTan = 0.5f))
        assertFalse("收紧到 4.0（≈14°）更挡", isSwipeUp(200f, dx = 60f, directionTan = 4f))
    }

    @Test
    fun `方向系数为 0 时退化成不卡方向`() {
        // 不该出现「除零 / 崩溃」，最坏也只是退化成只看距离
        assertTrue(isSwipeUp(100f, dx = 9999f, directionTan = 0f))
    }

    // ------------------------------------------------------------ 退化与兜底

    @Test
    fun `键高没测出来时退化成兜底下限`() {
        // height = 0 不能变成「0 × 系数 = 0 → 一碰就触发」
        assertFalse(isSwipeUp(47f, height = 0f))
        assertTrue(isSwipeUp(48f, height = 0f))
    }

    @Test
    fun `极小键盘不会一碰就触发`() {
        // 键高 20px（悬浮键盘被拖到很小）：1.0 × 20 = 20px，被下限抬到 48px
        assertFalse(isSwipeUp(30f, height = 20f))
        assertTrue(isSwipeUp(48f, height = 20f))
    }

    @Test
    fun `键高是脏值时退化成兜底下限而不是永不触发`() {
        // NaN 的键高如果直接参与乘法会把阈值变成 NaN，`dy >= NaN` 恒为 false ——
        // 那会表现成「上滑彻底没反应」，比误触发更难排查。这里必须退化成下限。
        assertTrue(SwipeUpMath.isSwipeUp(0f, 100f, Float.NaN, 1f, 1.5f, minDistance))
        assertTrue(SwipeUpMath.isSwipeUp(0f, 100f, Float.NEGATIVE_INFINITY, 1f, 1.5f, minDistance))
    }

    @Test
    fun `位移是 NaN 时不触发`() {
        assertFalse(SwipeUpMath.isSwipeUp(0f, Float.NaN, keyHeight, 1f, 1.5f, minDistance))
        // 横向是 NaN 时方向判定为 false，保守地不触发
        assertFalse(SwipeUpMath.isSwipeUp(Float.NaN, 200f, keyHeight, 1f, 1.5f, minDistance))
    }

    // ------------------------------------------------------------ 偏好脏值夹取

    @Test
    fun `距离系数的脏值会被夹回合法区间`() {
        // ratio 为 0 会让上滑永远触发不了（用户只会看到「上滑坏了」），必须兜住
        assertEquals(SwipeUpMath.DEFAULT_RATIO, SwipeUpMath.clampRatio(Float.NaN), 0f)
        assertEquals(SwipeUpMath.MIN_RATIO, SwipeUpMath.clampRatio(0f), 0f)
        assertEquals(SwipeUpMath.MIN_RATIO, SwipeUpMath.clampRatio(-3f), 0f)
        assertEquals(SwipeUpMath.MAX_RATIO, SwipeUpMath.clampRatio(9f), 0f)
        assertEquals(0.7f, SwipeUpMath.clampRatio(0.7f), 0f)
    }

    @Test
    fun `方向系数的脏值会被夹回合法区间`() {
        assertEquals(
            SwipeUpMath.DEFAULT_DIRECTION_TAN,
            SwipeUpMath.clampDirectionTan(Float.NaN),
            0f,
        )
        assertEquals(SwipeUpMath.MIN_DIRECTION_TAN, SwipeUpMath.clampDirectionTan(0f), 0f)
        assertEquals(SwipeUpMath.MIN_DIRECTION_TAN, SwipeUpMath.clampDirectionTan(-2f), 0f)
        assertEquals(SwipeUpMath.MAX_DIRECTION_TAN, SwipeUpMath.clampDirectionTan(99f), 0f)
        assertEquals(2f, SwipeUpMath.clampDirectionTan(2f), 0f)
    }

    // ------------------------------------------------------------ 阈值查询

    @Test
    fun `threshold 与 isSwipeUp 用同一套距离算法`() {
        val need = SwipeUpMath.threshold(keyHeight, SwipeUpMath.DEFAULT_RATIO, minDistance)
        assertEquals(100f, need, 0f)
        assertTrue(SwipeUpMath.isSwipeUp(0f, need, keyHeight, SwipeUpMath.DEFAULT_RATIO, 1.5f, minDistance))
        assertFalse(
            SwipeUpMath.isSwipeUp(0f, need - 0.1f, keyHeight, SwipeUpMath.DEFAULT_RATIO, 1.5f, minDistance),
        )
    }
}
