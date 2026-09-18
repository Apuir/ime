package com.ninthsoft.ime.input.handwriting.ochwpro

import kotlin.math.exp
import kotlin.math.min

/**
 * ochwpro 模型输出（`logits[7356]`）的解码：取 top-k 并换成概率。
 *
 * 纯计算，可在 JVM 单测里跑 —— 这里的边界（k 大于类别数、全是同分、含 NaN）
 * 在真机上只会表现为「候选莫名其妙」，很难从现象倒推。
 */
object OchwproDecode {

    /** 一个候选：类别下标与概率。 */
    class Scored(val index: Int, val probability: Float)

    /**
     * 取概率最高的 k 个。
     *
     * softmax **只对选中的 k 个算**，而不是先对 7356 个算再取前 k。
     * 两者数值等价（softmax 的分母相同，单调性也一致），但只算 k 次 `exp`
     * 而不是 7356 次 —— 识别是输入法里的高频路径，这种差别值得留。
     *
     * 返回的概率是真正的概率（和为 1），不是相对比例，因此可以直接展示。
     */
    fun topK(logits: FloatArray, k: Int): List<Scored> {
        if (k <= 0 || logits.isEmpty()) return emptyList()
        val size = min(k, logits.size)

        // 定长降序表：线性扫描一遍，遇更大者插进去。
        // 不做全排序：7356 个元素里只需要前 10 个，排序是纯浪费。
        val topValue = FloatArray(size) { Float.NEGATIVE_INFINITY }
        val topIndex = IntArray(size) { -1 }
        var filled = 0

        for (i in logits.indices) {
            val value = logits[i]
            // NaN 会破坏一切比较：它既不是最大也不是最小，混进来会让排序表错位。
            // 在输入法里「静默少一个候选」比「候选乱序」更容易排查，所以直接跳过。
            if (value.isNaN()) continue
            if (filled == size && value <= topValue[size - 1]) continue

            val limit = if (filled < size) filled else size - 1
            var position = limit
            while (position > 0 && topValue[position - 1] < value) {
                topValue[position] = topValue[position - 1]
                topIndex[position] = topIndex[position - 1]
                position--
            }
            topValue[position] = value
            topIndex[position] = i
            if (filled < size) filled++
        }

        if (filled == 0) return emptyList()

        // 数值稳定的 softmax：先减去最大值，避免 exp 溢出成 Inf
        val maxValue = topValue[0]
        var sum = 0f
        val exponentials = FloatArray(filled)
        for (i in 0 until filled) {
            val e = exp(topValue[i] - maxValue)
            exponentials[i] = e
            sum += e
        }
        if (sum <= 0f || !sum.isFinite()) return emptyList()

        // 丢掉概率为 0 的项：它们要么来自 -Inf（被屏蔽的位置），要么是
        // 「指数差大到下溢」的极小值。这两种情况下模型都在说「不可能是这个字」，
        // 让它们占着候选位只会把真正的次要候选挤出候选条。
        return (0 until filled)
            .map { i -> Scored(topIndex[i], exponentials[i] / sum) }
            .filter { it.probability > 0f }
    }
}
