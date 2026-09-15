package com.ninthsoft.ime

import com.ninthsoft.ime.base.priority.PreferenceScorer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「误选降权」的纯逻辑回归。
 *
 * 这一组断言对应需求：「手快选错了候选，删掉重打时，刚才那个错词应该降权」。
 * 关键性质是**净分可负** —— 改造前 app 只记 `click_count`（只增不减），
 * 错词会被自己的误操作永久顶在候选前面。
 */
class PreferenceScorerTest {

    private val day = PreferenceScorer.HALF_LIFE_MS
    private val now = 1_800_000_000_000L

    @Test
    fun noHistoryScoresZero() {
        assertEquals(
            0.0,
            PreferenceScorer.score(
                clickCount = 0, lastGoodAt = 0L, badCount = 0, lastBadAt = 0L, now = now
            ),
            1e-9,
        )
    }

    @Test
    fun selectingOnceScoresPositive() {
        val score = PreferenceScorer.score(
            clickCount = 1, lastGoodAt = now, badCount = 0, lastBadAt = 0L, now = now
        )
        assertTrue("选过一次应为正分，实际 $score", score > 0.0)
    }

    /** 核心回归：选错一次、马上删掉，净分必须转负。 */
    @Test
    fun selectThenUndoScoresNegative() {
        val score = PreferenceScorer.score(
            clickCount = 1, lastGoodAt = now, badCount = 1, lastBadAt = now, now = now
        )
        assertTrue("选一次 + 误选一次应当为负分，实际 $score", score < 0.0)
    }

    /** 连续误选会更深，但受负向封顶保护，不会无限下沉。 */
    @Test
    fun repeatedUndoIsCapped() {
        val score = PreferenceScorer.score(
            clickCount = 0, lastGoodAt = 0L, badCount = 100, lastBadAt = now, now = now
        )
        assertEquals(-PreferenceScorer.NEGATIVE_CAP, score, 1e-9)
    }

    /** 常用词也不会被无限抬高。 */
    @Test
    fun frequentSelectionIsCapped() {
        val score = PreferenceScorer.score(
            clickCount = 100_000, lastGoodAt = now, badCount = 0, lastBadAt = 0L, now = now
        )
        assertEquals(PreferenceScorer.POSITIVE_CAP, score, 1e-9)
    }

    @Test
    fun decayHalvesAfterOneHalfLife() {
        val fresh = PreferenceScorer.score(
            clickCount = 8, lastGoodAt = now, badCount = 0, lastBadAt = 0L, now = now
        )
        val aged = PreferenceScorer.score(
            clickCount = 8, lastGoodAt = now - day, badCount = 0, lastBadAt = 0L, now = now
        )
        assertEquals(fresh / 2.0, aged, 1e-6)
    }

    /** 惩罚必须会过期：7 个半衰期后残值 < 1%，等价于清除。 */
    @Test
    fun penaltyFadesAwayAfterAWeek() {
        val residual = PreferenceScorer.decay(7 * day)
        assertTrue("7 天后残值应 < 1%，实际 $residual", residual < 0.01)

        val score = PreferenceScorer.score(
            clickCount = 0, lastGoodAt = 0L, badCount = 3, lastBadAt = now - 7 * day, now = now
        )
        assertTrue("一周前的误选不该还压着候选，实际 $score", score > -0.05)
    }

    /** 时钟回拨 / 未来时间戳不能产出 >1 的衰减系数。 */
    @Test
    fun futureTimestampDoesNotAmplify() {
        assertEquals(1.0, PreferenceScorer.decay(-999_999L), 1e-9)
        val score = PreferenceScorer.score(
            clickCount = 10, lastGoodAt = now + day, badCount = 0, lastBadAt = 0L, now = now
        )
        assertTrue(score <= PreferenceScorer.POSITIVE_CAP)
    }

    @Test
    fun decayIsMonotonic() {
        var previous = Double.MAX_VALUE
        for (age in listOf(0L, day / 4, day / 2, day, 3 * day, 10 * day)) {
            val value = PreferenceScorer.decay(age)
            assertTrue("衰减应当单调不增", value <= previous)
            previous = value
        }
    }
}
