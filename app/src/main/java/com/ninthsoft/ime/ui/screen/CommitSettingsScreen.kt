package com.ninthsoft.ime.ui.screen

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
import androidx.compose.runtime.mutableIntStateOf
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
import com.ninthsoft.ime.data.manager.CandidateManager
import com.ninthsoft.ime.ui.screen.ScreenComponent.ClickableRow
import com.ninthsoft.ime.ui.screen.ScreenComponent.SettingsGroup
import com.ninthsoft.ime.ui.screen.ScreenComponent.SingleChoiceDialog
import com.ninthsoft.ime.ui.screen.ScreenComponent.SwitchRow
import com.ninthsoft.ime.ui.screen.ScreenComponent.barFontSize
import com.ninthsoft.ime.ui.screen.ScreenComponent.rowSubFontSize

/** 上屏设置：输入过程中输入框的实时显示内容，以及切换方案时的保留策略。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CommitSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current

    var previewMode by remember {
        mutableIntStateOf(CandidateManager.getPreviewMode(context))
    }
    var commitOnSwitch by remember {
        mutableStateOf(CandidateManager.isCommitPreviewOnSwitch(context))
    }
    var showModeDialog by remember { mutableStateOf(false) }

    val modeLabels = listOf(
        stringResource(R.string.preview_content_none),
        stringResource(R.string.preview_content_raw),
        stringResource(R.string.preview_content_first_candidate),
    )

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.commit_settings),
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

            SettingsGroup(title = stringResource(R.string.preview_content_group)) {
                ClickableRow(
                    title = stringResource(R.string.preview_content),
                    value = modeLabels.getOrElse(previewMode) { modeLabels.first() },
                    onClick = { showModeDialog = true },
                )
                Text(
                    text = stringResource(R.string.preview_content_desc),
                    fontSize = rowSubFontSize,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
            }

            SettingsGroup(title = stringResource(R.string.preview_switch_group)) {
                SwitchRow(
                    title = stringResource(R.string.preview_commit_on_switch),
                    checked = commitOnSwitch,
                    onCheckedChange = {
                        commitOnSwitch = it
                        CandidateManager.setCommitPreviewOnSwitch(context, it)
                    },
                )
                Text(
                    text = stringResource(R.string.preview_commit_on_switch_desc),
                    fontSize = rowSubFontSize,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
            }

            Spacer(Modifier.height(14.dp))
        }
    }

    if (showModeDialog) {
        SingleChoiceDialog(
            title = stringResource(R.string.preview_content),
            options = modeLabels,
            selectedIndex = previewMode,
            onSelect = { index ->
                previewMode = index
                CandidateManager.setPreviewMode(context, index)
                showModeDialog = false
            },
            onDismiss = { showModeDialog = false },
        )
    }
}
