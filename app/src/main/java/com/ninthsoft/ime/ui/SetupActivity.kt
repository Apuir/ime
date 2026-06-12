package com.ninthsoft.ime.ui

import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
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
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.Warning
import androidx.compose.runtime.Composable
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ninthsoft.ime.R
import com.ninthsoft.ime.data.theme.ThemeManager
import com.ninthsoft.ime.input.ImeInputMethodService
import com.ninthsoft.ime.ui.screen.ScreenComponent.groupFontSize
import com.ninthsoft.ime.ui.screen.ScreenComponent.rowFontSize
import com.ninthsoft.ime.ui.theme.ImeTheme

class SetupActivity : ComponentActivity() {

    private var isImeEnabled by mutableStateOf(false)
    private var isImeDefault by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        checkImeStatus()

        setContent {
            val themeMode = remember { mutableIntStateOf(ThemeManager.Theme.getMode(this)) }

            ImeTheme(themeMode = themeMode.intValue) {
                SetupScreen(
                    isImeEnabled = isImeEnabled,
                    isImeDefault = isImeDefault,
                    onOpenImeSettings = { openImeSettings() },
                    onShowImePicker = { showImePicker() },
                    onFinish = {
                        setResult(RESULT_OK)
                        finish()
                    })
            }
        }
    }

    override fun onResume() {
        super.onResume()
        checkImeStatus()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            checkImeStatus()
        }
    }

    private fun checkImeStatus() {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        val ourComponent = ComponentName(this, ImeInputMethodService::class.java)

        isImeEnabled = imm.enabledInputMethodList.any {
            it.packageName == ourComponent.packageName && it.serviceName == ourComponent.className
        }

        val currentId =
            Settings.Secure.getString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
        val ourId = ourComponent.flattenToShortString()
        isImeDefault = currentId == ourId

        // auto-finish intentionally removed; user always clicks the button to proceed
    }

    private fun openImeSettings() {
        startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
    }

    private fun showImePicker() {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        imm.showInputMethodPicker()
    }
}

@Composable
private fun SetupScreen(
    isImeEnabled: Boolean,
    isImeDefault: Boolean,
    onOpenImeSettings: () -> Unit,
    onShowImePicker: () -> Unit,
    onFinish: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = Icons.Default.Keyboard,
                contentDescription = null,
                modifier = Modifier.size(80.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(Modifier.height(8.dp))

            Text(
                text = stringResource(R.string.ime_setup_description),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                fontSize = rowFontSize,
            )

            Spacer(Modifier.height(32.dp))

            SetupStatusCard(
                title = stringResource(R.string.enable_ime),
                description = stringResource(R.string.enable_ime_desc),
                isComplete = isImeEnabled,
                completeText = stringResource(R.string.enabled),
                incompleteText = stringResource(R.string.not_enabled),
                actionText = stringResource(R.string.go_to_settings),
                onAction = onOpenImeSettings
            )

            Spacer(Modifier.height(16.dp))

            SetupStatusCard(
                title = stringResource(R.string.set_default_ime),
                description = stringResource(R.string.set_default_ime_desc),
                isComplete = isImeDefault,
                completeText = stringResource(R.string.is_default),
                incompleteText = stringResource(R.string.not_default),
                actionText = stringResource(R.string.select_ime),
                onAction = onShowImePicker
            )

            Spacer(Modifier.height(40.dp))

            val isConfigured = isImeEnabled && isImeDefault
            val buttonColors = if (isConfigured) {
                ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    contentColor = MaterialTheme.colorScheme.secondary
                )
            } else {
                ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            val buttonText = if (isConfigured) {
                stringResource(R.string.start_using)
            } else {
                stringResource(R.string.enter_settings)
            }

            Button(
                onClick = onFinish,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                colors = buttonColors
            ) {
                Text(
                    buttonText, fontSize = groupFontSize
                )
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun SetupStatusCard(
    title: String,
    description: String,
    isComplete: Boolean,
    completeText: String,
    incompleteText: String,
    actionText: String,
    onAction: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = if (isComplete) Icons.Default.CheckCircle else Icons.Default.Warning,
                    contentDescription = null,
                    tint = if (isComplete) MaterialTheme.colorScheme.secondary
                    else MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        if (isComplete) completeText else incompleteText,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (isComplete) MaterialTheme.colorScheme.secondary
                        else MaterialTheme.colorScheme.error
                    )
                }
            }

            Spacer(Modifier.height(8.dp))

            Text(
                description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (!isComplete) {
                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = onAction,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                ) {
                    Text(actionText)
                }
            }
        }
    }
}
