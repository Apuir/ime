package com.ninthsoft.ime

import com.ninthsoft.ime.engine.data.EngineMessage.Candidate
import com.ninthsoft.ime.engine.manager.CandidateGrouping
import com.ninthsoft.ime.engine.manager.GroupingConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 候选字长分组与分批的回归。
 *
 * 钉住五条约定：
 *
 * 1. 引擎的首选（`candidates[0]`）固定在最前 —— 它是空格上屏的目标，不能因为分组而换人；
 * 2. 其余候选按 4 字 → 5 字及以上 → 3 字 → 2 字 → 1 字排列，组内保持引擎顺序；
 * 3. 每批要凑够 [GroupingConfig.batchSize] 条：2~4 字组每组给
 *    [GroupingConfig.multiCharStep] 条、5 字及以上组给 [GroupingConfig.longStep] 条，
 *    **凑不满的部分全部由单字组补足**（所以单字越多、批次越满，短词也永远在最后）；
 * 4. 批次往后推进时结果**只在末尾追加**（前缀完全一致），否则用户划到底等来的新候选
 *    会插在他头顶，等于白要一批；
 * 5. 「这一批凑满了没有」要能被问出来 —— 引擎把长词排得很前，候选池不挖深就凑不满。
 */
class CandidateGroupingTest {

    private val config = GroupingConfig()

    /**
     * 造一个长度为 [length] 个码点、且**按 [seed] 唯一**的文本。
     *
     * 用「汉字区起点 + seed 的线性映射」而不是循环取字，是为了避免不同 seed 撞成同一个词 ——
     * 测试里需要靠文本区分候选来自哪一位。
     */
    private fun text(length: Int, seed: Int): String =
        (0 until length).joinToString("") { Char(0x4E00 + seed * 31 + it).toString() }

    /** 一段引擎顺序的候选池，列表位置即引擎位次（也就是常用度位次）。 */
    private fun pool(vararg lengths: Int): List<Candidate> =
        lengths.mapIndexed { position, length ->
            Candidate(index = position, text = text(length, position))
        }

    /** 长度为 [length]、共 [count] 条的同长候选池。 */
    private fun sameLength(length: Int, count: Int): List<Candidate> =
        pool(*IntArray(count) { length })

    private fun texts(candidates: List<Candidate>): List<String> = candidates.map { it.text }

    // ── 引擎首选固定在最前 ──

    /**
     * 第 0 个候选是空格上屏的目标（见 `CandidateRerankManager` 的「第 0 个不动」），
     * 分组不能把它挪到后面去，否则用户看到的「1.」和空格打出来的不是同一个词。
     */
    @Test
    fun engineFirstCandidateStaysAtFront() {
        val result = CandidateGrouping.apply(pool(1, 4, 3, 2), config = config)
        assertEquals(
            listOf(text(1, 0), text(4, 1), text(3, 2), text(2, 3)),
            texts(result),
        )
    }

    /** 固定在最前的那个不再参与分组，否则会在自己那一组里出现第二次。 */
    @Test
    fun pinnedCandidateIsNotRepeatedInItsGroup() {
        val result = CandidateGrouping.apply(sameLength(4, 10), config = config)
        assertEquals(texts(result).distinct().size, result.size)
        assertEquals(text(4, 0), texts(result).first())
    }

    // ── 分组顺序 ──

    @Test
    fun longerTextAlwaysComesFirst() {
        val result = CandidateGrouping.apply(pool(4, 4, 3, 2, 1), config = config)
        assertEquals(
            listOf(text(4, 0), text(4, 1), text(3, 2), text(2, 3), text(1, 4)),
            texts(result),
        )
    }

    /** 5 字以上夹在 4 字与 3 字之间：既不顶在四字词前面，也不压住三字词。 */
    @Test
    fun veryLongGroupSitsBetweenFourAndThree() {
        val result = CandidateGrouping.apply(pool(4, 2, 3, 5, 6), config = config)
        assertEquals(
            listOf(text(4, 0), text(5, 3), text(6, 4), text(3, 2), text(2, 1)),
            texts(result),
        )
    }

    /** 组内不动：引擎排好的顺序就是常用度顺序。 */
    @Test
    fun orderInsideGroupFollowsEngineRank() {
        val result = CandidateGrouping.apply(sameLength(2, 5), config = config)
        assertEquals((0..4).map { text(2, it) }, texts(result))
    }

    // ── 每批条数 ──

    @Test
    fun multiCharGroupGivesConfiguredCountPerBatch() {
        val candidates = sameLength(4, 20)
        val first = CandidateGrouping.apply(candidates, batch = 1, config = config)
        assertEquals(1 + config.multiCharStep, first.size)
        assertEquals((0..config.multiCharStep).map { text(4, it) }, texts(first))

        val second = CandidateGrouping.apply(candidates, batch = 2, config = config)
        assertEquals(1 + config.multiCharStep * 2, second.size)
        assertEquals((0..config.multiCharStep * 2).map { text(4, it) }, texts(second))
    }

    @Test
    fun longGroupGivesConfiguredCountPerBatch() {
        val candidates = sameLength(7, 20)
        val first = CandidateGrouping.apply(candidates, batch = 1, config = config)
        assertEquals(1 + config.longStep, first.size)
        assertEquals((0..config.longStep).map { text(7, it) }, texts(first))
    }

    /** 单字组没有配额，它负责把每批**补足**到 [GroupingConfig.batchSize]。 */
    @Test
    fun singleCharGroupFillsTheBatch() {
        val candidates = sameLength(1, 60)
        val first = CandidateGrouping.apply(candidates, batch = 1, config = config)
        assertEquals(1 + config.batchSize, first.size)
        assertEquals((0..config.batchSize).map { text(1, it) }, texts(first))

        // 一直往后要就能把剩下的全拿出来：单字不设总条数上限
        val second = CandidateGrouping.apply(candidates, batch = 2, config = config)
        assertEquals(60, second.size)
        assertEquals((0..59).map { text(1, it) }, texts(second))
    }

    /** 多字组各自只给配额，剩下的全由单字补 —— 一批里各组条数就是这个构成。 */
    @Test
    fun multiCharGroupsKeepQuotaAndSinglesFillTheRest() {
        // 首选那条给四字；4/3/2 字各 30 条；单字 100 条（够把一批填满）
        val candidates = pool(
            4,
            *(0 until 90).map { it % 3 + 2 }.toIntArray(),
            *IntArray(100) { 1 },
        )
        val first = CandidateGrouping.apply(candidates, batch = 1, config = config)
        val histogram = first.groupingBy { CandidateGrouping.charCount(it.text) }.eachCount()

        assertTrue("一批要凑够 ${config.batchSize} 条，实际 ${first.size}", first.size >= config.batchSize)
        assertEquals("首选那条也是四字", config.multiCharStep + 1, histogram[4])
        assertEquals(config.multiCharStep, histogram[3])
        assertEquals(config.multiCharStep, histogram[2])
        // 余下的位置全给单字
        assertEquals(config.batchSize - config.multiCharStep * 3, histogram[1])

        // 顺序仍然严格「字多的在前」
        val groups = first.map { CandidateGrouping.groupIndex(CandidateGrouping.charCount(it.text)) }
        assertEquals(groups.sorted(), groups)
    }

    /** 组里的候选不够一批时只给现有的，不会凭空补。 */
    @Test
    fun groupSmallerThanStepGivesWhatItHas() {
        val candidates = sameLength(3, 2)
        val first = CandidateGrouping.apply(candidates, batch = 1, config = config)
        assertEquals(listOf(text(3, 0), text(3, 1)), texts(first))
        assertEquals(
            texts(first),
            texts(CandidateGrouping.apply(candidates, batch = 2, config = config)),
        )
    }

    // ── 批次 ──

    /** 批次推进 = 只在末尾追加，前缀一条都不能变。 */
    @Test
    fun nextBatchOnlyAppendsAtTail() {
        // 1~4 字轮流，四种长度各 30 条
        val candidates = pool(*(0 until 120).map { it % 4 + 1 }.toIntArray())
        val first = CandidateGrouping.apply(candidates, batch = 1, config = config)
        val second = CandidateGrouping.apply(candidates, batch = 2, config = config)
        assertTrue("第二批不该比第一批少", second.size > first.size)
        assertEquals("前缀必须一致", texts(first), texts(second).take(first.size))
        assertEquals("不该有重复", second.size, texts(second).distinct().size)
    }

    /** 第二批是「各组再往后各取一批」，不是「重新挑一遍」。 */
    @Test
    fun secondBatchContinuesFromWhereFirstStopped() {
        val candidates = sameLength(4, 20)
        val first = CandidateGrouping.apply(candidates, batch = 1, config = config)
        val second = CandidateGrouping.apply(candidates, batch = 2, config = config)
        assertEquals((0..6).map { text(4, it) }, texts(first))
        assertEquals((0..12).map { text(4, it) }, texts(second))
    }

    /** 每批内部都要满足「字多的在前」：追加的那一段也得是四字词先出。 */
    @Test
    fun appendedBatchKeepsLengthOrderInsideItself() {
        val candidates = pool(*(0 until 120).map { it % 4 + 1 }.toIntArray())
        val first = CandidateGrouping.apply(candidates, batch = 1, config = config)
        val second = CandidateGrouping.apply(candidates, batch = 2, config = config)
        val appended = second.drop(first.size)
        val groups = appended.map {
            CandidateGrouping.groupIndex(CandidateGrouping.charCount(it.text))
        }
        assertEquals("追加段的组序必须单调不减", groups.sorted(), groups)
    }

    @Test
    fun hasMoreInBatchReportsWhetherNextBatchGrows() {
        val candidates = sameLength(2, 40)
        assertTrue(CandidateGrouping.hasMoreInBatch(candidates, batch = 1, config = config))
        assertFalse(CandidateGrouping.hasMoreInBatch(candidates, batch = 7, config = config))
    }

    // ── 候选池挖得够不够深 ──

    // ── 一批凑满了没有 ──

    /**
     * 引擎把长词排得很前，前 100 条里只有四字和五字以上（实测 `4444` 就是这样），
     * 那就只能凑出十来条 —— 「还没凑满」要能被问出来，`RimeEngine` 靠它决定继续挖深。
     */
    @Test
    fun reportsWhetherTheBatchIsFilled() {
        // 仿实测的浅池子：89 条四字 + 11 条五字以上，一条短词都没有
        val shallow = pool(
            *(0 until 89).map { 4 }.toIntArray(),
            *(0 until 11).map { 5 }.toIntArray(),
        )
        val shallowBatch = CandidateGrouping.apply(shallow, batch = 1, config = config)
        assertEquals("1 + 6 条四字 + 3 条五字以上", 10, shallowBatch.size)
        assertFalse(CandidateGrouping.isBatchFilled(shallow, batch = 1, config = config))

        // 挖深到短词那一层之后就能凑满
        val deep = pool(
            *(0 until 89).map { 4 }.toIntArray(),
            *(0 until 11).map { 5 }.toIntArray(),
            *IntArray(60) { 1 },
        )
        assertTrue(CandidateGrouping.isBatchFilled(deep, batch = 1, config = config))
    }

    // ── 边界 ──

    @Test
    fun emptyInputStaysEmpty() {
        assertTrue(CandidateGrouping.apply(emptyList(), config = config).isEmpty())
        assertFalse(CandidateGrouping.hasMoreInBatch(emptyList(), batch = 1, config = config))
    }

    @Test
    fun batchBelowOneIsTreatedAsFirst() {
        val candidates = pool(4, 2)
        assertEquals(
            texts(CandidateGrouping.apply(candidates, batch = 1, config = config)),
            texts(CandidateGrouping.apply(candidates, batch = 0, config = config)),
        )
    }

    /** 字长按码点算：emoji 是代理对，`String.length` 会把它数成 2 个字。 */
    @Test
    fun lengthCountsCodePointsNotChars() {
        assertEquals(2, CandidateGrouping.charCount("😀😀"))
        val result = CandidateGrouping.apply(
            listOf(Candidate(index = 0, text = text(3, 9)), Candidate(index = 1, text = "😀😀")),
            config = config,
        )
        assertEquals(listOf(text(3, 9), "😀😀"), texts(result))
    }

    @Test(expected = IllegalArgumentException::class)
    fun zeroStepIsRejected() {
        GroupingConfig(multiCharStep = 0)
    }
}
