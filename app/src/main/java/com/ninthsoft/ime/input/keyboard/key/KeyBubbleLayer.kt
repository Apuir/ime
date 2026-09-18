package com.ninthsoft.ime.input.keyboard.key

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.util.TypedValue
import android.view.View
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 按键气泡的实际绘制者：由**键盘窗口**持有，画在窗口自己的 `dispatchDraw` 里。
 *
 * 形状（借鉴 Xime 的 `ui/keyboard/SwipeBubble.kt`）：
 * ```
 *      ┌──────────────────────────┐   ← 主体：一排候选项，圆角 / 描边与键帽同一套
 *      │   1    2    3            │
 *      └────────┐        ┌────────┘
 *               │  键帽  │              ← 指针：与按键同宽同高，压住按键本身
 *               └────────┘
 * ```
 * 主体和指针用**同一条 Path** 画出来，交界处是两个内凹圆角，所以两者是连续的、
 * 没有接缝 —— 这就是「气泡和按键连成一体」的来源，而不是靠一个三角形尾巴去指。
 *
 * 主体悬在按键**上方**、指针压住按键：主体底边与键帽上沿齐平，指针底部的圆角取
 * 键帽圆角，正好落在键帽轮廓上。顶行按键的气泡主体压在顶栏（候选栏）区域上，
 * 这是有意的 —— 气泡只在按住期间存在，必须比手指高，否则会被手指挡住。
 *
 * 几何全部由 [KeyBubbleGeometryMath] 这个纯函数算（可离线测试），这里只管画。
 */
class KeyBubbleLayer(
    private val context: Context,
    /** 内容变了要让宿主重绘（这里是 Layer，拿不到宿主 View 的 invalidate）。 */
    private val onInvalidate: () -> Unit,
) : KeyBubble {

    private val density = context.resources.displayMetrics.density

    private fun dp(value: Float): Float = value * density

    private fun sp(value: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, value, context.resources.displayMetrics)

    private var controller: HasKeyBubble? = null
    private var labels: List<String> = emptyList()
    private var geometry: KeyBubbleGeometry? = null

    /** 是否真的画得出来（按键还没测量、窗口没宽度时为 false）。 */
    private var visible = false
    private var selected = 0
    private var contentLeftOnScreen = 0

    // ---- 画笔（全部复用，绘制过程零分配）----
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
    }
    private val highlightPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()

    override val isShowing: Boolean get() = visible

    override val itemCount: Int get() = labels.size

    override val selectedIndex: Int get() = selected

    override val itemStep: Float get() = geometry?.cellWidth ?: 0f

    override val contentLeft: Int get() = contentLeftOnScreen

    override val contentWidth: Int get() = (geometry?.contentWidth ?: 0f).roundToInt()

    /**
     * 显示气泡。返回 false 表示这次画不了（调用方应退回普通点击）。
     *
     * 几何在**显示时**一次算清、不在绘制时算：手势层在 `show` 返回后马上要用
     * [contentLeft] / [itemStep] 把手指位置换算成高亮项。
     */
    fun show(anchor: View, controller: HasKeyBubble, host: View): Boolean {
        val labels = controller.bubbleItems.map { it.label }
        if (labels.isEmpty()) return false
        if (host.width <= 0) return false
        if (anchor.width <= 0 || anchor.height <= 0) return false

        textPaint.textSize = sp(TEXT_SIZE_SP)
        val itemPadding = dp(KeyBubbleGeometryMath.ITEM_HORIZONTAL_PADDING_DP)
        // 每项等宽：取「最宽的一项」的宽度，手指滑动距离与下标才成线性（划到哪就是哪）。
        val perItemWidth = max(
            dp(KeyBubbleGeometryMath.ITEM_MIN_WIDTH_DP),
            labels.maxOf { textPaint.measureText(it) + itemPadding * 2f },
        )

        val anchorLocation = IntArray(2)
        anchor.getLocationInWindow(anchorLocation)
        val hostLocation = IntArray(2)
        host.getLocationInWindow(hostLocation)
        val hostScreenLocation = IntArray(2)
        host.getLocationOnScreen(hostScreenLocation)

        val keyLeft = (anchorLocation[0] - hostLocation[0]).toFloat()
        val keyTop = (anchorLocation[1] - hostLocation[1]).toFloat()

        // 指针要盖住**看得见的键帽**，而不是按键的布局矩形：布局矩形里还含着主题的
        // 键间距（keyHMargin / keyVMargin），照布局矩形画会连邻居键帽的边一起压住。
        val keyView = anchor as? KeyView
        val hInset = min((keyView?.hMargin ?: 0).toFloat(), anchor.width / 4f)
        val vInset = min((keyView?.vMargin ?: 0).toFloat(), anchor.height / 4f)

        val geometry = KeyBubbleGeometryMath.compute(
            hostWidth = host.width,
            keyLeft = keyLeft + hInset,
            keyTop = keyTop + vInset,
            keyRight = keyLeft + anchor.width - hInset,
            keyBottom = keyTop + anchor.height - vInset,
            perItemWidth = perItemWidth,
            itemCount = labels.size,
            bodyHeight = dp(KeyBubbleGeometryMath.BODY_HEIGHT_DP),
            screenMargin = dp(KeyBubbleGeometryMath.SCREEN_MARGIN_DP),
            cornerRadius = controller.bubbleCornerRadius,
        )

        this.geometry = geometry
        this.controller = controller
        this.labels = labels
        selected = 0
        contentLeftOnScreen = hostScreenLocation[0] + geometry.itemsLeft.roundToInt()

        visible = true
        onInvalidate()
        return true
    }

    override fun selectIndex(index: Int): Boolean {
        if (!visible || index !in labels.indices || index == selected) return false
        selected = index
        onInvalidate()
        return true
    }

    override fun dismiss() {
        if (!visible && labels.isEmpty()) return
        visible = false
        labels = emptyList()
        controller = null
        geometry = null
        onInvalidate()
    }

    /** 由宿主在 `dispatchDraw` 里调用，画在所有子 View 之上。 */
    fun draw(canvas: Canvas) {
        val controller = controller ?: return
        val geometry = geometry ?: return
        if (!visible || labels.isEmpty()) return

        buildPath(geometry)

        // 填充：不透明实色。主题里的键帽背景带 alpha（取用前已与键盘背景合成过一次），
        // 这里再把 alpha 顶到 255，确保气泡不会「透出下面的候选栏」。
        fillPaint.style = Paint.Style.FILL
        fillPaint.color = controller.bubbleBackgroundColor
        fillPaint.alpha = 255
        canvas.drawPath(path, fillPaint)

        // 描边：与键帽同一套颜色 / 厚度。主题的描边色自带 alpha（暗夜主题是
        // argb(40,255,255,255)），必须**保留**这个 alpha —— 顶成 255 会把气泡涂成一整块白。
        val strokeWidth = controller.bubbleStrokeWidth
        if (strokeWidth > 0 && (controller.bubbleStrokeColor ushr 24) != 0) {
            strokePaint.strokeWidth = strokeWidth.toFloat()
            strokePaint.color = controller.bubbleStrokeColor
            canvas.drawPath(path, strokePaint)
        }

        // 高亮项与文字只画在主体里：指针是一条细竖条，文字画上去会溢出到按键上。
        canvas.save()
        canvas.clipRect(
            geometry.bodyLeft - 1f,
            geometry.bodyTop - 1f,
            geometry.bodyRight + 1f,
            geometry.bodyBottom + 1f,
        )

        val radius = dp(HIGHLIGHT_RADIUS_DP)
        val gap = dp(HIGHLIGHT_GAP_DP)
        val bodyCenterY = (geometry.bodyTop + geometry.bodyBottom) / 2f
        val fontMetrics = textPaint.fontMetrics
        val baseline = bodyCenterY - (fontMetrics.ascent + fontMetrics.descent) / 2f

        for (index in labels.indices) {
            val left = geometry.itemsLeft + index * geometry.cellWidth
            if (index == selected) {
                highlightPaint.color = controller.bubbleSelectedBackgroundColor
                highlightPaint.alpha = 255
                canvas.drawRoundRect(
                    left + gap,
                    geometry.bodyTop + gap,
                    left + geometry.cellWidth - gap,
                    geometry.bodyBottom - gap,
                    radius,
                    radius,
                    highlightPaint,
                )
            }
            textPaint.color = if (index == selected) {
                controller.bubbleSelectedTextColor
            } else {
                controller.bubbleTextColor
            }
            textPaint.isFakeBoldText = index == selected
            canvas.drawText(labels[index], left + geometry.cellWidth / 2f, baseline, textPaint)
        }

        canvas.restore()
    }

    /**
     * 拼出「圆角主体 + 键宽指针」的闭合轮廓。
     *
     * 指针与主体交界处的两个**内凹**圆角是关键：没有它们，交界就是两个直角，
     * 看起来像两块拼起来的矩形；有了它们，整体才像一个从键帽里挤出来的形状。
     *
     * 气泡被屏幕夹住、主体一侧几乎贴着指针时，那一侧的底角按「齐平」处理
     * （不画圆角、直接交给内凹曲线），否则轮廓会自交、填充出现缺口。
     */
    private fun buildPath(geometry: KeyBubbleGeometry) {
        val r = geometry.bodyRadius
        val pointerWidth = geometry.pointerRight - geometry.pointerLeft
        val pointerHeight = (geometry.pointerBottom - geometry.bodyBottom).coerceAtLeast(0f)
        val pr = if (geometry.hasPointer) {
            min(r, min(pointerWidth / 2f, pointerHeight / 2f))
        } else {
            0f
        }

        val leftRoom = (geometry.pointerLeft - geometry.bodyLeft).coerceAtLeast(0f)
        val rightRoom = (geometry.bodyRight - geometry.pointerRight).coerceAtLeast(0f)
        val fillet = if (geometry.hasPointer) min(r, min(leftRoom, rightRoom)) else 0f
        val isRightFlush = !geometry.hasPointer || rightRoom <= r * 2f
        val isLeftFlush = !geometry.hasPointer || leftRoom <= r * 2f

        val left = geometry.bodyLeft
        val right = geometry.bodyRight
        val top = geometry.bodyTop
        val bottom = geometry.bodyBottom

        path.rewind()
        path.moveTo(left + r, top)
        path.lineTo(right - r, top)
        path.quadTo(right, top, right, top + r)

        // 右侧：主体右下角 → 内凹圆角 → 指针右侧
        if (isRightFlush) {
            path.lineTo(right, bottom)
        } else {
            path.lineTo(right, bottom - r)
            path.quadTo(right, bottom, right - r, bottom)
            path.lineTo(geometry.pointerRight + fillet, bottom)
        }
        if (geometry.hasPointer) {
            path.quadTo(geometry.pointerRight, bottom, geometry.pointerRight, bottom + fillet)
            path.lineTo(geometry.pointerRight, geometry.pointerBottom - pr)
            path.quadTo(
                geometry.pointerRight, geometry.pointerBottom,
                geometry.pointerRight - pr, geometry.pointerBottom,
            )
            path.lineTo(geometry.pointerLeft + pr, geometry.pointerBottom)
            path.quadTo(
                geometry.pointerLeft, geometry.pointerBottom,
                geometry.pointerLeft, geometry.pointerBottom - pr,
            )
            path.lineTo(geometry.pointerLeft, bottom + fillet)
            path.quadTo(geometry.pointerLeft, bottom, geometry.pointerLeft - fillet, bottom)
        }

        // 左侧：回到主体左下角 → 左上角
        if (isLeftFlush) {
            path.lineTo(left, bottom)
        } else {
            path.lineTo(left + r, bottom)
            path.quadTo(left, bottom, left, bottom - r)
        }
        path.lineTo(left, top + r)
        path.quadTo(left, top, left + r, top)
        path.close()
    }

    private companion object {
        const val TEXT_SIZE_SP = 20f

        const val HIGHLIGHT_RADIUS_DP = 6f

        /** 高亮块与所在项边界留的缝，避免相邻两项的高亮块贴在一起。 */
        const val HIGHLIGHT_GAP_DP = 2f
    }
}
