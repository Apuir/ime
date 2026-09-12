package com.ninthsoft.ime.data.manager

import android.content.Context
import androidx.core.content.edit
import com.ninthsoft.ime.input.keyboard.key.KeyBubbleItem
import com.ninthsoft.ime.input.keyboard.key.KeyboardAction

/**
 * 26 键字母键 / 九宫格数字键的可自定义映射，以及按键气泡的开关。
 *
 * 26 键：每个字母键下挂一个符号或数字（q→1、g→$ …），显示在键帽右下角，
 * 长按 / 上滑时出现在气泡里。这里只存「用户改过」的部分（[KEY_QWERTY]），
 * 没改过的键回落到 [DEFAULT_QWERTY_SYMBOLS]。
 *
 * 九宫格：每个数字键对应一串字母（2→abc、7→pqrs …），既决定键帽上显示的字母，
 * 也决定气泡里逐个列出的字母。
 */
object KeyboardKeyMapping {
    private const val PREFS_NAME = KeyboardManager.PREFS_NAME

    /** 气泡开关；公开给 `KeyboardWindowView` 用于判断是否要重建键盘。 */
    const val KEY_BUBBLE_ENABLED = "keyboard.key_bubble"

    /** 26 键字母键的次级符号 / 数字映射（只存用户改过的键）。 */
    const val KEY_QWERTY = "keyboard.key_mapping.qwerty"

    /** 九宫格数字键对应的字母串（只存用户改过的键）。 */
    const val KEY_T9 = "keyboard.key_mapping.t9"

    /** 用不可见控制字符分隔，避免与符号本身（逗号、冒号等）冲突。 */
    private const val SEPARATOR = "\u001F"

    // ---------------------------------------------------------------- 26 键

    /** 26 键字母键默认的次级符号 / 数字，与旧版写死的布局一致。 */
    val DEFAULT_QWERTY_SYMBOLS: Map<String, String> = linkedMapOf(
        // 第一行：数字
        "q" to "1", "w" to "2", "e" to "3", "r" to "4", "t" to "5",
        "y" to "6", "u" to "7", "i" to "8", "o" to "9", "p" to "0",
        // 第二行：符号
        "a" to "~", "s" to "!", "d" to "@", "f" to "#", "g" to "$",
        "h" to "%", "j" to "^", "k" to "&", "l" to "*",
        // 第三行：符号
        "z" to "(", "x" to ")", "c" to ":", "v" to ";", "b" to ",",
        "n" to "?", "m" to "/",
    )

    /** 26 键字母键的显示顺序（与键盘从上到下、从左到右一致），配置界面按它排版。 */
    val QWERTY_LETTERS: List<String> = DEFAULT_QWERTY_SYMBOLS.keys.toList()

    /** 用户在配置界面里可以点选的常用符号池。 */
    val SYMBOL_POOL: List<String> = listOf(
        "1", "2", "3", "4", "5", "6", "7", "8", "9", "0",
        "~", "!", "@", "#", "$", "%", "^", "&", "*", "(", ")", "-", "_", "=", "+",
        "[", "]", "{", "}", "<", ">", "|", "\\", "/", "?",
        ":", ";", ",", ".", "'", "\"", "`",
        "€", "£", "¥", "￥", "°", "±", "×", "÷", "√", "∞", "·", "…",
        "，", "。", "、", "；", "：", "？", "！",
        "“", "”", "‘", "’", "《", "》", "【", "】", "「", "」", "（", "）",
        "★", "☆", "✓", "←", "→", "↑", "↓",
    ).distinct()

    /** 某个字母键当前生效的次级符号 / 数字。 */
    fun qwertySymbol(context: Context, letter: String): String {
        val custom = loadMap(context, KEY_QWERTY)
        return custom[letter] ?: DEFAULT_QWERTY_SYMBOLS[letter] ?: ""
    }

    fun qwertySymbols(context: Context): Map<String, String> {
        val custom = loadMap(context, KEY_QWERTY)
        return DEFAULT_QWERTY_SYMBOLS.mapValues { (letter, default) ->
            custom[letter] ?: default
        }
    }

    /** 该字母键是否被改过（用于配置界面高亮与「恢复默认」按钮的可用状态）。 */
    fun hasCustomQwertySymbol(context: Context, letter: String): Boolean =
        loadMap(context, KEY_QWERTY).containsKey(letter)

    /** 只保存用户改动过的键，未改动的键从存储里删掉，这样默认值以后调整也能跟着走。 */
    fun setQwertySymbol(context: Context, letter: String, symbol: String) {
        val symbol = symbol.trim()
        val current = loadMap(context, KEY_QWERTY).toMutableMap()
        if (symbol.isEmpty() || symbol == DEFAULT_QWERTY_SYMBOLS[letter]) {
            current.remove(letter)
        } else {
            current[letter] = symbol
        }
        saveMap(context, KEY_QWERTY, current)
    }

    fun resetQwerty(context: Context) {
        prefs(context).edit { remove(KEY_QWERTY) }
    }

    // ------------------------------------------------------------ 九宫格

    /** 九键 / 15 键每个数字键默认对应的字母串。 */
    val DEFAULT_T9_LETTERS: Map<String, String> = linkedMapOf(
        "1" to "",
        "2" to "abc",
        "3" to "def",
        "4" to "ghi",
        "5" to "jkl",
        "6" to "mno",
        "7" to "pqrs",
        "8" to "tuv",
        "9" to "wxyz",
    )

    /** 配置界面里的显示顺序。 */
    val T9_DIGITS: List<String> = DEFAULT_T9_LETTERS.keys.toList()

    fun t9Letters(context: Context, digit: String): String {
        val custom = loadMap(context, KEY_T9)
        return custom[digit] ?: DEFAULT_T9_LETTERS[digit] ?: ""
    }

    fun t9Letters(context: Context): Map<String, String> {
        val custom = loadMap(context, KEY_T9)
        return DEFAULT_T9_LETTERS.mapValues { (digit, default) -> custom[digit] ?: default }
    }

    fun setT9Letters(context: Context, digit: String, letters: String) {
        val letters = letters.trim()
        val current = loadMap(context, KEY_T9).toMutableMap()
        if (letters == (DEFAULT_T9_LETTERS[digit] ?: "")) {
            current.remove(digit)
        } else {
            current[digit] = letters
        }
        saveMap(context, KEY_T9, current)
    }

    fun resetT9(context: Context) {
        prefs(context).edit { remove(KEY_T9) }
    }

    // ------------------------------------------------------------ 气泡开关

    /**
     * 是否在长按 / 上滑时弹出按键气泡。
     *
     * 关闭后回到旧行为：长按（或上滑，取决于「符号 / 数字输入方式」）直接上屏符号 / 数字。
     */
    fun isBubbleEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_BUBBLE_ENABLED, true)

    fun setBubbleEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit { putBoolean(KEY_BUBBLE_ENABLED, enabled) }
    }

    // ------------------------------------------------------------ 气泡内容

    /**
     * 26 键字母键的气泡内容：小写字母 → 次级符号 / 数字 → 大写字母。
     *
     * 顺序即气泡里从左到右的顺序，默认停在第一项（字母本身），
     * 所以长按不动再抬手就是输入该字母，不会误上屏符号。
     */
    fun qwertyBubbleItems(context: Context, letter: String): List<KeyBubbleItem> {
        val symbol = qwertySymbol(context, letter)
        return buildList {
            add(KeyBubbleItem(letter.lowercase(), KeyboardAction.KeySequenceAction(letter)))
            if (symbol.isNotEmpty()) {
                add(KeyBubbleItem(symbol, KeyboardAction.CommitAction(symbol)))
            }
            add(KeyBubbleItem(letter.uppercase(), KeyboardAction.KeySequenceAction(letter.uppercase())))
        }
    }

    /** 九宫格 / 15 键的气泡内容：数字 → 该键下的每个字母。 */
    fun t9BubbleItems(context: Context, digit: String, letters: String): List<KeyBubbleItem> {
        return buildList {
            add(KeyBubbleItem(digit, KeyboardAction.CommitAction(digit)))
            letters.forEach { ch ->
                add(KeyBubbleItem(ch.toString(), KeyboardAction.KeySequenceAction(ch.toString())))
            }
        }
    }

    // ------------------------------------------------------------ 存取

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** 存储格式：`键\u001F值\u001E键\u001F值…`（记录之间用 `\u001E`）。 */
    private fun loadMap(context: Context, key: String): Map<String, String> {
        val raw = prefs(context).getString(key, null) ?: return emptyMap()
        if (raw.isEmpty()) return emptyMap()
        return raw.split('\u001E').mapNotNull { record ->
            val parts = record.split(SEPARATOR)
            if (parts.size != 2 || parts[0].isEmpty()) null else parts[0] to parts[1]
        }.toMap()
    }

    private fun saveMap(context: Context, key: String, map: Map<String, String>) {
        if (map.isEmpty()) {
            prefs(context).edit { remove(key) }
            return
        }
        val raw = map.entries.joinToString("\u001E") { (k, v) -> "$k$SEPARATOR$v" }
        prefs(context).edit { putString(key, raw) }
    }
}
