package com.ninthsoft.ime.input.panel.toolbar

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import com.ninthsoft.ime.R
import com.ninthsoft.ime.input.panel.PanelAction

/**
 * 键盘上方工具栏中间区域可选用的工具目录。
 *
 * 用户在设置里勾选/排序后，[com.ninthsoft.ime.data.manager.KeyboardManager.Keyboard.ToolbarTools]
 * 以逗号分隔的 [key] 列表持久化，工具栏据此渲染中间那排图标。
 */
enum class ToolbarTool(
    val key: String,
    @StringRes val labelRes: Int,
    @DrawableRes val iconRes: Int,
    val action: PanelAction,
) {
    Undo("undo", R.string.tool_undo, R.drawable.ic_keyboard_undo, PanelAction.Undo),
    Redo("redo", R.string.tool_redo, R.drawable.ic_keyboard_redo, PanelAction.Redo),
    Cursor("cursor", R.string.menu_cursor, R.drawable.ic_keyboard_cursor_move, PanelAction.CursorMove),
    Resize("resize", R.string.tool_resize_keyboard, R.drawable.ic_keyboard_resize, PanelAction.ResizeKeyboard),
    Clipboard("clipboard", R.string.menu_clipboard, R.drawable.ic_keyboard_clipboard, PanelAction.Clipboard),
    Phrases("phrases", R.string.phrase_tab, R.drawable.ic_keyboard_star_david, PanelAction.CommonPhrases),
    Palette("palette", R.string.menu_theme, R.drawable.ic_keyboard_palette, PanelAction.Palette),
    Emoji("emoji", R.string.menu_emoji, R.drawable.ic_keyboard_emoticon, PanelAction.EmojiKeyboard),
    Symbol("symbol", R.string.menu_symbol, R.drawable.ic_keyboard_symbol, PanelAction.SymbolKeyboard),
    Voice("voice", R.string.menu_voice, R.drawable.ic_keyboard_voice, PanelAction.ToggleVoice),
    Prediction("prediction", R.string.model_prediction, R.drawable.ic_keyboard_lightbulb_on_outline, PanelAction.TogglePrediction),
    Comment("comment", R.string.menu_candidate_comment, R.drawable.ic_keyboard_bubble, PanelAction.ToggleShowComment),
    Traditional("traditional", R.string.menu_traditional_chinese, R.drawable.ic_keyboard_traditional_ch, PanelAction.ToggleTraditionalChinese),
    Ascii("ascii", R.string.menu_ascii_mode, R.drawable.ic_keyboard_english_mode, PanelAction.ToggleAsciiMode),
    EmojiInput("emoji_input", R.string.menu_emoji_input, R.drawable.ic_keyboard_sticker_emoji, PanelAction.ToggleEmojiInput),
    Schema("schema", R.string.menu_schema, R.drawable.ic_keyboard_tune, PanelAction.SchemaSettings),
    Settings("settings", R.string.menu_settings, R.drawable.ic_keyboard_setting, PanelAction.Settings),
    Reload("reload", R.string.menu_reload_engine, R.drawable.ic_keyboard_reload, PanelAction.ReloadEngine),
    About("about", R.string.menu_about, R.drawable.ic_keyboard_information_outline, PanelAction.About),
    Handwriting("handwriting", R.string.menu_handwriting, R.drawable.ic_keyboard_handwriting, PanelAction.ToggleHandwriting);

    companion object {
        private val byKey = entries.associateBy { it.key }

        val DEFAULT: List<ToolbarTool> = listOf(Undo, Redo, Cursor, Clipboard, Palette, Handwriting)

        fun byKey(key: String): ToolbarTool? = byKey[key]
    }
}
