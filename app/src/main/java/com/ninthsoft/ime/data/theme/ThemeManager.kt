package com.ninthsoft.ime.data.theme

import android.content.Context
import androidx.core.content.edit
import com.ninthsoft.ime.data.keyboard.theme.KeyboardTheme

object ThemeManager {
    const val PREFS_NAME = "ime_prefs"

    private const val DEFAULT_KEYBOARD_HEIGHT = 24
    private const val DEFAULT_PADDING_DP = 4

    object Theme {
        private const val PREFIX = "theme"

        const val MODE_SYSTEM = 0
        const val MODE_LIGHT = 1
        const val MODE_DARK = 2

        fun getMode(context: Context): Int {
            return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getInt("$PREFIX.mode", MODE_SYSTEM)
        }

        fun setMode(context: Context, mode: Int) {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit {
                putInt("$PREFIX.mode", mode)
            }
        }
    }

    object Keyboard {
        private const val PREFIX = "keyboard"

        fun getHeightPercent(context: Context): Int {
            return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getInt("$PREFIX.height", DEFAULT_KEYBOARD_HEIGHT)
        }

        fun setHeightPercent(context: Context, percent: Int) {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit {
                putInt("$PREFIX.height", percent)
            }
        }

        fun getIgnoreInsets(context: Context): Boolean {
            return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getBoolean("$PREFIX.ignore_insets", false)
        }

        fun setIgnoreInsets(context: Context, ignore: Boolean) {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit {
                putBoolean("$PREFIX.ignore_insets", ignore)
            }
        }

        fun getThemeId(context: Context): String {
            return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getString("$PREFIX.theme", KeyboardTheme.DEFAULT.id) ?: KeyboardTheme.DEFAULT.id
        }

        fun setThemeId(context: Context, themeId: String) {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit {
                putString("$PREFIX.theme", themeId)
            }
        }

        object Padding {
            private const val PREFIX = "keyboard.padding"

            fun getHorizontalDp(context: Context): Int {
                return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .getInt("$PREFIX.horizontal", DEFAULT_PADDING_DP)
            }

            fun setHorizontalDp(context: Context, dp: Int) {
                context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit {
                    putInt("$PREFIX.horizontal", dp)
                }
            }

            fun getBottomDp(context: Context): Int {
                return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .getInt("$PREFIX.bottom", DEFAULT_PADDING_DP)
            }

            fun setBottomDp(context: Context, dp: Int) {
                context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit {
                    putInt("$PREFIX.bottom", dp)
                }
            }
        }

        object Feedback {
            private const val PREFIX = "keyboard.feedback"

            fun getVibrationEnabled(context: Context): Boolean {
                return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .getBoolean("$PREFIX.vibration", true)
            }

            fun setVibrationEnabled(context: Context, enabled: Boolean) {
                context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit {
                    putBoolean("$PREFIX.vibration", enabled)
                }
            }
        }

        object Gap {
            private const val PREFIX = "keyboard.gap"

            private const val DEFAULT_HORIZONTAL_DP = 3
            private const val DEFAULT_VERTICAL_DP = 4

            fun getHorizontalDp(context: Context): Int {
                return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .getInt("$PREFIX.horizontal", DEFAULT_HORIZONTAL_DP)
            }

            fun setHorizontalDp(context: Context, dp: Int) {
                context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit {
                    putInt("$PREFIX.horizontal", dp)
                }
            }

            fun getVerticalDp(context: Context): Int {
                return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .getInt("$PREFIX.vertical", DEFAULT_VERTICAL_DP)
            }

            fun setVerticalDp(context: Context, dp: Int) {
                context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit {
                    putInt("$PREFIX.vertical", dp)
                }
            }
        }
    }
}
