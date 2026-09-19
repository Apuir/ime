package com.ninthsoft.ime.input.panel

sealed class PanelAction {
    data object SwitchKeyboard : PanelAction()
    data object EmojiKeyboard : PanelAction()
    data object SymbolKeyboard : PanelAction()
    data object Clipboard : PanelAction()
    data object CommonPhrases : PanelAction()
    data object TogglePrediction : PanelAction()
    data object ToggleShowComment : PanelAction()
    data object ToggleTraditionalChinese : PanelAction()
    data object ToggleEmojiInput : PanelAction()
    data object ToggleAsciiMode : PanelAction()
    data object AddPhrase : PanelAction()
    data class ClipTab(val isClipboard: Boolean) : PanelAction()
    data object ToggleVoice : PanelAction()

    /** 打开/收起手写面板（工具栏与菜单里的「手写」入口）。 */
    data object ToggleHandwriting : PanelAction()

    /** 切换中文输入方式（九键 / 26键 / 15键 / 手写）：弹出与地球键长按同一个列表。 */
    data object SwitchLayout : PanelAction()
    data object Settings : PanelAction()
    data object SchemaSettings : PanelAction()
    data object About : PanelAction()
    data object ReloadEngine : PanelAction()
    data object Undo : PanelAction()
    data object Redo : PanelAction()
    data object Palette : PanelAction()
    data object CursorMove : PanelAction()
    data object ResizeKeyboard : PanelAction()
    data object CloseKeyboard : PanelAction()
    data object ClearClipboard : PanelAction()
    data object ClearPhrases : PanelAction()
}
