package com.ninthsoft.ime.data.keyboard.theme

data class KeyboardTheme(
    val id: String,
    val name: String,
    val light: KeyboardColors.ColorScheme,
    val dark: KeyboardColors.ColorScheme,
) {
    companion object {
        val PRESETS = listOf(
            KeyboardThemePresets.Warm,
            KeyboardThemePresets.Slate,
            KeyboardThemePresets.Nord,
            KeyboardThemePresets.Monokai,
            KeyboardThemePresets.Pixel,
            KeyboardThemePresets.DeepBlue,
            KeyboardThemePresets.Amoled,
            KeyboardThemePresets.Forest,
        )

        val DEFAULT = KeyboardThemePresets.Warm

        fun byId(id: String) = PRESETS.find { it.id == id } ?: DEFAULT
    }
}
