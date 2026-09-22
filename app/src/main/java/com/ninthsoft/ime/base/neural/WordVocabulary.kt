package com.ninthsoft.ime.base.neural

/**
 * 神经模型的**输出词表**（`word_vocab.txt`）。
 *
 * 行号即词表 id，第 0 行是 `<unk>`。设计上要求成语/诗句被显式收进词表 ——
 * 否则 BPE 会把「心想事成」切成 2–3 片，「一次前向给出一个整词」就无从谈起。
 *
 * 反向索引（词 → id）是给候选打分用的：引擎给出的候选大多不在神经 top-k 里，
 * 但仍可以用它的词表 id 去 logits 里取概率，参与融合排序。
 */
class WordVocabulary private constructor(private val words: List<String>) {
    private val ids: Map<String, Int> by lazy {
        HashMap<String, Int>(words.size * 2).also { map ->
            // 同词多 id 时保留最小的那个，结果与文件顺序无关
            words.forEachIndexed { index, word -> map.putIfAbsent(word, index) }
        }
    }

    val size: Int
        get() = words.size

    operator fun get(id: Int): String = words.getOrNull(id).orEmpty()

    /** 不在词表里返回 [UNKNOWN_ID]；调用方据此跳过神经打分而不是当成 `<unk>` 的概率。 */
    fun idOf(word: String): Int = ids[word] ?: UNKNOWN_ID

    companion object {
        const val UNKNOWN_ID = -1

        /** 文件里约定的 `<unk>` 标记。 */
        const val UNKNOWN_TOKEN = "<unk>"

        fun parse(lines: Sequence<String>): WordVocabulary {
            val words = ArrayList<String>()
            for (line in lines) {
                val word = line.trimEnd('\r', '\n')
                // 词表里不允许出现制表符：它是短语索引/词库的分隔符，混进来会让调试无从下手
                words.add(if (word.isEmpty()) UNKNOWN_TOKEN else word)
            }
            return WordVocabulary(words)
        }
    }
}
