package com.ninthsoft.ime

import com.ninthsoft.ime.input.keyboard.key.KeyBubbleGeometry
import com.ninthsoft.ime.input.keyboard.key.KeyBubbleGeometryMath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 气泡几何的回归用例。
 *
 * 这里钉住的是「气泡长在按键哪一侧」以及「指针是否压住按键」这两件事 ——
 * 它们先后出过两次问题：
 *  - 旧实现把气泡做成 `PopupWindow`，按 **IME 窗口内**的剩余空间决定朝上还是朝下。
 *    顶行按键上方只剩顶栏那 48dp（比气泡还矮 1dp），于是**顶行一律翻到按键下面**：
 *    九宫格第一行按键的长按气泡跑到了按键下方。现在改成永远在上方，除非真的放不下。
 *  - 旧实现的尾巴是个 14dp 的小三角，与键帽各画各的，看起来是「贴在键盘上的一块」。
 *    现在是**与按键同宽同高的指针压住键帽**，主体底边与键帽上沿齐平，连成一体。
 */
class KeyBubbleGeometryTest {

    private val density = 3f

    private fun dp(value: Float): Float = value * density

    /** 常见竖屏：宽 360dp，键盘窗口就是键盘 + 顶栏（顶栏 48dp）。 */
    private fun geometry(
        keyTopDp: Float,
        keyHeightDp: Float = 48f,
        keyLeftDp: Float = 122f,
        keyWidthDp: Float = 79f,
        itemCount: Int = 4,
        hostWidthDp: Float = 360f,
    ): KeyBubbleGeometry = KeyBubbleGeometryMath.compute(
        hostWidth = (hostWidthDp * density).toInt(),
        keyLeft = dp(keyLeftDp),
        keyTop = dp(keyTopDp),
        keyRight = dp(keyLeftDp + keyWidthDp),
        keyBottom = dp(keyTopDp + keyHeightDp),
        perItemWidth = dp(KeyBubbleGeometryMath.ITEM_MIN_WIDTH_DP),
        itemCount = itemCount,
        bodyHeight = dp(KeyBubbleGeometryMath.BODY_HEIGHT_DP),
        screenMargin = dp(KeyBubbleGeometryMath.SCREEN_MARGIN_DP),
        cornerRadius = dp(9f),
    )

    /**
     * 回归：顶行按键（九宫格第一行的数字键）的气泡必须在按键**上方**。
     *
     * 顶行键帽上沿就在顶栏下沿（48dp），气泡主体 42dp 必须整个放得下。
     */
    @Test
    fun `顶行按键的气泡主体在按键上方`() {
        val keyTop = dp(48f)
        val keyBottom = dp(48f + 48f)
        val g = geometry(keyTopDp = 48f)

        assertEquals(keyTop, g.bodyBottom, 0.01f)
        assertTrue("主体必须完全在按键上方：bodyBottom=${g.bodyBottom} keyTop=$keyTop", g.bodyBottom <= keyTop)
        assertTrue("主体不能超出窗口上边界：bodyTop=${g.bodyTop}", g.bodyTop >= 0f)
        assertEquals(keyBottom, g.pointerBottom, 0.01f)
        assertTrue("指针必须比主体更靠下（压住按键）", g.pointerBottom > g.bodyBottom)
    }

    /** 非顶行的按键同样在上方 —— 位置不由「窗口内剩余空间」决定。 */
    @Test
    fun `第二行按键的气泡也在上方`() {
        val g = geometry(keyTopDp = 96f)
        assertTrue("主体底边不能低过键帽上沿", g.bodyBottom <= dp(96f) + 0.01f)
        assertEquals(dp(96f), g.bodyBottom, 0.01f)
    }

    /** 指针与按键同宽、同高：气泡看起来是从这个键帽里长出来的。 */
    @Test
    fun `指针与按键同宽并压住整个按键`() {
        val g = geometry(keyTopDp = 96f, keyLeftDp = 122f, keyWidthDp = 79f)
        assertEquals(dp(122f), g.pointerLeft, 0.01f)
        assertEquals(dp(122f + 79f), g.pointerRight, 0.01f)
        assertTrue(g.hasPointer)
    }

    /** 主体比指针宽：不然「主体 + 指针」是个倒过来的凸字，不像键帽长出来的。 */
    @Test
    fun `单项气泡的主体也比按键宽`() {
        val g = geometry(keyTopDp = 96f, itemCount = 1, keyWidthDp = 79f)
        assertTrue(
            "主体 ${g.bodyRight - g.bodyLeft} 必须宽于按键 ${dp(79f)}",
            g.bodyRight - g.bodyLeft >= dp(79f) * KeyBubbleGeometryMath.BODY_TO_KEY_WIDTH_RATIO,
        )
        // 内容区跟着主体居中，手势仍按内容区取项
        assertEquals(KeyBubbleGeometryMath.ITEM_MIN_WIDTH_DP * density, g.contentWidth, 0.01f)
        assertTrue(g.itemsLeft > g.bodyLeft)
    }

    /** 最左 / 最右的按键：主体被屏幕夹住，但指针仍落在主体内部，轮廓不会自交。 */
    @Test
    fun `屏幕边缘的按键指针仍夹在主体内`() {
        val left = geometry(keyTopDp = 96f, keyLeftDp = 0f, keyWidthDp = 36f)
        assertTrue(left.bodyLeft >= 0f)
        assertTrue(left.pointerLeft >= left.bodyLeft)
        assertTrue(left.pointerRight <= left.bodyRight)

        val right = geometry(keyTopDp = 96f, keyLeftDp = 324f, keyWidthDp = 36f)
        assertTrue(right.bodyRight <= dp(360f))
        assertTrue(right.pointerLeft >= right.bodyLeft)
        assertTrue(right.pointerRight <= right.bodyRight)
    }

    /** 悬浮键盘被拖到屏幕最上方：主体让位（压住按键），但绝不翻到按键下面。 */
    @Test
    fun `上方放不下时主体贴住窗口顶部而不是翻到下面`() {
        val g = geometry(keyTopDp = 4f)
        assertEquals(0f, g.bodyTop, 0.01f)
        assertTrue("主体仍然要在按键下沿之上", g.bodyBottom < dp(4f + 48f))
        assertEquals(dp(4f + 48f), g.pointerBottom, 0.01f)
    }

    /** 键盘被拖到屏幕最上方、按键又极矮时：主体已经压过按键下沿，指针退化成不画，但主体照样完整。 */
    @Test
    fun `主体压过按键时不画指针也不崩`() {
        val g = geometry(keyTopDp = 4f, keyHeightDp = 6f)
        assertFalse(g.hasPointer)
        assertEquals(0f, g.bodyTop, 0.01f)
        assertEquals(KeyBubbleGeometryMath.BODY_HEIGHT_DP * density, g.bodyBottom - g.bodyTop, 0.01f)
    }

    /** 手势取项的间距 = 内容区 / 项数，且内容区总是能被项数整除。 */
    @Test
    fun `每项宽度与内容区自洽`() {
        for (count in 1..5) {
            val g = geometry(keyTopDp = 96f, itemCount = count)
            assertEquals(g.contentWidth, g.cellWidth * count, 0.01f)
            assertTrue(g.cellWidth > 0f)
        }
    }
}
