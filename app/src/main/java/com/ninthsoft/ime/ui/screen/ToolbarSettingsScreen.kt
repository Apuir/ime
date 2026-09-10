package com.ninthsoft.ime.ui.screen

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ninthsoft.ime.R
import com.ninthsoft.ime.data.manager.KeyboardManager
import com.ninthsoft.ime.input.panel.toolbar.ToolbarTool
import com.ninthsoft.ime.ui.screen.ScreenComponent.SettingsGroup
import com.ninthsoft.ime.ui.screen.ScreenComponent.barFontSize
import com.ninthsoft.ime.ui.screen.ScreenComponent.rowSubFontSize

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ToolbarSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current

    var selectedKeys by remember {
        mutableStateOf(KeyboardManager.Keyboard.ToolbarTools.getKeys(context))
    }

    fun persist(keys: List<String>) {
        selectedKeys = keys
        KeyboardManager.Keyboard.ToolbarTools.setKeys(context, keys)
    }

    val selectedTools = selectedKeys.mapNotNull { ToolbarTool.byKey(it) }
    val availableTools = ToolbarTool.entries.filter { it.key !in selectedKeys }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.toolbar_tools),
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

            SettingsGroup(title = stringResource(R.string.toolbar_tools_selected)) {
                Text(
                    text = stringResource(R.string.toolbar_tools_hint),
                    fontSize = rowSubFontSize,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 4.dp),
                )
                if (selectedTools.isEmpty()) {
                    Text(
                        text = stringResource(R.string.toolbar_tools_empty),
                        fontSize = rowSubFontSize,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 8.dp),
                    )
                } else {
                    selectedTools.forEachIndexed { index, tool ->
                        ToolRow(
                            tool = tool,
                            showDivider = index != selectedTools.lastIndex,
                        ) {
                            IconButton(
                                onClick = {
                                    if (index > 0) {
                                        persist(selectedKeys.toMutableList().apply {
                                            add(index - 1, removeAt(index))
                                        })
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
                                    if (index < selectedKeys.lastIndex) {
                                        persist(selectedKeys.toMutableList().apply {
                                            add(index + 1, removeAt(index))
                                        })
                                    }
                                },
                                enabled = index < selectedKeys.lastIndex,
                            ) {
                                Icon(
                                    Icons.Filled.KeyboardArrowDown,
                                    contentDescription = stringResource(R.string.toolbar_tools_move_down),
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                            IconButton(
                                onClick = {
                                    persist(selectedKeys.filterIndexed { i, _ -> i != index })
                                },
                            ) {
                                Icon(
                                    Icons.Filled.Close,
                                    contentDescription = stringResource(R.string.theme_editor_delete),
                                    modifier = Modifier.size(18.dp),
                                    tint = MaterialTheme.colorScheme.error,
                                )
                            }
                        }
                    }
                }
            }

            if (availableTools.isNotEmpty()) {
                SettingsGroup(title = stringResource(R.string.toolbar_tools_available)) {
                    availableTools.forEachIndexed { index, tool ->
                        ToolRow(
                            tool = tool,
                            showDivider = index != availableTools.lastIndex,
                        ) {
                            IconButton(onClick = { persist(selectedKeys + tool.key) }) {
                                Icon(
                                    Icons.Filled.Add,
                                    contentDescription = stringResource(R.string.toolbar_tools_available),
                                    modifier = Modifier.size(20.dp),
                                    tint = MaterialTheme.colorScheme.primary,
                                )
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(14.dp))
        }
    }
}

@Composable
private fun ToolRow(
    tool: ToolbarTool,
    showDivider: Boolean,
    trailing: @Composable () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            painter = painterResource(tool.iconRes),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(12.dp))
        Text(
            text = stringResource(tool.labelRes),
            fontSize = rowSubFontSize,
            modifier = Modifier.weight(1f),
        )
        trailing()
    }
    if (showDivider) {
        androidx.compose.material3.HorizontalDivider(
            thickness = 0.5.dp,
            color = MaterialTheme.colorScheme.outlineVariant,
        )
    }
}
