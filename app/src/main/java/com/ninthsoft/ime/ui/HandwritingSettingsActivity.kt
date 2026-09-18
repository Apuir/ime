package com.ninthsoft.ime.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.ninthsoft.ime.data.manager.KeyboardManager
import com.ninthsoft.ime.ui.screen.HandwritingSettingsScreen
import com.ninthsoft.ime.ui.theme.ImeTheme

/**
 * 手写设置页宿主。
 *
 * 与 [VoiceSettingsActivity] 同样的写法：Compose 内容 + 跟主界面一致的主题
 * （主题模式在 Activity 里读一次即可，设置页本身不需要跟随主题热变更）。
 */
class HandwritingSettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val themeMode = KeyboardManager.Theme.getMode(this)

        setContent {
            ImeTheme(themeMode = themeMode) {
                HandwritingSettingsScreen(
                    onBack = { finish() },
                )
            }
        }
    }
}
