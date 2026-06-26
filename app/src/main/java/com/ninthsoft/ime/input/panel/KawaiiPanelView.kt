package com.ninthsoft.ime.input.panel

import android.annotation.SuppressLint
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.view.MotionEvent
import android.view.View
import com.ninthsoft.ime.data.keyboard.theme.KeyboardColors
import com.ninthsoft.ime.engine.data.EngineMessage
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sin

class KawaiiPanelView(context: Context) : View(context) {

    class Paints(context: Context) {
        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        val dividerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        val toolbarTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textAlign = Paint.Align.CENTER
            typeface = Typeface.DEFAULT_BOLD
        }
        val candidateBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        val candidateTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { }
        val candidateIndexPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { }

        fun updateColors(context: Context) {
            val panel = KeyboardColors.resolve(context).panel
            bgPaint.color = panel.background
            dividerPaint.color = (panel.background and 0x00ffffff) or 0x18000000
            toolbarTextPaint.color = panel.toolbarText
            candidateBgPaint.color = panel.candidateBackground
            candidateTextPaint.color = panel.candidateText
            candidateIndexPaint.color = panel.candidateIndex
        }

        fun applyDensity(density: Float) {
            toolbarTextPaint.textSize = 16f * density
            candidateTextPaint.textSize = 17f * density
            candidateIndexPaint.textSize = 14f * density
        }
    }

    interface IRenderer {
        fun draw(canvas: Canvas, width: Int, height: Int, paints: Paints, scrollX: Float = 0f)
        fun hitTest(
            x: Float, y: Float, width: Int, height: Int, scrollX: Float = 0f,
        ): KawaiiPanel.TouchResult?
    }

    class IdleRenderer : IRenderer {

        private data class ButtonDef(val label: String, val action: KawaiiPanel.Action)

        private val buttons = listOf(
            ButtonDef("符", KawaiiPanel.Action.SwitchKeyboard),
            ButtonDef("剪", KawaiiPanel.Action.Clipboard),
            ButtonDef("音", KawaiiPanel.Action.ToggleVoice),
            ButtonDef("⚙", KawaiiPanel.Action.Settings),
        )

        override fun draw(canvas: Canvas, width: Int, height: Int, paints: Paints, scrollX: Float) {
            if (width <= 0 || height <= 0) return
            val btnW = width / buttons.size
            val textY =
                height / 2f - (paints.toolbarTextPaint.descent() + paints.toolbarTextPaint.ascent()) / 2f
            for ((i, btn) in buttons.withIndex()) {
                canvas.drawText(btn.label, btnW * i + btnW / 2f, textY, paints.toolbarTextPaint)
            }
        }

        override fun hitTest(
            x: Float, y: Float, width: Int, height: Int, scrollX: Float,
        ): KawaiiPanel.TouchResult? {
            if (width <= 0) return null
            val index = (x / (width / buttons.size)).toInt().coerceIn(0, buttons.size - 1)
            return KawaiiPanel.TouchResult.ToolbarAction(buttons[index].action)
        }
    }

    class ComposingRenderer(private val candidates: List<EngineMessage.Candidate>) : IRenderer {

        private data class PillRect(val left: Float, val right: Float, val index: Int)

        private var lastPills: List<PillRect> = emptyList()
        var maxScrollX: Float = 0f
        var rerankAnimProgress: Float = -1f
        var rerankedText: String? = null
        var rerankInsertProgress: Float = 0f

        override fun draw(canvas: Canvas, width: Int, height: Int, paints: Paints, scrollX: Float) {
            lastPills = emptyList()
            if (width <= 0 || height <= 0 || candidates.isEmpty()) return

            val density = height / 48f
            val pillH = 34f * density
            val pillY = (height - pillH) / 2f
            val pillR = 6f * density
            val pad = 8f * density
            val gap = 6f * density
            val textY =
                pillY + pillH / 2f - (paints.candidateTextPaint.descent() + paints.candidateTextPaint.ascent()) / 2f

            val hasRerankPill = rerankInsertProgress > 0f || rerankedText != null
            val rerankLabel = "0. "
            val rerankText = rerankedText ?: "\u22EF"
            val rerankPillW = if (hasRerankPill) {
                paints.candidateIndexPaint.measureText(rerankLabel) +
                    paints.candidateTextPaint.measureText(rerankText) + pad * 2
            } else 0f
            val slideW = rerankPillW * rerankInsertProgress.coerceIn(0f, 1f)

            var x = pad + slideW + if (hasRerankPill) gap else 0f
            val pills = mutableListOf<PillRect>()
            for (c in candidates) {
                val indexW = paints.candidateIndexPaint.measureText("${c.index}. ")
                val textW = paints.candidateTextPaint.measureText(c.text)
                val commentW =
                    if (c.comment.isNotEmpty()) paints.candidateIndexPaint.measureText(" ${c.comment}") else 0f
                val pillW = indexW + textW + commentW + pad * 2
                pills.add(PillRect(x, x + pillW, c.index))
                x += pillW + gap
            }
            lastPills = pills

            maxScrollX = maxOf(0f, x - pad - width)

            canvas.save()
            canvas.clipRect(0f, 0f, width.toFloat(), height.toFloat())
            canvas.translate(scrollX, 0f)

            if (hasRerankPill && slideW > 0f) {
                val rerankLeft = pad
                val rerankRight = pad + slideW
                canvas.drawRoundRect(
                    rerankLeft, pillY, rerankRight, pillY + pillH, pillR, pillR,
                    paints.candidateBgPaint,
                )

                canvas.save()
                canvas.clipRect(rerankLeft, 0f, rerankRight, height.toFloat())
                paints.candidateIndexPaint.alpha = (255 * rerankInsertProgress.coerceIn(0f, 1f)).toInt()
                paints.candidateTextPaint.alpha = paints.candidateIndexPaint.alpha
                val idxW = paints.candidateIndexPaint.measureText(rerankLabel)
                canvas.drawText(rerankLabel, rerankLeft + pad, textY, paints.candidateIndexPaint)
                canvas.drawText(rerankText, rerankLeft + pad + idxW, textY, paints.candidateTextPaint)
                paints.candidateIndexPaint.alpha = 255
                paints.candidateTextPaint.alpha = 255
                canvas.restore()

                lastPills = listOf(PillRect(rerankLeft, rerankRight, -1)) + lastPills

                if (rerankAnimProgress >= 0f) {
                    val shimmerAlpha = ((sin(rerankAnimProgress * Math.PI * 2).toFloat() + 1f) / 2f * 100 + 30).toInt()
                    val shimmerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        color = Color.argb(shimmerAlpha, 100, 140, 255)
                        style = Paint.Style.FILL
                    }
                    canvas.drawRoundRect(
                        rerankLeft, pillY, rerankRight, pillY + pillH,
                        pillR, pillR, shimmerPaint,
                    )
                }
            }

            for ((i, c) in candidates.withIndex()) {
                val pill = pills[i]
                canvas.drawRoundRect(
                    pill.left, pillY, pill.right, pillY + pillH, pillR, pillR,
                    paints.candidateBgPaint,
                )
                val indexW = paints.candidateIndexPaint.measureText("${c.index}. ")
                val textW = paints.candidateTextPaint.measureText(c.text)
                canvas.drawText(
                    "${c.index + 1}. ", pill.left + pad, textY, paints.candidateIndexPaint,
                )
                canvas.drawText(c.text, pill.left + pad + indexW, textY, paints.candidateTextPaint)
                if (c.comment.isNotEmpty()) {
                    canvas.drawText(
                        " ${c.comment}",
                        pill.left + pad + indexW + textW,
                        textY,
                        paints.candidateIndexPaint,
                    )
                }
            }

            canvas.restore()
        }

        override fun hitTest(
            x: Float, y: Float, width: Int, height: Int, scrollX: Float,
        ): KawaiiPanel.TouchResult? {
            if (lastPills.isEmpty()) return null
            val density = height / 48f
            val pillH = 34f * density
            val pillY = (height - pillH) / 2f
            if (y < pillY || y > pillY + pillH) return null

            val adjustedX = x - scrollX
            for (pill in lastPills) {
                if (adjustedX >= pill.left && adjustedX <= pill.right) {
                    if (pill.index == -1) {
                        return KawaiiPanel.TouchResult.SelectRerankedCandidate(rerankedText ?: "")
                    }
                    val c = candidates.find { it.index == pill.index } ?: return null
                    return KawaiiPanel.TouchResult.SelectCandidate(c)
                }
            }
            return null
        }
    }

    var currentRenderer: IRenderer = IdleRenderer()
    var scrollX = 0f
    var onTap: ((KawaiiPanel.TouchResult?) -> Unit)? = null

    private val paints = Paints(context)
    private var lastTouchX = 0f
    private var isScrolling = false
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
        paints.applyDensity(resources.displayMetrics.density)
    }

    fun refreshTheme() {
        paints.updateColors(context)
        invalidate()
    }

    fun refreshDensity() {
        paints.applyDensity(resources.displayMetrics.density)
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val barH = (48 * resources.displayMetrics.density).roundToInt()
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), barH)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width <= 0 || height <= 0) return
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paints.bgPaint)
        currentRenderer.draw(canvas, width, height, paints, scrollX)
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
                val threshold = 10 * resources.displayMetrics.density
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
                    val result = currentRenderer.hitTest(event.x, event.y, width, height, scrollX)
                    onTap?.invoke(result)
                }
            }
        }
        return true
    }
}
