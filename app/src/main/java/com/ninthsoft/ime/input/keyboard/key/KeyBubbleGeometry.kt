package com.ninthsoft.ime.input.keyboard.key

import kotlin.math.max
import kotlin.math.min

/**
 * 一个气泡的几何：主体（放候选项的圆角块）与指针（压住按键的那条竖块）。
 *
 * 坐标都在**键盘窗口**的坐标系里，除了 [contentLeftOnScreen] 会由调用方加上窗口自身的屏幕位置。
 */
data class KeyBubbleGeometry(
    val bodyLeft: Float,
    val bodyTop: Float,
    val bodyRight: Float,
    val bodyBottom: Float,
    val pointerLeft: Float,
    val pointerRight: Float,
    val pointerBottom: Float,
    /** 指针是否画得出来（按键太矮 / 主体已经压住按键时不画）。 */
    val hasPointer: Boolean,
    /** 主体的圆角半径。 */
    val bodyRadius: Float,
    /** 候选项内容区（不含主体两侧留白）的左边缘。 */
    val itemsLeft: Float,
    /** 每项的宽度：相邻两项中心间距，手势层按它把手指位置换算成下标。 */
    val cellWidth: Float,
    /** 候选项内容区总宽。 */
    val contentWidth: Float,
)

/**
 * 气泡几何的**纯计算**部分：不碰 Canvas、不碰 View，只吃数字吐数字。
 *
 * 单独抽出来是因为这里的错法是「看不见的」—— 曾经的实现按 IME 窗口内的剩余空间决定
 * 气泡朝上还是朝下，而顶行按键上方只剩顶栏那 48dp，于是**顶行气泡一律翻到按键下面**，
 * 正是「九宫格第一行按键的气泡跑到按键下面」的成因。现在改成「永远在按键上方」，
 * 并由 `KeyBubbleGeometryTest` 把这条钉住。
 */
object KeyBubbleGeometryMath {

    /**
     * 主体高度必须小于顶栏高度（`KeyboardWindowView.PANEL_HEIGHT_DP` = 48dp），
     * 顶行按键的气泡才放得下。
     */
    const val BODY_HEIGHT_DP = 42f

    /** 单项最小宽度：窄候选（`a`、`1`）也要有能按住的宽度。 */
    const val ITEM_MIN_WIDTH_DP = 34f

    const val ITEM_HORIZONTAL_PADDING_DP = 6f

    /** 主体至少是键宽的多少倍 —— 保证主体比指针宽，形状才是「凸」的。 */
    const val BODY_TO_KEY_WIDTH_RATIO = 1.8f

    /** 气泡与屏幕左右边缘留的缝。 */
    const val SCREEN_MARGIN_DP = 2f

    /**
     * 算出气泡的几何。
     *
     * @param hostWidth 键盘窗口宽度（气泡不能超出它，否则会被窗口裁掉）。
     * @param keyLeft/keyTop/keyRight/keyBottom **键帽**（不含主题键间距）在窗口坐标系里的矩形。
     * @param perItemWidth 单个候选项的自然宽度（调用方按文字量出来）。
     * @param bodyHeight 主体高度（px）。
     * @param screenMargin 气泡与窗口左右边缘的最小间距（px）。
     * @param cornerRadius 键帽圆角（px），主体圆角、指针底角都用它，气泡才和键帽是一套。
     */
    fun compute(
        hostWidth: Int,
        keyLeft: Float,
        keyTop: Float,
        keyRight: Float,
        keyBottom: Float,
        perItemWidth: Float,
        itemCount: Int,
        bodyHeight: Float,
        screenMargin: Float,
        cornerRadius: Float,
    ): KeyBubbleGeometry {
        val keyWidth = keyRight - keyLeft
        val keyCenterX = (keyLeft + keyRight) / 2f

        val maxBodyWidth = (hostWidth - screenMargin * 2f).coerceAtLeast(1f)
        // 主体至少要明显比按键宽：主体比指针还窄的话，形状会变成一个倒过来的凸字，
        // 完全不像「从键帽里长出来的」。
        val minBodyWidth = min(keyWidth * BODY_TO_KEY_WIDTH_RATIO, maxBodyWidth)
        val bodyWidth = max(perItemWidth * itemCount, minBodyWidth).coerceAtMost(maxBodyWidth)

        // 主体底边贴着键帽上沿。顶上真的放不下时（悬浮键盘被拖到屏幕最上方）允许压住按键 ——
        // 气泡始终完整可见，指针随之变短，但**绝不翻到按键下面**。
        val bodyTop = (keyTop - bodyHeight).coerceAtLeast(0f)
        val bodyBottom = bodyTop + bodyHeight
        val bodyLeft = (keyCenterX - bodyWidth / 2f)
            .coerceIn(screenMargin, (hostWidth - screenMargin - bodyWidth).coerceAtLeast(screenMargin))
        val bodyRight = bodyLeft + bodyWidth

        // 内容区：自然宽度塞得下就居中（主体被最小宽度撑开时两侧留白），
        // 塞不下（极窄屏）才铺满主体 —— 手势的「划到哪一项」始终等于看到的那一项。
        val naturalContentWidth = perItemWidth * itemCount
        val cellWidth: Float
        val contentWidth: Float
        if (naturalContentWidth <= bodyWidth) {
            cellWidth = perItemWidth
            contentWidth = naturalContentWidth
        } else {
            cellWidth = bodyWidth / itemCount
            contentWidth = bodyWidth
        }
        val itemsLeft = bodyLeft + (bodyWidth - contentWidth) / 2f

        // 指针 = 按键本身，夹进主体内部（主体被屏幕夹窄时也不会戳出主体，
        // 此时指针与主体同宽，轮廓退化成一条竖直的方柱，仍然是合法形状）。
        val pointerLeft = max(keyLeft, bodyLeft)
        val pointerRight = min(keyRight, bodyRight)
        val pointerHeight = keyBottom - bodyBottom
        val hasPointer = pointerRight - pointerLeft > 1f && pointerHeight > 1f

        val bodyRadius = min(cornerRadius, min(bodyWidth, bodyHeight) / 2.5f).coerceAtLeast(0f)

        return KeyBubbleGeometry(
            bodyLeft = bodyLeft,
            bodyTop = bodyTop,
            bodyRight = bodyRight,
            bodyBottom = bodyBottom,
            pointerLeft = pointerLeft,
            pointerRight = pointerRight,
            pointerBottom = keyBottom,
            hasPointer = hasPointer,
            bodyRadius = bodyRadius,
            itemsLeft = itemsLeft,
            cellWidth = cellWidth,
            contentWidth = contentWidth,
        )
    }
}
