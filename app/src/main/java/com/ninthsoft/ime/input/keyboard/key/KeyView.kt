package com.ninthsoft.ime.input.keyboard.key

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.InsetDrawable
import android.graphics.drawable.LayerDrawable
import android.util.TypedValue
import android.view.View
import androidx.annotation.FloatRange
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.view.updateLayoutParams
import com.ninthsoft.ime.data.keyboard.theme.KeyboardColors
import com.ninthsoft.ime.data.keyboard.theme.KeyboardColors.SurfaceStyle
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
import androidx.core.graphics.drawable.toDrawable

abstract class KeyView(
    ctx: Context,
    protected val colors: KeyboardColors.ColorScheme,
    val def: KeyDef.Appearance,
) : CustomGestureView(ctx) {

    var bordered: Boolean = true
    var borderStroke: Boolean = false
    var rippled: Boolean = true
    var radius = if (colors.surfaceStyle == SurfaceStyle.Flat) dp(7f) else dp(5f)
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

    /** Text to show in the key preview popup (null = no preview) */
    open val displayText: String? get() = null

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
            setupBackgroundWithPress()
        } else {
            if (def.border != Border.Special) {
                setupBackgroundWithPress()
            }
        }
        add(appearanceView, lParams(matchParent, matchParent))
    }

    private var pressedLayerAlpha = 0
    private var bgAnimator: ValueAnimator? = null

    private fun setupBackgroundWithPress() {
        val bkgColor = when (def.variant) {
            Variant.Normal, Variant.AltForeground -> colors.keyBackground
            Variant.Alternative -> colors.specialKeyBackground
            Variant.Accent -> colors.accentKeyBackground
        }
        val pressedColor = when (def.variant) {
            Variant.Normal, Variant.AltForeground -> colors.keyPressed
            Variant.Alternative -> colors.specialKeyPressed
            Variant.Accent -> colors.accentKeyPressed
        }
        val hasShape = (bordered && def.border != Border.Off) || def.border == Border.On
        val normalBg: android.graphics.drawable.Drawable = if (hasShape) {
            createShapeBkg(bkgColor)
        } else {
            Color.TRANSPARENT.toDrawable()
        }
        val pressedBg: android.graphics.drawable.Drawable = if (hasShape) {
            createShapeBkg(pressedColor)
        } else {
            InsetDrawable(
                pressedColor.toDrawable(),
                hMargin, vMargin, hMargin, vMargin,
            )
        }
        val layered = LayerDrawable(arrayOf(normalBg, pressedBg))
        pressedBg.alpha = 0
        pressedLayerAlpha = 0
        appearanceView.background = layered
    }

    private fun createShapeBkg(color: Int): android.graphics.drawable.Drawable {
        val borderOrShadowWidth = dp(1)
        return when {
            borderStroke -> borderedKeyBackgroundDrawable(
                color, colors.keyBackground,
                radius, borderOrShadowWidth, hMargin, vMargin,
            )

            colors.surfaceStyle == SurfaceStyle.Flat -> flatKeyBackgroundDrawable(
                color, Color.argb(34, 255, 255, 255),
                radius, borderOrShadowWidth, hMargin, vMargin,
            )

            else -> shadowedKeyBackgroundDrawable(
                color, Color.argb(30, 0, 0, 0),
                radius, borderOrShadowWidth, hMargin, vMargin,
            )
        }
    }

    override fun setEnabled(enabled: Boolean) {
        super.setEnabled(enabled)
        appearanceView.alpha = if (enabled) 1f else 0.3f
    }

    override fun drawableStateChanged() {
        super.drawableStateChanged()
        val bg = appearanceView.background
        if (bg !is LayerDrawable || bg.numberOfLayers < 2) return

        bgAnimator?.cancel()

        val target = if (isPressed) 255 else 0
        val pressedDrawable = bg.getDrawable(1) ?: return
        bgAnimator = ValueAnimator.ofInt(pressedLayerAlpha, target).apply {
            duration = if (isPressed) 100 else 80
            addUpdateListener {
                val alpha = animatedValue as Int
                pressedLayerAlpha = alpha
                pressedDrawable.alpha = alpha
            }
            start()
        }
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
            bgAnimator?.cancel()
            val bkgRadius = dp(3f)
            val minHeight = dp(26)
            val hInset = dp(10)
            val vInset = if (h < minHeight) 0 else min((h - minHeight) / 2, dp(16))
            val normalBg = insetRadiusDrawable(
                hInset, vInset, bkgRadius, colors.keyBackground,
            )
            val pressedBg = insetRadiusDrawable(
                hInset, vInset, bkgRadius, colors.specialKeyPressed,
            )
            val layered = LayerDrawable(arrayOf(normalBg, pressedBg))
            pressedBg.alpha = 0
            pressedLayerAlpha = 0
            appearanceView.background = layered
            appearanceView.padding = 0
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
    override val displayText: String? get() = mainText.text.toString()

    val mainText = android.widget.TextView(ctx).apply {
        isClickable = false
        isFocusable = false
        background = null
        text = def.displayText
        setTextSize(TypedValue.COMPLEX_UNIT_DIP, def.textSize)
        textDirection = TEXT_DIRECTION_FIRST_STRONG_LTR
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
        mainText.id = generateViewId()
        mainText.updateLayoutParams<ConstraintLayout.LayoutParams> {
            topToTop = parentId
            bottomToBottom = parentId
            startToStart = parentId
            endToEnd = parentId
            verticalBias = 0.15f
        }
        altText.updateLayoutParams<ConstraintLayout.LayoutParams> {
            topToBottom = mainText.id
            startToStart = parentId
            endToEnd = parentId
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
        mainText.id = generateViewId()
        mainText.updateLayoutParams<ConstraintLayout.LayoutParams> {
            centerHorizontally()
            topToTop = parentId
            bottomToBottom = parentId
            verticalBias = 0.6f
        }
        img.updateLayoutParams<ConstraintLayout.LayoutParams> {
            centerHorizontally()
            bottomToTop = mainText.id
            topToTop = parentId
        }
        img.translationY = dp(12).toFloat()
        mainText.translationY = dp(4).toFloat()
    }
}
