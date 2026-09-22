package com.ninthsoft.ime

import com.ninthsoft.ime.base.phrase.PhraseIndex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.util.zip.GZIPInputStream

/**
 * 用**真实随包资产**跑一遍 [PhraseIndex]。
 *
 * [PhraseIndexTest] 用的是合成小表，能压住边界；但它在两件事上没有说服力：
 * 一是「按字节二分」在 64 万条真实记录上是否真的成立（依赖构建侧确实按 key 升序输出），
 * 二是验收要的那几条具体结果是不是真能出来。
 *
 * 这里直接把 `assets/phrase/phrase_index.tsv.gz` 解压后喂给同一份实现，
 * 等于在 JVM 上复现了端侧的查询路径。资产不存在时跳过（`assumeTrue`），
 * 不让裁剪包/精简 checkout 变成红。
 */
class PhraseIndexRealDataTest {

    private val index: PhraseIndex? by lazy {
        val candidates = listOf(
            File("src/main/assets/phrase/phrase_index.tsv.gz"),
            File("app/src/main/assets/phrase/phrase_index.tsv.gz"),
        )
        val asset = candidates.firstOrNull { it.isFile } ?: return@lazy null
        val bytes = GZIPInputStream(asset.inputStream().buffered(BUFFER_SIZE)).use { it.readBytes() }
        PhraseIndex.build(ByteBuffer.wrap(bytes))
    }

    private fun values(context: String): List<String> =
        index!!.query(context).map { it.value }

    @Test
    fun assetLoadsAndHasSubstantialContent() {
        assumeTrue("未随包短语索引，跳过", index != null)
        assertTrue("索引记录数异常：${index!!.recordCount}", index!!.recordCount > 100_000)
    }

    /** 验收条件一：诗句「上句→下句」。 */
    @Test
    fun poemPairIsCompleted() {
        assumeTrue(index != null)
        assertEquals(listOf("疑是地上霜"), values("床前明月光"))
    }

    /**
     * 验收条件二：成语前缀补全。
     *
     * 断言「首位是它」而不是「只有它」：一个前缀同时是好几个成语的前缀很正常
     * （`一心一` 就有 一心一意 / 一心一力 / 一心一德 …），
     * 按权重降序把它们都给出来正是想要的行为。
     */
    @Test
    fun idiomPrefixesAreCompleted() {
        assumeTrue(index != null)
        assertEquals("一心一意", values("一心一").firstOrNull())
        assertEquals("画蛇添足", values("画蛇添").firstOrNull())
        assertEquals("守株待兔", values("守株待").firstOrNull())
    }

    /** 验收条件三：歇后语「上句→下句」。 */
    @Test
    fun xiehouyuRiddlesAreAnswered() {
        assumeTrue(index != null)
        assertTrue(
            "竹篮打水 应给出含「一场空」的答案，实际 ${values("竹篮打水")}",
            values("竹篮打水").any { it.contains("一场空") },
        )
        assertTrue(
            "哑巴吃黄连 应给出含「有苦说不出」的答案，实际 ${values("哑巴吃黄连")}",
            values("哑巴吃黄连").any { it.contains("有苦说不出") },
        )
    }

    /**
     * 二分成立的前提：文件确实按 key 升序。这里抽样逐条核对相邻记录的 key 字节序 ——
     * 一旦构建侧改了排序，端侧会**静默地查不到**（不报错），只有这条断言能提前发现。
     */
    @Test
    fun keysAreSortedSoBinarySearchIsValid() {
        assumeTrue(index != null)
        val asset = listOf(
            File("src/main/assets/phrase/phrase_index.tsv.gz"),
            File("app/src/main/assets/phrase/phrase_index.tsv.gz"),
        ).first { it.isFile }

        var previous: ByteArray? = null
        var checked = 0
        GZIPInputStream(asset.inputStream().buffered(BUFFER_SIZE)).bufferedReader().useLines { lines ->
            for (line in lines) {
                if (line.isEmpty()) continue
                val key = line.substringBefore('\t').toByteArray(Charsets.UTF_8)
                val last = previous
                if (last != null && compareBytes(last, key) > 0) {
                    throw AssertionError(
                        "第 $checked 行 key 逆序：${String(last)} > ${String(key)}；" +
                            "按字节二分要求文件按 key 升序"
                    )
                }
                previous = key
                checked++
            }
        }
        assertTrue("应检查到足量记录，实际 $checked", checked > 100_000)
    }

    private fun compareBytes(a: ByteArray, b: ByteArray): Int {
        val limit = minOf(a.size, b.size)
        for (index in 0 until limit) {
            val left = a[index].toInt() and 0xFF
            val right = b[index].toInt() and 0xFF
            if (left != right) return left - right
        }
        return a.size - b.size
    }

    private companion object {
        const val BUFFER_SIZE = 64 * 1024
    }
}
