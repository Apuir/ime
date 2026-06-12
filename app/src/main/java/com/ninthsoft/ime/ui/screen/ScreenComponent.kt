package com.ninthsoft.ime.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ninthsoft.ime.R
import com.ninthsoft.ime.data.keyboard.theme.KeyboardTheme

object ScreenComponent {
    val groupFontSize = 14.sp
    val rowFontSize = 14.sp
    val spacerHeight = 6.dp
    const val SWITCH_SCALE = 0.7f

    @Composable
    fun SettingsGroup(
        title: String,
        content: @Composable () -> Unit,
    ) {
        Text(text = title, fontSize = groupFontSize, color =  MaterialTheme.colorScheme.primary)
        content()
    }

    @Composable
    fun SwitchRow(
        title: String,
        checked: Boolean,
        onCheckedChange: (Boolean) -> Unit,
        showDivider: Boolean = false,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f),
                fontSize = rowFontSize
            )
            Switch(
                checked = checked,
                onCheckedChange = onCheckedChange,
                modifier = Modifier.scale(SWITCH_SCALE),
                colors = SwitchDefaults.colors(
                    checkedThumbColor = MaterialTheme.colorScheme.onSecondaryContainer,
                    checkedTrackColor = MaterialTheme.colorScheme.secondary,
                    uncheckedThumbColor = MaterialTheme.colorScheme.outline,
                    uncheckedTrackColor = MaterialTheme.colorScheme.surfaceVariant,
                )
            )
        }
        if (showDivider) {
            HorizontalDivider(
                modifier = Modifier.padding(start = 4.dp, end = 4.dp),
                thickness = 0.5.dp,
                color = MaterialTheme.colorScheme.outlineVariant
            )
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    fun SliderRow(
        title: String,
        value: Float,
        valueLabel: String,
        range: ClosedFloatingPointRange<Float>,
        onValueChange: (Float) -> Unit,
        showDivider: Boolean = false,
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.weight(1f),
                    fontSize = rowFontSize
                )
                Text(
                    text = valueLabel,
                    style = MaterialTheme.typography.bodyMedium,
                    fontSize = rowFontSize
                )
            }
            Spacer(modifier = Modifier.fillMaxWidth().height(spacerHeight))
            Slider(value = value, onValueChange = onValueChange, valueRange = range, thumb = {
                Surface(
                    modifier = Modifier.size(width = 2.dp, height = 14.dp),
                    shape = RoundedCornerShape(1.dp),
                    color = MaterialTheme.colorScheme.secondary
                ) {}
            }, track = { sliderState ->
                SliderDefaults.Track(
                    sliderState = sliderState,
                    modifier = Modifier.height(4.dp),
                    colors = SliderDefaults.colors(
                        activeTrackColor = MaterialTheme.colorScheme.secondary,
                        inactiveTrackColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                )
            })
        }
        if (showDivider) {
            HorizontalDivider(
                modifier = Modifier.padding(start = 4.dp, end = 4.dp),
                thickness = 0.5.dp,
                color = MaterialTheme.colorScheme.outlineVariant
            )
        }
    }

    @Composable
    fun SectionHeader(title: String) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.padding(start = 4.dp, top = 8.dp, bottom = 4.dp),
            fontSize = groupFontSize
        )
    }

    @Composable
    fun ClickableSettingItem(
        title: String, subtitle: String, onClick: () -> Unit, showSpacer: Boolean = false,
    ) {
        Card(
            onClick = onClick,
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.bodyLarge, fontSize = groupFontSize)
                    if (showSpacer) {
                        Spacer(modifier = Modifier.height(4.dp))
                    }
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = rowFontSize
                    )
                }
                Icon(
                    Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }

    @Composable
    fun SingleChoiceDialog(
        title: String,
        options: List<String>,
        selectedIndex: Int,
        onSelect: (Int) -> Unit,
        onDismiss: () -> Unit
    ) {
        AlertDialog(onDismissRequest = onDismiss, title = { Text(title) }, text = {
            Column {
                options.forEachIndexed { index, option ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().clickable { onSelect(index) }.padding(vertical = 8.dp)
                    ) {
                        RadioButton(selected = index == selectedIndex, onClick = { onSelect(index) })
                        Spacer(Modifier.width(8.dp))
                        Text(option)
                    }
                }
            }
        }, confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        })
    }

    @Composable
    fun ThemeChip(
        theme: KeyboardTheme,
        selected: Boolean,
        onClick: () -> Unit,
    ) {
        val isDark = MaterialTheme.colorScheme.background.luminance() < 0.5f
        val colors = if (isDark) theme.dark else theme.light

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .clip(MaterialTheme.shapes.small)
                .clickable(onClick = onClick)
                .padding(4.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .border(
                        width = if (selected) 2.dp else 1.dp,
                        color = if (selected) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.outlineVariant,
                        shape = CircleShape,
                    )
                    .padding(if (selected) 2.dp else 1.dp)
            ) {
                Column {
                    Box(
                        modifier = Modifier.weight(1f).fillMaxSize()
                            .background(Color(colors.keyBackground)),
                    )
                    Box(
                        modifier = Modifier.weight(1f).fillMaxSize()
                            .background(Color(colors.accentKeyBackground)),
                    )
                }
            }
            Spacer(Modifier.height(4.dp))
            Text(
                text = theme.name,
                style = MaterialTheme.typography.labelSmall,
                fontSize = rowFontSize,
                textAlign = TextAlign.Center,
                color = if (selected) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
