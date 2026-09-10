package com.ninthsoft.ime.data.manager

import android.content.Context
import androidx.core.content.edit

/**
 * 符号页「最近」分类的数据源：记录用户在符号页点过的符号，
 * 最近使用的排在最前，去重后最多保留 [MAX] 个，并持久化到 SharedPreferences。
 */
object RecentSymbolManager {

    const val LABEL = "最近"
    const val MAX = 25

    private const val PREFS_NAME = "symbol_recent"
    private const val KEY_RECENT = "recent_symbols"
    /** 符号本身基本是单字符，用不出现在符号里的控制字符做分隔。 */
    private const val SEPARATOR = "\u0001"

    /** 还没有任何记录时的兜底内容，避免「最近」页空白。 */
    val DEFAULT: List<String> = listOf(
        "，", "。", "？", "！", "、", "：", "；", "（", "）",
        "“", "”", "《", "》", "【", "】", "…", "—", "·", "～",
    )

    fun get(context: Context): List<String> {
        val stored = readRaw(context)
        return if (stored.isEmpty()) DEFAULT else stored
    }

    fun record(context: Context, symbol: String) {
        if (symbol.isEmpty()) return
        val list = readRaw(context).toMutableList()
        list.remove(symbol)
        list.add(0, symbol)
        while (list.size > MAX) list.removeAt(list.size - 1)
        prefs(context).edit { putString(KEY_RECENT, list.joinToString(SEPARATOR)) }
    }

    fun clear(context: Context) {
        prefs(context).edit { remove(KEY_RECENT) }
    }

    private fun readRaw(context: Context): List<String> {
        val raw = prefs(context).getString(KEY_RECENT, "").orEmpty()
        if (raw.isEmpty()) return emptyList()
        return raw.split(SEPARATOR).filter { it.isNotEmpty() }.take(MAX)
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
