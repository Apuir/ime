package com.ninthsoft.ime.data.keyboard.theme

import kotlinx.serialization.Serializable

@Serializable
data class KeyboardTheme(
    val id: String,
    val name: String,
    val colors: KeyboardColors.ColorScheme,
) {
    companion object {
        const val DEFAULT_ID = "amoled"
        const val LIGHT_DEFAULT_ID = "light"
        const val DARK_DEFAULT_ID = "amoled"

        /** 主题选择列表：内置预设 + 全部用户自定义主题（数量不设上限）。 */
        val PRESETS: List<KeyboardTheme>
            get() = KeyboardThemePresets.ALL

        val DEFAULT: KeyboardTheme
            get() = KeyboardThemePresets.Amoled
        val LIGHT_DEFAULT: KeyboardTheme
            get() = KeyboardThemePresets.Light
        val DARK_DEFAULT: KeyboardTheme
            get() = KeyboardThemePresets.Amoled

        fun byId(id: String) = KeyboardThemePresets.ALL.find { it.id == id } ?: DEFAULT
    }
}
