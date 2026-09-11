package com.ninthsoft.ime.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ninthsoft.ime.R
import com.ninthsoft.ime.data.manager.KeyboardManager
import com.ninthsoft.ime.ui.screen.ScreenComponent.SettingsGroup
import com.ninthsoft.ime.ui.screen.ScreenComponent.barFontSize
import com.ninthsoft.ime.ui.screen.ScreenComponent.rowSubFontSize

/** 可编辑快捷符号栏的两块键盘。 */
private enum class SidePanelTarget { T9, Number }

/**
 * 备选符号池：点一下追加到侧栏末尾。
 * 只是常用项的快捷入口，任何符号都可以通过「自定义符号…」加入。
 */
private val SYMBOL_POOL = listOf(
    "，", "。", "、", "；", "：", "？", "！", "…", "——", "·",
    ",", ".", "?", "!", ":", ";", "'", "\"", "~", "_",
    "+", "-", "*", "/", "=", "%", "&", "^", "#", "@",
    "$", "￥", "€", "£", "¥", "°", "±", "×", "÷", "√",
    "(", ")", "[", "]", "{", "}", "<", ">", "|", "\\",
    "（", "）", "《", "》", "「", "」", "【", "】", "“", "”",
    "≤", "≥", "≠", "≈", "∞", "※", "•", "←", "→", "↑", "↓",
    "★", "☆", "✓", "✗",
).distinct()

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SidePanelSymbolsScreen(onBack: () -> Unit) {
    val context = LocalContext.current

    var t9Symbols by remember {
        mutableStateOf(KeyboardManager.Keyboard.SidePanelSymbols.getT9(context))
    }
    var numberSymbols by remember {
        mutableStateOf(KeyboardManager.Keyboard.SidePanelSymbols.getNumber(context))
    }
    var customTarget by remember { mutableStateOf<SidePanelTarget?>(null) }

    fun persistT9(symbols: List<String>) {
        t9Symbols = symbols
        KeyboardManager.Keyboard.SidePanelSymbols.setT9(context, symbols)
    }

    fun persistNumber(symbols: List<String>) {
        numberSymbols = symbols
        KeyboardManager.Keyboard.SidePanelSymbols.setNumber(context, symbols)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.side_panel_symbols),
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

            SettingsGroup(title = stringResource(R.string.side_panel_symbols_t9)) {
                SymbolListEditor(
                    symbols = t9Symbols,
                    defaultSymbols = KeyboardManager.Keyboard.SidePanelSymbols.DEFAULT_T9,
                    onChange = { persistT9(it) },
                    onAddCustom = { customTarget = SidePanelTarget.T9 },
                )
            }

            SettingsGroup(title = stringResource(R.string.side_panel_symbols_number)) {
                SymbolListEditor(
                    symbols = numberSymbols,
                    defaultSymbols = KeyboardManager.Keyboard.SidePanelSymbols.DEFAULT_NUMBER,
                    onChange = { persistNumber(it) },
                    onAddCustom = { customTarget = SidePanelTarget.Number },
                )
            }

            Spacer(Modifier.height(14.dp))
        }
    }

    customTarget?.let { target ->
        AddSymbolDialog(
            onAdd = { raw ->
                val symbol = raw.trim()
                if (symbol.isNotEmpty()) {
                    when (target) {
                        SidePanelTarget.T9 ->
                            if (symbol !in t9Symbols) persistT9(t9Symbols + symbol)

                        SidePanelTarget.Number ->
                            if (symbol !in numberSymbols) persistNumber(numberSymbols + symbol)
                    }
                }
            },
            onDismiss = { customTarget = null },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SymbolListEditor(
    symbols: List<String>,
    defaultSymbols: List<String>,
    onChange: (List<String>) -> Unit,
    onAddCustom: () -> Unit,
) {
    val available = SYMBOL_POOL.filter { it !in symbols }

    Text(
        text = stringResource(R.string.side_panel_symbols_hint),
        fontSize = rowSubFontSize,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(vertical = 4.dp),
    )

    if (symbols.isEmpty()) {
        Text(
            text = stringResource(R.string.side_panel_symbols_empty),
            fontSize = rowSubFontSize,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(vertical = 8.dp),
        )
    } else {
        symbols.forEachIndexed { index, symbol ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = symbol,
                    fontSize = 18.sp,
                    modifier = Modifier.weight(1f),
                )
                IconButton(
                    onClick = {
                        if (index > 0) {
                            onChange(
                                symbols.toMutableList().apply { add(index - 1, removeAt(index)) }
                            )
                        }
                    },
                    enabled = index > 0,
                ) {
                    Icon(
                        Icons.Filled.KeyboardArrowUp,
                        contentDescription = stringResource(R.string.toolbar_tools_move_up),
                        modifier = Modifier.size(18.dp),
                    )
                }
                IconButton(
                    onClick = {
                        if (index < symbols.lastIndex) {
                            onChange(
                                symbols.toMutableList().apply { add(index + 1, removeAt(index)) }
                            )
                        }
                    },
                    enabled = index < symbols.lastIndex,
                ) {
                    Icon(
                        Icons.Filled.KeyboardArrowDown,
                        contentDescription = stringResource(R.string.toolbar_tools_move_down),
                        modifier = Modifier.size(18.dp),
                    )
                }
                IconButton(
                    onClick = { onChange(symbols.filterIndexed { i, _ -> i != index }) },
                    // 至少保留一个符号，避免侧栏变成一块没有内容的空白区域。
                    enabled = symbols.size > 1,
                ) {
                    Icon(
                        Icons.Filled.Close,
                        contentDescription = stringResource(R.string.theme_editor_delete),
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.error,
                    )
                }
            }
            if (index != symbols.lastIndex) {
                HorizontalDivider(
                    thickness = 0.5.dp,
                    color = MaterialTheme.colorScheme.outlineVariant,
                )
            }
        }
    }

    Spacer(Modifier.height(10.dp))

    Text(
        text = stringResource(R.string.side_panel_symbols_available),
        fontSize = rowSubFontSize,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(bottom = 6.dp),
    )

    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        available.forEach { symbol ->
            SymbolChip(label = symbol) { onChange(symbols + symbol) }
        }
        SymbolChip(
            label = stringResource(R.string.side_panel_symbols_custom),
            primary = true,
            onClick = onAddCustom,
        )
    }

    Spacer(Modifier.height(4.dp))

    TextButton(
        onClick = { onChange(defaultSymbols) },
        enabled = symbols != defaultSymbols,
    ) {
        Text(
            text = stringResource(R.string.side_panel_symbols_reset),
            fontSize = rowSubFontSize,
        )
    }
}

@Composable
private fun SymbolChip(
    label: String,
    primary: Boolean = false,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(
                if (primary) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    MaterialTheme.colorScheme.surfaceVariant
                }
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
    ) {
        Text(
            text = label,
            fontSize = 15.sp,
            color = if (primary) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
    }
}

@Composable
private fun AddSymbolDialog(
    onAdd: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.side_panel_symbols_custom_title)) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                label = { Text(stringResource(R.string.side_panel_symbols_custom_hint)) },
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onAdd(text)
                    onDismiss()
                },
                enabled = text.isNotBlank(),
            ) {
                Text(stringResource(R.string.add))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}
