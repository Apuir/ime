package com.ninthsoft.ime.data.settings

import com.ninthsoft.ime.data.manager.CandidateManager
import com.ninthsoft.ime.data.manager.ClipboardManager
import com.ninthsoft.ime.data.manager.KeyboardKeyMapping
import com.ninthsoft.ime.data.manager.KeyboardManager
import com.ninthsoft.ime.data.manager.SchemaManager
import com.ninthsoft.ime.data.theme.ReadableTheme
import com.ninthsoft.ime.input.handwriting.HandwritingManager
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.float
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

enum class SettingType { BOOLEAN, INT, FLOAT, STRING }

data class SettingKey(val name: String, val type: SettingType)

/**
 * 备份白名单：只有列在这里的键才会进备份文件，导入时也只认这些键。
 *
 * 用白名单而不是「把 prefs 整个倒出来」，是因为 `keyboard_settings` 里混着三类不该跟着换机走的东西：
 * 一次性迁移标记（`vibration_scale` / `toolbar_tools.handwriting_added`）、
 * 设备相关的探测缓存（`handwriting.google_usable`）、以及 legacy 键。
 * 白名单还让文件与键名解耦：以后新增设置项时，不改这里就只是「不参与备份」，不会导出半个未知结构。
 */
object SettingsBackupSpec {
    const val FORMAT = "ime-settings"
    const val VERSION = 1

    /** 字符串值上限。用户自定义内容（按键映射、侧栏符号）都远小于它，超了按损坏处理。 */
    const val MAX_STRING_LENGTH = 4096

    private val B = SettingType.BOOLEAN
    private val I = SettingType.INT
    private val F = SettingType.FLOAT
    private val S = SettingType.STRING

    val PREFS: Map<String, List<SettingKey>> = linkedMapOf(
        KeyboardManager.PREFS_NAME to listOf(
            SettingKey(KeyboardManager.Theme.KEY_MODE, I),
            SettingKey(KeyboardManager.Keyboard.KEY_HEIGHT, I),
            SettingKey(KeyboardManager.Keyboard.KEY_HEIGHT_LANDSCAPE, I),
            SettingKey(KeyboardManager.Keyboard.KEY_WIDTH, I),
            SettingKey(KeyboardManager.Keyboard.KEY_POSITION_X, F),
            SettingKey(KeyboardManager.Keyboard.KEY_POSITION_Y, F),
            SettingKey(KeyboardManager.Keyboard.KEY_IGNORE_INSETS, B),
            SettingKey(KeyboardManager.Keyboard.KEY_THEME, S),
            SettingKey(KeyboardManager.Keyboard.KEY_FOLLOW_SYSTEM, B),
            SettingKey(KeyboardManager.Keyboard.KEY_LIGHT_THEME, S),
            SettingKey(KeyboardManager.Keyboard.KEY_DARK_THEME, S),
            SettingKey(KeyboardManager.Keyboard.Padding.KEY_HORIZONTAL, I),
            SettingKey(KeyboardManager.Keyboard.Padding.KEY_BOTTOM, I),
            SettingKey(KeyboardManager.Keyboard.Feedback.KEY_VIBRATION_EFFECT, I),
            SettingKey(KeyboardManager.Keyboard.Feedback.KEY_VIBRATION_LEVEL, I),
            SettingKey(KeyboardManager.Keyboard.Feedback.KEY_VIBRATION_IGNORE_SYSTEM, B),
            SettingKey(KeyboardManager.Keyboard.Feedback.KEY_SOUND, B),
            SettingKey(KeyboardManager.Keyboard.Gap.KEY_HORIZONTAL, I),
            SettingKey(KeyboardManager.Keyboard.Gap.KEY_VERTICAL, I),
            SettingKey(KeyboardManager.Keyboard.KeyRadius.KEY, I),
            SettingKey(KeyboardManager.Keyboard.RippleEffect.KEY, B),
            SettingKey(KeyboardManager.Keyboard.KeyBorderStroke.KEY, B),
            SettingKey(KeyboardManager.Keyboard.ExpandBorder.KEY, B),
            SettingKey(KeyboardManager.Keyboard.GestureInput.KEY, I),
            SettingKey(KeyboardManager.Keyboard.SwipeUp.KEY, F),
            SettingKey(KeyboardManager.Keyboard.SwipeUp.KEY_DIRECTION_TAN, F),
            SettingKey(KeyboardManager.Keyboard.ToolbarTools.KEY, S),
            SettingKey(KeyboardManager.Keyboard.SidePanelSymbols.KEY_T9, S),
            SettingKey(KeyboardManager.Keyboard.SidePanelSymbols.KEY_NUMBER, S),
            SettingKey(KeyboardKeyMapping.KEY_QWERTY, S),
            SettingKey(KeyboardKeyMapping.KEY_T9, S),
            SettingKey(KeyboardKeyMapping.KEY_BUBBLE_ENABLED, B),
            SettingKey(KeyboardManager.Keyboard.Floating.KEY_ENABLED, B),
            SettingKey(KeyboardManager.Keyboard.Floating.KEY_WIDTH, I),
            SettingKey(KeyboardManager.Keyboard.Floating.KEY_POSITION_X, F),
            SettingKey(KeyboardManager.Keyboard.Floating.KEY_POSITION_Y, F),
            SettingKey(KeyboardManager.Slot.KEY_CHINESE_KEYBOARD, S),
            SettingKey(KeyboardManager.Slot.KEY_CHINESE_SCHEMA, S),
            SettingKey(HandwritingManager.KEY_ENGINE_MODE, I),
            SettingKey(HandwritingManager.KEY_RECOGNIZE_ON_LIFT, B),
            SettingKey(HandwritingManager.KEY_RECOGNIZE_DELAY_MS, I),
            SettingKey(HandwritingManager.KEY_FULL_SCREEN, B),
        ),
        CandidateManager.PREFS_NAME to listOf(
            SettingKey(CandidateManager.KEY_PREDICTION_ENABLED, B),
            SettingKey(CandidateManager.KEY_TRADITIONAL_ENABLED, B),
            SettingKey(CandidateManager.KEY_EMOJI_ENABLED, B),
            SettingKey(CandidateManager.KEY_ASCII_MODE_ENABLED, B),
            SettingKey(CandidateManager.KEY_RERANK_ENABLED, B),
            SettingKey(CandidateManager.KEY_SHOW_INDEX, B),
            SettingKey(CandidateManager.KEY_SHOW_COMMENT, B),
            SettingKey(CandidateManager.KEY_BORDER, B),
            SettingKey(CandidateManager.KEY_PREVIEW_MODE, I),
            SettingKey(CandidateManager.KEY_COMMIT_PREVIEW_ON_SWITCH, B),
        ),
        SchemaManager.PREFS_NAME to listOf(
            SettingKey(SchemaManager.KEY_ENABLED_IDS, S),
            SettingKey(SchemaManager.KEY_GRAMMAR_MODEL, B),
        ),
        ClipboardManager.PREFS_NAME to listOf(
            SettingKey(ClipboardManager.KEY_MAX_ENTRIES, I),
            SettingKey(ClipboardManager.KEY_RETENTION_DAYS, I),
            SettingKey(ClipboardManager.KEY_POLL_INTERVAL_SECONDS, I),
        ),
    )
}

/**
 * 设置备份文件的内容。键名用 prefs 文件名与设置键本名，不另起一套短名：
 * 这是给人看、也可能被手改的文件，跟 `keyboard_settings` 里存的东西一一对应最不容易错。
 */
@Serializable
data class SettingsBackup(
    val format: String = SettingsBackupSpec.FORMAT,
    val version: Int = SettingsBackupSpec.VERSION,
    val appVersion: String = "",
    val exportedAt: Long = 0L,
    /** prefs 文件名 → 设置键 → 值；值按原始类型存（Bool / Int / Float / String）。 */
    val settings: Map<String, Map<String, JsonPrimitive>> = emptyMap(),
    /** 用户自定义主题；内置主题随包走，不进文件。 */
    val themes: List<ReadableTheme> = emptyList(),
) {
    val settingCount: Int get() = settings.values.sumOf { it.size }
}

object SettingsBackupCodec {
    private val json = Json {
        prettyPrint = true
        encodeDefaults = true
        ignoreUnknownKeys = true
    }

    fun encode(backup: SettingsBackup): String = json.encodeToString(backup)

    /** 解析失败、不是本应用的备份、或版本高于本端时返回 null。 */
    fun decode(text: String): SettingsBackup? {
        // 先看 format / version 再整体反序列化：`format`/`version` 在模型里带默认值，
        // 直接 decode 的话任意一个 `{}` 都能过校验，变成「导入了 0 项设置」。
        val root = runCatching { json.parseToJsonElement(text) as? JsonObject }.getOrNull()
            ?: return null
        val format = root["format"]?.jsonPrimitive?.contentOrNull ?: return null
        if (format != SettingsBackupSpec.FORMAT) return null
        val version = root["version"]?.jsonPrimitive?.intOrNull ?: return null
        if (version <= 0 || version > SettingsBackupSpec.VERSION) return null
        return runCatching { json.decodeFromJsonElement<SettingsBackup>(root) }.getOrNull()
    }

    /** 把「prefs 文件名 → 键 → 值」快照按白名单收成备份；类型不符或越界的值直接丢掉。 */
    fun collect(
        snapshot: Map<String, Map<String, Any?>>,
        appVersion: String,
        themes: List<ReadableTheme>,
        exportedAt: Long,
    ): SettingsBackup {
        val settings = SettingsBackupSpec.PREFS
            .mapValues { (prefsName, keys) ->
                val stored = snapshot[prefsName].orEmpty()
                keys.mapNotNull { key -> toJson(key, stored[key.name])?.let { key.name to it } }
                    .toMap()
            }
            .filterValues { it.isNotEmpty() }
        return SettingsBackup(
            appVersion = appVersion,
            exportedAt = exportedAt,
            settings = settings,
            themes = themes,
        )
    }

    /**
     * 把备份还原成「prefs 文件名 → 键 → 值」。
     *
     * 与 [collect] 共用同一份白名单，因此手改文件多写的键、类型不对的值都进不来 ——
     * 写盘前只认这里吐出来的结果。
     */
    fun values(backup: SettingsBackup): Map<String, Map<String, Any>> =
        SettingsBackupSpec.PREFS.mapNotNull { (prefsName, keys) ->
            val stored = backup.settings[prefsName] ?: return@mapNotNull null
            val entries = keys
                .mapNotNull { key -> stored[key.name]?.let { toValue(key, it)?.let { v -> key.name to v } } }
                .toMap()
            if (entries.isEmpty()) null else prefsName to entries
        }.toMap()

    private fun toJson(key: SettingKey, value: Any?): JsonPrimitive? = when (key.type) {
        SettingType.BOOLEAN -> (value as? Boolean)?.let { JsonPrimitive(it) }
        SettingType.INT -> (value as? Int)?.let { JsonPrimitive(it) }
        // 兼容把比例写成 Int 的写入路径：整数值也按 Float 收下。
        SettingType.FLOAT -> when (value) {
            is Float -> value.takeIf { it.isFinite() }?.let { JsonPrimitive(it) }
            is Int -> JsonPrimitive(value.toFloat())
            else -> null
        }
        SettingType.STRING -> (value as? String)
            ?.takeIf { it.length <= SettingsBackupSpec.MAX_STRING_LENGTH }
            ?.let { JsonPrimitive(it) }
    }

    private fun toValue(key: SettingKey, element: JsonPrimitive): Any? = runCatching {
        when (key.type) {
            SettingType.BOOLEAN -> element.boolean
            SettingType.INT -> element.int
            SettingType.FLOAT -> element.float.also { require(it.isFinite()) }
            SettingType.STRING -> element.content.also {
                require(it.length <= SettingsBackupSpec.MAX_STRING_LENGTH)
            }
        }
    }.getOrNull()
}
