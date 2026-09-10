package com.ninthsoft.ime.data

/**
 * 成对符号表：key 为前半（左括号 / 左引号），value 为需要自动补齐的后半。
 *
 * 只收录中文/全角引号与各类括号；**不含半角直引号 `'` `"`**，
 * 避免 don't、it's 这类英文输入被补成 don''t。
 */
object SymbolPair {

    private val PAIRS: Map<String, String> = mapOf(
        // 半角括号
        "(" to ")",
        "[" to "]",
        "{" to "}",
        "<" to ">",
        // 全角括号
        "（" to "）",
        "［" to "］",
        "｛" to "｝",
        "＜" to "＞",
        // 书名号 / 中文括号
        "《" to "》",
        "〈" to "〉",
        "「" to "」",
        "『" to "』",
        "【" to "】",
        "〔" to "〕",
        "〖" to "〗",
        "〘" to "〙",
        "〚" to "〛",
        "﹁" to "﹂",
        "﹃" to "﹄",
        "︵" to "︶",
        "︷" to "︸",
        "︹" to "︺",
        "︻" to "︼",
        "︽" to "︾",
        "︿" to "﹀",
        "⌜" to "⌝",
        "⌞" to "⌟",
        "⦅" to "⦆",
        "⦃" to "⦄",
        "❨" to "❩",
        "❲" to "❳",
        "❴" to "❵",
        // 中文 / 全角引号（含书名号式引号）
        "“" to "”",
        "‘" to "’",
        "‹" to "›",
        "«" to "»",
        "❛" to "❜",
        "❝" to "❞",
    )

    /** 若 [open] 是需要补齐的前半符号，返回后半，否则返回 null。 */
    fun closeFor(open: String): String? = PAIRS[open]
}
