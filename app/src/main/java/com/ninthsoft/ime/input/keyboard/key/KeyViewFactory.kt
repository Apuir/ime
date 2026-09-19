package com.ninthsoft.ime.input.keyboard.key

import android.content.Context
import com.ninthsoft.ime.data.keyboard.theme.KeyboardColors

/**
 * 按 [KeyDef.Appearance] 建出对应的 [KeyView]。
 *
 * 只做「外观 → 视图」这一件事。键盘专属的副作用（按键气泡、上滑触发系数、
 * `returnKeyView` / `spaceKeyView` 登记、涟漪）留在 `BaseKeyboard#createKeyView` ——
 * 面板复用这些键视图时不需要它们，混进来两边会互相打架。
 */
object KeyViewFactory {

    fun create(
        context: Context,
        colors: KeyboardColors.ColorScheme,
        def: KeyDef,
    ): KeyView = when (val appearance = def.appearance) {
        is KeyDef.Appearance.AltText -> AltTextKeyView(context, colors, appearance)
        is KeyDef.Appearance.ImageText -> ImageTextKeyView(context, colors, appearance)
        is KeyDef.Appearance.Text -> TextKeyView(context, colors, appearance)
        is KeyDef.Appearance.Image -> ImageKeyView(context, colors, appearance)
        is KeyDef.Appearance.SidePannel -> SidePanelKeyView(context, colors, appearance)
    }
}
