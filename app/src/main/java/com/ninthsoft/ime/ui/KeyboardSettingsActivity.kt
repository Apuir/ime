package com.ninthsoft.ime.ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import com.ninthsoft.ime.data.manager.KeyboardManager
import com.ninthsoft.ime.ui.screen.KeyboardSettingsScreen
import com.ninthsoft.ime.ui.theme.ImeTheme

class KeyboardSettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val themeMode = remember { mutableIntStateOf(KeyboardManager.Theme.getMode(this)) }
            ImeTheme(themeMode = themeMode.intValue) {
                KeyboardSettingsScreen(
                    onBack = { finish() },
                    onOpenToolbarSettings = {
                        startActivity(Intent(this, ToolbarSettingsActivity::class.java))
                    },
                    onOpenSidePanelSymbols = {
                        startActivity(Intent(this, SidePanelSymbolsActivity::class.java))
                    },
                    onOpenKeyMapping = {
                        startActivity(Intent(this, KeyMappingActivity::class.java))
                    },
                )
            }
        }
    }
}
