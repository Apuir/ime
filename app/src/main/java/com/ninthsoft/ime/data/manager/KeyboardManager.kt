package com.ninthsoft.ime.data.manager

import android.content.Context
import android.content.res.Configuration
import androidx.core.content.edit
import com.ninthsoft.ime.data.keyboard.theme.KeyboardTheme
import kotlin.math.roundToInt

object KeyboardManager {
    const val PREFS_NAME = "keyboard_settings"
    const val DEFAULT_KEYBOARD_HEIGHT = 24
    const val DEFAULT_KEYBOARD_HEIGHT_LANDSCAPE = 44
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
        const val KEY_HEIGHT = "$PREFIX.height"
        const val KEY_HEIGHT_LANDSCAPE = "$PREFIX.height_landscape"
        const val KEY_IGNORE_INSETS = "$PREFIX.ignore_insets"
        const val KEY_THEME = "$PREFIX.theme"
        const val KEY_FOLLOW_SYSTEM = "$PREFIX.follow_system"
        const val KEY_LIGHT_THEME = "$PREFIX.light_theme"
        const val KEY_DARK_THEME = "$PREFIX.dark_theme"

        fun getHeightPercent(context: Context): Int {
            return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getInt("$PREFIX.height", DEFAULT_KEYBOARD_HEIGHT)
        }

        fun setHeightPercent(context: Context, percent: Int) {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit {
                putInt("$PREFIX.height", percent)
            }
        }

        fun getHeightPercentLandscape(context: Context): Int {
            return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getInt("$PREFIX.height_landscape", DEFAULT_KEYBOARD_HEIGHT_LANDSCAPE)
        }

        fun setHeightPercentLandscape(context: Context, percent: Int) {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit {
                putInt("$PREFIX.height_landscape", percent)
            }
        }

        const val KEY_WIDTH = "$PREFIX.width"
        const val KEY_POSITION_X = "$PREFIX.pos_x"
        const val KEY_POSITION_Y = "$PREFIX.pos_y"

        /** 键盘尺寸可调范围（%），竖屏与横屏共用；键盘内的「调整大小」工具也用这两个范围。 */
        const val WIDTH_PERCENT_MIN = 30
        const val WIDTH_PERCENT_MAX = 100
        const val HEIGHT_PERCENT_MIN = 15
        const val HEIGHT_PERCENT_MAX = 70

        /** 竖屏键盘宽度（% 屏宽）。100% 时按原来的「贴底全宽」布局。 */
        fun getWidthPercent(context: Context): Int {
            return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getInt(KEY_WIDTH, WIDTH_PERCENT_MAX)
                .coerceIn(WIDTH_PERCENT_MIN, WIDTH_PERCENT_MAX)
        }

        fun setWidthPercent(context: Context, percent: Int) {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit {
                putInt(KEY_WIDTH, percent.coerceIn(WIDTH_PERCENT_MIN, WIDTH_PERCENT_MAX))
            }
        }

        fun getPositionXRatio(context: Context): Float {
            return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getFloat(KEY_POSITION_X, 0.5f)
        }

        fun getPositionYRatio(context: Context): Float {
            return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getFloat(KEY_POSITION_Y, 1f)
        }

        fun setPosition(context: Context, xRatio: Float, yRatio: Float) {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit {
                putFloat(KEY_POSITION_X, xRatio.coerceIn(0f, 1f))
                putFloat(KEY_POSITION_Y, yRatio.coerceIn(0f, 1f))
            }
        }

        /** 竖屏尺寸/位置恢复默认：全宽、贴底居中。 */
        fun resetLayout(context: Context) {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit {
                remove(KEY_WIDTH)
                remove(KEY_POSITION_X)
                remove(KEY_POSITION_Y)
            }
        }

        fun resetHeightPercent(context: Context) {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit {
                putInt(KEY_HEIGHT, DEFAULT_KEYBOARD_HEIGHT)
            }
        }

        fun resetHeightPercentLandscape(context: Context) {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit {
                putInt(KEY_HEIGHT_LANDSCAPE, DEFAULT_KEYBOARD_HEIGHT_LANDSCAPE)
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

        fun getFollowSystem(context: Context): Boolean {
            return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getBoolean(KEY_FOLLOW_SYSTEM, false)
        }

        fun setFollowSystem(context: Context, followSystem: Boolean) {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit {
                putBoolean(KEY_FOLLOW_SYSTEM, followSystem)
            }
        }

        fun getThemeId(context: Context): String {
            return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getString("$PREFIX.theme", KeyboardTheme.DEFAULT_ID) ?: KeyboardTheme.DEFAULT_ID
        }

        fun setThemeId(context: Context, themeId: String) {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit {
                putString("$PREFIX.theme", themeId)
            }
        }

        fun getLightThemeId(context: Context): String {
            return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getString(KEY_LIGHT_THEME, KeyboardTheme.LIGHT_DEFAULT_ID)
                    ?: KeyboardTheme.LIGHT_DEFAULT_ID
        }

        fun setLightThemeId(context: Context, themeId: String) {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit {
                putString(KEY_LIGHT_THEME, themeId)
            }
        }

        fun getDarkThemeId(context: Context): String {
            return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getString(KEY_DARK_THEME, KeyboardTheme.DARK_DEFAULT_ID)
                    ?: KeyboardTheme.DARK_DEFAULT_ID
        }

        fun setDarkThemeId(context: Context, themeId: String) {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit {
                putString(KEY_DARK_THEME, themeId)
            }
        }

        object Padding {
            private const val PREFIX = "keyboard.padding"
            const val KEY_HORIZONTAL = "$PREFIX.horizontal"
            const val KEY_BOTTOM = "$PREFIX.bottom"

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

            /** 振动强度等级：0 = 关闭，1..5 依次增强（1 很轻 → 5 最强）。 */
            const val VIBRATION_LEVEL_MIN = 0
            const val VIBRATION_LEVEL_MAX = 5
            const val VIBRATION_LEVEL_DEFAULT = 3

            private const val KEY_VIBRATION_LEVEL = "$PREFIX.vibration_level"
            private const val KEY_VIBRATION_LEGACY = "$PREFIX.vibration"
            private const val KEY_VIBRATION_IGNORE_SYSTEM = "$PREFIX.vibration_ignore_system"

            /** 当前振动强度等级（0 表示关闭）。 */
            fun getVibrationLevel(context: Context): Int {
                val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                if (prefs.contains(KEY_VIBRATION_LEVEL)) {
                    return prefs.getInt(KEY_VIBRATION_LEVEL, VIBRATION_LEVEL_DEFAULT)
                        .coerceIn(VIBRATION_LEVEL_MIN, VIBRATION_LEVEL_MAX)
                }
                // 兼容旧版本只有「开 / 关」的布尔设置。
                return if (prefs.getBoolean(KEY_VIBRATION_LEGACY, true)) {
                    VIBRATION_LEVEL_DEFAULT
                } else {
                    VIBRATION_LEVEL_MIN
                }
            }

            fun setVibrationLevel(context: Context, level: Int) {
                context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit {
                    putInt(
                        KEY_VIBRATION_LEVEL,
                        level.coerceIn(VIBRATION_LEVEL_MIN, VIBRATION_LEVEL_MAX),
                    )
                }
            }

            fun getVibrationEnabled(context: Context): Boolean {
                return getVibrationLevel(context) != VIBRATION_LEVEL_MIN
            }

            fun setVibrationEnabled(context: Context, enabled: Boolean) {
                setVibrationLevel(
                    context,
                    if (enabled) VIBRATION_LEVEL_DEFAULT else VIBRATION_LEVEL_MIN,
                )
            }

            /**
             * 是否忽略系统「触感 / 振动」开关。
             *
             * 开启后按键振动只由本应用设置控制：正常走媒体振动通道（不受系统「触摸时振动」影响），
             * 系统振动总开关关闭时退回无障碍通道继续振动。
             */
            fun getIgnoreSystemSettings(context: Context): Boolean {
                return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .getBoolean(KEY_VIBRATION_IGNORE_SYSTEM, true)
            }

            fun setIgnoreSystemSettings(context: Context, ignore: Boolean) {
                context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit {
                    putBoolean(KEY_VIBRATION_IGNORE_SYSTEM, ignore)
                }
            }

            fun getSoundEnabled(context: Context): Boolean {
                return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .getBoolean("$PREFIX.sound", true)
            }

            fun setSoundEnabled(context: Context, enabled: Boolean) {
                context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit {
                    putBoolean("$PREFIX.sound", enabled)
                }
            }
        }

        object Gap {
            private const val PREFIX = "keyboard.gap"
            const val KEY_HORIZONTAL = "$PREFIX.horizontal"
            const val KEY_VERTICAL = "$PREFIX.vertical"

            private const val DEFAULT_HORIZONTAL_DP = 3
            private const val DEFAULT_VERTICAL_DP = 3

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

        object KeyRadius {
            const val KEY = "keyboard.key_radius"
            private const val DEFAULT_RADIUS_DP = 14

            fun getDp(context: Context): Int {
                return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .getInt(KEY, DEFAULT_RADIUS_DP)
            }

            fun setDp(context: Context, dp: Int) {
                context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit {
                    putInt(KEY, dp)
                }
            }
        }

        object RippleEffect {
            const val KEY = "keyboard.ripple_effect"

            fun isEnabled(context: Context): Boolean {
                return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .getBoolean(KEY, false)
            }

            fun setEnabled(context: Context, enabled: Boolean) {
                context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit {
                    putBoolean(KEY, enabled)
                }
            }
        }

        object KeyBorderStroke {
            const val KEY = "keyboard.key_border_stroke"

            fun isEnabled(context: Context): Boolean {
                return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .getBoolean(KEY, true)
            }

            fun setEnabled(context: Context, enabled: Boolean) {
                context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit {
                    putBoolean(KEY, enabled)
                }
            }
        }

        /**
         * 符号 / 数字的次级输入手势，二选一（互斥）：
         * 长按 [MODE_LONG_PRESS] 或上滑 [MODE_SWIPE_UP]。
         */
        object GestureInput {
            const val KEY = "keyboard.gesture_input"
            const val MODE_LONG_PRESS = 0
            const val MODE_SWIPE_UP = 1

            fun getMode(context: Context): Int {
                return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .getInt(KEY, MODE_LONG_PRESS)
            }

            fun setMode(context: Context, mode: Int) {
                context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit {
                    putInt(KEY, mode)
                }
            }

            fun isSwipeUp(context: Context): Boolean = getMode(context) == MODE_SWIPE_UP
        }

        /** 键盘上方工具栏中间那排可自定义的工具（有序）。 */
        object ToolbarTools {
            const val KEY = "keyboard.toolbar_tools"

            val DEFAULT_KEYS = listOf("undo", "redo", "cursor", "clipboard", "palette")

            fun getKeys(context: Context): List<String> {
                val raw = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .getString(KEY, null) ?: return DEFAULT_KEYS
                if (raw.isEmpty()) return emptyList()
                return raw.split(',').filter { it.isNotBlank() }
            }

            fun setKeys(context: Context, keys: List<String>) {
                context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit {
                    putString(KEY, keys.joinToString(","))
                }
            }
        }

        object ExpandBorder {
            const val KEY = "keyboard.expand_borders"

            fun isEnabled(context: Context): Boolean {
                return !context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .getBoolean(KEY, false)
            }

            fun setEnabled(context: Context, enabled: Boolean) {
                context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit {
                    putBoolean(KEY, !enabled)
                }
            }
        }

        /**
         * 横屏悬浮键盘：横屏时键盘不再铺满整个屏幕宽度，而是以一张可拖动的小卡片悬浮在应用之上。
         *
         * 实现上 IME 窗口仍占满屏幕，但通过 `onComputeInsets` 只把卡片的范围报告为可触摸区域，
         * 因此卡片之外的手势会穿透到下层应用，应用也不会被键盘顶起。
         */
        object Floating {
            private const val PREFIX = "keyboard.floating"
            const val KEY_ENABLED = "$PREFIX.enabled"
            const val KEY_WIDTH = "$PREFIX.width"
            const val KEY_POSITION_X = "$PREFIX.pos_x"
            const val KEY_POSITION_Y = "$PREFIX.pos_y"

            /** 用户没有手动设置宽度时的兜底值（正常会走自适应计算）。 */
            private const val DEFAULT_WIDTH_PERCENT = 45

            /** 自适应默认值的取值范围（% 屏宽）。 */
            private const val ADAPTIVE_WIDTH_MIN = 35
            private const val ADAPTIVE_WIDTH_MAX = 70

            /** 滑杆可调范围（% 屏宽）。 */
            const val WIDTH_PERCENT_MIN = 30
            const val WIDTH_PERCENT_MAX = 100

            /** 悬浮卡片横向位置比例：0=贴左，1=贴右，默认居中。 */
            private const val DEFAULT_POSITION_X = 0.5f

            /** 悬浮卡片纵向位置比例：0=贴顶，1=贴底，默认贴底。 */
            private const val DEFAULT_POSITION_Y = 1f

            fun isEnabled(context: Context): Boolean {
                return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .getBoolean(KEY_ENABLED, true)
            }

            fun setEnabled(context: Context, enabled: Boolean) {
                context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit {
                    putBoolean(KEY_ENABLED, enabled)
                }
            }

            /** 当前是否应使用悬浮键盘：开关开启且处于横屏。 */
            fun shouldUseFloating(context: Context): Boolean {
                return isEnabled(context) && context.resources.configuration.orientation ==
                    Configuration.ORIENTATION_LANDSCAPE
            }

            /**
             * 悬浮键盘宽度（% 屏宽）。用户手动拖过滑杆就用用户值，否则用自适应默认值。
             */
            fun getWidthPercent(context: Context): Int {
                val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                if (prefs.contains(KEY_WIDTH)) {
                    return prefs.getInt(KEY_WIDTH, DEFAULT_WIDTH_PERCENT)
                        .coerceIn(WIDTH_PERCENT_MIN, WIDTH_PERCENT_MAX)
                }
                return adaptiveWidthPercent(context)
            }

            /**
             * 默认宽度：让卡片宽度约等于屏幕**短边**。
             *
             * 横屏时短边就是竖屏宽度，因此悬浮键盘的按键宽度与竖屏基本一致，
             * 不会出现「又宽又扁」的横条。20:9 手机约 45%，16:9 约 56%，与方向无关。
             */
            private fun adaptiveWidthPercent(context: Context): Int {
                val dm = context.resources.displayMetrics
                val shortSide = minOf(dm.widthPixels, dm.heightPixels)
                val longSide = maxOf(dm.widthPixels, dm.heightPixels)
                if (longSide <= 0) return DEFAULT_WIDTH_PERCENT
                return (shortSide * 100f / longSide).roundToInt()
                    .coerceIn(ADAPTIVE_WIDTH_MIN, ADAPTIVE_WIDTH_MAX)
            }

            fun setWidthPercent(context: Context, percent: Int) {
                context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit {
                    putInt(KEY_WIDTH, percent.coerceIn(WIDTH_PERCENT_MIN, WIDTH_PERCENT_MAX))
                }
            }

            /** 宽度是否被用户手动设置过；未设置时滑杆显示的是自适应默认值。 */
            fun isWidthUserSet(context: Context): Boolean =
                context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).contains(KEY_WIDTH)

            /** 清除手动宽度，恢复自适应默认值。 */
            fun resetWidth(context: Context) {
                context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit {
                    remove(KEY_WIDTH)
                }
            }

            fun getPositionXRatio(context: Context): Float {
                return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .getFloat(KEY_POSITION_X, DEFAULT_POSITION_X)
            }

            fun getPositionYRatio(context: Context): Float {
                return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .getFloat(KEY_POSITION_Y, DEFAULT_POSITION_Y)
            }

            fun setPosition(context: Context, xRatio: Float, yRatio: Float) {
                context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit {
                    putFloat(KEY_POSITION_X, xRatio.coerceIn(0f, 1f))
                    putFloat(KEY_POSITION_Y, yRatio.coerceIn(0f, 1f))
                }
            }

            /** 把悬浮卡片位置恢复到默认（水平居中、贴底）。 */
            fun resetPosition(context: Context) {
                setPosition(context, DEFAULT_POSITION_X, DEFAULT_POSITION_Y)
            }
        }
    }
}
