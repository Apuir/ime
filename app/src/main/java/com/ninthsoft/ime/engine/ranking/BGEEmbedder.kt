package com.ninthsoft.ime.engine.ranking

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import com.ninthsoft.ime.base.onnx.OnnxEngine
import org.json.JSONObject
import timber.log.Timber
import java.io.Closeable
import java.io.File
import kotlin.math.sqrt

class WordPieceTokenizer {
    private val CLS_ID = 101
    private val SEP_ID = 102
    private val PAD_ID = 0
    private val UNK_ID = 100

    private var vocab: Map<String, Int> = emptyMap()
    private var continuingPrefix = "##"

    fun load(jsonPath: String) {
        val json = JSONObject(File(jsonPath).readText())
        val model = json.getJSONObject("model")
        continuingPrefix = model.optString("continuing_subword_prefix", "##")

        val vocabObj = model.getJSONObject("vocab")
        vocab = buildMap(vocabObj.length()) {
            for (key in vocabObj.keys()) {
                put(key, vocabObj.getInt(key))
            }
        }
        Timber.i("WordPiece tokenizer loaded: vocab=${vocab.size}")
    }

    fun encode(text: String, maxLength: Int = 512): Triple<LongArray, LongArray, LongArray> {
        val tokens = tokenize(text)
        val ids = mutableListOf(CLS_ID)
        ids.addAll(tokens.map { vocab[it] ?: UNK_ID })
        ids.add(SEP_ID)

        val truncated = ids.take(maxLength).toMutableList()
        val attentionMask = MutableList(truncated.size) { 1 }
        val tokenTypeIds = MutableList(truncated.size) { 0 }

        while (truncated.size < maxLength) {
            truncated.add(PAD_ID)
            attentionMask.add(0)
            tokenTypeIds.add(0)
        }

        return Triple(
            truncated.map { it.toLong() }.toLongArray(),
            attentionMask.map { it.toLong() }.toLongArray(),
            tokenTypeIds.map { it.toLong() }.toLongArray(),
        )
    }

    private fun tokenize(text: String): List<String> {
        val result = mutableListOf<String>()
        for (word in preTokenize(text)) {
            result.addAll(tokenizeWord(word))
        }
        return result
    }

    private fun tokenizeWord(word: String): List<String> {
        if (word.isEmpty()) return emptyList()
        if (word in vocab) return listOf(word)

        val result = mutableListOf<String>()
        var start = 0
        while (start < word.length) {
            var end = word.length
            var found = false
            while (end > start) {
                val sub = word.substring(start, end)
                val key = if (start == 0) sub else continuingPrefix + sub
                if (key in vocab) {
                    result.add(key)
                    found = true
                    break
                }
                end--
            }
            if (!found) {
                result.add("[UNK]")
                start++
            } else {
                start = end
            }
        }
        return result
    }

    private fun preTokenize(text: String): List<String> {
        val result = mutableListOf<String>()
        var buf = StringBuilder()
        for (ch in text.trim()) {
            if (ch.isWhitespace()) {
                if (buf.isNotEmpty()) { result.add(buf.toString()); buf = StringBuilder() }
            } else if (isCJK(ch.code)) {
                if (buf.isNotEmpty()) { result.add(buf.toString()); buf = StringBuilder() }
                result.add(ch.toString())
            } else {
                buf.append(ch)
            }
        }
        if (buf.isNotEmpty()) result.add(buf.toString())
        return result.filter { it.isNotEmpty() }
    }

    private fun isCJK(cp: Int): Boolean {
        return cp in 0x4E00..0x9FFF ||
                cp in 0x3400..0x4DBF ||
                cp in 0xF900..0xFAFF ||
                cp in 0x20000..0x2A6DF ||
                cp in 0x2F800..0x2FA1F
    }
}

class BGEEmbedder : Closeable {
    val engine = OnnxEngine()
    val tokenizer = WordPieceTokenizer()

    private var loaded = false

    fun load(modelPath: String, tokenizerPath: String) {
        tokenizer.load(tokenizerPath)
        engine.loadModel(modelPath, OnnxEngine.SessionConfig(
            intraOpThreads = 2,
            interOpThreads = 1,
            optLevel = OrtSession.SessionOptions.OptLevel.BASIC_OPT,
            enableCpuMemArena = true,
            memoryPatternOptimization = true,
        ))
        loaded = true
        Timber.i("BGE embedder ready: model=$modelPath")
    }

    /**
     * 将文本编码为 L2 归一化后的向量 (dim=512)
     */
    fun encode(text: String, maxLength: Int = 512): FloatArray {
        check(loaded) { "Call load() first" }

        val (inputIds, attentionMask, tokenTypeIds) = tokenizer.encode(text, maxLength)
        val env = OrtEnvironment.getEnvironment()
        val idsTensor = OnnxTensor.createTensor(env, arrayOf(inputIds))
        val maskTensor = OnnxTensor.createTensor(env, arrayOf(attentionMask))
        val typeIdsTensor = OnnxTensor.createTensor(env, arrayOf(tokenTypeIds))

        val inputs = mapOf(
            "input_ids" to idsTensor,
            "attention_mask" to maskTensor,
            "token_type_ids" to typeIdsTensor,
        )
        try {
            val result = engine.runAllOutputs(inputs)
            result.use { res ->
                @Suppress("UNCHECKED_CAST")
                val batches = (res.get(0) as OnnxTensor).value as Array<Array<FloatArray>>
                val clsVector = batches[0][0]
                return l2Normalize(clsVector)
            }
        } finally {
            idsTensor.close()
            maskTensor.close()
            typeIdsTensor.close()
        }
    }

    /** 余弦相似度（向量已 L2 归一化时等价于点积） */
    fun similarity(a: FloatArray, b: FloatArray): Float {
        var sum = 0f
        for (i in a.indices) sum += a[i] * b[i]
        return sum
    }

    override fun close() {
        engine.close()
        loaded = false
    }

    private fun l2Normalize(vec: FloatArray): FloatArray {
        val sumSq = vec.fold(0f) { acc, v -> acc + v * v }
        val norm = sqrt(sumSq)
        return FloatArray(vec.size) { vec[it] / norm }
    }
}
