package com.ninthsoft.ime.base.neural

import kotlinx.serialization.json.Json

/**
 * 神经模型的**输入字表**（`char2id.json`）。
 *
 * 底座是字级中文 GPT-2，所以输入是「每个汉字一个 id」。字表由训练侧从底座
 * tokenizer 派生（能单独成 token 的字才收），并**在训练与端侧用同一份** ——
 * 端侧不做 BPE：中文场景下逐字查表与底座分词的差异只出现在极生僻字上，
 * 而那些字本来就该走 `<unk>`。这个取舍换来的是端侧零分词依赖、行为可预测。
 */
class CharVocabulary private constructor(
    private val ids: Map<String, Int>,
    val unknownId: Int,
) {
    val size: Int
        get() = ids.size

    /**
     * 逐**码点**编码。用码点而不是 `Char`：emoji 等增补平面字符是代理对，
     * 按 `Char` 编码会把它劈成两个都查不到的半截字符。
     */
    fun encode(text: String): IntArray {
        if (text.isEmpty()) return IntArray(0)
        val out = IntArray(text.codePointCount(0, text.length))
        var index = 0
        var offset = 0
        while (offset < text.length) {
            val codePoint = text.codePointAt(offset)
            offset += Character.charCount(codePoint)
            out[index++] = ids[String(Character.toChars(codePoint))] ?: unknownId
        }
        return out
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        /**
         * 解析失败抛异常（调用方按损坏模型处理）。
         * 字表约定含 `"<unk>"`，缺了就补一个 id 0 —— 旧文件兼容，不至于整块功能不可用。
         */
        fun parse(text: String): CharVocabulary {
            val parsed: Map<String, Int> = json.decodeFromString(text)
            val unk = parsed[WordVocabulary.UNKNOWN_TOKEN] ?: 0
            return CharVocabulary(parsed, unk)
        }
    }
}
