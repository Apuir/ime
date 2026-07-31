package com.ninthsoft.ime.input.panel.component

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.drawable.Drawable
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.widget.OverScroller
import com.ninthsoft.ime.R
import com.ninthsoft.ime.data.keyboard.theme.KeyboardColors
import com.ninthsoft.ime.data.manager.ClipboardRepository
import kotlin.math.abs
import kotlin.math.roundToInt

@SuppressLint("ViewConstructor")
class ClipboardView(
    context: Context,
    colors: KeyboardColors.ColorScheme,
) : ComponentView(context, colors) {

    var onItemClick: ((ClipboardRepository.Entry) -> Unit)? = null
    var onItemLongClick: ((ClipboardRepository.Entry, Float, Float) -> Unit)? = null

    private val density = resources.displayMetrics.density

    private val pillR = 6f * density
    private val pillPad = 8f * density
    private val gap = 6f * density
    private val hMargin = 10f * density

    private var entries = listOf<ClipboardRepository.Entry>()

    private data class EntryLayout(val height: Float, val lines: List<String>)
    private var entryLayouts = listOf<EntryLayout>()

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val indexPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pressPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val cloudDrawable: Drawable? = context.getDrawable(R.drawable.ic_keyboard_clipboard_cloud)
    private val cloudIconSize = 14f * density

    private var pressedIndex = -1
    private var maxScroll = 0f
    private var scrollOffsetY = 0f
    private val scroller = OverScroller(context)
    private var velocityTracker: VelocityTracker? = null
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private var lastTouchY = 0f
    private var isScrolling = false
    private var longPressPending = false
    private var longPressX = 0f
    private var longPressY = 0f
    private val longPressTimeout = ViewConfiguration.getLongPressTimeout()
    private val longPressRunnable = Runnable {
        if (pressedIndex in entries.indices) {
            val entry = entries[pressedIndex]
            longPressPending = false
            pressedIndex = -1
            invalidate()
            onItemLongClick?.invoke(entry, longPressX, longPressY)
        }
    }

    private val emptyView: View = object : View(context) {
        private val emptyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textAlign = Paint.Align.CENTER
            textSize = 14f * density
            color = colors.panel.candidateIndex
        }
        override fun onDraw(canvas: Canvas) {
            val text = context.getString(R.string.clipboard_empty)
            canvas.drawText(text, width / 2f, height / 2f, emptyPaint)
        }
    }.apply {
        layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        visibility = View.GONE
    }

    init {
        updateColors()
        addView(emptyView)
    }

    fun show(list: List<ClipboardRepository.Entry>) {
        entries = list
        resetScroll()
        updateColors()
        computeEntryLayouts()
        invalidate()
        super.show()
    }

    private fun resetScroll() {
        scroller.forceFinished(true)
        scrollOffsetY = 0f
    }

    private fun updateColors() {
        val panel = KeyboardColors.resolve(context).panel
        bgPaint.color = panel.candidateBackground
        textPaint.color = panel.candidateText
        textPaint.textSize = 17f * density
        indexPaint.color = panel.candidateIndex
        indexPaint.textSize = 13f * density
        pressPaint.color = panel.candidateBackground
        cloudDrawable?.setTint(panel.candidateIndex)
    }

    private fun computeEntryLayouts() {
        if (entries.isEmpty() || width <= 0) {
            entryLayouts = emptyList()
            return
        }
        val fm = textPaint.fontMetrics
        val lh = fm.descent - fm.ascent
        val maxTextW = width - hMargin * 2 - pillPad * 2 - indexPaint.measureText("9. ") - cloudIconSize - 2f * density
        entryLayouts = entries.map { entry ->
            val lines = breakText(entry.text, maxTextW, 4)
            EntryLayout(height = lh * lines.size + pillPad * 2, lines = lines)
        }
    }

    private fun breakText(text: String, maxWidth: Float, maxLines: Int): List<String> {
        val lines = mutableListOf<String>()
        val cleanText = text.replace('\n', ' ')
        var start = 0
        while (start < cleanText.length && lines.size < maxLines) {
            val count = textPaint.breakText(cleanText, start, cleanText.length, true, maxWidth, null)
            var line = cleanText.substring(start, start + count)
            start += count
            if (start < cleanText.length && lines.size == maxLines - 1) {
                while (line.isNotEmpty() && textPaint.measureText(line + "...") > maxWidth) {
                    line = line.dropLast(1)
                }
                line += "..."
            }
            lines.add(line)
        }
        return lines
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        computeEntryLayouts()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width <= 0 || height <= 0) return

        if (entries.isEmpty()) {
            emptyView.visibility = View.VISIBLE
            return
        }
        emptyView.visibility = View.GONE

        var totalContentH = hMargin * 2f
        entryLayouts.forEach { totalContentH += it.height + gap }
        maxScroll = maxOf(0f, totalContentH - height)

        canvas.save()
        canvas.clipRect(0, 0, width, height)
        canvas.translate(0f, hMargin - scrollOffsetY)

        var pillY = 0f
        for ((i, entry) in entries.withIndex()) {
            val layout = entryLayouts.getOrNull(i) ?: break
            val h = layout.height
            val left = hMargin
            val right = width - hMargin

            if (pillY + h < scrollOffsetY || pillY > scrollOffsetY + height) {
                pillY += h + gap
                continue
            }

            if (i == pressedIndex) {
                canvas.drawRoundRect(left, pillY, right, pillY + h, pillR, pillR, pressPaint)
            } else {
                canvas.drawRoundRect(left, pillY, right, pillY + h, pillR, pillR, bgPaint)
            }

            val indexLabel = "${i + 1}. "
            val indexW = indexPaint.measureText(indexLabel)
            val textStartX = left + pillPad + indexW

            val fm = textPaint.fontMetrics
            val lh = fm.descent - fm.ascent
            val baseline = pillY + pillPad - fm.ascent

            canvas.drawText(indexLabel, left + pillPad, baseline, indexPaint)

            if (entry.cloud && cloudDrawable != null) {
                val iconLeft = left + pillPad + indexW + 2f * density
                val iconTop = baseline - cloudIconSize
                cloudDrawable.setBounds(
                    iconLeft.toInt(), iconTop.toInt(),
                    (iconLeft + cloudIconSize).toInt(), (iconTop + cloudIconSize).toInt()
                )
                cloudDrawable.draw(canvas)
            }

            for ((li, line) in layout.lines.withIndex()) {
                canvas.drawText(line, textStartX, baseline + lh * li, textPaint)
            }

            pillY += h + gap
        }

        canvas.restore()
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (entries.isEmpty()) return false
        velocityTracker?.addMovement(event)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                scroller.forceFinished(true)
                lastTouchY = event.y
                isScrolling = false
                velocityTracker = VelocityTracker.obtain()
                velocityTracker?.addMovement(event)

                val contentY = scrollOffsetY + event.y - hMargin
                var index = -1
                var cumulative = 0f
                for (i in entryLayouts.indices) {
                    val h = entryLayouts[i].height
                    if (contentY >= cumulative && contentY < cumulative + h) {
                        index = i
                        break
                    }
                    cumulative += h + gap
                }
                if (index in entries.indices) {
                    pressedIndex = index
                    longPressPending = true
                    longPressX = event.x
                    longPressY = event.y
                    postDelayed(longPressRunnable, longPressTimeout.toLong())
                    invalidate()
                }
            }

            MotionEvent.ACTION_MOVE -> {
                val dy = event.y - lastTouchY
                if (!isScrolling && abs(dy) > touchSlop) {
                    if (longPressPending) {
                        removeCallbacks(longPressRunnable)
                        longPressPending = false
                    }
                    isScrolling = true
                    pressedIndex = -1
                    invalidate()
                }
                if (isScrolling) {
                    val rawOffset = scrollOffsetY - dy
                    scrollOffsetY = if (rawOffset < 0) {
                        rawOffset * 0.3f
                    } else if (rawOffset > maxScroll) {
                        maxScroll + (rawOffset - maxScroll) * 0.3f
                    } else rawOffset
                    lastTouchY = event.y
                    invalidate()
                }
            }

            MotionEvent.ACTION_UP -> {
                removeCallbacks(longPressRunnable)
                velocityTracker?.let { tracker ->
                    tracker.computeCurrentVelocity(1000)
                    val vy = tracker.yVelocity
                    if (abs(vy) > ViewConfiguration.get(context).scaledMinimumFlingVelocity) {
                        val startY = scrollOffsetY.roundToInt()
                        val velY = (-vy).toInt()
                        val maxY = maxScroll.toInt()
                        scroller.fling(0, startY, 0, velY, 0, 0, 0, maxY)
                        postInvalidateOnAnimation()
                    }
                }
                velocityTracker?.recycle()
                velocityTracker = null

                if (!isScrolling && pressedIndex >= 0 && pressedIndex < entries.size) {
                    onItemClick?.invoke(entries[pressedIndex])
                }
                longPressPending = false
                pressedIndex = -1
                invalidate()
            }

            MotionEvent.ACTION_CANCEL -> {
                removeCallbacks(longPressRunnable)
                velocityTracker?.recycle()
                velocityTracker = null
                pressedIndex = -1
                invalidate()
            }
        }
        return true
    }

    override fun computeScroll() {
        if (scroller.computeScrollOffset()) {
            scrollOffsetY = scroller.currY.toFloat().coerceIn(0f, maxScroll)
            invalidate()
            postInvalidateOnAnimation()
        }
    }
}
