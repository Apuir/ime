package com.ninthsoft.ime.input.keyboard.key

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.InsetDrawable
import android.graphics.drawable.RippleDrawable
import android.graphics.drawable.StateListDrawable
import android.util.TypedValue
import android.view.View
import androidx.annotation.FloatRange
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.view.updateLayoutParams
import com.ninthsoft.ime.data.keyboard.theme.KeyboardColors
import com.ninthsoft.ime.input.keyboard.key.KeyDef.Appearance.Border
import com.ninthsoft.ime.input.keyboard.key.KeyDef.Appearance.Variant
import splitties.dimensions.dp
import splitties.views.dsl.constraintlayout.centerHorizontally
import splitties.views.dsl.constraintlayout.centerInParent
import splitties.views.dsl.constraintlayout.constraintLayout
import splitties.views.dsl.constraintlayout.lParams
import splitties.views.dsl.constraintlayout.parentId
import splitties.views.dsl.core.add
import splitties.views.dsl.core.imageView
import splitties.views.dsl.core.lParams
import splitties.views.dsl.core.matchParent
import splitties.views.dsl.core.wrapContent
import splitties.views.imageResource
import splitties.views.padding
import kotlin.math.min
import kotlin.math.roundToInt

abstract class KeyView(
    ctx: Context,
    protected val colors: KeyboardColors.ColorScheme,
    val def: KeyDef.Appearance,
) : CustomGestureView(ctx) {

    var bordered: Boolean = true
    var borderStroke: Boolean = false
    var rippled: Boolean = true
    var radius = dp(6f)
    var hMargin: Int = dp(3)
    var vMargin: Int = dp(4)

    private val cachedLocation = intArrayOf(0, 0)
    private val cachedBounds = Rect()
    private var boundsValid = false
    val bounds: Rect
        get() = cachedBounds.also {
            if (!boundsValid) updateBounds()
        }

    @FloatRange(0.0, 1.0)
    var layoutMarginLeft = 0f

    @FloatRange(0.0, 1.0)
    var layoutMarginRight = 0f

    protected val appearanceView = constraintLayout {
        isDuplicateParentStateEnabled = true
    }

    init {
        isEnabled = true
        isHapticFeedbackEnabled = false
        if (def.viewId > 0) {
            id = def.viewId
        }
        visibility = def.visibility

        if ((bordered && def.border != Border.Off) || def.border == Border.On) {
            val bkgColor = when (def.variant) {
                Variant.Normal, Variant.AltForeground -> colors.keyBackground
                Variant.Alternative -> colors.specialKeyBackground
                Variant.Accent -> colors.accentKeyBackground
            }
            val borderOrShadowWidth = dp(1)
            appearanceView.background = if (borderStroke) {
                borderedKeyBackgroundDrawable(
                    bkgColor, colors.keyBackground,
                    radius, borderOrShadowWidth, hMargin, vMargin,
                )
            } else {
                shadowedKeyBackgroundDrawable(
                    bkgColor, colors.keyBackground,
                    radius, borderOrShadowWidth, hMargin, vMargin,
                )
            }
            setupPressHighlight()
        } else {
            if (def.border != Border.Special) {
                setupPressHighlight()
            }
        }
        add(appearanceView, lParams(matchParent, matchParent))
    }

    private fun setupPressHighlight() {
        val mask = if (bordered) {
            insetRadiusDrawable(hMargin, vMargin, radius, Color.WHITE)
        } else {
            InsetDrawable(
                android.graphics.drawable.ColorDrawable(Color.WHITE),
                hMargin, vMargin, hMargin, vMargin,
            )
        }
        appearanceView.foreground = if (rippled) {
            RippleDrawable(
                ColorStateList.valueOf(colors.keyPressed),
                null,
                mask,
            )
        } else {
            StateListDrawable().apply {
                addState(
                    intArrayOf(android.R.attr.state_pressed),
                    if (bordered) {
                        insetRadiusDrawable(hMargin, vMargin, radius, colors.keyPressed)
                    } else {
                        InsetDrawable(
                            android.graphics.drawable.ColorDrawable(colors.keyPressed),
                            hMargin, vMargin, hMargin, vMargin,
                        )
                    },
                )
            }
        }
    }

    override fun setEnabled(enabled: Boolean) {
        super.setEnabled(enabled)
        appearanceView.alpha = if (enabled) 1f else 0.3f
    }

    fun updateBounds() {
        val (x, y) = cachedLocation.also { appearanceView.getLocationInWindow(it) }
        cachedBounds.set(x, y, x + appearanceView.width, y + appearanceView.height)
        boundsValid = true
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        boundsValid = false
        if (layoutMarginLeft != 0f || layoutMarginRight != 0f) {
            val w = right - left
            val h = bottom - top
            val layoutWidth = (w * (1f - layoutMarginLeft - layoutMarginRight)).roundToInt()
            appearanceView.updateLayoutParams<LayoutParams> {
                leftMargin = (w * layoutMarginLeft).roundToInt()
                rightMargin = (w * layoutMarginRight).roundToInt()
            }
            appearanceView.measure(
                MeasureSpec.makeMeasureSpec(layoutWidth, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY),
            )
        }
        super.onLayout(changed, left, top, right, bottom)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        if (bordered) return
        if (def.viewId == button_space) {
            val bkgRadius = dp(3f)
            val minHeight = dp(26)
            val hInset = dp(10)
            val vInset = if (h < minHeight) 0 else min((h - minHeight) / 2, dp(16))
            appearanceView.background = insetRadiusDrawable(
                hInset, vInset, bkgRadius, colors.keyBackground,
            )
            appearanceView.padding = 0
            setupPressHighlight()
        }
    }

    companion object {
        var button_space = 0
        var button_return = 0
        var button_backspace = 0
        var button_caps = 0
        var button_lang = 0
        var button_voice = 0
        var button_quickphrase = 0
    }
}

@SuppressLint("ViewConstructor")
open class TextKeyView(
    ctx: Context,
    colors: KeyboardColors.ColorScheme,
    def: KeyDef.Appearance.Text,
) : KeyView(ctx, colors, def) {
    val mainText = android.widget.TextView(ctx).apply {
        isClickable = false
        isFocusable = false
        background = null
        text = def.displayText
        setTextSize(TypedValue.COMPLEX_UNIT_DIP, def.textSize)
        textDirection = View.TEXT_DIRECTION_FIRST_STRONG_LTR
        setTypeface(typeface, def.textStyle)
        setTextColor(
            when (def.variant) {
                Variant.Normal, Variant.AltForeground -> colors.keyText
                Variant.Alternative -> colors.specialKeyText
                Variant.Accent -> colors.accentKeyText
            },
        )
    }

    init {
        appearanceView.apply {
            add(mainText, lParams(wrapContent, wrapContent) {
                centerInParent()
            })
        }
    }
}

@SuppressLint("ViewConstructor")
class AltTextKeyView(
    ctx: Context,
    colors: KeyboardColors.ColorScheme,
    def: KeyDef.Appearance.AltText,
) : TextKeyView(ctx, colors, def) {

    val altText = android.widget.TextView(ctx).apply {
        isClickable = false
        isFocusable = false
        setTextSize(TypedValue.COMPLEX_UNIT_DIP, 10.666667f)
        setTypeface(typeface, Typeface.BOLD)
        text = def.altText
        setTextColor(colors.altText)
    }

    init {
        appearanceView.apply {
            add(altText, lParams(wrapContent, wrapContent))
        }
        applyLayout()
    }

    private fun applyLayout() {
        mainText.updateLayoutParams<ConstraintLayout.LayoutParams> {
            topToTop = parentId
            bottomToBottom = parentId
        }
        altText.updateLayoutParams<ConstraintLayout.LayoutParams> {
            topToTop = parentId
            topMargin = vMargin
            rightToRight = parentId
        }
    }
}

@SuppressLint("ViewConstructor")
class ImageKeyView(
    ctx: Context,
    colors: KeyboardColors.ColorScheme,
    def: KeyDef.Appearance.Image,
) : KeyView(ctx, colors, def) {
    val img = imageView {
        isClickable = false
        isFocusable = false
        imageTintList = ColorStateList.valueOf(
            when (def.variant) {
                Variant.Normal, Variant.AltForeground -> colors.keyText
                Variant.Alternative -> colors.specialKeyText
                Variant.Accent -> colors.accentKeyText
            },
        )
        imageResource = def.src
    }

    init {
        appearanceView.apply {
            add(img, lParams(wrapContent, wrapContent) {
                centerInParent()
            })
        }
    }
}

@SuppressLint("ViewConstructor")
class ImageTextKeyView(
    ctx: Context,
    colors: KeyboardColors.ColorScheme,
    def: KeyDef.Appearance.ImageText,
) : TextKeyView(ctx, colors, def) {
    val img = imageView {
        isClickable = false
        isFocusable = false
        imageTintList = ColorStateList.valueOf(
            when (def.variant) {
                Variant.Normal, Variant.AltForeground -> colors.keyText
                Variant.Alternative -> colors.specialKeyText
                Variant.Accent -> colors.accentKeyText
            },
        )
        imageResource = def.src
    }

    init {
        appearanceView.apply {
            add(img, lParams(dp(13), dp(13)))
        }
        mainText.updateLayoutParams<ConstraintLayout.LayoutParams> {
            centerHorizontally()
            bottomToBottom = parentId
            bottomMargin = vMargin + dp(4)
            topToTop = 0
        }
        img.updateLayoutParams<ConstraintLayout.LayoutParams> {
            centerHorizontally()
            topToTop = parentId
        }
    }
}
