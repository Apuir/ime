package com.ninthsoft.ime

import com.ninthsoft.ime.data.manager.KeyboardKeyMapping
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 26 键字母键符号映射 / 九键字母映射的默认值回归。
 *
 * 这些默认值就是旧版写死在 `QwertyKeyboard` / `T9Keyboard` 里的那套键位，
 * 一旦被改动，老用户的默认手感就变了，所以这里钉住。
 */
class KeyMappingDefaultsTest {

    @Test
    fun qwertyCoversEveryLetterExactlyOnce() {
        val symbols = KeyboardKeyMapping.DEFAULT_QWERTY_SYMBOLS

        assertEquals(26, symbols.size)
        // 键盘上出现的字母与映射表必须完全一致，缺一个键就会回落成空符号。
        assertEquals(
            "abcdefghijklmnopqrstuvwxyz",
            symbols.keys.sorted().joinToString(""),
        )
        assertEquals(
            "abcdefghijklmnopqrstuvwxyz",
            KeyboardKeyMapping.QWERTY_LETTERS.sorted().joinToString(""),
        )
    }

    @Test
    fun qwertyDefaultsMatchTheLegacyHardcodedLayout() {
        val expected = mapOf(
            "q" to "1", "w" to "2", "e" to "3", "r" to "4", "t" to "5",
            "y" to "6", "u" to "7", "i" to "8", "o" to "9", "p" to "0",
            "a" to "~", "s" to "!", "d" to "@", "f" to "#", "g" to "$",
            "h" to "%", "j" to "^", "k" to "&", "l" to "*",
            "z" to "(", "x" to ")", "c" to ":", "v" to ";", "b" to ",",
            "n" to "?", "m" to "/",
        )

        assertEquals(expected, KeyboardKeyMapping.DEFAULT_QWERTY_SYMBOLS)
    }

    @Test
    fun t9DefaultsMatchTheLegacyLetterGroups() {
        val expected = mapOf(
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

        assertEquals(expected, KeyboardKeyMapping.DEFAULT_T9_LETTERS)
        assertEquals(
            KeyboardKeyMapping.T9_DIGITS.toSet(),
            expected.keys.toSet(),
        )
    }

    @Test
    fun qwertyRowsFollowTheKeyboardOrder() {
        // 配置界面的预览按三行排版，顺序必须和真实键盘一致。
        assertEquals(
            listOf(
                "qwertyuiop",
                "asdfghjkl",
                "zxcvbnm",
            ),
            listOf(
                KeyboardKeyMapping.QWERTY_LETTERS.take(10).joinToString(""),
                KeyboardKeyMapping.QWERTY_LETTERS.subList(10, 19).joinToString(""),
                KeyboardKeyMapping.QWERTY_LETTERS.drop(19).joinToString(""),
            ),
        )
    }

    @Test
    fun symbolPoolEntriesAreTypableInOneKeyBubbleItem() {
        // 气泡每一项都是一个 TextView，过长的串会把它撑变形；常用项都是 1~2 个字符。
        KeyboardKeyMapping.SYMBOL_POOL.forEach { symbol ->
            assertTrue("符号池里有空项", symbol.isNotEmpty())
            assertTrue("符号池里有过长的项：$symbol", symbol.length <= 2)
        }
        // 默认映射里的符号都必须在备选池里，否则用户改完想改回来找不到它。
        KeyboardKeyMapping.DEFAULT_QWERTY_SYMBOLS.values.forEach { symbol ->
            assertTrue("默认符号 $symbol 不在符号池里", symbol in KeyboardKeyMapping.SYMBOL_POOL)
        }
    }
}
