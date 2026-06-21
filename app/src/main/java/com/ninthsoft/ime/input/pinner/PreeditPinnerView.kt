package com.ninthsoft.ime.input.pinner

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.view.View
import kotlin.math.roundToInt

@SuppressLint("ViewConstructor")
class PreeditPinnerView(context: Context) : View(context) {

    var preeditText: String? = null
        set(value) {
            field = value
            if (value.isNullOrBlank()) requestLayout()
            invalidate()
        }

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.DEFAULT_BOLD
    }

    fun applyTheme(bgColor: Int, textColor: Int, textSize: Float) {
        bgPaint.color = (bgColor and 0x00ffffff) or 0xBB000000.toInt()
        textPaint.color = textColor
        textPaint.textSize = textSize
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val text = preeditText
        if (text.isNullOrBlank()) {
            setMeasuredDimension(0, 0)
            return
        }
        val density = resources.displayMetrics.density
        val pad = 10f * density
        val pillH = 30f * density
        val textW = textPaint.measureText(text)
        setMeasuredDimension((textW + pad * 2).roundToInt(), pillH.roundToInt())
    }

    override fun onDraw(canvas: Canvas) {
        val text = preeditText ?: return
        if (text.isEmpty()) return
        val density = resources.displayMetrics.density
        val r = 8f * density
        canvas.drawRoundRect(0f, 0f, width.toFloat(), height.toFloat(), r, r, bgPaint)
        val textY = height / 2f - (textPaint.descent() + textPaint.ascent()) / 2f
        canvas.drawText(text, 10f * density, textY, textPaint)
    }
}
