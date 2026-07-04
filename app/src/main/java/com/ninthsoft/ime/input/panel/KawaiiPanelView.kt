package com.ninthsoft.ime.input.panel

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.view.MotionEvent
import android.view.View
import com.ninthsoft.ime.R
import com.ninthsoft.ime.data.theme.ThemeManager
import com.ninthsoft.ime.engine.data.EngineMessage
import com.ninthsoft.ime.input.panel.toolbar.IdleRenderer
import kotlin.math.abs
import kotlin.math.roundToInt

@SuppressLint("UseCompatLoadingForDrawables")
class KawaiiPanelView(context: Context) : View(context) {

    var currentRenderer: IRenderer
    var scrollX = 0f
    var onTap: ((KawaiiPanel.TouchResult?) -> Unit)? = null
    var onExpandChanged: ((Boolean, List<EngineMessage.Candidate>) -> Unit)? = null
    var isExpanded: Boolean = false
        private set

    fun setExpanded(expanded: Boolean) {
        if (isExpanded == expanded) return
        isExpanded = expanded
        if (expanded) {
            val candidates = (currentRenderer as? ComposingRenderer)?.candidates ?: return
            onExpandChanged?.invoke(true, candidates)
        } else {
            onExpandChanged?.invoke(false, emptyList())
        }
        invalidate()
    }

    private val paints = Paints(context)
    private var lastTouchX = 0f
    private var isScrolling = false
    private val screenDensity = resources.displayMetrics.density
    private var rerankShimmer: ValueAnimator? = null
    private var rerankInsert: ValueAnimator? = null

    fun showRerankAnimation() {
        val renderer = currentRenderer as? ComposingRenderer ?: return
        rerankShimmer?.cancel()
        rerankInsert?.cancel()

        renderer.rerankInsertProgress = 0.01f
        rerankInsert = ValueAnimator.ofFloat(0.01f, 1f).apply {
            duration = 220
            addUpdateListener {
                renderer.rerankInsertProgress = animatedValue as Float
                invalidate()
            }
            start()
        }

        rerankShimmer = ValueAnimator.ofFloat(0f, 1f).apply {
            startDelay = 220
            duration = 700
            repeatCount = ValueAnimator.INFINITE
            addUpdateListener {
                renderer.rerankAnimProgress = animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    fun setRerankedCandidate(candidate: EngineMessage.Candidate) {
        rerankShimmer?.cancel()
        rerankShimmer = null
        rerankInsert?.cancel()
        rerankInsert = null
        val renderer = currentRenderer as? ComposingRenderer
        renderer?.rerankAnimProgress = -1f
        renderer?.rerankInsertProgress = 1f
        renderer?.rerankedText = candidate.text
        invalidate()
    }

    init {
        paints.updateColors(context)
        paints.applyDensity(screenDensity)
        val hPad = ThemeManager.Keyboard.Padding.getHorizontalDp(context).toFloat()
        currentRenderer = IdleRenderer(
            context.getDrawable(R.drawable.ic_keyboard_menu),
            context.getDrawable(R.drawable.ic_keyboard_arrow_back),
            context.getDrawable(R.drawable.ic_keyboard_clipboard),
            context.getDrawable(R.drawable.ic_keyboard_redo),
            context.getDrawable(R.drawable.ic_keyboard_undo),
            context.getDrawable(R.drawable.ic_keyboard_palette),
            context.getDrawable(R.drawable.ic_keyboard_cursor_move),
            context.getDrawable(R.drawable.ic_keyboard_keyboard_close),
            hPad,
        )
    }

    fun refreshTheme() {
        paints.updateColors(context)
        invalidate()
    }

    fun refreshDensity() {
        paints.applyDensity(resources.displayMetrics.density)
        invalidate()
    }

    fun updateHorizontalPadding(hPadDp: Float) {
        when (val r = currentRenderer) {
            is IdleRenderer -> r.horizontalPaddingDp = hPadDp
            is ComposingRenderer -> r.horizontalPaddingDp = hPadDp
        }
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(
            MeasureSpec.getSize(widthMeasureSpec),
            MeasureSpec.getSize(heightMeasureSpec),
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width <= 0 || height <= 0) return
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paints.bgPaint)
        currentRenderer.draw(canvas, width, height, paints, scrollX, isExpanded, screenDensity)
    }

    private var pressAnimator: ValueAnimator? = null

    private fun startPressAnimation(renderer: IdleRenderer) {
        pressAnimator?.cancel()
        renderer.pressAlpha = 130
        renderer.pressRadius = 0f
        pressAnimator = ValueAnimator.ofFloat(0f, renderer.pressRadiusMax).apply {
            duration = 500
            addUpdateListener {
                renderer.pressRadius = animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastTouchX = event.x
                isScrolling = false
                parent.requestDisallowInterceptTouchEvent(true)
            }

            MotionEvent.ACTION_MOVE -> {
                if (currentRenderer !is ComposingRenderer) return true
                val dx = event.x - lastTouchX
                val threshold = 10 * screenDensity
                if (isScrolling || abs(dx) > threshold) {
                    isScrolling = true
                    val renderer = currentRenderer as ComposingRenderer
                    scrollX = (scrollX + dx).coerceIn(-renderer.maxScrollX, 0f)
                    lastTouchX = event.x
                    invalidate()
                }
            }

            MotionEvent.ACTION_UP -> {
                if (!isScrolling) {
                    val result = currentRenderer.hitTest(
                        event.x, event.y, width, height, scrollX, isExpanded, screenDensity,
                    )
                    if (currentRenderer is IdleRenderer && result != null) {
                        startPressAnimation(currentRenderer as IdleRenderer)
                    }
                    onTap?.invoke(result)
                }
            }
        }
        return true
    }
}
