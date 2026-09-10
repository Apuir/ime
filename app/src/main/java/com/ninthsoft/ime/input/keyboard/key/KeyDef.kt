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
        /** 纵向跨行数；>1 时该键会脱离所在行，作为跨行键参与布局（需配合 BaseKeyboard 的 span 逻辑）。 */
        val rowSpan: Int = 1,
        /** 跨行键是否锚定在右侧（默认左侧，用于左侧标点栏）。仅 rowSpan > 1 时生效。 */
        val alignRight: Boolean = false,
    ) {
        enum class Variant { Normal, Alternative, Accent, AltForeground, None }

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
            rowSpan: Int = 1,
            alignRight: Boolean = false,
        ) : Appearance(percentWidth, variant, border, margin, viewId, visibility, rowSpan, alignRight)

        class AltText(
            displayText: String,
            val altText: String,
            val altTextTranslationY: Int = 0,
            val mainTextTranslationY: Int = 0,
            val altTextSize:Float  = 11f,
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

        class SidePannel(
            rowSpan: Int = 3,
            val visableRow: Int = 4,
            percentWidth: Float = 0.1f,
            variant: Variant = Variant.Normal,
            border: Border = Border.Default,
            margin: Boolean = true,
            viewId: Int = -1,
        ) : Appearance(percentWidth, variant, border, margin, viewId, rowSpan = rowSpan)

        class Image(
            @DrawableRes val src: Int,
            percentWidth: Float = 0.1f,
            variant: Variant = Variant.Normal,
            border: Border = Border.Default,
            margin: Boolean = true,
            viewId: Int = -1,
            rowSpan: Int = 1,
            alignRight: Boolean = false,
        ) : Appearance(
            percentWidth, variant, border, margin, viewId,
            rowSpan = rowSpan, alignRight = alignRight,
        )

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
        class Press(val action: KeyboardAction) : Behavior()

        /**
         * 长按行为。
         * [altInput] 为 true 表示这是按键上次级符号/数字的输入（如 26 键字母键上的数字、
         * 九键上的数字），开启「上滑输入」手势模式时会改为上滑触发、同时禁用长按。
         */
        class LongPress(val action: KeyboardAction, val altInput: Boolean = false) : Behavior()

        class Repeat(val action: KeyboardAction) : Behavior()
        class Swipe(val action: KeyboardAction) : Behavior()
        class DoubleTap(val action: KeyboardAction) : Behavior()
    }

    sealed class Popup {
        open class Preview(val content: String) : Popup()
        class AltPreview(content: String, val alternative: String) : Preview(content)
        class Keyboard(val label: String, val keys: List<KeyboardAction>) : Popup()
        class Menu(val items: Array<Item>) : Popup() {
            class Item(
                val label: String,
                @DrawableRes val icon: Int,
                val action: KeyboardAction,
            )
        }
    }
}
