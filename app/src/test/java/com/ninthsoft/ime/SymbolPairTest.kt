package com.ninthsoft.ime

import com.ninthsoft.ime.data.Symbol
import com.ninthsoft.ime.data.SymbolPair
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SymbolPairTest {

    @Test
    fun chineseQuotesAndBracketsArePaired() {
        assertEquals("）", SymbolPair.closeFor("（"))
        assertEquals("】", SymbolPair.closeFor("【"))
        assertEquals("》", SymbolPair.closeFor("《"))
        assertEquals("」", SymbolPair.closeFor("「"))
        assertEquals("”", SymbolPair.closeFor("“"))
        assertEquals("’", SymbolPair.closeFor("‘"))
    }

    @Test
    fun asciiBracketsArePaired() {
        assertEquals(")", SymbolPair.closeFor("("))
        assertEquals("]", SymbolPair.closeFor("["))
        assertEquals("}", SymbolPair.closeFor("{"))
        assertEquals(">", SymbolPair.closeFor("<"))
    }

    @Test
    fun straightQuotesAndClosingSymbolsAreNotPaired() {
        assertNull(SymbolPair.closeFor("'"))
        assertNull(SymbolPair.closeFor("\""))
        assertNull(SymbolPair.closeFor("）"))
        assertNull(SymbolPair.closeFor("，"))
        assertNull(SymbolPair.closeFor("a"))
    }

    @Test
    fun symbolCategoriesOrderMatchesDesign() {
        assertEquals(
            listOf("最近", "中文", "英文", "数学", "序号", "括号", "箭头", "全角", "其他"),
            Symbol.Symbol.map { it.first.label },
        )
        // 「最近」是动态内容，静态数组留空占位
        assertTrue(Symbol.Symbol.first().second.isEmpty())
        // 「数学」里不再包含 0-9
        val math = Symbol.Symbol.first { it.first.label == "数学" }.second
        assertTrue(math.none { it.length == 1 && it[0] in '0'..'9' })
    }
}
