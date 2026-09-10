package com.ninthsoft.ime.input.keyboard.key

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.OverScroller
import com.ninthsoft.ime.data.keyboard.theme.KeyboardColors
import com.ninthsoft.ime.data.manager.KeyboardManager
import kotlin.math.abs
import kotlin.math.max

@SuppressLint("ViewConstructor")
class GridKeyboardView(
    context: Context,
    private val colors: KeyboardColors.ColorScheme,
    private var columns: Int = 5,
    private var rows: Int = 5,
) : ViewGroup(context) {

    var onKeyAction: ((KeyboardAction) -> Unit)? = null
    var onKeyPressed: ((KeyView) -> Unit)? = null

    private var scrollOffsetY = 0f
    private var rowH = 0
    private var totalRows = 0

    private val scroller = OverScroller(context)
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val minFlingVelocity = ViewConfiguration.get(context).scaledMinimumFlingVelocity
    private val maxFlingVelocity = ViewConfiguration.get(context).scaledMaximumFlingVelocity
    private var velocityTracker: VelocityTracker? = null
    private var lastY = 0f
    private var downY = 0f
    private var dragging = false
    private var stretch = 0f
    private var stretchAnimator: ValueAnimator? = null
    private val overScrollLimit get() = max(height * 0.45f, 1f)

    /**
     * 上一次实际布局过的可见行范围。
     * 符号分类动辄上百个符号，如果每次 ACTION_MOVE 都 requestLayout，
     * 会逐帧 measure/layout 全部按键（每个 KeyView 内部还套 ConstraintLayout），
     * 体感就是「划着划着卡住」。这里改成画布平移 + 仅在跨行时重新布局。
     */
    private var lastVisibleRange: IntRange = IntRange.EMPTY

    private val visibleRowRange: IntRange
        get() {
            if (rowH <= 0 || height <= 0) return IntRange.EMPTY
            val first = (scrollOffsetY / rowH).toInt() - 1
            val last = ((scrollOffsetY + height) / rowH).toInt() + 1
            return first..last
        }

    private val maxScroll
        get(): Int {
            val contentH = totalRows * rowH
            return (contentH - height).coerceAtLeast(0)
        }

    fun setItems(items: List<KeyDef>) {
        removeAllViews()
        for (def in items) {
            val keyView = createKeyView(def)
            addView(keyView)
        }
        scrollOffsetY = 0f
        stretch = 0f
        lastVisibleRange = IntRange.EMPTY
        stretchAnimator?.cancel()
        if (!scroller.isFinished) scroller.abortAnimation()
        scrollTo(0, 0)
        requestLayout()
    }

    private fun createKeyView(def: KeyDef): KeyView {
        return when (def.appearance) {
            is KeyDef.Appearance.AltText -> AltTextKeyView(context, colors, def.appearance)
            is KeyDef.Appearance.ImageText -> ImageTextKeyView(context, colors, def.appearance)
            is KeyDef.Appearance.Text -> TextKeyView(context, colors, def.appearance)
            is KeyDef.Appearance.Image -> ImageKeyView(context, colors, def.appearance)
            else -> TextKeyView(
                context, colors, KeyDef.Appearance.Text(
                    displayText = "?", textSize = 16f, percentWidth = 1f / columns,
                )
            )
        }.apply {
            borderStroke = KeyboardManager.Keyboard.KeyBorderStroke.isEnabled(context)
            hMargin = 0
            vMargin = 0
            setOnClickListener {
                val action =
                    def.behaviors.filterIsInstance<KeyDef.Behavior.Press>().firstOrNull()?.action
                if (action != null) {
                    onKeyAction?.invoke(action)
                }
            }
            onPressedChanged = { key ->
                if (key.isPressed) onKeyPressed?.invoke(key)
            }
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = MeasureSpec.getSize(heightMeasureSpec)
        val colW = w / columns
        val newRowH = if (rows > 0) h / rows else h
        if (newRowH != rowH) lastVisibleRange = IntRange.EMPTY
        rowH = newRowH
        totalRows = (childCount + columns - 1) / columns
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            child.measure(
                MeasureSpec.makeMeasureSpec(colW, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(rowH, MeasureSpec.EXACTLY)
            )
        }
        setMeasuredDimension(w, h)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val colW = (r - l) / columns
        val range = visibleRowRange
        lastVisibleRange = range
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            val row = i / columns
            val visible = row in range
            val target = if (visible) VISIBLE else GONE
            if (child.visibility != target) child.visibility = target
            // 视口外的按键直接 GONE：既不参与布局也不参与绘制
            if (!visible) continue
            val col = i % columns
            val top = row * rowH
            child.layout(col * colW, top, (col + 1) * colW, top + rowH)
        }
    }

    /**
     * 更新滚动位置：用 scrollTo 做画布平移（只 invalidate），
     * 只有可见行范围变化时才 requestLayout 重新摆放这一屏的按键。
     * stretch 之前完全没有参与布局，导致越界拖动零反馈，这里一并补上。
     */
    private fun applyScroll() {
        scrollTo(0, (scrollOffsetY - stretch).toInt())
        val range = visibleRowRange
        if (range != lastVisibleRange) {
            lastVisibleRange = range
            requestLayout()
        }
    }

    override fun computeScroll() {
        if (dragging) return
        if (scroller.computeScrollOffset()) {
            scrollOffsetY = scroller.currY.toFloat()
            clampScroll()
            applyScroll()
        }
    }

    private fun clampScroll() {
        scrollOffsetY = scrollOffsetY.coerceIn(0f, maxScroll.toFloat())
    }

    private fun hitTest(x: Float, y: Float): Int {
        val adjustedY = (y + scrollOffsetY).toInt()
        val row = if (rowH > 0) adjustedY / rowH else 0
        val col = (x / ((right - left) / columns)).toInt().coerceIn(0, columns - 1)
        val i = row * columns + col
        return if (i in 0 until childCount) i else -1
    }

    private fun dragBy(deltaY: Float) {
        val proposed = scrollOffsetY + deltaY
        when {
            proposed < 0f -> {
                scrollOffsetY = 0f
                stretch = rubberBand(-proposed)
            }

            proposed > maxScroll -> {
                scrollOffsetY = maxScroll.toFloat()
                stretch = -rubberBand(proposed - maxScroll)
            }

            else -> {
                scrollOffsetY = proposed
                stretch = 0f
            }
        }
    }

    private fun rubberBand(d: Float): Float {
        val limit = overScrollLimit
        return limit * d / (limit + d)
    }

    private fun springBackIfNeeded(): Boolean {
        if (stretch != 0f) {
            animateStretchBack()
            return true
        }
        if (scrollOffsetY < 0f || scrollOffsetY > maxScroll) {
            scroller.springBack(
                0, scrollOffsetY.toInt(), 0, 0, 0, maxScroll
            )
            postInvalidateOnAnimation()
            return true
        }
        return false
    }

    private fun animateStretchBack() {
        stretchAnimator?.cancel()
        stretchAnimator = ValueAnimator.ofFloat(stretch, 0f).apply {
            duration = 260
            addUpdateListener {
                stretch = it.animatedValue as Float
                applyScroll()
            }
            start()
        }
    }

    private fun fling(velocityY: Int) {
        @Suppress("DEPRECATION") scroller.fling(
            0, scrollOffsetY.toInt(), 0, velocityY, 0, 0, 0, maxScroll
        )
        postInvalidateOnAnimation()
    }

    private fun recycleVelocityTracker() {
        velocityTracker?.recycle()
        velocityTracker = null
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onInterceptTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                stretchAnimator?.cancel()
                if (!scroller.isFinished) scroller.abortAnimation()
                velocityTracker = VelocityTracker.obtain()
                velocityTracker?.addMovement(event)
                downY = event.y
                lastY = event.y
                dragging = false
                return false
            }

            MotionEvent.ACTION_MOVE -> {
                velocityTracker?.addMovement(event)
                startDragIfNeeded(event.y)
                if (dragging) return true
                lastY = event.y
                return false
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                recycleVelocityTracker()
                dragging = false
                return false
            }
        }
        return super.onInterceptTouchEvent(event)
    }

    /** 超过 touchSlop 就接管为拖动手势。放在这里是因为它可能从 intercept 或 touch 两条路径进来。 */
    private fun startDragIfNeeded(y: Float) {
        if (dragging) return
        if (abs(y - downY) <= touchSlop) return
        dragging = true
        stretch = 0f
        if (!scroller.isFinished) scroller.abortAnimation()
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            // 手势落在最后一行右侧的空白格时，没有任何子 View 成为 touch target，
            // 后续 MOVE 不会再回到 onInterceptTouchEvent，只能在 onTouchEvent 里启动拖动。
            MotionEvent.ACTION_DOWN -> return true

            MotionEvent.ACTION_MOVE -> {
                velocityTracker?.addMovement(event)
                startDragIfNeeded(event.y)
                if (dragging) {
                    val dy = lastY - event.y
                    dragBy(dy)
                    applyScroll()
                }
                lastY = event.y
                return true
            }

            MotionEvent.ACTION_UP -> {
                velocityTracker?.addMovement(event)
                velocityTracker?.computeCurrentVelocity(1000, maxFlingVelocity.toFloat())
                val velocityY = velocityTracker?.yVelocity ?: 0f
                recycleVelocityTracker()

                if (dragging) {
                    dragging = false
                    springBackIfNeeded()
                    if (abs(velocityY) >= minFlingVelocity) fling(-velocityY.toInt())
                }
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                recycleVelocityTracker()
                dragging = false
                springBackIfNeeded()
                return true
            }
        }
        return true
    }
}
