package com.ninthsoft.ime.data

import com.ninthsoft.ime.data.keyboard.theme.KeyboardTheme
import com.ninthsoft.ime.data.keyboard.theme.KeyboardThemePresets
import com.ninthsoft.ime.data.theme.CompactTheme
import com.ninthsoft.ime.data.theme.ReadableTheme
import kotlinx.serialization.json.Json
import java.io.File

object ThemeStore {
    private const val THEMES_FILE = "themes.json"

    // themes.json 使用可读的关键字（长 key）与 #AARRGGBB 十六进制颜色，美化排版
    private val readableJson = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
    }

    // 兼容旧版：长 key + Int 颜色（KeyboardTheme）或紧凑短 key（CompactTheme）
    private val fallbackJson = Json { ignoreUnknownKeys = true }

    fun refresh() {
        val file = File(App.themesDir, THEMES_FILE)
        val themes = if (file.isFile) {
            val text = file.readText()
            runCatching {
                readableJson.decodeFromString<List<ReadableTheme>>(text)
                    .map { it.toKeyboardTheme() }
            }.getOrElse {
                runCatching {
                    fallbackJson.decodeFromString<List<KeyboardTheme>>(text)
                }.getOrElse {
                    runCatching {
                        fallbackJson.decodeFromString<List<CompactTheme>>(text)
                            .map { it.toKeyboardTheme() }
                    }.getOrElse { emptyList() }
                }
            }
        } else {
            emptyList()
        }
        // 去重（相同 id 只保留最后一个），不再限制自定义主题数量。
        val deduped = LinkedHashMap<String, KeyboardTheme>()
        themes.forEach { deduped[it.id] = it }
        KeyboardThemePresets.setCustomThemes(deduped.values.toList())
    }

    /** 按 id 查找自定义主题。 */
    fun find(id: String): KeyboardTheme? =
        KeyboardThemePresets.customThemes.firstOrNull { it.id == id }

    /**
     * 新增或更新自定义主题；相同 id 会被原位覆盖。始终成功。
     */
    fun save(theme: KeyboardTheme) {
        val current = KeyboardThemePresets.customThemes.toMutableList()
        val existingIndex = current.indexOfFirst { it.id == theme.id }
        if (existingIndex >= 0) {
            current[existingIndex] = theme
        } else {
            current.add(theme)
        }
        applyCustomThemes(current)
    }

    /**
     * 直接导入主题（二维码 / 编辑器）。
     * 相同 id 的主题会被原位替换，否则追加。
     */
    fun import(theme: KeyboardTheme) = save(theme)

    /** 覆盖指定槽位的用户主题。 */
    fun overwrite(index: Int, theme: KeyboardTheme) {
        val current = KeyboardThemePresets.customThemes.toMutableList()
        if (index in current.indices) {
            current[index] = theme
        } else {
            current.add(theme)
        }
        applyCustomThemes(current)
    }

    /** 重命名指定 id 的自定义主题。 */
    fun rename(id: String, newName: String): Boolean {
        val current = KeyboardThemePresets.customThemes.toMutableList()
        val index = current.indexOfFirst { it.id == id }
        if (index < 0) return false
        current[index] = current[index].copy(name = newName)
        applyCustomThemes(current)
        return true
    }

    /** 删除指定 id 的自定义主题。 */
    fun delete(id: String): Boolean {
        val current = KeyboardThemePresets.customThemes.toMutableList()
        val removed = current.removeAll { it.id == id }
        if (!removed) return false
        applyCustomThemes(current)
        return true
    }

    /** 生成一个不与现有主题冲突的自定义主题 id。 */
    fun newThemeId(): String {
        val existing = KeyboardThemePresets.customThemes.map { it.id }.toSet()
        var index = 1
        while (true) {
            val candidate = "custom_${System.currentTimeMillis()}_$index"
            if (candidate !in existing) return candidate
            index++
        }
    }

    private fun applyCustomThemes(themes: List<KeyboardTheme>) {
        val file = File(App.themesDir, THEMES_FILE)
        runCatching {
            file.writeText(
                readableJson.encodeToString(themes.map { ReadableTheme.from(it) })
            )
        }
        KeyboardThemePresets.setCustomThemes(themes)
    }
}
