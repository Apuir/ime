package com.ninthsoft.ime.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ninthsoft.ime.R
import com.ninthsoft.ime.base.feedback.InputFeedbacks
import com.ninthsoft.ime.data.manager.KeyboardManager
import com.ninthsoft.ime.ui.screen.ScreenComponent.ClickableRow
import com.ninthsoft.ime.ui.screen.ScreenComponent.SettingsGroup
import com.ninthsoft.ime.ui.screen.ScreenComponent.SingleChoiceDialog
import com.ninthsoft.ime.ui.screen.ScreenComponent.SliderRow
import com.ninthsoft.ime.ui.screen.ScreenComponent.SwitchRow
import com.ninthsoft.ime.ui.screen.ScreenComponent.barFontSize
import com.ninthsoft.ime.ui.screen.ScreenComponent.rowSubFontSize

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KeyboardSettingsScreen(
    onBack: () -> Unit,
    onOpenToolbarSettings: () -> Unit = {},
    onOpenSidePanelSymbols: () -> Unit = {},
) {
    val context = LocalContext.current
    // 「系统触感」模式只能通过 View 才能拿到厂商预置的按键效果，所以预览也需要它。
    val view = LocalView.current

    var keySoundEnabled by remember {
        mutableStateOf(KeyboardManager.Keyboard.Feedback.getSoundEnabled(context))
    }
    var keyVibrationLevel by remember {
        mutableIntStateOf(KeyboardManager.Keyboard.Feedback.getVibrationLevel(context))
    }
    var vibrationEffect by remember {
        mutableIntStateOf(KeyboardManager.Keyboard.Feedback.getVibrationEffect(context))
    }
    var ignoreSystemVibration by remember {
        mutableStateOf(KeyboardManager.Keyboard.Feedback.getIgnoreSystemSettings(context))
    }
    var showVibrationLevelDialog by remember { mutableStateOf(false) }
    var showVibrationEffectDialog by remember { mutableStateOf(false) }
    var keyBorderEnabled by remember {
        mutableStateOf(KeyboardManager.Keyboard.KeyBorderStroke.isEnabled(context))
    }
    var keyXGap by remember {
        mutableFloatStateOf(KeyboardManager.Keyboard.Gap.getHorizontalDp(context).toFloat())
    }
    var keyYGap by remember {
        mutableFloatStateOf(KeyboardManager.Keyboard.Gap.getVerticalDp(context).toFloat())
    }
    var keyboardHeight by remember {
        mutableFloatStateOf(KeyboardManager.Keyboard.getHeightPercent(context).toFloat())
    }
    var keyboardHeightLandscape by remember {
        mutableFloatStateOf(KeyboardManager.Keyboard.getHeightPercentLandscape(context).toFloat())
    }
    var landscapeFloating by remember {
        mutableStateOf(KeyboardManager.Keyboard.Floating.isEnabled(context))
    }
    var floatingWidth by remember {
        mutableFloatStateOf(KeyboardManager.Keyboard.Floating.getWidthPercent(context).toFloat())
    }
    var floatingWidthUserSet by remember {
        mutableStateOf(KeyboardManager.Keyboard.Floating.isWidthUserSet(context))
    }
    var horizontalPadding by remember {
        mutableFloatStateOf(KeyboardManager.Keyboard.Padding.getHorizontalDp(context).toFloat())
    }
    var bottomPadding by remember {
        mutableFloatStateOf(KeyboardManager.Keyboard.Padding.getBottomDp(context).toFloat())
    }
    var ignoreInsets by remember {
        mutableStateOf(KeyboardManager.Keyboard.getIgnoreInsets(context))
    }
    var keyRadius by remember {
        mutableFloatStateOf(KeyboardManager.Keyboard.KeyRadius.getDp(context).toFloat())
    }
    var rippleEnabled by remember {
        mutableStateOf(KeyboardManager.Keyboard.RippleEffect.isEnabled(context))
    }
    var expandBorder by remember {
        mutableStateOf(KeyboardManager.Keyboard.ExpandBorder.isEnabled(context))
    }
    var altInputMode by remember {
        mutableIntStateOf(KeyboardManager.Keyboard.GestureInput.getMode(context))
    }
    var showGestureDialog by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.keyboard_settings),
                        fontSize = barFontSize,
                        fontWeight = FontWeight.Bold,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.back),
                            modifier = Modifier.scale(0.8f),
                            tint = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Spacer(Modifier.height(4.dp))

            SettingsGroup(title = stringResource(R.string.key_feedback)) {
                SwitchRow(
                    title = stringResource(R.string.key_sound),
                    checked = keySoundEnabled,
                    onCheckedChange = {
                        keySoundEnabled = it
                        KeyboardManager.Keyboard.Feedback.setSoundEnabled(context, it)
                    },
                )
                SwitchRow(
                    title = stringResource(R.string.key_vibration),
                    checked = keyVibrationLevel > KeyboardManager.Keyboard.Feedback.VIBRATION_LEVEL_MIN,
                    onCheckedChange = { enabled ->
                        val level = if (enabled) {
                            KeyboardManager.Keyboard.Feedback.VIBRATION_LEVEL_DEFAULT
                        } else {
                            KeyboardManager.Keyboard.Feedback.VIBRATION_LEVEL_MIN
                        }
                        keyVibrationLevel = level
                        KeyboardManager.Keyboard.Feedback.setVibrationLevel(context, level)
                        if (enabled) InputFeedbacks.previewVibration(view, level)
                    },
                )
                if (keyVibrationLevel > KeyboardManager.Keyboard.Feedback.VIBRATION_LEVEL_MIN) {
                    val systemEffect = vibrationEffect ==
                        KeyboardManager.Keyboard.Feedback.VIBRATION_EFFECT_SYSTEM
                    ClickableRow(
                        title = stringResource(R.string.vibration_effect),
                        value = vibrationEffectLabel(vibrationEffect),
                        onClick = { showVibrationEffectDialog = true },
                    )
                    if (systemEffect) {
                        // 系统触感模式下强度和开关都由系统决定，隐藏自定义档位与忽略开关。
                        Text(
                            text = stringResource(R.string.vibration_effect_system_desc),
                            fontSize = rowSubFontSize,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(bottom = 8.dp),
                        )
                    } else {
                        ClickableRow(
                            title = stringResource(R.string.vibration_strength),
                            value = vibrationLevelLabel(keyVibrationLevel),
                            onClick = { showVibrationLevelDialog = true },
                        )
                        SwitchRow(
                            title = stringResource(R.string.vibration_ignore_system),
                            checked = ignoreSystemVibration,
                            onCheckedChange = {
                                ignoreSystemVibration = it
                                KeyboardManager.Keyboard.Feedback.setIgnoreSystemSettings(
                                    context,
                                    it,
                                )
                                if (it) InputFeedbacks.previewVibration(view, keyVibrationLevel)
                            },
                        )
                        Text(
                            text = stringResource(R.string.vibration_ignore_system_desc),
                            fontSize = rowSubFontSize,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(bottom = 8.dp),
                        )
                    }
                }
                SwitchRow(
                    title = stringResource(R.string.key_ripple_effect),
                    checked = rippleEnabled,
                    onCheckedChange = {
                        rippleEnabled = it
                        KeyboardManager.Keyboard.RippleEffect.setEnabled(context, it)
                    },
                )
                SwitchRow(
                    title = stringResource(R.string.key_border_show),
                    checked = keyBorderEnabled,
                    onCheckedChange = {
                        keyBorderEnabled = it
                        KeyboardManager.Keyboard.KeyBorderStroke.setEnabled(context, it)
                    },
                )
                SwitchRow(
                    title = stringResource(R.string.expand_border),
                    checked = expandBorder,
                    onCheckedChange = {
                        expandBorder = it
                        KeyboardManager.Keyboard.ExpandBorder.setEnabled(context, it)
                    },
                )
                Spacer(Modifier.height(14.dp))
                SliderRow(
                    title = stringResource(R.string.key_corner_radius),
                    value = keyRadius,
                    valueLabel = "${keyRadius.toInt()} dp",
                    range = 0f..32f,
                    onValueChange = {
                        keyRadius = it
                        KeyboardManager.Keyboard.KeyRadius.setDp(context, it.toInt())
                    },
                )
                SliderRow(
                    title = stringResource(R.string.key_x_gap),
                    value = keyXGap,
                    valueLabel = "${keyXGap.toInt()} dp",
                    range = 0f..16f,
                    onValueChange = {
                        keyXGap = it
                        KeyboardManager.Keyboard.Gap.setHorizontalDp(context, it.toInt())
                    },
                )
                SliderRow(
                    title = stringResource(R.string.key_y_gap),
                    value = keyYGap,
                    valueLabel = "${keyYGap.toInt()} dp",
                    range = 0f..16f,
                    onValueChange = {
                        keyYGap = it
                        KeyboardManager.Keyboard.Gap.setVerticalDp(context, it.toInt())
                    },
                    showDivider = true,
                )
            }

            SettingsGroup(title = stringResource(R.string.keyboard_gesture)) {
                ClickableRow(
                    title = stringResource(R.string.gesture_alt_input),
                    value = stringResource(
                        if (altInputMode == KeyboardManager.Keyboard.GestureInput.MODE_SWIPE_UP) {
                            R.string.gesture_swipe_up
                        } else {
                            R.string.gesture_long_press
                        }
                    ),
                    onClick = { showGestureDialog = true },
                )
                Text(
                    text = stringResource(
                        if (altInputMode == KeyboardManager.Keyboard.GestureInput.MODE_SWIPE_UP) {
                            R.string.gesture_swipe_up_desc
                        } else {
                            R.string.gesture_long_press_desc
                        }
                    ),
                    fontSize = rowSubFontSize,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
            }

            SettingsGroup(title = stringResource(R.string.toolbar_tools)) {
                ClickableRow(
                    title = stringResource(R.string.toolbar_tools),
                    value = stringResource(R.string.toolbar_tools_desc),
                    onClick = onOpenToolbarSettings,
                )
            }

            SettingsGroup(title = stringResource(R.string.side_panel_symbols)) {
                ClickableRow(
                    title = stringResource(R.string.side_panel_symbols),
                    value = stringResource(R.string.side_panel_symbols_desc),
                    onClick = onOpenSidePanelSymbols,
                )
            }

            SettingsGroup(title = stringResource(R.string.keyboard_layout)) {
                Spacer(modifier = Modifier.height(14.dp))

                SliderRow(
                    title = stringResource(R.string.keyboard_height),
                    value = keyboardHeight,
                    valueLabel = "${keyboardHeight.toInt()}%",
                    range = 20f..50f,
                    onValueChange = {
                        keyboardHeight = it
                        KeyboardManager.Keyboard.setHeightPercent(context, it.toInt())
                    },
                )
                SliderRow(
                    title = stringResource(R.string.keyboard_height_landscape),
                    value = keyboardHeightLandscape,
                    valueLabel = "${keyboardHeightLandscape.toInt()}%",
                    range = 30f..70f,
                    onValueChange = {
                        keyboardHeightLandscape = it
                        KeyboardManager.Keyboard.setHeightPercentLandscape(context, it.toInt())
                    },
                )
                SwitchRow(
                    title = stringResource(R.string.landscape_floating_keyboard),
                    checked = landscapeFloating,
                    onCheckedChange = {
                        landscapeFloating = it
                        KeyboardManager.Keyboard.Floating.setEnabled(context, it)
                    },
                )
                if (landscapeFloating) {
                    Text(
                        text = stringResource(R.string.landscape_floating_keyboard_desc),
                        fontSize = rowSubFontSize,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                    SliderRow(
                        title = stringResource(R.string.floating_keyboard_width),
                        value = floatingWidth,
                        valueLabel = "${floatingWidth.toInt()}%",
                        range = KeyboardManager.Keyboard.Floating.WIDTH_PERCENT_MIN.toFloat()..
                            KeyboardManager.Keyboard.Floating.WIDTH_PERCENT_MAX.toFloat(),
                        onValueChange = {
                            floatingWidth = it
                            floatingWidthUserSet = true
                            KeyboardManager.Keyboard.Floating.setWidthPercent(context, it.toInt())
                        },
                    )
                    Text(
                        text = stringResource(R.string.floating_keyboard_width_hint),
                        fontSize = rowSubFontSize,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                    if (floatingWidthUserSet) {
                        ClickableRow(
                            title = stringResource(R.string.floating_keyboard_reset_width),
                            value = stringResource(R.string.floating_keyboard_width_hint_short),
                            onClick = {
                                KeyboardManager.Keyboard.Floating.resetWidth(context)
                                floatingWidth =
                                    KeyboardManager.Keyboard.Floating.getWidthPercent(context).toFloat()
                                floatingWidthUserSet = false
                            },
                        )
                    }
                    ClickableRow(
                        title = stringResource(R.string.floating_keyboard_reset_position),
                        value = stringResource(R.string.floating_keyboard_reset_position_desc),
                        onClick = { KeyboardManager.Keyboard.Floating.resetPosition(context) },
                    )
                }
                SliderRow(
                    title = stringResource(R.string.horizontal_padding),
                    value = horizontalPadding,
                    valueLabel = "${horizontalPadding.toInt()} dp",
                    range = 0f..20f,
                    onValueChange = {
                        horizontalPadding = it
                        KeyboardManager.Keyboard.Padding.setHorizontalDp(context, it.toInt())
                    },
                )
                SliderRow(
                    title = stringResource(R.string.bottom_padding),
                    value = bottomPadding,
                    valueLabel = "${bottomPadding.toInt()} dp",
                    range = 0f..40f,
                    onValueChange = {
                        bottomPadding = it
                        KeyboardManager.Keyboard.Padding.setBottomDp(context, it.toInt())
                    },
                )
                SwitchRow(
                    title = stringResource(R.string.ignore_system_insets),
                    checked = ignoreInsets,
                    onCheckedChange = {
                        ignoreInsets = it
                        KeyboardManager.Keyboard.setIgnoreInsets(context, it)
                    },
                    showDivider = false,
                )
            }
            Spacer(Modifier.height(14.dp))
        }
    }

    if (showGestureDialog) {
        SingleChoiceDialog(
            title = stringResource(R.string.gesture_alt_input),
            options = listOf(
                stringResource(R.string.gesture_long_press),
                stringResource(R.string.gesture_swipe_up),
            ),
            selectedIndex = if (altInputMode == KeyboardManager.Keyboard.GestureInput.MODE_SWIPE_UP) 1 else 0,
            onSelect = { index ->
                val mode = if (index == 1) {
                    KeyboardManager.Keyboard.GestureInput.MODE_SWIPE_UP
                } else {
                    KeyboardManager.Keyboard.GestureInput.MODE_LONG_PRESS
                }
                KeyboardManager.Keyboard.GestureInput.setMode(context, mode)
                altInputMode = mode
                showGestureDialog = false
            },
            onDismiss = { showGestureDialog = false },
        )
    }

    if (showVibrationEffectDialog) {
        SingleChoiceDialog(
            title = stringResource(R.string.vibration_effect),
            options = listOf(
                stringResource(R.string.vibration_effect_system),
                stringResource(R.string.vibration_effect_custom),
            ),
            selectedIndex = if (vibrationEffect ==
                KeyboardManager.Keyboard.Feedback.VIBRATION_EFFECT_SYSTEM
            ) {
                0
            } else {
                1
            },
            onSelect = { index ->
                val effect = if (index == 0) {
                    KeyboardManager.Keyboard.Feedback.VIBRATION_EFFECT_SYSTEM
                } else {
                    KeyboardManager.Keyboard.Feedback.VIBRATION_EFFECT_CUSTOM
                }
                vibrationEffect = effect
                KeyboardManager.Keyboard.Feedback.setVibrationEffect(context, effect)
                // 不关闭弹窗，方便两种效果来回对比。
                InputFeedbacks.previewVibration(view, keyVibrationLevel)
            },
            onDismiss = { showVibrationEffectDialog = false },
        )
    }

    if (showVibrationLevelDialog) {
        val maxLevel = KeyboardManager.Keyboard.Feedback.VIBRATION_LEVEL_MAX
        SingleChoiceDialog(
            title = stringResource(R.string.vibration_strength),
            options = (1..maxLevel).map { vibrationLevelLabel(it) },
            selectedIndex = (keyVibrationLevel - 1).coerceIn(0, maxLevel - 1),
            onSelect = { index ->
                val level = index + 1
                keyVibrationLevel = level
                KeyboardManager.Keyboard.Feedback.setVibrationLevel(context, level)
                // 立即试振，方便逐级对比力度。
                InputFeedbacks.previewVibration(view, level)
            },
            onDismiss = { showVibrationLevelDialog = false },
        )
    }
}

/** 振动效果来源的显示文案。 */
@Composable
private fun vibrationEffectLabel(effect: Int): String {
    return stringResource(
        if (effect == KeyboardManager.Keyboard.Feedback.VIBRATION_EFFECT_SYSTEM) {
            R.string.vibration_effect_system
        } else {
            R.string.vibration_effect_custom
        }
    )
}

/** 等级 1..10 对应的文案资源，顺序与 [KeyboardManager.Keyboard.Feedback.VIBRATION_LEVEL_MAX] 对齐。 */
private val VIBRATION_LEVEL_LABEL_RES = intArrayOf(
    R.string.vibration_level_1,
    R.string.vibration_level_2,
    R.string.vibration_level_3,
    R.string.vibration_level_4,
    R.string.vibration_level_5,
    R.string.vibration_level_6,
    R.string.vibration_level_7,
    R.string.vibration_level_8,
    R.string.vibration_level_9,
    R.string.vibration_level_10,
)

@Composable
private fun vibrationLevelLabel(level: Int): String {
    val index = (level - 1).coerceIn(0, VIBRATION_LEVEL_LABEL_RES.lastIndex)
    return stringResource(VIBRATION_LEVEL_LABEL_RES[index])
}
