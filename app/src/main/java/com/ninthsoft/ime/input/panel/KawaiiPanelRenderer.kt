package com.ninthsoft.ime.input.panel

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.drawable.Drawable
import androidx.core.graphics.withClip
import androidx.core.graphics.withRotation
import androidx.core.graphics.withSave
import com.ninthsoft.ime.engine.data.EngineMessage

class ComposingRenderer(
    var candidates: List<EngineMessage.Candidate>,
    private val expandDrawable: Drawable?,
    var horizontalPaddingDp: Float,
    var iconScale: Float = 1f,
) : IRenderer {

    private data class PillRect(val left: Float, val right: Float, val index: Int)

    private var lastPills: List<PillRect> = emptyList()
    var maxScrollX: Float = 0f

    override fun draw(
        canvas: Canvas, width: Int, height: Int, paints: Paints,
        scrollX: Float, isExpanded: Boolean, density: Float,
    ) {
        lastPills = emptyList()
        if (width <= 0 || height <= 0 || candidates.isEmpty()) return

        val pillH = 34f * density
        val pillY = (height - pillH) / 2f
        val pillR = 6f * density
        val hPad = horizontalPaddingDp * density
        val pillPad = 8f * density
        val gap = 6f * density
        val textY =
            pillY + pillH / 2f - (paints.candidateTextPaint.descent() + paints.candidateTextPaint.ascent()) / 2f

        val expandBtnW = 32f * density
        val expandBtnGap = 22f * density
        val expandBtnRight = width.toFloat() - hPad
        val expandBtnLeft = expandBtnRight - expandBtnW
        val pillsEnd = expandBtnLeft - expandBtnGap

        var x = hPad
        val pills = mutableListOf<PillRect>()
        for ((i, c) in candidates.withIndex()) {
            val indexW = paints.candidateIndexPaint.measureText("${i + 1}. ")
            val textW = paints.candidateTextPaint.measureText(c.text)
            val commentW =
                if (c.comment.isNotEmpty()) paints.candidateIndexPaint.measureText(" ${c.comment}") else 0f
            val pillW = indexW + textW + commentW + pillPad * 2
            pills.add(PillRect(x, x + pillW, c.index))
            x += pillW + gap
        }
        lastPills = pills

        maxScrollX = maxOf(0f, x - hPad - pillsEnd)

        val dividerX = (pillsEnd + expandBtnLeft) / 2f
        val fadeW = 24f * density
        val fadeStart = (dividerX - fadeW).coerceAtLeast(0f)
        val fadePaint = Paint()

        canvas.withClip(0f, 0f, pillsEnd, height.toFloat()) {
            withSave {
                translate(scrollX, 0f)

                for ((i, c) in candidates.withIndex()) {
                    val pill = pills[i]
                    drawRoundRect(
                        pill.left, pillY, pill.right, pillY + pillH, pillR, pillR,
                        paints.candidateBgPaint,
                    )
                    val indexW = paints.candidateIndexPaint.measureText("${i + 1}. ")
                    val textW = paints.candidateTextPaint.measureText(c.text)
                    drawText(
                        "${i + 1}. ", pill.left + pillPad, textY, paints.candidateIndexPaint,
                    )
                    drawText(c.text, pill.left + pillPad + indexW, textY, paints.candidateTextPaint)
                    if (c.comment.isNotEmpty()) {
                        drawText(
                            " ${c.comment}",
                            pill.left + pillPad + indexW + textW,
                            textY,
                            paints.candidateIndexPaint,
                        )
                    }
                }
            }

            fadePaint.shader = LinearGradient(
                fadeStart, 0f, dividerX, 0f,
                Color.TRANSPARENT, paints.bgPaint.color,
                Shader.TileMode.CLAMP,
            )
            drawRect(fadeStart, 0f, dividerX, height.toFloat(), fadePaint)
        }

        canvas.drawRect(pillsEnd, 0f, width.toFloat(), height.toFloat(), paints.bgPaint)
        canvas.drawLine(dividerX, pillY + pillH / 4f, dividerX, pillY + pillH * 3f / 4f, paints.dividerPaint)
        canvas.drawRoundRect(
            expandBtnLeft, pillY, expandBtnRight, pillY + pillH, pillR, pillR,
            paints.candidateBgPaint,
        )
        val cx = expandBtnLeft + expandBtnW / 2f
        val cy = pillY + pillH / 2f
        val d = expandDrawable
        if (d != null) {
            d.setTint(paints.toolbarIconColor)
            val iw = d.intrinsicWidth.toFloat() * iconScale
            val ih = d.intrinsicHeight.toFloat() * iconScale
            d.setBounds(
                (cx - iw / 2f).toInt(), (cy - ih / 2f).toInt(),
                (cx + iw / 2f).toInt(), (cy + ih / 2f).toInt(),
            )
            if (isExpanded) {
                canvas.withRotation(180f, cx, cy) {
                    d.draw(this)
                }
            } else {
                d.draw(canvas)
            }
        }
    }

    override fun hitTest(
        x: Float, y: Float, width: Int, height: Int,
        scrollX: Float, isExpanded: Boolean, density: Float,
    ): KawaiiPanel.TouchResult? {
        if (candidates.isEmpty()) return null
        val pillH = 34f * density
        val pillY = (height - pillH) / 2f
        if (y < pillY || y > pillY + pillH) return null

        val expandRightMargin = horizontalPaddingDp * density
        val expandBtnW = 32f * density
        val expandBtnLeft = width - expandRightMargin - expandBtnW
        val expandBtnRight = width - expandRightMargin
        if (x in expandBtnLeft..expandBtnRight) {
            return if (isExpanded) KawaiiPanel.TouchResult.CollapseCandidates
            else KawaiiPanel.TouchResult.ExpandCandidates
        }

        if (lastPills.isEmpty()) return null
        val adjustedX = x - scrollX
        for (pill in lastPills) {
            if (adjustedX >= pill.left && adjustedX <= pill.right) {
                val c = candidates.find { it.index == pill.index } ?: return null
                return KawaiiPanel.TouchResult.SelectCandidate(c)
            }
        }
        return null
    }
}
