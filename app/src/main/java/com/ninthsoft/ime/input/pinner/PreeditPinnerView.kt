package com.ninthsoft.ime.input.pinner

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.view.View
import com.ninthsoft.ime.engine.data.EngineMessage
import kotlin.math.roundToInt

@SuppressLint("ViewConstructor")
class PreeditPinnerView(context: Context) : View(context) {

    var preeditItems: List<EngineMessage.DynamicPreedit.DynamicPreeditItem> = emptyList()
        set(value) {
            field = value
            if (value.isEmpty()) requestLayout()
            invalidate()
        }

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.DEFAULT_BOLD
    }
    private val secondaryTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.DEFAULT_BOLD
    }
    private val pad = 10f * context.resources.displayMetrics.density

    fun applyTheme(bgColor: Int, textColor: Int, secondaryTextColor: Int, textSize: Float) {
        bgPaint.color = bgColor
        textPaint.color = textColor
        textPaint.textSize = textSize
        secondaryTextPaint.color = secondaryTextColor
        secondaryTextPaint.textSize = textSize - 2
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val items = preeditItems
        if (items.isEmpty()) {
            setMeasuredDimension(0, 0)
            return
        }
        val density = resources.displayMetrics.density
        val pillH = 30f * density
        var totalW = 0f
        for (item in items) {
            totalW += textPaint.measureText(item.text)
        }
        setMeasuredDimension((totalW + pad * 2).roundToInt(), pillH.roundToInt())
    }

    override fun onDraw(canvas: Canvas) {
        val items = preeditItems
        if (items.isEmpty()) return
        val density = resources.displayMetrics.density
        val r = 8f * density
        canvas.drawRoundRect(0f, 0f, width.toFloat(), height.toFloat(), r, r, bgPaint)
        val textY = height / 2f - (textPaint.descent() + textPaint.ascent()) / 2f
        var x = pad
        for (item in items) {
            val paint = if (item.type == EngineMessage.DynamicPreedit.DynamicPreeditType.Normal) textPaint else secondaryTextPaint
            canvas.drawText(item.text, x, textY, paint)
            x += paint.measureText(item.text)
        }
    }
}
