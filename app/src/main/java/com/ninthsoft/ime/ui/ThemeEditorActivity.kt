package com.ninthsoft.ime.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import com.ninthsoft.ime.data.manager.KeyboardManager
import com.ninthsoft.ime.ui.screen.ThemeEditorScreen
import com.ninthsoft.ime.ui.theme.ImeTheme

class ThemeEditorActivity : ComponentActivity() {

    companion object {
        private const val EXTRA_THEME_ID = "extra_theme_id"

        /** @param themeId 为空表示新建主题。 */
        fun intent(context: Context, themeId: String? = null): Intent =
            Intent(context, ThemeEditorActivity::class.java).apply {
                if (themeId != null) putExtra(EXTRA_THEME_ID, themeId)
            }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val themeId = intent.getStringExtra(EXTRA_THEME_ID)
        setContent {
            val themeMode = remember { mutableIntStateOf(KeyboardManager.Theme.getMode(this)) }
            ImeTheme(themeMode = themeMode.intValue) {
                ThemeEditorScreen(themeId = themeId, onBack = { finish() })
            }
        }
    }
}
