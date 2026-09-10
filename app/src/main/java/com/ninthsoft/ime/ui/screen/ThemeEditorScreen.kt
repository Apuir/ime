package com.ninthsoft.ime.ui.screen

import android.content.Context
import android.view.View
import android.widget.Toast
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.ninthsoft.ime.R
import com.ninthsoft.ime.base.feedback.InputFeedbacks
import com.ninthsoft.ime.data.ThemeStore
import com.ninthsoft.ime.data.keyboard.theme.KeyboardColors
import com.ninthsoft.ime.data.keyboard.theme.KeyboardTheme
import com.ninthsoft.ime.data.manager.KeyboardManager
import com.ninthsoft.ime.input.keyboard.impl.QwertyKeyboard
import com.ninthsoft.ime.input.keyboard.impl.T15Keyboard
import com.ninthsoft.ime.input.keyboard.impl.T9Keyboard
import com.ninthsoft.ime.input.keyboard.window.KeyboardStateManager
import com.ninthsoft.ime.ui.screen.ScreenComponent.SettingsGroup
import com.ninthsoft.ime.ui.screen.ScreenComponent.SliderRow
import com.ninthsoft.ime.ui.screen.ScreenComponent.barFontSize
import com.ninthsoft.ime.ui.screen.ScreenComponent.rowSubFontSize
import kotlinx.coroutines.delay

private class ColorField(
    @StringRes val labelRes: Int,
    val get: (KeyboardColors.ColorScheme) -> Int,
    val set: (KeyboardColors.ColorScheme, Int) -> KeyboardColors.ColorScheme,
)

private val mainColorFields = listOf(
    ColorField(R.string.color_keyBackground, { it.keyBackground }, { c, v -> c.copy(keyBackground = v) }),
    ColorField(R.string.color_keyPressed, { it.keyPressed }, { c, v -> c.copy(keyPressed = v) }),
    ColorField(R.string.color_keyBorderStroke, { it.keyBorderStroke }, { c, v -> c.copy(keyBorderStroke = v) }),
    ColorField(R.string.color_keyText, { it.keyText }, { c, v -> c.copy(keyText = v) }),
)

private val specialColorFields = listOf(
    ColorField(R.string.color_specialKeyBackground, { it.specialKeyBackground }, { c, v -> c.copy(specialKeyBackground = v) }),
    ColorField(R.string.color_specialKeyPressed, { it.specialKeyPressed }, { c, v -> c.copy(specialKeyPressed = v) }),
    ColorField(R.string.color_specialKeyBorderStroke, { it.specialKeyBorderStroke }, { c, v -> c.copy(specialKeyBorderStroke = v) }),
    ColorField(R.string.color_specialKeyText, { it.specialKeyText }, { c, v -> c.copy(specialKeyText = v) }),
)

private val accentColorFields = listOf(
    ColorField(R.string.color_accentKeyBackground, { it.accentKeyBackground }, { c, v -> c.copy(accentKeyBackground = v) }),
    ColorField(R.string.color_accentKeyPressed, { it.accentKeyPressed }, { c, v -> c.copy(accentKeyPressed = v) }),
    ColorField(R.string.color_accentKeyBorderStroke, { it.accentKeyBorderStroke }, { c, v -> c.copy(accentKeyBorderStroke = v) }),
    ColorField(R.string.color_accentKeyText, { it.accentKeyText }, { c, v -> c.copy(accentKeyText = v) }),
)

private val otherColorFields = listOf(
    ColorField(R.string.color_altText, { it.altText }, { c, v -> c.copy(altText = v) }),
    ColorField(R.string.color_background, { it.background }, { c, v -> c.copy(background = v) }),
    ColorField(R.string.color_toastBackground, { it.toastBackground }, { c, v -> c.copy(toastBackground = v) }),
    ColorField(R.string.color_toastText, { it.toastText }, { c, v -> c.copy(toastText = v) }),
)

private val panelColorFields = listOf(
    ColorField(R.string.color_panelBackground, { it.panel.background }, { c, v -> c.copy(panel = c.panel.copy(background = v)) }),
    ColorField(R.string.color_toolbarText, { it.panel.toolbarText }, { c, v -> c.copy(panel = c.panel.copy(toolbarText = v)) }),
    ColorField(R.string.color_toolbarActived, { it.panel.toolbarActived }, { c, v -> c.copy(panel = c.panel.copy(toolbarActived = v)) }),
    ColorField(R.string.color_toolbarIcon, { it.panel.toolbarIcon }, { c, v -> c.copy(panel = c.panel.copy(toolbarIcon = v)) }),
    ColorField(R.string.color_candidateBackground, { it.panel.candidateBackground }, { c, v -> c.copy(panel = c.panel.copy(candidateBackground = v)) }),
    ColorField(R.string.color_candidateText, { it.panel.candidateText }, { c, v -> c.copy(panel = c.panel.copy(candidateText = v)) }),
    ColorField(R.string.color_candidateIndex, { it.panel.candidateIndex }, { c, v -> c.copy(panel = c.panel.copy(candidateIndex = v)) }),
    ColorField(R.string.color_candidateDivider, { it.panel.candidateDivider }, { c, v -> c.copy(panel = c.panel.copy(candidateDivider = v)) }),
    ColorField(R.string.color_toolbarPressed, { it.panel.toolbarPressed }, { c, v -> c.copy(panel = c.panel.copy(toolbarPressed = v)) }),
)

private val pinnerColorFields = listOf(
    ColorField(R.string.color_pinnerBackground, { it.pinner.background }, { c, v -> c.copy(pinner = c.pinner.copy(background = v)) }),
    ColorField(R.string.color_pinnerText, { it.pinner.textColor }, { c, v -> c.copy(pinner = c.pinner.copy(textColor = v)) }),
    ColorField(R.string.color_pinnerSecondaryText, { it.pinner.secondaryTextColor }, { c, v -> c.copy(pinner = c.pinner.copy(secondaryTextColor = v)) }),
)

/**
 * GUI 主题编辑器：调整配色、按键形状/边框/尺寸后命名保存并使用。
 *
 * @param themeId 为空表示新建；否则编辑已有自定义主题。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ThemeEditorScreen(
    themeId: String?,
    onBack: () -> Unit,
) {
    val context = LocalContext.current

    // 预览嵌入的是真实键盘，按键会走 InputFeedbacks；编辑器期间关掉震动/按键音。
    DisposableEffect(Unit) {
        InputFeedbacks.suppressFeedback = true
        onDispose { InputFeedbacks.suppressFeedback = false }
    }

    fun baseTheme(): KeyboardTheme {
        themeId?.let { id ->
            ThemeStore.find(id)?.let { return it }
        }
        return KeyboardColors.themeFor(context)
    }

    val existing = themeId?.let { ThemeStore.find(it) }
    var draftName by remember {
        mutableStateOf(existing?.name ?: context.getString(R.string.theme_editor_new))
    }
    var scheme by remember {
        mutableStateOf(
            baseTheme().colors.let { colors ->
                // 已有主题保留其内嵌几何参数；新建主题以当前全局几何为默认值。
                if (colors.geometry != null) colors
                else colors.copy(geometry = KeyboardColors.currentGeometry(context))
            }
        )
    }
    val draftId = remember { themeId ?: ThemeStore.newThemeId() }

    var editingColor by remember { mutableStateOf<ColorField?>(null) }
    var previewLayout by remember {
        mutableStateOf(PreviewLayout.fromName(KeyboardStateManager.getCurrentSchema()?.layout))
    }

    // 预览是真实键盘 View，重建成本较高：拖动滑杆时防抖，停手后再重建。
    var renderedScheme by remember { mutableStateOf(scheme) }
    LaunchedEffect(scheme) {
        delay(60)
        renderedScheme = scheme
    }

    fun resetFromCurrentTheme() {
        val current = KeyboardColors.themeFor(context)
        scheme = current.colors.copy(geometry = KeyboardColors.currentGeometry(context))
        Toast.makeText(context, R.string.theme_editor_reset_from_current, Toast.LENGTH_SHORT).show()
    }

    fun save() {
        val name = draftName.trim()
        if (name.isEmpty()) {
            Toast.makeText(context, R.string.theme_editor_name_required, Toast.LENGTH_SHORT).show()
            return
        }
        val geometry = KeyboardColors.KeyGeometry(
            cornerRadius = scheme.cornerRadius,
            keyHMargin = scheme.keyHMargin,
            keyVMargin = scheme.keyVMargin,
            keyboardHeightPercent = scheme.geometry?.keyboardHeightPercent
                ?: KeyboardManager.Keyboard.getHeightPercent(context),
            keyboardHeightLandscapePercent = scheme.geometry?.keyboardHeightLandscapePercent
                ?: KeyboardManager.Keyboard.getHeightPercentLandscape(context),
        )
        val finalScheme = scheme.copy(geometry = geometry)
        val theme = KeyboardTheme(id = draftId, name = name, colors = finalScheme)
        ThemeStore.save(theme)
        KeyboardManager.Keyboard.setFollowSystem(context, false)
        KeyboardManager.Keyboard.setThemeId(context, theme.id)
        KeyboardColors.applyGeometry(context, finalScheme)
        Toast.makeText(
            context,
            context.getString(R.string.theme_editor_saved, name),
            Toast.LENGTH_SHORT,
        ).show()
        onBack()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(
                            if (existing == null) R.string.theme_editor_new
                            else R.string.theme_editor_edit
                        ),
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
                actions = {
                    IconButton(onClick = { resetFromCurrentTheme() }) {
                        Icon(
                            Icons.Filled.Refresh,
                            contentDescription = stringResource(R.string.theme_editor_reset_from_current),
                            tint = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                    IconButton(onClick = { save() }) {
                        Icon(
                            Icons.Filled.Check,
                            contentDescription = stringResource(R.string.theme_editor_save),
                            tint = MaterialTheme.colorScheme.primary,
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
                .padding(padding),
        ) {
            // 固定在顶部的实时预览：下方参数滚动时它始终可见。
            ThemePreview(
                scheme = renderedScheme,
                previewLayout = previewLayout,
                onLayoutChange = { previewLayout = it },
            )
            HorizontalDivider(
                thickness = 0.5.dp,
                color = MaterialTheme.colorScheme.outlineVariant,
            )
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
            Spacer(Modifier.height(4.dp))

            OutlinedTextField(
                value = draftName,
                onValueChange = { draftName = it },
                label = { Text(stringResource(R.string.theme_editor_name)) },
                placeholder = { Text(stringResource(R.string.theme_editor_name_hint)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.height(8.dp))

            SettingsGroup(title = stringResource(R.string.theme_editor_section_shape)) {
                Text(
                    text = stringResource(R.string.theme_editor_key_shape),
                    fontSize = rowSubFontSize,
                    modifier = Modifier.padding(top = 4.dp),
                )
                Spacer(Modifier.height(6.dp))
                SegmentedRow(
                    options = listOf(
                        stringResource(R.string.shape_rounded),
                        stringResource(R.string.shape_rectangle),
                        stringResource(R.string.shape_oval),
                    ),
                    selectedIndex = when (scheme.keyShape) {
                        KeyboardColors.KeyShape.Rounded -> 0
                        KeyboardColors.KeyShape.Rectangle -> 1
                        KeyboardColors.KeyShape.Oval -> 2
                    },
                    onSelect = { index ->
                        scheme = scheme.copy(
                            keyShape = when (index) {
                                1 -> KeyboardColors.KeyShape.Rectangle
                                2 -> KeyboardColors.KeyShape.Oval
                                else -> KeyboardColors.KeyShape.Rounded
                            }
                        )
                    },
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.theme_editor_surface_style),
                    fontSize = rowSubFontSize,
                )
                Spacer(Modifier.height(6.dp))
                SegmentedRow(
                    options = listOf(
                        stringResource(R.string.surface_raised),
                        stringResource(R.string.surface_flat),
                    ),
                    selectedIndex = if (scheme.surfaceStyle == KeyboardColors.SurfaceStyle.Flat) 1 else 0,
                    onSelect = { index ->
                        scheme = scheme.copy(
                            surfaceStyle = if (index == 1) {
                                KeyboardColors.SurfaceStyle.Flat
                            } else {
                                KeyboardColors.SurfaceStyle.Raised
                            }
                        )
                    },
                )
                Spacer(Modifier.height(8.dp))
                SliderRow(
                    title = stringResource(R.string.theme_editor_border_width),
                    value = scheme.keyBorderWidth,
                    valueLabel = "${scheme.keyBorderWidth.toInt()} dp",
                    range = 0f..8f,
                    onValueChange = { scheme = scheme.copy(keyBorderWidth = it) },
                )
                SliderRow(
                    title = stringResource(R.string.key_corner_radius),
                    value = scheme.cornerRadius,
                    valueLabel = "${scheme.cornerRadius.toInt()} dp",
                    range = 0f..32f,
                    onValueChange = { scheme = scheme.copy(cornerRadius = it) },
                )
            }

            SettingsGroup(title = stringResource(R.string.theme_editor_section_geometry)) {
                SliderRow(
                    title = stringResource(R.string.key_x_gap),
                    value = scheme.keyHMargin,
                    valueLabel = "${scheme.keyHMargin.toInt()} dp",
                    range = 0f..16f,
                    onValueChange = { scheme = scheme.copy(keyHMargin = it) },
                )
                SliderRow(
                    title = stringResource(R.string.key_y_gap),
                    value = scheme.keyVMargin,
                    valueLabel = "${scheme.keyVMargin.toInt()} dp",
                    range = 0f..16f,
                    onValueChange = { scheme = scheme.copy(keyVMargin = it) },
                )
                SliderRow(
                    title = stringResource(R.string.theme_editor_keyboard_height),
                    value = (scheme.geometry?.keyboardHeightPercent ?: 24).toFloat(),
                    valueLabel = "${scheme.geometry?.keyboardHeightPercent ?: 24}%",
                    range = 20f..50f,
                    onValueChange = { v ->
                        scheme = scheme.copy(
                            geometry = (scheme.geometry ?: KeyboardColors.currentGeometry(context))
                                .copy(keyboardHeightPercent = v.toInt())
                        )
                    },
                )
                SliderRow(
                    title = stringResource(R.string.theme_editor_keyboard_height_landscape),
                    value = (scheme.geometry?.keyboardHeightLandscapePercent ?: 44).toFloat(),
                    valueLabel = "${scheme.geometry?.keyboardHeightLandscapePercent ?: 44}%",
                    range = 30f..70f,
                    onValueChange = { v ->
                        scheme = scheme.copy(
                            geometry = (scheme.geometry ?: KeyboardColors.currentGeometry(context))
                                .copy(keyboardHeightLandscapePercent = v.toInt())
                        )
                    },
                )
            }

            SettingsGroup(title = stringResource(R.string.theme_editor_section_colors)) {
                ColorGroup(
                    titleRes = R.string.theme_editor_group_main,
                    fields = mainColorFields,
                    scheme = scheme,
                    onPick = { editingColor = it },
                )
                ColorGroup(
                    titleRes = R.string.theme_editor_group_special,
                    fields = specialColorFields,
                    scheme = scheme,
                    onPick = { editingColor = it },
                )
                ColorGroup(
                    titleRes = R.string.theme_editor_group_accent,
                    fields = accentColorFields,
                    scheme = scheme,
                    onPick = { editingColor = it },
                )
                ColorGroup(
                    titleRes = R.string.theme_editor_group_other,
                    fields = otherColorFields,
                    scheme = scheme,
                    onPick = { editingColor = it },
                )
                ColorGroup(
                    titleRes = R.string.theme_editor_group_panel,
                    fields = panelColorFields,
                    scheme = scheme,
                    onPick = { editingColor = it },
                )
                ColorGroup(
                    titleRes = R.string.theme_editor_group_pinner,
                    fields = pinnerColorFields,
                    scheme = scheme,
                    onPick = { editingColor = it },
                    showDivider = false,
                )
            }

            Spacer(Modifier.height(8.dp))
            Button(
                onClick = { save() },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.theme_editor_save))
            }
            Spacer(Modifier.height(24.dp))
            }
        }
    }

    editingColor?.let { field ->
        ColorPickerDialog(
            initial = field.get(scheme),
            onConfirm = { newColor ->
                scheme = field.set(scheme, newColor)
                editingColor = null
            },
            onDismiss = { editingColor = null },
        )
    }
}

@Composable
private fun ColorGroup(
    @StringRes titleRes: Int,
    fields: List<ColorField>,
    scheme: KeyboardColors.ColorScheme,
    onPick: (ColorField) -> Unit,
    showDivider: Boolean = true,
) {
    Text(
        text = stringResource(titleRes),
        fontSize = rowSubFontSize,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 10.dp, bottom = 2.dp),
    )
    fields.forEachIndexed { index, field ->
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onPick(field) }
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(field.labelRes),
                fontSize = rowSubFontSize,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = hexString(field.get(scheme)),
                fontSize = rowSubFontSize,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(10.dp))
            Box(
                modifier = Modifier
                    .size(26.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color(field.get(scheme)))
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(6.dp)),
            )
        }
        if (index != fields.lastIndex) {
            HorizontalDivider(
                thickness = 0.5.dp,
                color = MaterialTheme.colorScheme.outlineVariant,
            )
        }
    }
    if (showDivider) {
        HorizontalDivider(
            thickness = 0.5.dp,
            color = MaterialTheme.colorScheme.outlineVariant,
        )
    }
}

@Composable
private fun SegmentedRow(
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(3.dp),
    ) {
        options.forEachIndexed { index, label ->
            val selected = index == selectedIndex
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(8.dp))
                    .background(
                        if (selected) MaterialTheme.colorScheme.primary else Color.Transparent
                    )
                    .clickable { onSelect(index) }
                    .padding(vertical = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = label,
                    fontSize = rowSubFontSize,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (selected) MaterialTheme.colorScheme.onPrimary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun ColorPickerDialog(
    initial: Int,
    onConfirm: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    var color by remember { mutableIntStateOf(initial) }
    var hexText by remember { mutableStateOf(hexString(initial)) }

    fun apply(newColor: Int) {
        color = newColor
        hexText = hexString(newColor)
    }

    val a = (color ushr 24) and 0xFF
    val r = (color ushr 16) and 0xFF
    val g = (color ushr 8) and 0xFF
    val b = color and 0xFF

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.theme_editor_pick_color)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(color))
                        .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(8.dp)),
                )
                OutlinedTextField(
                    value = hexText,
                    onValueChange = { text ->
                        hexText = text
                        parseHexColor(text)?.let { color = it }
                    },
                    label = { Text(stringResource(R.string.theme_editor_hex)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
                    modifier = Modifier.fillMaxWidth(),
                )
                ChannelSlider(
                    label = stringResource(R.string.theme_editor_alpha),
                    value = a,
                    onChange = { apply((color and 0x00FFFFFF) or (it shl 24)) },
                )
                ChannelSlider(
                    label = stringResource(R.string.theme_editor_red),
                    value = r,
                    onChange = { apply((color and 0xFF00FFFF.toInt()) or (it shl 16)) },
                )
                ChannelSlider(
                    label = stringResource(R.string.theme_editor_green),
                    value = g,
                    onChange = { apply((color and 0xFFFF00FF.toInt()) or (it shl 8)) },
                )
                ChannelSlider(
                    label = stringResource(R.string.theme_editor_blue),
                    value = b,
                    onChange = { apply((color and 0xFFFFFF00.toInt()) or it) },
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(color) }) {
                Text(stringResource(R.string.theme_editor_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}

@Composable
private fun ChannelSlider(
    label: String,
    value: Int,
    onChange: (Int) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = label,
            fontSize = rowSubFontSize,
            modifier = Modifier.width(56.dp),
        )
        Slider(
            value = value.toFloat(),
            onValueChange = { onChange(it.toInt().coerceIn(0, 255)) },
            valueRange = 0f..255f,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = value.toString(),
            fontSize = rowSubFontSize,
            modifier = Modifier.width(36.dp),
        )
    }
}

private enum class PreviewLayout(
    @StringRes val labelRes: Int,
) {
    Qwerty(R.string.preview_layout_qwerty),
    T9(R.string.preview_layout_t9),
    T15(R.string.preview_layout_t15);

    companion object {
        fun fromName(name: String?): PreviewLayout = when (name?.lowercase()) {
            "t9" -> T9
            "t15" -> T15
            else -> Qwerty
        }
    }
}

/** 用真实的键盘 View 作预览：布局、文字、配色、形状与真机一致。 */
private fun createPreviewKeyboard(
    context: Context,
    layout: PreviewLayout,
    colors: KeyboardColors.ColorScheme,
): View = when (layout) {
    PreviewLayout.Qwerty -> QwertyKeyboard(context, colors)
    PreviewLayout.T9 -> T9Keyboard(context, colors)
    PreviewLayout.T15 -> T15Keyboard(context, colors)
}

/** 只把影响键盘绘制的字段作为重建依据，改面板 / 拼音条配色时无需重建键盘。 */
private fun keyboardStyleKey(scheme: KeyboardColors.ColorScheme): List<Any> = listOf(
    scheme.background,
    scheme.surfaceStyle,
    scheme.cornerRadius,
    scheme.keyHMargin,
    scheme.keyVMargin,
    scheme.keyBorderWidth,
    scheme.keyShape,
    scheme.keyBackground,
    scheme.keyPressed,
    scheme.keyBorderStroke,
    scheme.keyText,
    scheme.specialKeyBackground,
    scheme.specialKeyPressed,
    scheme.specialKeyBorderStroke,
    scheme.specialKeyText,
    scheme.accentKeyBackground,
    scheme.accentKeyPressed,
    scheme.accentKeyBorderStroke,
    scheme.accentKeyText,
    scheme.altText,
)

/**
 * 固定在编辑器顶部的实时预览：直接嵌入真实的 26 键 / 九键 / 15 键键盘 View。
 * 预览键盘没有挂 KeyActionListener，因此按键有按压效果但不会真正输入。
 */
@Composable
private fun ThemePreview(
    scheme: KeyboardColors.ColorScheme,
    previewLayout: PreviewLayout,
    onLayoutChange: (PreviewLayout) -> Unit,
) {
    val context = LocalContext.current
    val metrics = context.resources.displayMetrics
    val screenHeightDp = metrics.heightPixels / metrics.density
    val heightPercent = scheme.geometry?.keyboardHeightPercent
        ?: KeyboardManager.Keyboard.getHeightPercent(context)
    val keyboardHeight = (screenHeightDp * (heightPercent / 100f)).coerceIn(150f, 260f).dp
    val styleKey = keyboardStyleKey(scheme)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Text(
            text = stringResource(R.string.theme_editor_preview),
            fontSize = rowSubFontSize,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(6.dp))
        SegmentedRow(
            options = PreviewLayout.entries.map { stringResource(it.labelRes) },
            selectedIndex = previewLayout.ordinal,
            onSelect = { onLayoutChange(PreviewLayout.entries[it]) },
        )
        Spacer(Modifier.height(8.dp))
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(Color(scheme.background))
                .padding(6.dp),
        ) {
            // 候选 / 工具栏面板
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(22.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color(scheme.panel.background)),
                contentAlignment = Alignment.CenterStart,
            ) {
                Text(
                    text = "候选 1  候选 2  候选 3",
                    color = Color(scheme.panel.candidateText),
                    fontSize = 10.sp,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
            Spacer(Modifier.height(5.dp))
            key(previewLayout, styleKey) {
                AndroidView(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(keyboardHeight),
                    factory = { ctx -> createPreviewKeyboard(ctx, previewLayout, scheme) },
                )
            }
        }
    }
}

private fun hexString(color: Int): String = "#%08X".format(color)

private fun parseHexColor(raw: String): Int? {
    val clean = raw.trim().removePrefix("#")
    return when (clean.length) {
        8 -> clean.toLongOrNull(16)?.toInt()
        6 -> clean.toLongOrNull(16)?.let { (0xFF000000L or it).toInt() }
        else -> null
    }
}
