package com.ninthsoft.ime.data.keyboard.theme

import android.content.Context
import android.content.res.Configuration
import com.ninthsoft.ime.data.manager.KeyboardManager
import kotlinx.serialization.Serializable

object KeyboardColors {

    @Serializable
    enum class SurfaceStyle {
        Raised,
        Flat,
    }

    /** 按键形状：圆角矩形 / 直角矩形 / 椭圆（药丸）。 */
    @Serializable
    enum class KeyShape {
        Rounded,
        Rectangle,
        Oval,
    }

    /**
     * 键盘几何参数快照。仅当主题由主题编辑器保存时非空；
     * 应用该主题时会把这里的值写回 [com.ninthsoft.ime.data.manager.KeyboardManager] 的全局设置。
     */
    @Serializable
    data class KeyGeometry(
        val cornerRadius: Float = 14f,
        val keyHMargin: Float = 3f,
        val keyVMargin: Float = 3f,
        val keyboardHeightPercent: Int = 24,
        val keyboardHeightLandscapePercent: Int = 44,
    )

    @Serializable
    data class ColorScheme(
        val keyBackground: Int,
        val keyPressed: Int,
        val keyBorderStroke: Int,
        val specialKeyBackground: Int,
        val specialKeyPressed: Int,
        val specialKeyBorderStroke: Int,
        val accentKeyBackground: Int,
        val accentKeyPressed: Int,
        val accentKeyBorderStroke: Int,
        val keyText: Int,
        val specialKeyText: Int,
        val accentKeyText: Int,
        val altText: Int,
        val background: Int,
        val surfaceStyle: SurfaceStyle = SurfaceStyle.Raised,
        val cornerRadius: Float = 5f,
        val keyHMargin: Float = 3f,
        val keyVMargin: Float = 4f,
        /** 按键边框描边厚度（dp）。0 表示不绘制边框。 */
        val keyBorderWidth: Float = 1f,
        /** 按键形状。 */
        val keyShape: KeyShape = KeyShape.Rounded,
        /** 键盘几何参数快照；null 表示不随主题改变几何设置。 */
        val geometry: KeyGeometry? = null,
        val panel: PanelColors,
        val pinner: PinnerColors,
        val toastBackground: Int = specialKeyBackground,
        val toastText: Int = keyText,
    ) {
        @Serializable
        data class PanelColors(
            val background: Int,
            val toolbarText: Int,
            val toolbarActived: Int,
            val toolbarIcon: Int,
            val candidateBackground: Int,
            val candidateText: Int,
            val candidateIndex: Int,
            val candidateDivider: Int,
            val toolbarPressed: Int,
        ) {
            companion object {
                fun from(c: ColorScheme) = PanelColors(
                    background = c.background,
                    toolbarText = c.keyText,
                    toolbarActived = c.accentKeyBackground,
                    toolbarIcon = c.specialKeyText,
                    candidateBackground = c.specialKeyBackground,
                    candidateText = c.keyText,
                    candidateIndex = c.altText,
                    candidateDivider = c.altText,
                    toolbarPressed = c.keyPressed,
                )
            }
        }

        @Serializable
        data class PinnerColors(
            val background: Int,
            val textColor: Int,
            val secondaryTextColor: Int,
        ) {
            companion object {
                fun from(c: ColorScheme) = PinnerColors(
                    background = c.keyBackground,
                    textColor = c.accentKeyText,
                    secondaryTextColor = c.altText,
                )
            }
        }
    }

    fun resolve(context: Context): ColorScheme {
        val c = themeFor(context).colors
        val userRadius = KeyboardManager.Keyboard.KeyRadius.getDp(context).toFloat()
        val userHMargin = KeyboardManager.Keyboard.Gap.getHorizontalDp(context).toFloat()
        val userVMargin = KeyboardManager.Keyboard.Gap.getVerticalDp(context).toFloat()
        return c.copy(
            cornerRadius = userRadius,
            keyHMargin = userHMargin,
            keyVMargin = userVMargin,
        )
    }

    /** 当前全局键盘几何参数快照，供主题编辑器作为默认值。 */
    fun currentGeometry(context: Context): KeyGeometry = KeyGeometry(
        cornerRadius = KeyboardManager.Keyboard.KeyRadius.getDp(context).toFloat(),
        keyHMargin = KeyboardManager.Keyboard.Gap.getHorizontalDp(context).toFloat(),
        keyVMargin = KeyboardManager.Keyboard.Gap.getVerticalDp(context).toFloat(),
        keyboardHeightPercent = KeyboardManager.Keyboard.getHeightPercent(context),
        keyboardHeightLandscapePercent = KeyboardManager.Keyboard.getHeightPercentLandscape(context),
    )

    /**
     * 把主题内嵌的几何参数写回全局设置。仅当主题带有 [ColorScheme.geometry] 时生效，
     * 内置预设（geometry 为 null）不会覆盖用户的键盘几何设置。
     */
    fun applyGeometry(context: Context, scheme: ColorScheme) {
        val g = scheme.geometry ?: return
        KeyboardManager.Keyboard.KeyRadius.setDp(context, g.cornerRadius.toInt())
        KeyboardManager.Keyboard.Gap.setHorizontalDp(context, g.keyHMargin.toInt())
        KeyboardManager.Keyboard.Gap.setVerticalDp(context, g.keyVMargin.toInt())
        KeyboardManager.Keyboard.setHeightPercent(context, g.keyboardHeightPercent)
        KeyboardManager.Keyboard.setHeightPercentLandscape(
            context, g.keyboardHeightLandscapePercent,
        )
    }

    fun themeFor(context: Context): KeyboardTheme {
        val followSystem = KeyboardManager.Keyboard.getFollowSystem(context)
        if (followSystem) {
            val isDark = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
                    Configuration.UI_MODE_NIGHT_YES
            val themeId = if (isDark) KeyboardManager.Keyboard.getDarkThemeId(context)
                else KeyboardManager.Keyboard.getLightThemeId(context)
            return KeyboardTheme.byId(themeId)
        }
        val themeId = KeyboardManager.Keyboard.getThemeId(context)
        return KeyboardTheme.byId(themeId)
    }
}
