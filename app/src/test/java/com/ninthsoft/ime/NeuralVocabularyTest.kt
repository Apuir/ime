package com.ninthsoft.ime

import com.ninthsoft.ime.base.neural.CharVocabulary
import com.ninthsoft.ime.base.neural.WordVocabulary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 输入字表 / 输出词表的纯逻辑回归。
 *
 * 两个方向各有一条容易错的性质：
 *
 * - **字表按码点编码**：emoji 是代理对，按 `Char` 编码会把它劈成两个都查不到的半截字符；
 * - **词表行号即 id**：神经 logits 的下标直接映射到这个列表，错一位就会「预测出别的词」，
 *   而且不会报错。所以行号语义与 `<unk>` 位置必须钉死。
 */
class NeuralVocabularyTest {

    @Test
    fun wordVocabularyUsesLineIndexAsId() {
        val vocabulary = WordVocabulary.parse(
            sequenceOf(WordVocabulary.UNKNOWN_TOKEN, "今天", "天气", "不错")
        )
        assertEquals(4, vocabulary.size)
        assertEquals("今天", vocabulary[1])
        assertEquals(3, vocabulary.idOf("不错"))
        assertEquals(WordVocabulary.UNKNOWN_TOKEN, vocabulary[0])
    }

    /** 不在词表里要返回 -1，让调用方区分「没有神经信号」与「概率很小」。 */
    @Test
    fun unknownWordIsReportedAsUnknownId() {
        val vocabulary = WordVocabulary.parse(
            sequenceOf(WordVocabulary.UNKNOWN_TOKEN, "今天")
        )
        assertEquals(WordVocabulary.UNKNOWN_ID, vocabulary.idOf("明天"))
        assertTrue(vocabulary.idOf("明天") < 0)
    }

    @Test
    fun outOfRangeLookupIsEmptyNotCrash() {
        val vocabulary = WordVocabulary.parse(sequenceOf(WordVocabulary.UNKNOWN_TOKEN, "今天"))
        assertEquals("", vocabulary[99])
        assertEquals("", vocabulary[-1])
    }

    /** 同词多 id 时保留最小的：结果与文件里重复项的顺序无关。 */
    @Test
    fun duplicateWordsResolveToTheSmallestId() {
        val vocabulary = WordVocabulary.parse(
            sequenceOf(WordVocabulary.UNKNOWN_TOKEN, "今天", "天气", "今天")
        )
        assertEquals(1, vocabulary.idOf("今天"))
    }

    @Test
    fun trailingCarriageReturnIsTolerated() {
        val vocabulary = WordVocabulary.parse(sequenceOf("<unk>", "今天\r"))
        assertEquals("今天", vocabulary[1])
    }

    private val chars = CharVocabulary.parse("""{"<unk>":0,"你":5,"好":6}""")

    @Test
    fun charVocabularyEncodesPerCodePoint() {
        assertEquals(0, chars.unknownId)
        assertArrayEqualsInt(intArrayOf(5, 6), chars.encode("你好"))
        assertArrayEqualsInt(intArrayOf(0), chars.encode("x"))
        assertArrayEqualsInt(intArrayOf(5, 0, 6), chars.encode("你x好"))
        assertArrayEqualsInt(IntArray(0), chars.encode(""))
    }

    /** emoji 是增补平面字符：必须整体查表一次，而不是拆成两个代理字符。 */
    @Test
    fun surrogatePairsAreEncodedAsOneUnknown() {
        assertArrayEqualsInt(intArrayOf(0), chars.encode("🙂"))
        assertArrayEqualsInt(intArrayOf(5, 0, 6), chars.encode("你🙂好"))
    }

    @Test
    fun charVocabularyWithoutUnkStillWorks() {
        val withoutUnk = CharVocabulary.parse("""{"你":1}""")
        assertEquals(0, withoutUnk.unknownId)
        assertArrayEqualsInt(intArrayOf(0), withoutUnk.encode("好"))
    }

    private fun assertArrayEqualsInt(expected: IntArray, actual: IntArray) {
        assertEquals(expected.joinToString(","), actual.joinToString(","))
    }
}
