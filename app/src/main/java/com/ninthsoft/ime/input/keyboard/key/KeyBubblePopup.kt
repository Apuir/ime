package com.ninthsoft.ime.input.keyboard.key

import android.content.Context
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.TextView
import kotlin.math.roundToInt

/**
 * 按键气泡：长按 / 上滑按键时从键帽上方「长出来」的一条候选栏。
 *
 * 外形是一块圆角矩形加一个指向按键中心的三角形尾巴 —— 尾巴不是单独一层，
 * 而是和矩形一起构成同一个 `Path`，这样两者共用一套圆角，看起来才像从按键里长出来的。
 *
 * 交互：手指不离开屏幕，左右滑动切换高亮项，抬手时提交高亮项（提交由调用方完成）。
 * 位置：默认贴在按键正上方；上面放不下（键盘很高、或按的是顶行键）就翻到按键下方，尾巴朝上；
 * 屏幕左右边缘处夹回屏幕内，同时尾巴仍尽量对准按键中心。
 */
class KeyBubblePopup(private val context: Context) {

    /** 主题里给的是 dp，这里统一换算成像素；不能改用 `Context.dp()`，它的返回值是 Float。 */
    private val density: Float = context.resources.displayMetrics.density

    private fun dpInt(value: Int): Int = (value * density).roundToInt()

    private var popupWindow: PopupWindow? = null
    private var itemViews: List<TextView> = emptyList()

    var selectedIndex = 0
        private set

    /** 气泡内容区（不含尾巴）的宽度。 */
    var contentWidth = 0
        private set

    /** 气泡内容区左边缘在窗口中的 x 坐标。 */
    var contentLeft = 0
        private set

    val isShowing: Boolean get() = popupWindow != null

    val itemCount: Int get() = itemViews.size

    /** 相邻两项中心点的水平间距，供手势层把滑动距离换算成第几项。 */
    val itemStep: Float get() = if (itemCount == 0) 0f else contentWidth.toFloat() / itemCount

    fun show(
        anchor: View,
        items: List<KeyBubbleItem>,
        normalTextColor: Int,
        selectedTextColor: Int,
        bgColor: Int,
        selectedBgColor: Int,
        cornerRadius: Float = dpInt(9).toFloat(),
        strokeColor: Int = android.graphics.Color.TRANSPARENT,
        strokeWidth: Int = 0,
        selectIndex: Int = 0,
    ) {
        dismiss()
        if (items.isEmpty()) return

        val itemHeight = dpInt(42)
        val itemMinWidth = dpInt(34)
        val horizontalPadding = dpInt(6)
        // 圆角半径跟键帽一致（主题的 keyRadius），描边也用键帽那套颜色 + 厚度，
        // 这样气泡和键盘看起来是同一套控件；描边同时起到「和背景拉开」的作用。
        val corner = cornerRadius
        val tailWidth = dpInt(14).toFloat()
        val tailHeight = dpInt(7).toFloat()

        val container = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }

        itemViews = items.map { item ->
            TextView(context).apply {
                text = item.label
                gravity = Gravity.CENTER
                includeFontPadding = false
                maxLines = 1
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
                setTextColor(normalTextColor)
                setPadding(horizontalPadding, 0, horizontalPadding, 0)
                layoutParams = LinearLayout.LayoutParams(0, itemHeight, 1f)
                container.addView(this)
            }
        }

        // 先量一次拿到「每项按文字宽度算出来的最小宽度」，再取所有项里最大的那个，
        // 让每一项等宽 —— 这样手指滑动的距离和下标是线性的，才有「划到哪个就是哪个」的手感。
        val perItemWidth = maxOf(
            itemMinWidth,
            items.maxOf { item ->
                val probe = TextView(context).apply {
                    text = item.label
                    includeFontPadding = false
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
                    setPadding(horizontalPadding, 0, horizontalPadding, 0)
                }
                probe.measure(
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
                    View.MeasureSpec.makeMeasureSpec(itemHeight, View.MeasureSpec.AT_MOST),
                )
                probe.measuredWidth
            },
        )
        contentWidth = perItemWidth * itemCount
        container.measure(
            View.MeasureSpec.makeMeasureSpec(contentWidth, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(itemHeight, View.MeasureSpec.EXACTLY),
        )
        val popupHeight = itemHeight.toFloat() + tailHeight

        val anchorLocation = IntArray(2)
        anchor.getLocationInWindow(anchorLocation)
        val anchorCenterX = anchorLocation[0] + anchor.width / 2
        val metrics = context.resources.displayMetrics
        val screenWidth = metrics.widthPixels
        val screenHeight = metrics.heightPixels

        contentLeft = (anchorCenterX - contentWidth / 2)
            .coerceIn(0, (screenWidth - contentWidth).coerceAtLeast(0))

        // 尾巴对准按键中心；气泡被夹到屏幕边缘时尾巴跟着偏移，但不能越过圆角。
        val tailMargin = corner + tailWidth
        val tailCenterX = (anchorCenterX - contentLeft).toFloat()
            .coerceIn(
                tailMargin,
                (contentWidth - tailMargin).coerceAtLeast(tailMargin),
            )

        val spaceAbove = anchorLocation[1]
        val tailDown = spaceAbove >= popupHeight
        val anchorBottom = anchorLocation[1] + anchor.height
        val maxPopupTop = (screenHeight - popupHeight).coerceAtLeast(0f)
        val popupTop = if (tailDown) {
            (spaceAbove - popupHeight).toInt()
        } else {
            (anchorBottom + tailHeight).coerceAtMost(maxPopupTop).toInt()
        }
        container.background = bubbleDrawable(
            bgColor = bgColor,
            width = contentWidth,
            height = popupHeight.toInt(),
            corner = corner,
            tailCenterX = tailCenterX,
            tailWidth = tailWidth,
            tailHeight = tailHeight,
            tailDown = tailDown,
            strokeColor = strokeColor,
            strokeWidth = strokeWidth,
        )
        popupWindow = PopupWindow(container, contentWidth, popupHeight.toInt(), false).apply {
            isOutsideTouchable = false
            isTouchable = false
            // 气泡可能高过 IME 窗口顶部（键盘高度调大、或按顶行键时），必须允许画出窗口外。
            setClippingEnabled(false)
            elevation = dpInt(8).toFloat()
            showAtLocation(anchor, Gravity.TOP or Gravity.START, contentLeft, popupTop)
        }

        selectIndex(
            index = selectIndex,
            normalTextColor = normalTextColor,
            selectedTextColor = selectedTextColor,
            selectedBgColor = selectedBgColor,
        )
    }

    /**
     * 切换高亮项。返回是否发生了变化，调用方据此只在真正切换时补一次触感反馈。
     */
    fun selectIndex(
        index: Int,
        normalTextColor: Int,
        selectedTextColor: Int,
        selectedBgColor: Int,
    ): Boolean {
        if (index !in itemViews.indices) return false
        val changed = index != selectedIndex
        selectedIndex = index
        itemViews.forEachIndexed { i, view ->
            if (i == index) {
                view.setTextColor(selectedTextColor)
                view.background = GradientDrawable().apply {
                    setColor(selectedBgColor)
                    cornerRadius = dpInt(6).toFloat()
                }
            } else {
                view.setTextColor(normalTextColor)
                view.background = null
            }
        }
        return changed
    }

    fun dismiss() {
        popupWindow?.dismiss()
        popupWindow = null
        itemViews = emptyList()
        contentWidth = 0
        contentLeft = 0
        selectedIndex = 0
    }

    /**
     * 生成「圆角矩形 + 圆角尾巴」的背景。
     *
     * 背景**一定是不透明实色**：这是浮在键盘之上的一层，半透明会让人以为没画出来
     * （`specialKeyBackground` / `accentKeyBackground` 在主题里都带 alpha，用之前已经跟
     * 键盘背景合成过了）。
     *
     * 轮廓用 `PathMeasure` 沿正反两段拼出来：正向从左上角画到尾巴左根，反向从尾巴右根绕回右上角，
     * 两段首尾相接正好闭合。圆角用二次贝塞尔近似（半径小的时候和真圆弧肉眼无差）。
     */
    private fun bubbleDrawable(
        bgColor: Int,
        width: Int,
        height: Int,
        corner: Float,
        tailCenterX: Float,
        tailWidth: Float,
        tailHeight: Float,
        tailDown: Boolean,
        strokeColor: Int,
        strokeWidth: Int,
    ): Drawable {
        val w = width.toFloat()
        val h = height.toFloat()
        val r = corner.coerceIn(0f, minOf(w, h) / 4f)
        val bodyTop = if (tailDown) 0f else tailHeight
        val bodyBottom = if (tailDown) h - tailHeight else h
        val half = tailWidth / 2f
        val tailGap = 2f * density
        val minTailLeft = r + tailGap
        val maxTailLeft = (w - r - half - tailGap).coerceAtLeast(minTailLeft)
        val tailLeft = (tailCenterX - half).coerceIn(minTailLeft, maxTailLeft)
        val tailRight = tailLeft + tailWidth

        // 尾巴斜边与底边之间的圆角半径。
        val t = (tailWidth / 2.6f).coerceAtMost(half)

        val forward = Path().apply {
            moveTo(r, bodyTop)
            lineTo(w - r, bodyTop)
            quadTo(w, bodyTop, w, bodyTop + r)
            lineTo(w, bodyBottom - r)
            quadTo(w, bodyBottom, w - r, bodyBottom)
            lineTo(tailRight + t, bodyBottom)
            if (tailDown) {
                quadTo(tailRight, bodyBottom, tailRight - t * 0.35f, bodyBottom + t * 0.7f)
                lineTo(tailCenterX, h)
            } else {
                quadTo(tailRight, bodyBottom, tailRight - t * 0.35f, bodyBottom - t * 0.7f)
                lineTo(tailCenterX, 0f)
            }
        }
        val backward = Path().apply {
            moveTo(tailCenterX, if (tailDown) h else 0f)
            if (tailDown) {
                lineTo(tailLeft + t * 0.35f, bodyBottom + t * 0.7f)
                quadTo(tailLeft, bodyBottom, tailLeft - t, bodyBottom)
            } else {
                lineTo(tailLeft + t * 0.35f, bodyBottom - t * 0.7f)
                quadTo(tailLeft, bodyBottom, tailLeft - t, bodyBottom)
            }
            lineTo(r, bodyBottom)
            quadTo(0f, bodyBottom, 0f, bodyBottom - r)
            lineTo(0f, bodyTop + r)
            quadTo(0f, bodyTop, r, bodyTop)
        }

        val measure = PathMeasure()
        val outline = Path()
        measure.setPath(forward, false)
        measure.getSegment(0f, measure.length, outline, true)
        measure.setPath(backward, false)
        measure.getSegment(0f, measure.length, outline, true)
        outline.close()

        return BubbleDrawable(
            path = outline,
            fillColor = bgColor,
            strokeColor = strokeColor,
            strokeWidth = strokeWidth,
        )
    }
}

/**
 * 气泡背景：把一条闭合路径按实色填充（+ 可选描边）画出来。
 *
 * 没有用 `ShapeDrawable(PathShape(...))`：`PathShape` 会把路径按原始尺寸归一化后再缩放，
 * 这里本来就按像素坐标构建，直接画更直观，也不会出现「看起来是半透明」的意外。
 * 填充色强制 alpha = 255，保证气泡一定是实心的。
 */
private class BubbleDrawable(
    private val path: Path,
    fillColor: Int,
    private val strokeColor: Int,
    private val strokeWidth: Int,
) : Drawable() {

    /** 气泡必须是实心的：主题里的背景色带 alpha，这里统一把 alpha 顶到 255。 */
    private val opaqueFill: Int = fillColor or ALPHA_MASK

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        if (strokeWidth > 0) {
            style = Paint.Style.FILL_AND_STROKE
            this.strokeWidth = strokeWidth.toFloat()
            strokeJoin = Paint.Join.ROUND
            strokeCap = Paint.Cap.ROUND
            // FILL_AND_STROKE 下 paint.color 同时决定填充与描边颜色：
            // 描边压在填充边缘上，所以取描边色既画出了边框，也铺满了内部。
            color = strokeColor
        } else {
            style = Paint.Style.FILL
            color = opaqueFill
        }
        alpha = 255
    }

    override fun draw(canvas: Canvas) {
        if (strokeWidth <= 0) {
            paint.color = opaqueFill
            paint.alpha = 255
        }
        canvas.drawPath(path, paint)
    }

    override fun setAlpha(alpha: Int) {
        paint.alpha = alpha
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        paint.colorFilter = colorFilter
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.OPAQUE

    private companion object {
        const val ALPHA_MASK = -0x1000000
    }
}
