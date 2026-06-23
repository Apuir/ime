package com.ninthsoft.ime.input.keyboard.key

import android.graphics.Typeface
import android.view.View
import androidx.annotation.DrawableRes

open class KeyDef(
    val appearance: Appearance,
    val behaviors: Set<Behavior>,
    val popups: Array<Popup>? = null,
) {
    sealed class Appearance(
        val percentWidth: Float,
        val variant: Variant,
        var border: Border,
        val margin: Boolean,
        val viewId: Int,
        var visibility: Int = View.VISIBLE,
    ) {
        enum class Variant { Normal, Alternative, Accent, AltForeground }

        enum class Border { Default, On, Off, Special }

        open class Text(
            val displayText: String,
            val textSize: Float,
            val textStyle: Int = Typeface.NORMAL,
            percentWidth: Float = 0.1f,
            variant: Variant = Variant.Normal,
            border: Border = Border.Default,
            margin: Boolean = true,
            viewId: Int = -1,
            visibility: Int = View.VISIBLE,
        ) : Appearance(percentWidth, variant, border, margin, viewId, visibility)

        class AltText(
            displayText: String,
            val altText: String,
            textSize: Float,
            textStyle: Int = Typeface.NORMAL,
            percentWidth: Float = 0.1f,
            variant: Variant = Variant.Normal,
            border: Border = Border.Default,
            margin: Boolean = true,
            viewId: Int = -1,
            visibility: Int = View.VISIBLE,
        ) : Text(
            displayText,
            textSize,
            textStyle,
            percentWidth,
            variant,
            border,
            margin,
            viewId,
            visibility
        )

        class Image(
            @DrawableRes val src: Int,
            percentWidth: Float = 0.1f,
            variant: Variant = Variant.Normal,
            border: Border = Border.Default,
            margin: Boolean = true,
            viewId: Int = -1,
        ) : Appearance(percentWidth, variant, border, margin, viewId)

        class ImageText(
            displayText: String,
            textSize: Float,
            textStyle: Int = Typeface.NORMAL,
            @DrawableRes val src: Int,
            percentWidth: Float = 0.1f,
            variant: Variant = Variant.Normal,
            border: Border = Border.Default,
            margin: Boolean = true,
            viewId: Int = -1,
        ) : Text(displayText, textSize, textStyle, percentWidth, variant, border, margin, viewId)
    }

    sealed class Behavior {
        class Press(val action: KeyAction) : Behavior()
        class LongPress(val action: KeyAction) : Behavior()
        class Repeat(val action: KeyAction) : Behavior()
        class Swipe(val action: KeyAction) : Behavior()
        class DoubleTap(val action: KeyAction) : Behavior()
    }

    sealed class Popup {
        open class Preview(val content: String) : Popup()
        class AltPreview(content: String, val alternative: String) : Preview(content)
        class Keyboard(val label: String, val keys: List<String> = emptyList()) : Popup()
        class Menu(val items: Array<Item>) : Popup() {
            class Item(
                val label: String,
                @DrawableRes val icon: Int,
                val action: KeyAction,
            )
        }
    }
}
