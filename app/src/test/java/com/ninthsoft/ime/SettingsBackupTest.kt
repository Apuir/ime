package com.ninthsoft.ime

import com.ninthsoft.ime.data.manager.CandidateManager
import com.ninthsoft.ime.data.manager.KeyboardManager
import com.ninthsoft.ime.data.settings.SettingsBackup
import com.ninthsoft.ime.data.settings.SettingsBackupCodec
import com.ninthsoft.ime.data.settings.SettingsBackupSpec
import com.ninthsoft.ime.data.theme.ReadableColors
import com.ninthsoft.ime.data.theme.ReadablePanel
import com.ninthsoft.ime.data.theme.ReadablePinner
import com.ninthsoft.ime.data.theme.ReadableTheme
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class SettingsBackupTest {

    private val keyboardPrefs = KeyboardManager.PREFS_NAME
    private val candidatePrefs = CandidateManager.PREFS_NAME

    private fun collect(
        snapshot: Map<String, Map<String, Any?>>,
        themes: List<ReadableTheme> = emptyList(),
    ): SettingsBackup = SettingsBackupCodec.collect(
        snapshot = snapshot,
        appVersion = "2.5.0",
        themes = themes,
        exportedAt = 1_700_000_000_000L,
    )

    @Test
    fun collectKeepsWhitelistedKeysWithTypes() {
        val backup = collect(
            mapOf(
                keyboardPrefs to mapOf(
                    KeyboardManager.Keyboard.KEY_HEIGHT to 30,
                    KeyboardManager.Keyboard.KEY_FOLLOW_SYSTEM to true,
                    KeyboardManager.Keyboard.SwipeUp.KEY to 0.5f,
                    KeyboardManager.Keyboard.KEY_THEME to "custom_seafoam",
                ),
                candidatePrefs to mapOf(CandidateManager.KEY_SHOW_INDEX to false),
            )
        )

        val keyboard = backup.settings.getValue(keyboardPrefs)
        assertEquals(JsonPrimitive(30), keyboard[KeyboardManager.Keyboard.KEY_HEIGHT])
        assertEquals(JsonPrimitive(true), keyboard[KeyboardManager.Keyboard.KEY_FOLLOW_SYSTEM])
        assertEquals(JsonPrimitive(0.5f), keyboard[KeyboardManager.Keyboard.SwipeUp.KEY])
        assertEquals(JsonPrimitive("custom_seafoam"), keyboard[KeyboardManager.Keyboard.KEY_THEME])
        assertEquals(JsonPrimitive(false), backup.settings.getValue(candidatePrefs)[CandidateManager.KEY_SHOW_INDEX])
        assertEquals("2.5.0", backup.appVersion)
    }

    @Test
    fun collectDropsInternalAndUnknownKeys() {
        val backup = collect(
            mapOf(
                keyboardPrefs to mapOf(
                    "keyboard.feedback.vibration_scale" to 2,
                    "keyboard.feedback.vibration" to true,
                    "keyboard.toolbar_tools.handwriting_added" to true,
                    "keyboard.slot.active" to "Chinese",
                    "handwriting.google_usable" to 1,
                    "keyboard.not_a_setting" to 1,
                    KeyboardManager.Keyboard.KEY_HEIGHT to 30,
                ),
                "asset_extract_prefs" to mapOf("extracted" to true),
                "symbol_recent" to mapOf("recent_symbols" to "，"),
            )
        )

        assertEquals(1, backup.settingCount)
        assertEquals(
            setOf(KeyboardManager.Keyboard.KEY_HEIGHT),
            backup.settings.getValue(keyboardPrefs).keys,
        )
    }

    @Test
    fun collectDropsTypeMismatchAndOverlongStrings() {
        val backup = collect(
            mapOf(
                keyboardPrefs to mapOf(
                    KeyboardManager.Keyboard.KEY_HEIGHT to "tall",
                    KeyboardManager.Keyboard.KEY_FOLLOW_SYSTEM to 1,
                    KeyboardManager.Keyboard.ToolbarTools.KEY to
                        "x".repeat(SettingsBackupSpec.MAX_STRING_LENGTH + 1),
                )
            )
        )

        assertEquals(0, backup.settingCount)
    }

    @Test
    fun valuesDropUnknownFilesKeysAndBadTypes() {
        val backup = SettingsBackupCodec.decode(
            """
            {
              "format": "ime-settings",
              "version": 1,
              "settings": {
                "keyboard_settings": {
                  "keyboard.height": 30,
                  "keyboard.height_landscape": "high",
                  "keyboard.follow_system": "yes",
                  "keyboard.swipe_up.ratio": 0.75,
                  "keyboard.not_a_setting": 1
                },
                "asset_extract_prefs": { "extracted": true }
              }
            }
            """.trimIndent()
        )

        assertNotNull(backup)
        val values = SettingsBackupCodec.values(backup!!)
        assertEquals(
            mapOf(
                "keyboard.height" to 30,
                "keyboard.swipe_up.ratio" to 0.75f,
            ),
            values[keyboardPrefs],
        )
        assertFalse(values.containsKey("asset_extract_prefs"))
    }

    @Test
    fun encodeDecodeRoundTripKeepsSettingsAndThemes() {
        val theme = sampleTheme()
        val backup = collect(
            mapOf(
                keyboardPrefs to mapOf(
                    KeyboardManager.Keyboard.KEY_THEME to theme.id,
                    KeyboardManager.Keyboard.SwipeUp.KEY to 1.25f,
                ),
                candidatePrefs to mapOf(CandidateManager.KEY_ASCII_MODE_ENABLED to true),
            ),
            themes = listOf(theme),
        )

        val decoded = SettingsBackupCodec.decode(SettingsBackupCodec.encode(backup))

        assertNotNull(decoded)
        assertEquals(backup.settingCount, decoded!!.settingCount)
        assertEquals(listOf(theme), decoded.themes)
        assertEquals(SettingsBackupCodec.values(backup), SettingsBackupCodec.values(decoded))
        assertEquals(1_700_000_000_000L, decoded.exportedAt)
    }

    @Test
    fun decodeRejectsForeignNewerAndBrokenFiles() {
        assertNull(SettingsBackupCodec.decode("not json at all"))
        assertNull(SettingsBackupCodec.decode("{}"))
        assertNull(SettingsBackupCodec.decode("""{"format":"other-app","version":1}"""))
        assertNull(SettingsBackupCodec.decode("""{"format":"${SettingsBackupSpec.FORMAT}"}"""))
        assertNull(SettingsBackupCodec.decode("""{"format":"${SettingsBackupSpec.FORMAT}","version":0}"""))
        assertNull(
            SettingsBackupCodec.decode(
                """{"format":"${SettingsBackupSpec.FORMAT}","version":${SettingsBackupSpec.VERSION + 1}}"""
            )
        )
        assertNotNull(
            SettingsBackupCodec.decode(
                """{"format":"${SettingsBackupSpec.FORMAT}","version":1}"""
            )
        )
    }

    private fun sampleTheme() = ReadableTheme(
        id = "custom_test",
        name = "测试主题",
        colors = ReadableColors(
            keyBackground = 0xFF1A3F4E.toInt(),
            keyPressed = 0xFF24546A.toInt(),
            keyBorderStroke = 0xFF2F6280.toInt(),
            specialKeyBackground = 0xFF16333F.toInt(),
            specialKeyPressed = 0xFF205067.toInt(),
            specialKeyBorderStroke = 0xFF2F6280.toInt(),
            accentKeyBackground = 0xFF2EC2E6.toInt(),
            accentKeyPressed = 0xFF6BD6EF.toInt(),
            accentKeyBorderStroke = 0xFF2EC2E6.toInt(),
            keyText = 0xFFE8F6FA.toInt(),
            specialKeyText = 0xFF8FC3D4.toInt(),
            accentKeyText = 0xFF062331.toInt(),
            altText = 0xFF6FA8BC.toInt(),
            background = 0xFF0F2A33.toInt(),
            panel = ReadablePanel(
                background = 0xFF0F2A33.toInt(),
                toolbarText = 0xFFE8F6FA.toInt(),
                toolbarActived = 0xFF2EC2E6.toInt(),
                toolbarIcon = 0xFF8FC3D4.toInt(),
                candidateBackground = 0xFF1A3F4E.toInt(),
                candidateText = 0xFFE8F6FA.toInt(),
                candidateIndex = 0xFF6FA8BC.toInt(),
                candidateDivider = 0xFF6FA8BC.toInt(),
                toolbarPressed = 0xFF24546A.toInt(),
            ),
            pinner = ReadablePinner(
                background = 0xFF1A3F4E.toInt(),
                textColor = 0xFFE8F6FA.toInt(),
                secondaryTextColor = 0xFF6FA8BC.toInt(),
            ),
        ),
    )
}
