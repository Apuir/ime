package com.ninthsoft.ime

import com.ninthsoft.ime.base.phrase.PhraseIndex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer

/**
 * 短语索引（路径 1）的纯逻辑回归。
 *
 * 这份索引实测有 **60 万个 key / 64 万条记录**，装 `HashMap` 要上百 MB 堆，
 * 所以实现是「整块字节 mmap + 偏移表二分」。二分成立的前提是**文件按 key 升序**，
 * 而这里所有断言都建立在同一个查询语义上：**最长匹配优先**。
 *
 * 「最长匹配」是最容易写错、也最容易被忽略的一条：如果按由短到长匹配，
 * 打「床前明月光」会先命中「床前明月」的前缀补全，永远出不来「疑是地上霜」。
 */
class PhraseIndexTest {

    /**
     * 测试表按 **key 升序**给出（含一句有意加的「雪白 → 雪白雪白」，
     * 它是 key 同时是 value 前缀与后缀的叠词，用来压 `endsWith` 过滤那条分支）。
     */
    private val tsv = listOf(
        "一心\t一心一意\t50",
        "一心一\t一心一意\t100",
        "床前明月光\t疑是地上霜\t80",
        "画蛇添\t画蛇添足\t90",
        "竹篮\t竹篮打水一场空\t40",
        "竹篮打水\t一场空\t70",
        "竹篮打水\t枉费功\t60",
        "雪白\t雪白雪白\t30",
    )

    private fun indexOf(lines: List<String>): PhraseIndex =
        PhraseIndex.build(
            ByteBuffer.wrap(lines.joinToString("\n").toByteArray(Charsets.UTF_8))
        )

    private fun valuesOf(context: String, maxResults: Int = PhraseIndex.MAX_RESULTS): List<String> =
        indexOf(tsv).query(context, maxResults).map { it.value }

    @Test
    fun longestMatchWins() {
        // 「竹篮打水」既是 key「竹篮打水」的开头，也包含 key「竹篮」；
        // 必须先给出配对（一场空），而不是更短 key 的补全
        val values = valuesOf("他竹篮打水")
        assertEquals(listOf("一场空", "枉费功"), values)
    }

    @Test
    fun poemPairIsCompleted() {
        assertEquals(listOf("疑是地上霜"), valuesOf("静夜思里那句床前明月光"))
    }

    @Test
    fun idiomPrefixIsCompleted() {
        assertEquals(listOf("画蛇添足"), valuesOf("他画蛇添"))
    }

    /** 已经打出来的内容不能再推一遍：叠词「雪白雪白」的 key「雪白」会命中，但必须被过滤。 */
    @Test
    fun alreadyTypedValueIsNotOffered() {
        assertTrue(valuesOf("地上雪白雪白").isEmpty())
        // 只打到「雪白」时照常补全
        assertEquals(listOf("雪白雪白"), valuesOf("地上雪白"))
    }

    @Test
    fun multipleValuesForKeyKeepWeightOrder() {
        val suggestions = indexOf(tsv).query("竹篮打水")
        assertEquals(listOf("一场空", "枉费功"), suggestions.map { it.value })
        assertEquals(70, suggestions[0].weight)
        assertEquals(60, suggestions[1].weight)
    }

    @Test
    fun unrelatedContextReturnsNothing() {
        assertTrue(valuesOf("完全不相干的一句话").isEmpty())
        assertTrue(valuesOf("").isEmpty())
        // 单字尾部不查：单字 key 是噪声，最短从 2 字起
        assertTrue(valuesOf("好").isEmpty())
    }

    @Test
    fun maxResultsIsHonoured() {
        assertEquals(1, valuesOf("他竹篮打水", maxResults = 1).size)
        assertTrue(valuesOf("他竹篮打水", maxResults = 0).isEmpty())
    }

    /** 格式损坏的行只丢自己，不能让整份索引不可用。表按 key 升序，二分才成立。 */
    @Test
    fun malformedLinesAreSkipped() {
        val broken = listOf(
            "一心一\t一心一意\t100",
            "三字\t三字经\t20",
            "坏\t坏\t10",              // key == value：前缀补全里不合法
            "两列\t没有权重",
            "权重非数字\t坏行\tabc",
            "空值\t\t10",              // value 为空
        )
        val index = indexOf(broken)
        assertEquals(listOf("一心一意"), index.query("一心一").map { it.value })
        assertEquals(listOf("三字经"), index.query("三字").map { it.value })
    }

    @Test
    fun recordCountIgnoresEmptyLines() {
        val withBlanks = tsv + listOf("", "", "")
        assertEquals(tsv.size, indexOf(withBlanks).recordCount)
    }

    @Test
    fun keyLengthBoundsAreEnforced() {
        val index = indexOf(listOf("单\t单字补全\t10", "双字\t双字补全\t20"))
        assertTrue("单字 key 必须被拒绝", index.query("单").isEmpty())
        assertEquals(listOf("双字补全"), index.query("双字").map { it.value })
    }

    /** 越界的 key 长度在构建脚本里就不该出现，但真出现了也只能丢，不能崩。 */
    @Test
    fun oversizedKeysAreIgnored() {
        val longKey = "字".repeat(PhraseIndex.MAX_KEY_CHARS + 1)
        val index = indexOf(listOf("双字\t双字补全\t20", "$longKey\t太长的键\t10"))
        assertTrue(index.query(longKey).isEmpty())
        assertEquals(listOf("双字补全"), index.query("双字").map { it.value })
    }

    @Test
    fun emptyInputIsUsable() {
        val index = PhraseIndex.build(ByteBuffer.wrap(ByteArray(0)))
        assertEquals(0, index.recordCount)
        assertTrue(index.query("随便").isEmpty())
    }
}
