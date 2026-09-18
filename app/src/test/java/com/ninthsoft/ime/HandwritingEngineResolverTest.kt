package com.ninthsoft.ime

import com.ninthsoft.ime.input.handwriting.HwEngineChoice
import com.ninthsoft.ime.input.handwriting.HwEngineMode
import com.ninthsoft.ime.input.handwriting.HandwritingEngineResolver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [HandwritingEngineResolver] 的回归测试。
 *
 * 这一层错了不会报错也不会崩，只会表现为「准度莫名变了」或「选了 Google 却用本地」，
 * 所以这里**穷举全部输入组合**（3 模式 × 2 GMS × 2 已下载 × 2 自检 = 24 种），
 * 而不是只挑几个看着重要的场景。
 */
class HandwritingEngineResolverTest {

    private fun decide(
        mode: HwEngineMode,
        gms: Boolean,
        downloaded: Boolean,
        verified: Boolean,
    ) = HandwritingEngineResolver.decide(mode, gms, downloaded, verified)

    @Test
    fun `LOCAL 模式在任何输入下都选本地`() {
        for (gms in listOf(true, false)) {
            for (downloaded in listOf(true, false)) {
                for (verified in listOf(true, false)) {
                    assertEquals(
                        "LOCAL 模式不该受 Google 状态影响（gms=$gms downloaded=$downloaded verified=$verified）",
                        HwEngineChoice.USE_LOCAL,
                        decide(HwEngineMode.LOCAL, gms, downloaded, verified),
                    )
                }
            }
        }
    }

    @Test
    fun `GOOGLE 模式永不自动降级到本地`() {
        // 这是 Prompt 明确要求的语义：用户点名要 Google 时，不可用就如实报不可用，
        // 由 UI 提示并提供一键切本地 —— 而不是悄悄地换引擎。
        // 一旦这里返回 USE_LOCAL，用户的显式选择就被无声推翻了。
        for (gms in listOf(true, false)) {
            for (downloaded in listOf(true, false)) {
                for (verified in listOf(true, false)) {
                    assertNotEquals(
                        "GOOGLE 模式不得返回 USE_LOCAL（gms=$gms downloaded=$downloaded verified=$verified）",
                        HwEngineChoice.USE_LOCAL,
                        decide(HwEngineMode.GOOGLE, gms, downloaded, verified),
                    )
                }
            }
        }
    }

    @Test
    fun `AUTO 模式在无 GMS 时落本地`() {
        assertEquals(
            HwEngineChoice.USE_LOCAL,
            decide(HwEngineMode.AUTO, gms = false, downloaded = true, verified = true),
        )
    }

    @Test
    fun `AUTO 模式优先 Google 只要它确认可用`() {
        assertEquals(
            HwEngineChoice.USE_GOOGLE,
            decide(HwEngineMode.AUTO, gms = true, downloaded = true, verified = true),
        )
    }

    @Test
    fun `AUTO 模式在模型未下载时先去下载`() {
        assertEquals(
            HwEngineChoice.DOWNLOAD_GOOGLE,
            decide(HwEngineMode.AUTO, gms = true, downloaded = false, verified = false),
        )
    }

    /** 决策表的行。 */
    private data class Case(
        val mode: HwEngineMode,
        val gmsAvailable: Boolean,
        val downloaded: Boolean,
        val verified: Boolean,
        val expected: HwEngineChoice,
    )

    @Test
    fun `决策表穷举全部 18 种可能组合`() {
        // 表格化的意义：这些组合是「规格」而不是「实现的当下行为」，
        // 后来改动时能一眼看出哪一格被改了意图。
        val table = listOf(
            // ---- LOCAL：不看 Google 状态 ----
            Case(HwEngineMode.LOCAL, true, true, true, HwEngineChoice.USE_LOCAL),
            Case(HwEngineMode.LOCAL, true, true, false, HwEngineChoice.USE_LOCAL),
            Case(HwEngineMode.LOCAL, true, false, false, HwEngineChoice.USE_LOCAL),
            Case(HwEngineMode.LOCAL, false, true, true, HwEngineChoice.USE_LOCAL),
            Case(HwEngineMode.LOCAL, false, true, false, HwEngineChoice.USE_LOCAL),
            Case(HwEngineMode.LOCAL, false, false, false, HwEngineChoice.USE_LOCAL),

            // ---- GOOGLE：不降级；缺 GMS 或模型残缺都报不可用 ----
            Case(HwEngineMode.GOOGLE, true, true, true, HwEngineChoice.USE_GOOGLE),
            Case(HwEngineMode.GOOGLE, true, true, false, HwEngineChoice.UNAVAILABLE),
            Case(HwEngineMode.GOOGLE, true, false, false, HwEngineChoice.DOWNLOAD_GOOGLE),
            Case(HwEngineMode.GOOGLE, false, true, true, HwEngineChoice.UNAVAILABLE),
            Case(HwEngineMode.GOOGLE, false, true, false, HwEngineChoice.UNAVAILABLE),
            Case(HwEngineMode.GOOGLE, false, false, false, HwEngineChoice.UNAVAILABLE),

            // ---- AUTO：优先 Google，不可用则静默落到本地 ----
            Case(HwEngineMode.AUTO, true, true, true, HwEngineChoice.USE_GOOGLE),
            Case(HwEngineMode.AUTO, true, true, false, HwEngineChoice.USE_LOCAL),
            Case(HwEngineMode.AUTO, true, false, false, HwEngineChoice.DOWNLOAD_GOOGLE),
            Case(HwEngineMode.AUTO, false, true, true, HwEngineChoice.USE_LOCAL),
            Case(HwEngineMode.AUTO, false, true, false, HwEngineChoice.USE_LOCAL),
            Case(HwEngineMode.AUTO, false, false, false, HwEngineChoice.USE_LOCAL),
        )

        // 顺带保证表格本身是穷举的：3 模式 × 2（GMS）× 3 种模型状态 = 18。
        // 模型状态只有三种可能 —— （已下载且自检过）/（已下载但自检没过）/（未下载）；
        // 「未下载却自检通过」在逻辑上不存在（自检要先能打开模型）。
        // 少写一格时这条会先失败，而不是让某个组合悄悄没被测到。
        assertEquals(18, table.size)
        val distinct = table.map { listOf(it.mode, it.gmsAvailable, it.downloaded, it.verified) }
        assertEquals(18, distinct.toSet().size)

        for (case in table) {
            assertEquals(
                "mode=${case.mode} gms=${case.gmsAvailable} " +
                    "downloaded=${case.downloaded} verified=${case.verified}",
                case.expected,
                decide(case.mode, case.gmsAvailable, case.downloaded, case.verified),
            )
        }
    }

    @Test
    fun `模型标记为已下载但自检不过时不去自动重下`() {
        // 「标记为已下载 + 自检不过」= 下载中断留下的残缺模型。
        // 自动反复重下不可控，所以两种模式都不走下载路径：
        // AUTO 干净地落本地，GOOGLE 如实报不可用（由设置页的「删除后重新下载」修复）。
        assertEquals(
            HwEngineChoice.USE_LOCAL,
            decide(HwEngineMode.AUTO, true, downloaded = true, verified = false),
        )
        assertEquals(
            HwEngineChoice.UNAVAILABLE,
            decide(HwEngineMode.GOOGLE, true, downloaded = true, verified = false),
        )
    }

    @Test
    fun `未下载时下载失败后 AUTO 落本地 GOOGLE 报不可用`() {
        assertEquals(
            HwEngineChoice.USE_LOCAL,
            HandwritingEngineResolver.afterDownload(HwEngineMode.AUTO, false),
        )
        assertEquals(
            HwEngineChoice.UNAVAILABLE,
            HandwritingEngineResolver.afterDownload(HwEngineMode.GOOGLE, false),
        )
    }

    @Test
    fun `下载成功后一律用 Google`() {
        for (mode in HwEngineMode.entries) {
            assertEquals(
                "mode=$mode",
                HwEngineChoice.USE_GOOGLE,
                HandwritingEngineResolver.afterDownload(mode, true),
            )
        }
    }

    @Test
    fun `HwEngineMode 的取值与偏好存储序数保持稳定`() {
        // 模式是**按 ordinal 存进偏好**的（HandwritingManager.setEngineMode），
        // 所以在枚举中间插入新值会让老用户的设置静默错位。
        // 需要新增取值时，请追加到末尾。
        assertEquals(HwEngineMode.AUTO, HwEngineMode.fromValue(0))
        assertEquals(HwEngineMode.GOOGLE, HwEngineMode.fromValue(1))
        assertEquals(HwEngineMode.LOCAL, HwEngineMode.fromValue(2))
        // 越界回落到 AUTO，而不是抛异常（偏好里的脏值不该让键盘崩）
        assertEquals(HwEngineMode.AUTO, HwEngineMode.fromValue(99))
        assertEquals(HwEngineMode.AUTO, HwEngineMode.fromValue(-1))
        assertTrue(HwEngineMode.entries.size == 3)
    }
}
