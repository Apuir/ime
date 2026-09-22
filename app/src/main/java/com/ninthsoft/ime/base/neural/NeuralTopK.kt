package com.ninthsoft.ime.base.neural

/**
 * 从词表 logits 里取 top-k 词表下标。
 *
 * 刻意放在 Kotlin 侧而不是 ONNX 图里（设计已定）：图里加 TopK/ArgMax 会让导出
 * 复杂化，而 3 万个数在 Kotlin 里选 25 个是微秒级的事 —— 真正的开销在前向本身。
 *
 * 用**有界插入**而不是整表排序：k 通常 ≤ 25，插入法只需要 O(V·k) 的移动，
 * 且没有装箱和临时数组。
 */
object NeuralTopK {
    /**
     * 返回按 logit **降序**排列的词表下标，最多 [k] 个。
     *
     * 跳过 NaN 与 -inf：动态范围 int8 量化在极端输入下会产出非有限值，
     * 把它们当候选会让 softmax 概率变成 NaN。
     */
    fun topK(logits: FloatArray, k: Int): IntArray {
        if (k <= 0 || logits.isEmpty()) return IntArray(0)

        val size = minOf(k, logits.size)
        val bestIndex = IntArray(size) { -1 }
        val bestValue = FloatArray(size) { Float.NEGATIVE_INFINITY }
        var filled = 0

        for (index in logits.indices) {
            val value = logits[index]
            if (value.isNaN() || value == Float.NEGATIVE_INFINITY) continue
            if (filled == size && value <= bestValue[filled - 1]) continue

            var position = if (filled < size) filled else size - 1
            // 严格大于才后移：同分时保留词表里更靠前的那个，结果确定
            while (position > 0 && value > bestValue[position - 1]) {
                bestValue[position] = bestValue[position - 1]
                bestIndex[position] = bestIndex[position - 1]
                position--
            }
            bestValue[position] = value
            bestIndex[position] = index
            if (filled < size) filled++
        }

        return bestIndex.copyOf(filled)
    }
}
