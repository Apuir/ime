package com.ninthsoft.ime.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ninthsoft.ime.R
import com.ninthsoft.ime.data.database.AppDatabase
import com.ninthsoft.ime.data.manager.CandidateManager
import com.ninthsoft.ime.ui.screen.ScreenComponent.ClickableRow
import com.ninthsoft.ime.ui.screen.ScreenComponent.SettingsGroup
import com.ninthsoft.ime.ui.screen.ScreenComponent.SwitchRow
import com.ninthsoft.ime.ui.screen.ScreenComponent.barFontSize
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CandidateSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var predictionEnabled by remember {
        mutableStateOf(CandidateManager.isPredictionEnabled(context))
    }
    var rerankEnabled by remember {
        mutableStateOf(CandidateManager.isRerankEnabled(context))
    }
    var showIndex by remember {
        mutableStateOf(CandidateManager.isShowIndex(context))
    }
    var showComment by remember {
        mutableStateOf(CandidateManager.isShowComment(context))
    }
    var borderEnabled by remember {
        mutableStateOf(CandidateManager.isBorderEnabled(context))
    }
    var learnedCount by remember { mutableStateOf(0) }
    var showResetDialog by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    suspend fun refreshLearnedCount() {
        learnedCount = withContext(Dispatchers.IO) {
            runCatching { AppDatabase.getInstance(context).candidatePreferDao().count() }
                .getOrDefault(0)
        }
    }

    LaunchedEffect(Unit) { refreshLearnedCount() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.prediction_candidates),
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
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Spacer(Modifier.height(4.dp))

            SettingsGroup(title = stringResource(R.string.model_prediction)) {
                SwitchRow(
                    title = stringResource(R.string.model_prediction_enable),
                    checked = predictionEnabled,
                    onCheckedChange = {
                        predictionEnabled = it
                        CandidateManager.setPredictionEnabled(context, it)
                    },
                )
            }

            SettingsGroup(title = stringResource(R.string.model_enhance)) {
                SwitchRow(
                    title = stringResource(R.string.model_rerank_enable),
                    checked = rerankEnabled,
                    onCheckedChange = {
                        rerankEnabled = it
                        CandidateManager.setRerankEnabled(context, it)
                    },
                )
            }

            SettingsGroup(title = stringResource(R.string.candidate_layout)) {
                SwitchRow(
                    title = stringResource(R.string.candidate_show_index),
                    checked = showIndex,
                    onCheckedChange = {
                        showIndex = it
                        CandidateManager.setShowIndex(context, it)
                    },
                )
                SwitchRow(
                    title = stringResource(R.string.candidate_show_comment),
                    checked = showComment,
                    onCheckedChange = {
                        showComment = it
                        CandidateManager.setShowComment(context, it)
                    },
                )
                SwitchRow(
                    title = stringResource(R.string.candidate_border),
                    checked = borderEnabled,
                    onCheckedChange = {
                        borderEnabled = it
                        CandidateManager.setBorderEnabled(context, it)
                    },
                )
            }

            // 「常用词偏好」和「误选记录」都会影响候选排序，所以给一个出口：
            // 用户觉得排序被带偏了，可以一键回到初始状态（剪贴板/常用语不受影响）。
            SettingsGroup(title = stringResource(R.string.candidate_learning)) {
                ClickableRow(
                    title = stringResource(R.string.candidate_reset_learning),
                    value = if (learnedCount > 0) {
                        learnedCount.toString()
                    } else {
                        stringResource(R.string.candidate_reset_learning_empty)
                    },
                    onClick = { showResetDialog = true },
                )
            }
        }
    }

    if (showResetDialog) {
        AlertDialog(
            onDismissRequest = { showResetDialog = false },
            title = { Text(stringResource(R.string.candidate_reset_learning)) },
            text = {
                Column {
                    Text(stringResource(R.string.candidate_reset_learning_confirm))
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.candidate_reset_learning_hint),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    showResetDialog = false
                    scope.launch {
                        withContext(Dispatchers.IO) {
                            runCatching {
                                AppDatabase.getInstance(context).candidatePreferDao().clearAll()
                            }
                        }
                        refreshLearnedCount()
                    }
                }) {
                    Text(stringResource(R.string.resize_reset))
                }
            },
            dismissButton = {
                TextButton(onClick = { showResetDialog = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
}
