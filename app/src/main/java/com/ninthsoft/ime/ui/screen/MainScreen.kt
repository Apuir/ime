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
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.ninthsoft.ime.R
import com.ninthsoft.ime.ui.screen.ScreenComponent.ClickableSettingItem
import com.ninthsoft.ime.ui.screen.ScreenComponent.SectionHeader
import com.ninthsoft.ime.ui.screen.ScreenComponent.SingleChoiceDialog
import com.ninthsoft.ime.ui.screen.ScreenComponent.groupFontSize

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    currentThemeMode: Int,
    onThemeModeChanged: (Int) -> Unit,
    onOpenImeSetup: () -> Unit,
    onOpenKeyboardSettings: () -> Unit
) {
    val themes = listOf(
        stringResource(R.string.theme_follow_system),
        stringResource(R.string.theme_light),
        stringResource(R.string.theme_dark)
    )

    var showThemeDialog by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(title = {
                Text(
                    text = stringResource(R.string.app_name),
                    fontSize = groupFontSize,
                    modifier = Modifier.padding(horizontal = 0.dp, vertical = 0.dp),
                )
            }, navigationIcon = {
                IconButton(onClick = onOpenImeSetup) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = stringResource(R.string.ime_setup_title),
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
                .padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Spacer(Modifier.height(8.dp))

            SectionHeader(stringResource(R.string.basic_settings))

            ClickableSettingItem(
                title = stringResource(R.string.theme),
                subtitle = themes[currentThemeMode],
                onClick = { showThemeDialog = true },
                showSpacer = true
            )

            Spacer(Modifier.height(8.dp))
            SectionHeader(stringResource(R.string.keyboard_settings))

            ClickableSettingItem(
                title = stringResource(R.string.keyboard_settings),
                subtitle = stringResource(R.string.keyboard_settings_desc),
                onClick = onOpenKeyboardSettings,
                showSpacer = true
            )

            Spacer(Modifier.height(32.dp))
        }
    }

    if (showThemeDialog) {
        SingleChoiceDialog(
            title = stringResource(R.string.theme),
            options = themes,
            selectedIndex = currentThemeMode,
            onSelect = { onThemeModeChanged(it); showThemeDialog = false },
            onDismiss = { showThemeDialog = false })
    }
}
