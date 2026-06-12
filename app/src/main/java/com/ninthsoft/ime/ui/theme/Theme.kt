package com.ninthsoft.ime.ui.theme

import android.annotation.SuppressLint
import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.core.view.WindowCompat
import com.ninthsoft.ime.data.theme.ThemeManager.Theme.MODE_DARK
import com.ninthsoft.ime.data.theme.ThemeManager.Theme.MODE_LIGHT

private val LightColorScheme = lightColorScheme(
    primary = Color(0xFF2080F0),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD9EBFF),
    onPrimaryContainer = Color(0xFF003E8A),

    secondary = Color(0xFF18A058),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFDDF5E8),
    onSecondaryContainer = Color(0xFF004D1A),

    tertiary = Color(0xFFF0A020),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFFFE8C2),
    onTertiaryContainer = Color(0xFF6B4200),

    background = Color(0xFFF8F9FB),
    onBackground = Color(0xFF18181C),

    surface = Color(0xFFF8F9FB),
    onSurface = Color(0xFF18181C),

    surfaceVariant = Color(0xFFF2F3F5),
    onSurfaceVariant = Color(0xFF606266),

    error = Color(0xFFD03050),
    onError = Color.White,
    errorContainer = Color(0xFFFFDCE3),
    onErrorContainer = Color(0xFF7A001D),

    outline = Color(0xFFD0D3D8)
)


private val DarkColorScheme = darkColorScheme(
    primary = Color(0xFF63A9FF),
    onPrimary = Color(0xFF002C63),
    primaryContainer = Color(0xFF004A9F),
    onPrimaryContainer = Color(0xFFD9EBFF),

    secondary = Color(0xFF4CD787),
    onSecondary = Color(0xFF00381A),
    secondaryContainer = Color(0xDF005A2D),
    onSecondaryContainer = Color(0xFFDDF5E8),

    tertiary = Color(0xFFFFC75E),
    onTertiary = Color(0xFF4A2C00),
    tertiaryContainer = Color(0xFF6A4300),
    onTertiaryContainer = Color(0xFFFFE8C2),

    background = Color(0xFF18181C),
    onBackground = Color(0xFFE5E7EB),

    surface = Color(0xFF18181C),
    onSurface = Color(0xFFE5E7EB),

    surfaceVariant = Color(0xFF2A2D33),
    onSurfaceVariant = Color(0xFFB5B8BE),

    error = Color(0xFFFF6B81),
    onError = Color(0xFF4A0010),
    errorContainer = Color(0xFF7A1F34),
    onErrorContainer = Color(0xFFFFDCE3),

    outline = Color(0xFF5A5F68)
)

@SuppressLint("ContextCastToActivity")
@Composable
fun ImeTheme(
    themeMode: Int = 0, dynamicColor: Boolean = false, content: @Composable () -> Unit
) {
    val darkTheme = when (themeMode) {
        MODE_LIGHT -> false
        MODE_DARK -> true
        else -> isSystemInDarkTheme()
    }

    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }

        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme, typography = Typography, content = content
    )

    val activity = LocalContext.current as? Activity
    SideEffect {
        activity?.window?.let { window ->
            WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightStatusBars =
                !darkTheme
        }
    }
}
