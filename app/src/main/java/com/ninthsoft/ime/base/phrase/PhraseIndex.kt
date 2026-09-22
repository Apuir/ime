package com.ninthsoft.ime.base.phrase

import java.nio.ByteBuffer

/**
 * 「路径 1」的短语补全索引：成语 / 歇后语 / 名句 / 诗句「上句→下句」。
 *
 * **这一类刻意不交给神经网络**（调研结论）：它们是固定长短语，
 * 前缀匹配 100% 准确、微秒级、零功耗；让模型去生成只会更差更慢更耗电。
 *
 * 数据是构建期产出的 `phrase_index.tsv.gz`，每行 `key\tvalue\tweight`，
 * 按 `key` 升序、`weight` 降序排好。查询语义是**最长匹配优先**：
 * 从尾部 16 字逐步缩到 2 字，第一个命中的 key 就是答案 ——
 * 「床前明月光」既要能补出「疑是地上霜」，也不能在用户打到「床前明月」时
 * 反而给出更短 key 的东西。
 *
 * ## 为什么是 mmap + 偏移表，而不是 `HashMap`
 *
 * 实测这份索引有 **60 万个 key / 64 万条记录**。装进 `HashMap<String, List<Entry>>`
 * 光对象头与容器开销就是**上百 MB 堆** —— 输入法常驻进程扛不住。
 * 这里改成：整个 TSV 由 [PhraseIndexStore] 解压到文件后**只读 mmap**，
 * 堆上只留一张 `IntArray` 偏移表（64 万 × 4 B ≈ 2.6 MB），查询靠对偏移表做二分、
 * 再按字节比较 key。代价是每次查要做十几次字节比较（微秒级，本来就远低于一次按键的预算）；
 * 收益是**堆占用从上百 MB 降到几 MB**，而且被访问到的页由内核按需换入换出。
 *
 * UTF-8 的字节序与码点序一致，所以「按字节二分」与构建脚本的 `sorted()` 同序 ——
 * 这是这套做法成立的前提，构建脚本必须保持按 key 升序输出。
 */
class PhraseIndex private constructor(
    private val bytes: ByteBuffer,
    private val recordOffsets: IntArray,
) {
    data class Suggestion(val key: String, val value: String, val weight: Int)

    val recordCount: Int
        get() = recordOffsets.size

    /**
     * 取出最多 [maxResults] 条建议。命中不了就返回空表 —— 调用方据此完全不介入候选。
     *
     * 已经打出来的内容会被过滤掉：前缀补全的 key 是短语的真前缀，
     * 用户把整个成语打完时若不过滤，就会把同一个成语再推一次。
     */
    fun query(context: String, maxResults: Int = MAX_RESULTS): List<Suggestion> {
        if (context.isEmpty() || maxResults <= 0 || recordOffsets.isEmpty()) return emptyList()

        val out = ArrayList<Suggestion>(maxResults)
        // 最长匹配优先：由长到短试，第一个命中的长度就是答案，不再看更短的 key
        val longest = minOf(MAX_KEY_CHARS, context.codePointCount(0, context.length))
        for (length in longest downTo MIN_KEY_CHARS) {
            val query = lastCodePoints(context, length).toByteArray(Charsets.UTF_8)
            var index = lowerBound(bytes, recordOffsets, query)
            var scanned = 0
            var matched = false
            while (index < recordOffsets.size && scanned < MAX_ENTRIES_PER_KEY) {
                val offset = recordOffsets[index]
                if (compareKey(bytes, offset, query) != 0) break
                matched = true
                scanned++
                index++
                val record = readRecord(bytes, offset) ?: continue
                if (context.endsWith(record.value)) continue
                out.add(record)
                if (out.size >= maxResults) break
            }
            if (matched) return out
        }
        return emptyList()
    }

    companion object {
        /** key 长度上限，与构建脚本一致。 */
        const val MAX_KEY_CHARS = 16

        /** 单字 key 是噪声（「的」会命中一大片），最短从 2 字起。 */
        const val MIN_KEY_CHARS = 2

        const val MAX_RESULTS = 5

        /** 同一个 key 最多看几条 —— 文件已按 weight 降序，取前几条即可。 */
        private const val MAX_ENTRIES_PER_KEY = 8

        private const val TAB = '\t'.code
        private const val LF = '\n'.code
        private const val MAX_WEIGHT = 1_000_000

        /**
         * 从整块 TSV 字节建立偏移表。
         *
         * **不做二次排序**：那会多占一份与文件等大的内存。顺序由构建脚本保证
         * （`key` 升序 → `weight` 降序 → `value` 升序）。
         */
        fun build(data: ByteBuffer): PhraseIndex {
            val size = data.limit()
            val offsets = ArrayList<Int>(size / 48 + 1)
            var lineStart = 0
            var index = 0
            while (index <= size) {
                val atEnd = index == size
                if (atEnd || (data.get(index).toInt() and 0xFF) == LF) {
                    // 跳过空行：偏移表只记有内容的记录
                    if (index > lineStart) offsets.add(lineStart)
                    lineStart = index + 1
                }
                index++
            }
            return PhraseIndex(data, offsets.toIntArray())
        }

        private fun byteAt(data: ByteBuffer, index: Int): Int =
            if (index < 0 || index >= data.limit()) -1 else data.get(index).toInt() and 0xFF

        /**
         * 比较「第 [offset] 条记录的 key」与 [query] 的字典序。
         *
         * key 以制表符结束；换行与越界也当作结束，避免格式损坏时一路读进下一条记录。
         */
        private fun compareKey(data: ByteBuffer, offset: Int, query: ByteArray): Int {
            var position = offset
            var queryIndex = 0
            while (true) {
                val byte = byteAt(data, position)
                if (byte == TAB || byte == LF || byte < 0) {
                    return if (queryIndex == query.size) 0 else -1
                }
                if (queryIndex >= query.size) return 1
                val difference = byte - (query[queryIndex].toInt() and 0xFF)
                if (difference != 0) return difference
                position++
                queryIndex++
            }
        }

        /** 第一条 key ≥ [query] 的记录下标。 */
        private fun lowerBound(data: ByteBuffer, offsets: IntArray, query: ByteArray): Int {
            var low = 0
            var high = offsets.size
            while (low < high) {
                val mid = (low + high) ushr 1
                if (compareKey(data, offsets[mid], query) < 0) low = mid + 1 else high = mid
            }
            return low
        }

        /** 解析一条记录：跳过 key，读出 value 与 weight。格式不对返回 null。 */
        private fun readRecord(data: ByteBuffer, offset: Int): Suggestion? {
            val keyEnd = indexOf(data, offset, TAB) ?: return null
            val valueEnd = indexOf(data, keyEnd + 1, TAB) ?: return null
            val weightEnd = indexOf(data, valueEnd + 1, LF) ?: data.limit()

            var weight = 0
            var digits = 0
            for (position in (valueEnd + 1) until weightEnd) {
                val byte = byteAt(data, position)
                if (byte < '0'.code || byte > '9'.code) return null
                weight = weight * 10 + (byte - '0'.code)
                if (weight > MAX_WEIGHT) return null
                digits++
            }
            if (digits == 0) return null

            val key = readString(data, offset, keyEnd)
            val value = readString(data, keyEnd + 1, valueEnd)
            if (key.isEmpty() || value.isEmpty() || key == value) return null
            val keyChars = key.codePointCount(0, key.length)
            if (keyChars < MIN_KEY_CHARS || keyChars > MAX_KEY_CHARS) return null
            return Suggestion(key, value, weight)
        }

        /**
         * 从 [from] 起找第一个等于 [target] 的字节位置。
         *
         * **必须先把 target 比掉再看终止条件**：搜换行时 `target == LF`，
         * 若先判「遇到 LF 就放弃」就永远找不到行尾，
         * 结果是每条记录都读不出来（查询静默返回空表）。
         */
        private fun indexOf(data: ByteBuffer, from: Int, target: Int): Int? {
            var position = from
            while (true) {
                val byte = byteAt(data, position)
                if (byte == target) return position
                if (byte < 0 || byte == LF) return null
                position++
            }
        }

        private fun readString(data: ByteBuffer, from: Int, to: Int): String {
            if (to <= from) return ""
            val buffer = ByteArray(to - from)
            var position = from
            var index = 0
            while (position < to) {
                buffer[index++] = data.get(position)
                position++
            }
            return String(buffer, Charsets.UTF_8)
        }

        private fun lastCodePoints(text: String, count: Int): String {
            val total = text.codePointCount(0, text.length)
            if (total <= count) return text
            return text.substring(text.offsetByCodePoints(0, total - count))
        }
    }
}
