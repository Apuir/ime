package com.ninthsoft.ime.ui.screen

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ninthsoft.ime.R
import com.ninthsoft.ime.data.settings.SettingsBackup
import com.ninthsoft.ime.data.settings.SettingsBackupStore
import com.ninthsoft.ime.ui.screen.ScreenComponent.ClickableRow
import com.ninthsoft.ime.ui.screen.ScreenComponent.SettingsGroup
import com.ninthsoft.ime.ui.screen.ScreenComponent.barFontSize
import com.ninthsoft.ime.ui.screen.ScreenComponent.rowSubFontSize
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 设置分享：把全部设置项与自定义主题导出成一个 JSON 文件，换设备时导入还原。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsShareScreen(onBack: () -> Unit) {
    val context = LocalContext.current

    // 选中文件后先解析、再让用户确认，避免误点一个文件就把设置覆盖掉。
    var pendingImport by remember { mutableStateOf<SettingsBackup?>(null) }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val backup = SettingsBackupStore.export(context)
        val message = if (SettingsBackupStore.write(context, backup, uri)) {
            context.getString(
                R.string.settings_share_exported, backup.settingCount, backup.themes.size
            )
        } else {
            context.getString(R.string.settings_share_export_failed)
        }
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
    }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val backup = SettingsBackupStore.read(context, uri)
        if (backup == null) {
            Toast.makeText(context, R.string.settings_share_invalid_file, Toast.LENGTH_SHORT).show()
        } else {
            pendingImport = backup
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.settings_share),
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

            SettingsGroup(title = stringResource(R.string.settings_share_group)) {
                ClickableRow(
                    title = stringResource(R.string.settings_share_export),
                    value = stringResource(R.string.settings_share_export_value),
                    onClick = { exportLauncher.launch(defaultBackupFileName()) },
                )
                Text(
                    text = stringResource(R.string.settings_share_export_info),
                    fontSize = rowSubFontSize,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
                HorizontalDivider(
                    thickness = 0.5.dp,
                    color = MaterialTheme.colorScheme.outlineVariant,
                )
                ClickableRow(
                    title = stringResource(R.string.settings_share_import),
                    value = stringResource(R.string.settings_share_import_value),
                    onClick = { importLauncher.launch(arrayOf("*/*")) },
                )
                Text(
                    text = stringResource(R.string.settings_share_import_info),
                    fontSize = rowSubFontSize,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
            }

            Spacer(Modifier.height(14.dp))
        }
    }

    pendingImport?.let { backup ->
        AlertDialog(
            onDismissRequest = { pendingImport = null },
            title = { Text(stringResource(R.string.settings_share_import)) },
            text = { Text(stringResource(R.string.settings_share_import_confirm)) },
            confirmButton = {
                TextButton(onClick = {
                    val result = SettingsBackupStore.apply(context, backup)
                    pendingImport = null
                    Toast.makeText(
                        context,
                        context.getString(
                            R.string.settings_share_imported, result.settings, result.themes
                        ),
                        Toast.LENGTH_SHORT,
                    ).show()
                }) {
                    Text(stringResource(R.string.settings_share_import))
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingImport = null }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
}

private fun defaultBackupFileName(): String =
    "ime-settings-${SimpleDateFormat("yyyyMMdd", Locale.US).format(Date())}.json"
