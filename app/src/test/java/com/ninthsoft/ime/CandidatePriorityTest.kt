package com.ninthsoft.ime

import com.ninthsoft.ime.base.priority.CandidateFeature
import com.ninthsoft.ime.base.priority.PriorityCalculator
import com.ninthsoft.ime.base.priority.WeightConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 候选打分的取向回归。
 *
 * 这组断言钉住的是本次改造的**设计取向**：以引擎（Rime）自己的排序为主，
 * 用户偏好可以明显撼动它，语法模型与词长只做微调。
 *
 * 改造前的问题：`baseScore` 权重 0.5 却恒传 0、词长权重 0.1 且无位次先验，
 * 于是「长词无条件抬前 + 引擎排序被整段打乱」。
 *
 * 注意 `wordLength` 现在**只有「联想」下一词在传**：方案候选的字长排序已经交给
 * `CandidateGrouping` 显式负责（分组之后同组同长，传了也不影响次序），见 `CandidateGroupingTest`。
 */
class CandidatePriorityTest {

    private val calculator = PriorityCalculator()
    private val weights = WeightConfig()

    private fun score(
        rank: Int = 0,
        rankSpan: Int = 24,
        preference: Double = 0.0,
        wordLength: Int = 2,
        baseScore: Double = 0.0,
    ) = calculator.calculate(
        CandidateFeature(
            baseScore = baseScore,
            preference = preference,
            wordLength = wordLength,
            rank = rank,
            rankSpan = rankSpan,
        ),
        weights,
    )

    @Test
    fun earlierRankScoresHigher() {
        assertTrue(score(rank = 1) > score(rank = 2))
        assertTrue(score(rank = 2) > score(rank = 20))
    }

    /** 词长是微调项：长词不能靠字数压过引擎的位次差距。 */
    @Test
    fun wordLengthCannotOverrideBigRankGap() {
        val longButLate = score(rank = 24, wordLength = 8)
        val shortButEarly = score(rank = 1, wordLength = 1)
        assertTrue(
            "7 字长词不该压过位次更靠前的单字候选",
            shortButEarly > longButLate,
        )
    }

    /** 用户偏好是强信号：打满的正偏好应当能顶掉约 10 个位次。 */
    @Test
    fun strongPreferenceBeatsModerateRankGap() {
        val preferredLate = score(rank = 11, preference = PriorityCalculator.PREFERENCE_SCALE)
        val plainEarly = score(rank = 1, preference = 0.0)
        assertTrue("常用的词应该能上移", preferredLate > plainEarly)
    }

    /** 反向：被打满的负偏好应当把候选压到 10 位之后。 */
    @Test
    fun strongNegativePreferencePushesCandidateDown() {
        val demotedEarly = score(rank = 1, preference = -PriorityCalculator.PREFERENCE_SCALE)
        val neutralLate = score(rank = 11, preference = 0.0)
        assertTrue("误选过的词应该往下掉", neutralLate > demotedEarly)
    }

    @Test
    fun negativePreferenceIsWorseThanNoPreferenceAtSameRank() {
        assertTrue(score(rank = 5, preference = -2.0) < score(rank = 5, preference = 0.0))
    }

    @Test
    fun grammarScoreRaisesScore() {
        assertTrue(score(baseScore = 12.0) > score(baseScore = 0.0))
    }

    @Test
    fun rankSpanZeroDoesNotCrash() {
        val value = score(rank = 3, rankSpan = 0)
        assertTrue(value.isFinite())
    }

    @Test(expected = IllegalArgumentException::class)
    fun negativeWeightIsRejected() {
        WeightConfig(rankPrior = -1.0)
    }

    @Test(expected = IllegalArgumentException::class)
    fun allZeroWeightsAreRejected() {
        WeightConfig(baseScore = 0.0, preference = 0.0, rankPrior = 0.0, wordLength = 0.0)
    }

    @Test
    fun defaultWeightsFavourEngineOrder() {
        // 位次先验应当是默认权重里最大的一项：改造后「引擎排序为主」。
        assertEquals(true, weights.rankPrior > weights.preference)
        assertEquals(true, weights.rankPrior > weights.baseScore)
        assertEquals(true, weights.rankPrior > weights.wordLength)
        assertEquals(1.0, weights.baseScore + weights.preference + weights.rankPrior + weights.wordLength, 1e-9)
    }
}
