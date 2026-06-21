package com.ninthsoft.ime.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.ninthsoft.ime.R
import com.ninthsoft.ime.data.keyboard.theme.KeyboardTheme
import com.ninthsoft.ime.data.theme.ThemeManager
import com.ninthsoft.ime.ui.screen.ScreenComponent.SettingsGroup
import com.ninthsoft.ime.ui.screen.ScreenComponent.SliderRow
import com.ninthsoft.ime.ui.screen.ScreenComponent.SwitchRow
import com.ninthsoft.ime.ui.screen.ScreenComponent.ThemeChip
import com.ninthsoft.ime.ui.screen.ScreenComponent.barFontSize
import com.ninthsoft.ime.ui.screen.ScreenComponent.groupFontSize

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KeyboardSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var keySoundEnabled by remember { mutableStateOf(true) }
    var keyVibrationEnabled by remember {
        mutableStateOf(ThemeManager.Keyboard.Feedback.getVibrationEnabled(context))
    }
    var keyXGap by remember {
        mutableFloatStateOf(ThemeManager.Keyboard.Gap.getHorizontalDp(context).toFloat())
    }
    var keyYGap by remember {
        mutableFloatStateOf(ThemeManager.Keyboard.Gap.getVerticalDp(context).toFloat())
    }
    var keyboardHeight by remember {
        mutableFloatStateOf(ThemeManager.Keyboard.getHeightPercent(context).toFloat())
    }
    var horizontalPadding by remember {
        mutableFloatStateOf(ThemeManager.Keyboard.Padding.getHorizontalDp(context).toFloat())
    }
    var bottomPadding by remember {
        mutableFloatStateOf(ThemeManager.Keyboard.Padding.getBottomDp(context).toFloat())
    }
    var ignoreInsets by remember {
        mutableStateOf(ThemeManager.Keyboard.getIgnoreInsets(context))
    }
    var selectedThemeId by remember {
        mutableStateOf(ThemeManager.Keyboard.getThemeId(context))
    }

    Scaffold(
        topBar = {
            TopAppBar(title = {
                Text(
                    text = stringResource(R.string.keyboard_settings),
                    fontSize = barFontSize,
                    modifier = Modifier.padding(horizontal = 0.dp, vertical = 0.dp),
                )
            }, navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = stringResource(R.string.back),
                        modifier = Modifier.scale(0.7f)
                    )
                }
            })
        }) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Spacer(Modifier.height(8.dp))

            SettingsGroup(title = stringResource(R.string.keyboard_theme)) {
                Spacer(Modifier.height(4.dp))
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(KeyboardTheme.PRESETS) { theme ->
                        val isSelected = theme.id == selectedThemeId
                        ThemeChip(
                            theme = theme, selected = isSelected, onClick = {
                                selectedThemeId = theme.id
                                ThemeManager.Keyboard.setThemeId(context, theme.id)
                            })
                    }
                }
                Spacer(Modifier.height(4.dp))
            }

            SettingsGroup(title = stringResource(R.string.key_feedback)) {
                SwitchRow(
                    title = stringResource(R.string.key_sound),
                    checked = keySoundEnabled,
                    onCheckedChange = { keySoundEnabled = it },
                )
                SwitchRow(
                    title = stringResource(R.string.key_vibration),
                    checked = keyVibrationEnabled,
                    onCheckedChange = {
                        keyVibrationEnabled = it
                        ThemeManager.Keyboard.Feedback.setVibrationEnabled(context, it)
                    },
                )
                SliderRow(
                    title = stringResource(R.string.key_x_gap),
                    value = keyXGap,
                    valueLabel = "${keyXGap.toInt()} dp",
                    range = 0f..12f,
                    onValueChange = {
                        keyXGap = it
                        ThemeManager.Keyboard.Gap.setHorizontalDp(context, it.toInt())
                    },
                )
                SliderRow(
                    title = stringResource(R.string.key_y_gap),
                    value = keyYGap,
                    valueLabel = "${keyYGap.toInt()} dp",
                    range = 0f..16f,
                    onValueChange = {
                        keyYGap = it
                        ThemeManager.Keyboard.Gap.setVerticalDp(context, it.toInt())
                    },
                    showDivider = true
                )
            }

            SettingsGroup(title = stringResource(R.string.keyboard_layout)) {
                Spacer(modifier = Modifier.height(1.dp))

                SliderRow(
                    title = stringResource(R.string.keyboard_height),
                    value = keyboardHeight,
                    valueLabel = "${keyboardHeight.toInt()}%",
                    range = 20f..50f,
                    onValueChange = {
                        keyboardHeight = it
                        ThemeManager.Keyboard.setHeightPercent(context, it.toInt())
                    },
                )
                SliderRow(
                    title = stringResource(R.string.horizontal_padding),
                    value = horizontalPadding,
                    valueLabel = "${horizontalPadding.toInt()} dp",
                    range = 0f..20f,
                    onValueChange = {
                        horizontalPadding = it
                        ThemeManager.Keyboard.Padding.setHorizontalDp(context, it.toInt())
                    },
                )
                SliderRow(
                    title = stringResource(R.string.bottom_padding),
                    value = bottomPadding,
                    valueLabel = "${bottomPadding.toInt()} dp",
                    range = 0f..40f,
                    onValueChange = {
                        bottomPadding = it
                        ThemeManager.Keyboard.Padding.setBottomDp(context, it.toInt())
                    },
                )
                SwitchRow(
                    title = stringResource(R.string.ignore_system_insets),
                    checked = ignoreInsets,
                    onCheckedChange = {
                        ignoreInsets = it
                        ThemeManager.Keyboard.setIgnoreInsets(context, it)
                    },
                    showDivider = false
                )
            }
            Spacer(Modifier.height(4.dp))
        }
    }
}
