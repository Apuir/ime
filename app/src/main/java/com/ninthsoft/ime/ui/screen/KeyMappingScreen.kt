package com.ninthsoft.ime.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ninthsoft.ime.R
import com.ninthsoft.ime.data.manager.KeyboardKeyMapping
import com.ninthsoft.ime.ui.screen.ScreenComponent.SettingsGroup
import com.ninthsoft.ime.ui.screen.ScreenComponent.SwitchRow
import com.ninthsoft.ime.ui.screen.ScreenComponent.barFontSize
import com.ninthsoft.ime.ui.screen.ScreenComponent.rowSubFontSize

/**
 * 按键映射设置页：
 * - 顶部开关控制「长按 / 上滑是否弹气泡」；
 * - 26 键：点任意字母键改它下面的符号 / 数字，改动会立刻显示在键帽右下角和气泡里；
 * - 九键：点任意数字键改它包含的字母（也用于 15 键气泡里的字母）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KeyMappingScreen(onBack: () -> Unit) {
    val context = LocalContext.current

    var bubbleEnabled by remember {
        mutableStateOf(KeyboardKeyMapping.isBubbleEnabled(context))
    }
    var qwerty by remember {
        mutableStateOf(KeyboardKeyMapping.qwertySymbols(context))
    }
    var t9 by remember {
        mutableStateOf(KeyboardKeyMapping.t9Letters(context))
    }
    var editingLetter by remember { mutableStateOf<String?>(null) }
    var editingDigit by remember { mutableStateOf<String?>(null) }

    fun setSymbol(letter: String, symbol: String) {
        KeyboardKeyMapping.setQwertySymbol(context, letter, symbol)
        qwerty = KeyboardKeyMapping.qwertySymbols(context)
    }

    fun setLetters(digit: String, letters: String) {
        KeyboardKeyMapping.setT9Letters(context, digit, letters)
        t9 = KeyboardKeyMapping.t9Letters(context)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.key_mapping),
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

            SettingsGroup(title = stringResource(R.string.key_bubble)) {
                SwitchRow(
                    title = stringResource(R.string.key_bubble_enabled),
                    checked = bubbleEnabled,
                    onCheckedChange = {
                        bubbleEnabled = it
                        KeyboardKeyMapping.setBubbleEnabled(context, it)
                    },
                )
                Text(
                    text = stringResource(R.string.key_bubble_desc),
                    fontSize = rowSubFontSize,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 4.dp, end = 4.dp, bottom = 8.dp),
                )
            }

            SettingsGroup(title = stringResource(R.string.key_mapping_qwerty)) {
                Text(
                    text = stringResource(R.string.key_mapping_qwerty_hint),
                    fontSize = rowSubFontSize,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 4.dp, end = 4.dp, bottom = 8.dp),
                )
                QwertyPreview(
                    symbols = qwerty,
                    onPick = { editingLetter = it },
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(
                        onClick = {
                            KeyboardKeyMapping.resetQwerty(context)
                            qwerty = KeyboardKeyMapping.qwertySymbols(context)
                        },
                        enabled = qwerty != KeyboardKeyMapping.DEFAULT_QWERTY_SYMBOLS,
                    ) {
                        Text(
                            text = stringResource(R.string.key_mapping_reset),
                            fontSize = rowSubFontSize,
                        )
                    }
                }
            }

            SettingsGroup(title = stringResource(R.string.key_mapping_t9)) {
                Text(
                    text = stringResource(R.string.key_mapping_t9_hint),
                    fontSize = rowSubFontSize,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 4.dp, end = 4.dp, bottom = 8.dp),
                )
                T9List(
                    letters = t9,
                    onPick = { editingDigit = it },
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(
                        onClick = {
                            KeyboardKeyMapping.resetT9(context)
                            t9 = KeyboardKeyMapping.t9Letters(context)
                        },
                        enabled = t9 != KeyboardKeyMapping.DEFAULT_T9_LETTERS,
                    ) {
                        Text(
                            text = stringResource(R.string.key_mapping_reset),
                            fontSize = rowSubFontSize,
                        )
                    }
                }
            }

            Spacer(Modifier.height(14.dp))
        }
    }

    editingLetter?.let { letter ->
        val symbol = qwerty[letter].orEmpty()
        TextInputDialog(
            title = stringResource(R.string.key_mapping_symbol_title, letter),
            initial = symbol,
            label = stringResource(R.string.key_mapping_symbol_hint),
            chipPool = KeyboardKeyMapping.SYMBOL_POOL,
            onConfirm = { value ->
                if (value.isNotEmpty()) setSymbol(letter, value)
                editingLetter = null
            },
            onReset = {
                setSymbol(letter, KeyboardKeyMapping.DEFAULT_QWERTY_SYMBOLS[letter].orEmpty())
                editingLetter = null
            },
            onDismiss = { editingLetter = null },
        )
    }

    editingDigit?.let { digit ->
        val letters = t9[digit].orEmpty()
        TextInputDialog(
            title = stringResource(R.string.key_mapping_letters_title, digit),
            initial = letters,
            label = stringResource(R.string.key_mapping_letters_hint),
            chipPool = ('a'..'z').map { it.toString() },
            chipAppends = true,
            onConfirm = { value ->
                setLetters(digit, value)
                editingDigit = null
            },
            onReset = {
                KeyboardKeyMapping.setT9Letters(context, digit, KeyboardKeyMapping.DEFAULT_T9_LETTERS[digit].orEmpty())
                t9 = KeyboardKeyMapping.t9Letters(context)
                editingDigit = null
            },
            onDismiss = { editingDigit = null },
        )
    }
}

private val QWERTY_ROWS = listOf(
    "qwertyuiop",
    "asdfghjkl",
    "zxcvbnm",
)

/**
 * 26 键的迷你预览：每个键画成一个小方块，主文字是字母、下方小字是它对应的符号 / 数字，
 * 点一下就能改。三行按真实键盘的缩进排布，改映射时空间位置一目了然。
 */
@Composable
private fun QwertyPreview(
    symbols: Map<String, String>,
    onPick: (String) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        QWERTY_ROWS.forEachIndexed { index, row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
            ) {
                // 第二行缩进半格、第三行缩进一格，模拟真实键盘的错位排列。
                if (index > 0) Spacer(Modifier.size((index * 10).dp))
                row.forEach { ch ->
                    val letter = ch.toString()
                    LetterCell(
                        letter = letter,
                        symbol = symbols[letter].orEmpty(),
                        onClick = { onPick(letter) },
                        modifier = Modifier.weight(1f),
                    )
                }
                if (index > 0) Spacer(Modifier.size((index * 10).dp))
            }
        }
    }
}

@Composable
private fun LetterCell(
    letter: String,
    symbol: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .padding(horizontal = 2.dp)
            .height(52.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outlineVariant,
                shape = RoundedCornerShape(8.dp),
            )
            .clickable(onClick = onClick),
    ) {
        Column(
            modifier = Modifier.align(Alignment.Center),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = letter,
                fontSize = 17.sp,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = symbol,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

/** 九键：每个数字一行，左边是数字，右边是它当前包含的字母。 */
@Composable
private fun T9List(
    letters: Map<String, String>,
    onPick: (String) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        KeyboardKeyMapping.T9_DIGITS.forEach { digit ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onPick(digit) }
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = digit,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(28.dp),
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.size(12.dp))
                Text(
                    text = letters[digit].orEmpty(),
                    fontSize = 16.sp,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TextInputDialog(
    title: String,
    initial: String,
    label: String,
    chipPool: List<String> = emptyList(),
    chipAppends: Boolean = false,
    onConfirm: (String) -> Unit,
    onReset: () -> Unit,
    onDismiss: () -> Unit,
) {
    var text by remember { mutableStateOf(initial) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
            ) {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    singleLine = true,
                    label = { Text(label) },
                    modifier = Modifier.fillMaxWidth(),
                )
                if (chipPool.isNotEmpty()) {
                    // 常用项点一下就填进去，省去在输入法里打这些符号的麻烦。
                    Text(
                        text = stringResource(R.string.key_mapping_quick_pick),
                        fontSize = rowSubFontSize,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 12.dp, bottom = 2.dp),
                    )
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        chipPool.forEach { symbol ->
                            SymbolChip(label = symbol) {
                                text = if (chipAppends) text + symbol else symbol
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(text.trim()) },
                enabled = text.isNotBlank(),
            ) {
                Text(stringResource(R.string.done))
            }
        },
        dismissButton = {
            Row {
                TextButton(onClick = onReset) {
                    Text(stringResource(R.string.key_mapping_restore_default))
                }
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.cancel))
                }
            }
        },
    )
}

@Composable
private fun SymbolChip(
    label: String,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        Text(
            text = label,
            fontSize = 14.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
