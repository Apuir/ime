package com.ninthsoft.ime

import com.ninthsoft.ime.base.neural.NeuralPrompt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 神经模型上下文准备的纯逻辑回归。
 *
 * 这一组对应设计里两条硬要求：
 * 1. **上下文是一整段**（≈210 汉字），不是 n-gram 那 4 个字；
 * 2. **超出按标点截断**，且**垃圾长串不能污染 prompt**。
 *
 * 最容易被写错、也最难在真机上定位的是第 2 类：截断切在代理对中间会产出半个字符，
 * 模型只会把它当 `<unk>`，表现为「偶尔少一个字」，肉眼几乎看不出来。
 */
class NeuralPromptTest {

    @Test
    fun shortTextPassesThroughUnchanged() {
        // 没超预算时整段都是真上文，一个字符都不该丢（包括开头那半句）
        val text = "你好。今天天气不错"
        assertEquals(text, NeuralPrompt.build(text))
    }

    @Test
    fun truncationKeepsTheTail() {
        val text = "甲".repeat(300)
        val result = NeuralPrompt.build(text, maxChars = 120)
        assertEquals("甲".repeat(120), result)
    }

    /** 核心行为：窗口左端切在半句话中间时，把那个残句丢掉。 */
    @Test
    fun truncationDropsTheLeadingPartialSentence() {
        val text = "甲".repeat(100) + "。" + "乙".repeat(100) + "。" + "丙".repeat(50)
        val result = NeuralPrompt.build(text, maxChars = 120)
        // 尾部 120 字 = 「乙×69 。 丙×50」，残句「乙×69」应当被丢掉
        assertEquals("丙".repeat(50), result)
    }

    /** 整段都没有句末标点时宁可保留硬切口，也不要清空上下文。 */
    @Test
    fun hardCutWhenNoSentenceBoundaryExists() {
        val text = "丁".repeat(300)
        assertEquals("丁".repeat(120), NeuralPrompt.build(text, maxChars = 120))
    }

    @Test
    fun resultNeverExceedsBudget() {
        val text = "字".repeat(1000) + "。"
        val result = NeuralPrompt.build(text, maxChars = 210)
        assertTrue(
            "结果不应超过预算，实际 ${result.codePointCount(0, result.length)}",
            result.codePointCount(0, result.length) <= 210,
        )
    }

    /** 截断不能切在代理对中间：否则 emoji 会变成两个查不到的半截字符。 */
    @Test
    fun truncationDoesNotSplitSurrogatePairs() {
        val text = "🙂".repeat(250)
        val result = NeuralPrompt.build(text, maxChars = 120)
        assertEquals("应正好留下 120 个码点", 120, result.codePointCount(0, result.length))
        assertEquals("🙂".repeat(120), result)
    }

    @Test
    fun longAsciiRunIsStripped() {
        val url = "https://example.com/" + "a".repeat(60)
        val cleaned = NeuralPrompt.sanitize("你好${url}世界")
        assertFalse("URL 不该留在中文上文里：$cleaned", cleaned.contains("example"))
        assertTrue(cleaned.contains("你好"))
        assertTrue(cleaned.contains("世界"))
    }

    /** 边界：恰好等于上限的 ASCII 串是正常内容，超过才当垃圾。 */
    @Test
    fun asciiRunBoundaryIsExact() {
        val kept = "a".repeat(24)
        assertEquals("你好${kept}世界", NeuralPrompt.sanitize("你好${kept}世界"))
        // 剔除后补一个空格，否则两段中文会被粘成一个不存在的词
        assertEquals("你好 世界", NeuralPrompt.sanitize("你好${"a".repeat(25)}世界"))
    }

    @Test
    fun whitespaceIsCollapsed() {
        assertEquals("你好 世界", NeuralPrompt.sanitize("你好\n\n  世界 "))
    }

    @Test
    fun controlCharactersAreRemoved() {
        assertEquals("你好", NeuralPrompt.sanitize("你\u0000好"))
    }

    @Test
    fun usableRequiresCjkAndCleanEnding() {
        assertTrue(NeuralPrompt.isUsable("今天天气"))
        assertFalse("以标点结尾不预测", NeuralPrompt.isUsable("今天天气，"))
        assertFalse("以字母结尾不预测", NeuralPrompt.isUsable("今天天气a"))
        assertFalse("纯数字没有中文", NeuralPrompt.isUsable("123456"))
        assertFalse("太短不值得前向", NeuralPrompt.isUsable("好"))
    }
}
