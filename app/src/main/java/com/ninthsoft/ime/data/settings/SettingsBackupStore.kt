package com.ninthsoft.ime.data.settings

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import androidx.core.content.edit
import com.ninthsoft.ime.data.ThemeStore
import com.ninthsoft.ime.data.keyboard.theme.KeyboardThemePresets
import com.ninthsoft.ime.data.theme.ReadableTheme

data class SettingsImportResult(val settings: Int, val themes: Int)

/** 设置备份的落盘侧：读 / 写 SharedPreferences 与自定义主题，文件读写走 SAF 的 Uri。 */
object SettingsBackupStore {

    fun export(context: Context): SettingsBackup {
        val snapshot = SettingsBackupSpec.PREFS.keys.associateWith { prefsName ->
            context.getSharedPreferences(prefsName, Context.MODE_PRIVATE).all.mapValues { it.value }
        }
        return SettingsBackupCodec.collect(
            snapshot = snapshot,
            appVersion = appVersion(context),
            themes = KeyboardThemePresets.customThemes.map { ReadableTheme.from(it) },
            exportedAt = System.currentTimeMillis(),
        )
    }

    fun write(context: Context, backup: SettingsBackup, uri: Uri): Boolean = runCatching {
        val out = context.contentResolver.openOutputStream(uri) ?: error("cannot open $uri")
        out.use { it.write(SettingsBackupCodec.encode(backup).toByteArray(Charsets.UTF_8)) }
    }.isSuccess

    fun read(context: Context, uri: Uri): SettingsBackup? = runCatching {
        val text = context.contentResolver.openInputStream(uri)?.use {
            it.readBytes().toString(Charsets.UTF_8)
        } ?: return null
        SettingsBackupCodec.decode(text)
    }.getOrNull()

    /**
     * 合并写入：只覆盖备份里出现的键，目标设备上其它设置与数据（剪贴板、常用语、学习记录）不受影响。
     * 与主题导入同一套语义 —— 同 id 覆盖，否则追加。
     */
    fun apply(context: Context, backup: SettingsBackup): SettingsImportResult {
        val values = SettingsBackupCodec.values(backup)
        var applied = 0
        values.forEach { (prefsName, entries) ->
            context.getSharedPreferences(prefsName, Context.MODE_PRIVATE).edit {
                entries.forEach { (key, value) -> putValue(key, value) }
            }
            applied += entries.size
        }
        var themes = 0
        backup.themes.forEach { theme ->
            runCatching {
                ThemeStore.import(theme.toKeyboardTheme())
                themes++
            }
        }
        return SettingsImportResult(settings = applied, themes = themes)
    }

    private fun appVersion(context: Context): String = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty()
    }.getOrDefault("")

    private fun SharedPreferences.Editor.putValue(key: String, value: Any) {
        when (value) {
            is Boolean -> putBoolean(key, value)
            is Int -> putInt(key, value)
            is Float -> putFloat(key, value)
            is String -> putString(key, value)
        }
    }
}
