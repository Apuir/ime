package com.ninthsoft.ime

import com.ninthsoft.ime.data.Symbol
import com.ninthsoft.ime.data.SymbolExtra
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
        // 基础 9 类保持设计顺序；SymbolExtra.Categories 的新增分类追加在它们之后。
        // 注意：`Symbol.Symbol` 只含符号分类，Emoji 在独立的 `Symbol.Emoji` 里，不参与此断言。
        val baseLabels = listOf("最近", "中文", "英文", "数学", "序号", "括号", "箭头", "全角", "其他")
        val expected = baseLabels + SymbolExtra.Categories.map { it.first.label }
        assertEquals(expected, Symbol.Symbol.map { it.first.label })
    }

    @Test
    fun symbolCategoriesAreUsable() {
        // 「最近」是动态内容，静态数组留空占位
        assertTrue(Symbol.Symbol.first().second.isEmpty())
        // 除「最近」外每个分类都要有可点符号，且分类名不重复（重复分类会让菜单出现两个同名入口）
        val labels = Symbol.Symbol.map { it.first.label }
        assertEquals(labels.size, labels.toSet().size)
        Symbol.Symbol.drop(1).forEach { (category, symbols) ->
            assertTrue("分类「${category.label}」没有任何符号", symbols.isNotEmpty())
        }
        // 同一分类内不重复（Symbol.kt 合并 SymbolExtra 后已做 distinct）
        Symbol.Symbol.forEach { (category, symbols) ->
            assertEquals(
                "分类「${category.label}」存在重复符号",
                symbols.size,
                symbols.toSet().size,
            )
        }
    }

    @Test
    fun mathCategoryHasNoAsciiDigits() {
        // 「数学」里不再包含 0-9（阿拉伯数字归数字键盘，符号页不该再出现单字符 0-9）
        val math = Symbol.Symbol.first { it.first.label == "数学" }.second
        assertTrue(math.none { it.length == 1 && it[0] in '0'..'9' })
    }
}
