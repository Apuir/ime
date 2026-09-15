package com.ninthsoft.ime.base.priority

import kotlin.math.ln1p
import kotlin.math.pow

/**
 * 用户偏好的「净分」计算：把「用户选过多少次」和「用户误选后删掉过多少次」
 * 合成为一个**可正可负**的数，并随时间衰减。
 *
 * 为什么需要负分：这是本次改造的核心需求之一 —— 用户手快选错了候选，删掉重打时，
 * 那个错词应该往下掉。改造前 app 只记 `click_count`（只增不减），错词会被自己
 * 的误操作永久顶在候选前面。
 *
 * 衰减的存在同样重要：惩罚必须**会过期**。否则用户失误一次，那个词就再也上不来了。
 * 半衰期取 24 小时，7 天（7 个半衰期）后残值不足 1%，等价于「清除」。
 *
 * 本对象是纯函数，不碰数据库，便于单测。
 */
object PreferenceScorer {

    /** 衰减半衰期：24 小时。 */
    const val HALF_LIFE_MS = 24L * 60 * 60 * 1000

    /** 正向封顶：再常用也不无限抬升（`ln1p` 本身增长很慢，这里是第二道保险）。 */
    const val POSITIVE_CAP = 4.0

    /** 负向封顶：连续误选同一个词，最多压到这个程度，不会「消失」。 */
    const val NEGATIVE_CAP = 3.0

    /** 指数衰减系数：`0.5 ^ (age / halfLife)`，限制在 `(0, 1]`。 */
    fun decay(ageMs: Long, halfLifeMs: Long = HALF_LIFE_MS): Double {
        if (halfLifeMs <= 0L) return 1.0
        val age = if (ageMs > 0L) ageMs else 0L
        return 0.5.pow(age.toDouble() / halfLifeMs.toDouble())
    }

    /**
     * 计算净偏好。
     *
     * - 正向：`ln1p(点击次数) × 衰减` —— 用对数是对高频词做软压，避免一两个词
     *   因为点了很多次就永远霸榜；
     * - 负向：`误选次数 × 衰减` —— 用线性，让头一两次误选就明显见效；
     * - 结果按上下限夹取，且**不改变候选是否存在**，只影响排序。
     *
     * @param clickCount 被选中上屏的累计次数
     * @param lastGoodAt 最近一次被选中上屏的时间（0 表示没有）
     * @param badCount 被判定为误选（上屏后又被删掉、或被长按删除）的累计次数
     * @param lastBadAt 最近一次被判定误选的时间（0 表示没有）
     * @param now 当前时间
     */
    fun score(
        clickCount: Int,
        lastGoodAt: Long,
        badCount: Int,
        lastBadAt: Long,
        now: Long,
    ): Double {
        val good = if (clickCount > 0 && lastGoodAt > 0L) {
            (ln1p(clickCount.toDouble()) * decay(now - lastGoodAt))
                .coerceAtMost(POSITIVE_CAP)
        } else {
            0.0
        }
        val bad = if (badCount > 0 && lastBadAt > 0L) {
            (badCount.toDouble() * decay(now - lastBadAt)).coerceAtMost(NEGATIVE_CAP)
        } else {
            0.0
        }
        return (good - bad).coerceIn(-NEGATIVE_CAP, POSITIVE_CAP)
    }
}
