package com.ninthsoft.ime.input.keyboard.key

import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.graphics.drawable.LayerDrawable
import androidx.annotation.ColorInt
import com.ninthsoft.ime.data.keyboard.theme.KeyboardColors

fun radiusDrawable(
    r: Float,
    @ColorInt color: Int = Color.WHITE,
): Drawable = GradientDrawable().apply {
    setColor(color)
    cornerRadius = r
}

fun insetRadiusDrawable(
    hInset: Int,
    vInset: Int,
    r: Float = 0f,
    @ColorInt color: Int = Color.WHITE,
): Drawable = InsetDrawable(
    radiusDrawable(r, color),
    hInset, vInset, hInset, vInset,
)

fun insetOvalDrawable(
    hInset: Int,
    vInset: Int,
    @ColorInt color: Int = Color.WHITE,
): Drawable = InsetDrawable(
    GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(color)
    },
    hInset, vInset, hInset, vInset,
)

fun shadowedKeyBackgroundDrawable(
    @ColorInt bkgColor: Int,
    @ColorInt shadowColor: Int,
    radius: Float,
    shadowWidth: Int,
    hMargin: Int,
    vMargin: Int,
): Drawable = LayerDrawable(
    arrayOf(
        radiusDrawable(radius, shadowColor),
        radiusDrawable(radius, bkgColor),
    ),
).apply {
    setLayerInset(0, hMargin, vMargin, hMargin, vMargin - shadowWidth)
    setLayerInset(1, hMargin, vMargin, hMargin, vMargin)
}

fun flatKeyBackgroundDrawable(
    @ColorInt bkgColor: Int,
    @ColorInt strokeColor: Int,
    radius: Float,
    strokeWidth: Int,
    hMargin: Int,
    vMargin: Int,
): Drawable = LayerDrawable(
    arrayOf(
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radius
            setColor(bkgColor)
            setStroke(strokeWidth, strokeColor)
        },
    ),
).apply {
    setLayerInset(0, hMargin, vMargin, hMargin, vMargin)
}

fun borderedKeyBackgroundDrawable(
    @ColorInt bkgColor: Int,
    @ColorInt strokeColor: Int,
    radius: Float,
    strokeWidth: Int,
    hMargin: Int,
    vMargin: Int,
): Drawable = LayerDrawable(
    arrayOf(
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radius
            setColor(bkgColor)
            setStroke(strokeWidth, strokeColor)
        },
    ),
).apply {
    setLayerInset(0, hMargin, vMargin, hMargin, vMargin)
}

/**
 * 按键背景：支持圆角矩形 / 直角矩形 / 椭圆三种形状，并可指定描边厚度。
 * [strokeWidth] <= 0 时不绘制描边。
 */
fun keyBackgroundDrawable(
    @ColorInt bkgColor: Int,
    @ColorInt strokeColor: Int,
    radius: Float,
    strokeWidth: Int,
    shape: KeyboardColors.KeyShape,
    hMargin: Int,
    vMargin: Int,
): Drawable = LayerDrawable(
    arrayOf(
        GradientDrawable().apply {
            this.shape = when (shape) {
                KeyboardColors.KeyShape.Oval -> GradientDrawable.OVAL
                else -> GradientDrawable.RECTANGLE
            }
            cornerRadius = when (shape) {
                KeyboardColors.KeyShape.Rectangle -> 0f
                else -> radius
            }
            setColor(bkgColor)
            if (strokeWidth > 0) {
                setStroke(strokeWidth, strokeColor)
            }
        },
    ),
).apply {
    setLayerInset(0, hMargin, vMargin, hMargin, vMargin)
}

fun highlightMaskDrawable(
    @ColorInt color: Int,
    bordered: Boolean,
    hMargin: Int,
    vMargin: Int,
    radius: Float,
): Drawable = if (bordered) {
    insetRadiusDrawable(hMargin, vMargin, radius, color)
} else {
    InsetDrawable(ColorDrawable(color), hMargin, vMargin, hMargin, vMargin)
}
