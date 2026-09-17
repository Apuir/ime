package com.ninthsoft.ime

import com.ninthsoft.ime.input.SelectionMath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [SelectionMath.isCursorAwayFromComposition] 的边界。
 *
 * 这里钉住的是「光标离开组合区 → 结束组合」的**唯一判据**。写错的两种后果都很严重：
 *  - 太敏感（连打时误判）→ **组合每打一个字就被打断，根本打不出词**（已踩过一次）；
 *  - 太迟钝（用户点别处不判）→ 输入法挂在旧组合上，继续往旧位置后面接着输入。
 */
class SelectionMathTest {

    @Test
    fun `光标停在组合区末尾时不算离开`() {
        assertFalse(SelectionMath.isCursorAwayFromComposition(5, 5, 0, 5))
    }

    @Test
    fun `光标停在组合区起点时不算离开`() {
        assertFalse(SelectionMath.isCursorAwayFromComposition(0, 0, 0, 5))
    }

    /**
     * 回归用例：连续打拼音时，组合区从起点向后一步步扩展（n → ni → niu …），
     * 每一步的光标都必须落在区间内。
     *
     * 曾经判据比的是「预期光标位置」，且把它算成了「写入前光标位置 + 新长度」——
     * 组合区起点被当成了「上一次的落点」，于是**第二个字母起**位置就对不上，
     * 打一个字结束一次组合，表现为「打不了字，只出一个字母」。
     */
    @Test
    fun `连打拼音时每一步都不算离开`() {
        // 组合区起点固定为 0，每按一个键光标前进一位
        for (cursor in 1..8) {
            assertFalse(
                "光标 $cursor 应仍在组合区 0..$cursor 内",
                SelectionMath.isCursorAwayFromComposition(cursor, cursor, 0, cursor),
            )
        }
    }

    /**
     * 回归用例：系统回调是异步的，连打时上一拍的回调会晚到。
     * 组合区已经扩到 `0..3` 了，回来的却是上一拍的 `2` —— 也必须认定为「还没离开」。
     */
    @Test
    fun `迟到的回调落在组合区内时不算离开`() {
        assertFalse(SelectionMath.isCursorAwayFromComposition(1, 1, 0, 2))
        assertFalse(SelectionMath.isCursorAwayFromComposition(2, 2, 0, 3))
        assertFalse(SelectionMath.isCursorAwayFromComposition(0, 0, 0, 3))
    }

    @Test
    fun `光标移到组合区之外算离开`() {
        // 点到了组合区后面
        assertTrue(SelectionMath.isCursorAwayFromComposition(7, 7, 0, 5))
        // 点到了组合区前面（组合区不在行首的情况）
        assertTrue(SelectionMath.isCursorAwayFromComposition(2, 2, 3, 5))
    }

    @Test
    fun `拖出选区算离开`() {
        // 写预览时从不产生选区，出现选区就是用户自己拖的
        assertTrue(SelectionMath.isCursorAwayFromComposition(2, 4, 0, 5))
        assertTrue(SelectionMath.isCursorAwayFromComposition(1, 5, 0, 5))
    }

    @Test
    fun `没有可用组合区时一律不算离开`() {
        // 还没写过预览
        assertFalse(SelectionMath.isCursorAwayFromComposition(0, 0, -1, -1))
        // 退化区间：宿主会传 null 直接跳过判定，这里兜住区间本身不合法的情况
        assertFalse(SelectionMath.isCursorAwayFromComposition(9, 9, 5, 5))
        assertFalse(SelectionMath.isCursorAwayFromComposition(9, 9, 8, 2))
    }

    // ---------- 组合区范围的计算（就是这里出过错） ----------

    /**
     * 核心回归用例：组合区起点 = 写入前光标位置 − 上一轮预览长度。
     *
     * `setComposingText` 替换的是现有组合区，插入点是**起点**；而写入前光标停在上一轮的
     * **末尾**。当初没做这个减法，直接用「光标位置 + 新长度」，从第二个字母起范围就整体偏大，
     * 判定永远认为「用户把光标挪走了」→ 每打一个字结束一次组合。
     */
    @Test
    fun `组合区起点要减掉上一轮预览的长度`() {
        // 首次写入：光标在 0，写入 "n"
        assertEquals(0..1, SelectionMath.compositionRange(0, 0, 1))
        // 第二轮：光标落在 1，上一轮 "n" 长度 1，本轮 "ni" 长度 2 → 起点仍是 0
        assertEquals(0..2, SelectionMath.compositionRange(1, 1, 2))
        // 第三轮：光标落在 2，上一轮 "ni" 长度 2，本轮 "niu" 长度 3 → 起点仍是 0
        assertEquals(0..3, SelectionMath.compositionRange(2, 2, 3))
    }

    @Test
    fun `组合区不在行首时起点同样正确`() {
        // 输入框里已有「你好」，光标在 2，首次写入一个字符
        assertEquals(2..3, SelectionMath.compositionRange(2, 0, 1))
        // 第二轮：光标落在 3，上一轮长度 1，本轮长度 2 → 起点仍是 2
        assertEquals(2..4, SelectionMath.compositionRange(3, 1, 2))
    }

    @Test
    fun `位置不可信时不给组合区`() {
        // 读不到光标位置
        assertNull(SelectionMath.compositionRange(-1, 0, 1))
        // 光标位置比上一轮预览还靠前 —— 两边状态不同步，宁可不判
        assertNull(SelectionMath.compositionRange(1, 5, 2))
        // 新预览为空（清空组合走的是另一条分支，不该走到这里）
        assertNull(SelectionMath.compositionRange(0, 0, 0))
    }

    /**
     * 端到端串一遍：把 [SelectionMath.compositionRange] 的产出直接喂给
     * [SelectionMath.isCursorAwayFromComposition]，确认连打全程都不会被判成「离开」。
     */
    @Test
    fun `连打时范围计算与判定能串起来`() {
        var previousPreviewLength = 0
        var cursorBefore = 0
        for (length in 1..8) {
            val range = SelectionMath.compositionRange(cursorBefore, previousPreviewLength, length)
            assertEquals(0..length, range)
            // 本轮落点必须判为「没离开」
            assertFalse(SelectionMath.isCursorAwayFromComposition(length, length, 0, length))
            // 上一拍迟到的回调也必须判为「没离开」
            if (length > 1) {
                assertFalse(
                    SelectionMath.isCursorAwayFromComposition(length - 1, length - 1, 0, length)
                )
            }
            previousPreviewLength = length
            cursorBefore = length
        }
    }
}
