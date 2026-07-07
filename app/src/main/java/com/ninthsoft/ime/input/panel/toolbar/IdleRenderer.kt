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
    clipboardDrawable: Drawable? = null,
    undoRightDrawable: Drawable? = null,
    redoRightDrawable: Drawable? = null,
    paletteDrawable: Drawable? = null,
    cursorMoveDrawable: Drawable? = null,
    private val expandDrawable: Drawable? = null,
    var horizontalPaddingDp: Float = 0f,
    var iconScale: Float = 0.94f,
) : IRenderer {

    private val centerButtons = listOf(
        ImageButton(undoRightDrawable, KawaiiPanel.Action.RotateLeft, iconScale = iconScale),
        ImageButton(redoRightDrawable, KawaiiPanel.Action.RotateRight, iconScale = iconScale),
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
        val centerAreaLeft = menuLeft + fixedW
        val centerAreaW = width - centerAreaLeft - hPad - fixedW
        val otherW = centerAreaW / centerButtons.size

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
            val d = if (showArrow) arrowDrawable else menuDrawable
            if (d != null) {
                d.setTint(paints.toolbarIconColor)
                val iw = d.intrinsicWidth.toFloat() * iconScale
                val ih = d.intrinsicHeight.toFloat() * iconScale
                d.setBounds(
                    (menuCenter - iw / 2f).toInt(), (height / 2f - ih / 2f).toInt(),
                    (menuCenter + iw / 2f).toInt(), (height / 2f + ih / 2f).toInt(),
                )
                d.draw(canvas)
            }
        }

        for ((i, btn) in centerButtons.withIndex()) {
            btn.draw(
                canvas, centerAreaLeft + otherW * i + otherW / 2f,
                height / 2f, paints, density,
            )
        }

        val expandLeft = width - hPad - fixedW
        val expandCenter = expandLeft + fixedW / 2f
        if (expandDrawable != null) {
            expandDrawable.setTint(paints.toolbarIconColor)
            val iw = expandDrawable.intrinsicWidth.toFloat() * iconScale
            val ih = expandDrawable.intrinsicHeight.toFloat() * iconScale
            expandDrawable.setBounds(
                (expandCenter - iw / 2f).toInt(), (height / 2f - ih / 2f).toInt(),
                (expandCenter + iw / 2f).toInt(), (height / 2f + ih / 2f).toInt(),
            )
            if (isExpanded) {
                canvas.withRotation(180f, expandCenter, height / 2f) { expandDrawable.draw(this) }
            } else {
                expandDrawable.draw(canvas)
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
                return KawaiiPanel.TouchResult.ToolbarAction(KawaiiPanel.Action.CloseKeyboard)
            }

            in hPad..menuRight -> {
                pressCx = hPad + fixedW / 2f
                pressCy = height / 2f
                pressRadiusMax = height * 0.55f
                pressRadius = 0f
                showArrow = !showArrow
                return KawaiiPanel.TouchResult.ToolbarAction(KawaiiPanel.Action.SwitchKeyboard)
            }

            else -> {
                val centerAreaW = width - menuRight - hPad - fixedW
                val otherW = centerAreaW / centerButtons.size
                val index = ((x - menuRight) / otherW).toInt().coerceIn(0, centerButtons.size - 1)
                pressCx = menuRight + otherW * index + otherW / 2f
                pressCy = height / 2f
                pressRadiusMax = height * 0.55f
                pressRadius = 0f
                return KawaiiPanel.TouchResult.ToolbarAction(centerButtons[index].action)
            }
        }
    }

    private var showArrow: Boolean = false
}
