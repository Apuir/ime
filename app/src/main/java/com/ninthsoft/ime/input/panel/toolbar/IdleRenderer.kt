package com.ninthsoft.ime.input.panel.toolbar

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.drawable.Drawable
import androidx.core.graphics.withRotation
import com.ninthsoft.ime.input.panel.IRenderer
import com.ninthsoft.ime.input.panel.KawaiiPanel
import com.ninthsoft.ime.input.panel.Paints

class IdleRenderer(
    private val menuDrawable: Drawable? = null,
    private val arrowDrawable: Drawable? = null,
    private val clipboardDrawable: Drawable? = null,
    undoRightDrawable: Drawable? = null,
    redoRightDrawable: Drawable? = null,
    paletteDrawable: Drawable? = null,
    cursorMoveDrawable: Drawable? = null,
    private val expandDrawable: Drawable? = null,
    private val clearDrawable: Drawable? = null,
    var horizontalPaddingDp: Float = 0f,
    var centerHorizontalPaddingDp: Float = 12f,
    var iconScale: Float = 0.94f,
) : IRenderer {

    var textEditingMode: Boolean = false
    var clipboardMode: Boolean = false
    var copyText: String? = null
    var recording: Boolean = false

    private fun dimColor(color: Int): Int {
        return if (recording) (color and 0x00FFFFFF) or 0x5A000000.toInt() else color
    }

    private val centerButtons = listOf(
        ImageButton(undoRightDrawable, KawaiiPanel.Action.Undo, iconScale = iconScale),
        ImageButton(redoRightDrawable, KawaiiPanel.Action.Redo, iconScale = iconScale),
        ImageButton(cursorMoveDrawable, KawaiiPanel.Action.CursorMove, iconScale = iconScale),
        ImageButton(clipboardDrawable, KawaiiPanel.Action.Clipboard, iconScale = iconScale),
        ImageButton(paletteDrawable, KawaiiPanel.Action.Palette, iconScale = iconScale),
    )

    var pressAlpha: Int = 0
    var pressCx: Float = 0f
    var pressCy: Float = 0f
    var pressRadius: Float = 0f
    var pressRadiusMax: Float = 0f
    private val pressPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    override fun draw(
        canvas: Canvas, width: Int, height: Int, paints: Paints,
        scrollX: Float, isExpanded: Boolean, density: Float,
    ) {
        if (width <= 0 || height <= 0) return
        val hPad = (horizontalPaddingDp + 4) * density
        val fixedW = 32f * density

        val menuLeft = hPad
        val menuCenter = menuLeft + fixedW / 2f
        val centerPad = centerHorizontalPaddingDp * density
        val centerAreaLeft = menuLeft + fixedW + centerPad
        val centerAreaW = width - centerAreaLeft - hPad - fixedW - centerPad

        if (pressRadius > 0f && pressRadiusMax > 0f) {
            val progress = (pressRadius / pressRadiusMax).coerceIn(0f, 1f)
            val currentAlpha = (pressAlpha * (1f - progress)).toInt().coerceIn(0, 255)
            if (currentAlpha > 0) {
                pressPaint.color = paints.toolbarPressedColor
                pressPaint.alpha = currentAlpha
                canvas.drawCircle(pressCx, pressCy, pressRadius, pressPaint)
            }
        }

        if (menuDrawable != null) {
            val d = if (clipboardMode || textEditingMode || copyText != null || showArrow) arrowDrawable else menuDrawable
            if (d != null) {
                d.setTint(dimColor(paints.toolbarIconColor))
                val iw = d.intrinsicWidth.toFloat() * iconScale
                val ih = d.intrinsicHeight.toFloat() * iconScale
                d.setBounds(
                    (menuCenter - iw / 2f).toInt(), (height / 2f - ih / 2f).toInt(),
                    (menuCenter + iw / 2f).toInt(), (height / 2f + ih / 2f).toInt(),
                )
                d.draw(canvas)
            }
        }

        if (copyText != null) {
            val t = copyText!!
            val textPaint = if (recording) Paint(paints.candidateTextPaint).apply {
                color = dimColor(paints.candidateTextPaint.color)
            } else paints.candidateTextPaint
            val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.FILL
                color = dimColor(paints.candidateBgPaint.color)
            }
            val pillR = 6f * density
            val pillH = 34f * density
            val pillPad = 8f * density
            val clipLeft = centerAreaLeft + 16f * density
            val clipRight = width - hPad - fixedW - centerPad - 16f * density
            val textCenterY = height / 2f
            val gap = 8f * density
            val iconW = (clipboardDrawable?.intrinsicWidth?.toFloat()?.times(iconScale)?.toInt() ?: 0)
            val iconH = (clipboardDrawable?.intrinsicHeight?.toFloat()?.times(iconScale)?.toInt() ?: 0)
            val iconAvail = if (clipboardDrawable != null) iconW + gap else 0f
            val availW = clipRight - clipLeft
            val maxTextW = availW - iconAvail - pillPad * 2
            val src = if (t.length > 256) t.take(256) else t
            val ellipsized = if (textPaint.measureText(src) <= maxTextW) {
                src
            } else {
                var lo = 0
                var hi = src.length
                while (lo < hi) {
                    val mid = (lo + hi) / 2
                    if (textPaint.measureText(src.take(mid) + "…") <= maxTextW) lo = mid + 1
                    else hi = mid
                }
                src.take((lo - 1).coerceAtLeast(0)) + "…"
            }
            val textW = textPaint.measureText(ellipsized)
            val totalW = iconAvail + textW
            val contentLeft = clipLeft + (availW - totalW) / 2f
            val bgLeft = contentLeft - pillPad
            val bgRight = contentLeft + totalW + pillPad
            val bgTop = textCenterY - pillH / 2f
            val bgBottom = textCenterY + pillH / 2f
            canvas.drawRoundRect(bgLeft, bgTop, bgRight, bgBottom, pillR, pillR, bgPaint)
            var drawX = contentLeft
            if (clipboardDrawable != null) {
                clipboardDrawable.setTint(dimColor(paints.toolbarIconColor))
                val iconTop = (textCenterY - iconH / 2f).toInt()
                clipboardDrawable.setBounds(drawX.toInt(), iconTop, drawX.toInt() + iconW, iconTop + iconH)
                clipboardDrawable.draw(canvas)
                drawX += iconW + gap
            }
            val textY = textCenterY - (textPaint.descent() + textPaint.ascent()) / 2f
            canvas.drawText(ellipsized, drawX, textY, textPaint)
        } else {
            val otherW = centerAreaW / centerButtons.size
            for ((i, btn) in centerButtons.withIndex()) {
                val savedColor = paints.toolbarIconColor
                val disabled = ((clipboardMode || textEditingMode) && i >= 2) || recording
                if (disabled) {
                    paints.toolbarIconColor = (savedColor and 0x00FFFFFF) or 0x62000000.toInt()
                }
                btn.draw(
                    canvas, centerAreaLeft + otherW * i + otherW / 2f,
                    height / 2f, paints, density,
                )
                if (disabled) {
                    paints.toolbarIconColor = savedColor
                }
            }
        }

        val expandLeft = width - hPad - fixedW
        val expandCenter = expandLeft + fixedW / 2f
        val expandIcon = when {
            clipboardMode -> clearDrawable
            else -> expandDrawable
        }
        if (expandIcon != null) {
            expandIcon.setTint(paints.toolbarIconColor)
            val iw = expandIcon.intrinsicWidth.toFloat() * iconScale
            val ih = expandIcon.intrinsicHeight.toFloat() * iconScale
            expandIcon.setBounds(
                (expandCenter - iw / 2f).toInt(), (height / 2f - ih / 2f).toInt(),
                (expandCenter + iw / 2f).toInt(), (height / 2f + ih / 2f).toInt(),
            )
            if (!clipboardMode && !textEditingMode && isExpanded) {
                canvas.withRotation(180f, expandCenter, height / 2f) { expandIcon.draw(this) }
            } else {
                expandIcon.draw(canvas)
            }
        }
    }

    override fun hitTest(
        x: Float, y: Float, width: Int, height: Int,
        scrollX: Float, isExpanded: Boolean, density: Float,
    ): KawaiiPanel.TouchResult? {
        if (width <= 0) return null
        val hPad = (horizontalPaddingDp + 4) * density
        val fixedW = 32f * density

        val menuRight = hPad + fixedW
        val expandLeft = width - hPad - fixedW

        when (x) {
            in expandLeft..width.toFloat() -> {
                pressCx = expandLeft + fixedW / 2f
                pressCy = height / 2f
                pressRadiusMax = height * 0.55f
                pressRadius = 0f
                return KawaiiPanel.TouchResult.ToolbarAction(
                    when {
                        clipboardMode -> KawaiiPanel.Action.ClearClipboard
                        else -> KawaiiPanel.Action.CloseKeyboard
                    },
                    tapX = x,
                    tapY = y,
                )
            }

            in hPad..menuRight -> {
                pressCx = hPad + fixedW / 2f
                pressCy = height / 2f
                pressRadiusMax = height * 0.55f
                pressRadius = 0f
                return KawaiiPanel.TouchResult.ToolbarAction(
                    KawaiiPanel.Action.SwitchKeyboard,
                    tapX = x,
                    tapY = y,
                )
            }

            else -> {
                if (copyText != null) return null
                val centerPad = centerHorizontalPaddingDp * density
                val centerAreaLeft = menuRight + centerPad
                val centerAreaW = width - centerAreaLeft - hPad - fixedW - centerPad
                val otherW = centerAreaW / centerButtons.size
                val index = ((x - centerAreaLeft) / otherW).toInt().coerceIn(0, centerButtons.size - 1)
                if ((clipboardMode || textEditingMode) && index >= 2) return null
                pressCx = centerAreaLeft + otherW * index + otherW / 2f
                pressCy = height / 2f
                pressRadiusMax = height * 0.55f
                pressRadius = 0f
                return KawaiiPanel.TouchResult.ToolbarAction(
                    centerButtons[index].action,
                    tapX = x,
                    tapY = y,
                )
            }
        }
    }

    var showArrow: Boolean = false
}
