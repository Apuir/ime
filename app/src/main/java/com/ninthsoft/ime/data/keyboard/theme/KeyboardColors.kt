package com.ninthsoft.ime.data.keyboard.theme

import android.content.Context
import android.content.res.Configuration
import com.ninthsoft.ime.data.theme.ThemeManager

object KeyboardColors {

    enum class SurfaceStyle {
        Raised,
        Flat,
    }

    data class ColorScheme(
        val keyBackground: Int,
        val keyPressed: Int,
        val specialKeyBackground: Int,
        val specialKeyPressed: Int,
        val accentKeyBackground: Int,
        val accentKeyPressed: Int,
        val keyText: Int,
        val specialKeyText: Int,
        val accentKeyText: Int,
        val altText: Int,
        val background: Int,
        val surfaceStyle: SurfaceStyle = SurfaceStyle.Raised,
    )

    fun resolve(context: Context): ColorScheme {
        val isDark = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
                Configuration.UI_MODE_NIGHT_YES
        val themeId = ThemeManager.Keyboard.getThemeId(context)
        val theme = KeyboardTheme.byId(themeId)
        return if (isDark) theme.dark else theme.light
    }
}
