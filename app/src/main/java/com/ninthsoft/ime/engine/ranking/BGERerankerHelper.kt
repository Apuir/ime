package com.ninthsoft.ime.engine.ranking

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import com.ninthsoft.ime.base.onnx.OnnxEngine
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber
import java.io.Closeable
import java.io.File
import kotlin.math.min

private const val SPACE_PREFIX = "\u2581"
private const val MAX_TOKEN_LEN = 16

private data class TokenEntry(val id: Int, val score: Float)

class XLMRobertaBPETokenizer {
    private val CLS_ID = 0
    private val PAD_ID = 1
    private val SEP_ID = 2
    private val UNK_ID = 3

    private var vocab: Map<String, TokenEntry> = emptyMap()

    fun load(tokenizerJsonPath: String) {
        val json = JSONObject(File(tokenizerJsonPath).readText())
        val model = json.getJSONObject("model")
        vocab = parseVocab(model)
        Timber.i("Tokenizer loaded: vocab=${vocab.size}")
    }

    private fun parseVocab(model: JSONObject): Map<String, TokenEntry> {
        return when (val vocabValue = model.get("vocab")) {
            is JSONObject -> buildMap(vocabValue.length()) {
                for (key in vocabValue.keys()) {
                    put(key, TokenEntry(vocabValue.getInt(key), 0f))
                }
            }

            is JSONArray -> buildMap(vocabValue.length()) {
                for (i in 0 until vocabValue.length()) {
                    val entry = vocabValue.getJSONArray(i)
                    val token = entry.getString(0)
                    val score = entry.getDouble(1).toFloat()
                    put(token, TokenEntry(i, score))
                }
            }

            else -> throw IllegalStateException("Unsupported vocab format: ${vocabValue.javaClass}")
        }
    }

    fun encodePair(
        query: String, document: String, maxLength: Int = 512
    ): Pair<LongArray, LongArray> {
        val queryIds = encode(query)
        val docIds = encode(document)

        val inputIds = mutableListOf<Int>(CLS_ID)
        inputIds.addAll(queryIds)
        inputIds.add(SEP_ID)
        inputIds.addAll(docIds)
        inputIds.add(SEP_ID)

        val truncated = inputIds.take(maxLength).toMutableList()
        val attentionMask = MutableList(truncated.size) { 1 }

        while (truncated.size < maxLength) {
            truncated.add(PAD_ID)
            attentionMask.add(0)
        }

        return Pair(
            truncated.map { it.toLong() }.toLongArray(),
            attentionMask.map { it.toLong() }.toLongArray()
        )
    }

    fun encode(text: String): List<Int> {
        val words = preTokenize(text)
        val ids = mutableListOf<Int>()
        for ((i, word) in words.withIndex()) {
            val target = if (i == 0) word else SPACE_PREFIX + word
            for (token in tokenizeWord(target)) {
                ids.add(vocab[token]?.id ?: UNK_ID)
            }
        }
        return ids
    }

    private fun tokenizeWord(word: String): List<String> {
        if (word.isEmpty()) return emptyList()
        if (word in vocab) return listOf(word)

        val n = word.length
        val bestScore = FloatArray(n + 1) { Float.NEGATIVE_INFINITY }
        val bestPrev = IntArray(n + 1) { -1 }
        bestScore[0] = 0f

        for (i in 0 until n) {
            if (bestScore[i] == Float.NEGATIVE_INFINITY) continue
            val maxEnd = min(n, i + MAX_TOKEN_LEN)
            for (j in i + 1..maxEnd) {
                val sub = word.substring(i, j)
                val entry = vocab[sub] ?: continue
                val newScore = bestScore[i] + entry.score
                if (newScore > bestScore[j]) {
                    bestScore[j] = newScore
                    bestPrev[j] = i
                }
            }
        }

        val result = mutableListOf<String>()
        var pos = n
        while (pos > 0) {
            val prev = bestPrev[pos]
            if (prev == -1) return word.map { it.toString() }
            result.add(word.substring(prev, pos))
            pos = prev
        }
        result.reverse()
        return result
    }

    private fun preTokenize(text: String): List<String> {
        return text.lowercase().trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
    }
}

class BGEModelHelper : Closeable {

    val engine = OnnxEngine()
    val tokenizer = XLMRobertaBPETokenizer()
    private var loaded = false

    fun load(modelPath: String, tokenizerJsonPath: String) {
        tokenizer.load(tokenizerJsonPath)
        engine.loadModel(
            modelPath, OnnxEngine.SessionConfig(
                intraOpThreads = 1,
                interOpThreads = 1,
                optLevel = OrtSession.SessionOptions.OptLevel.BASIC_OPT,
                enableCpuMemArena = true,
                memoryPatternOptimization = true
            )
        )
        loaded = true
        Timber.i("BGE reranker ready: model=$modelPath")
    }

    fun computeScore(query: String, document: String, maxLength: Int = 512): Float {
        check(loaded) { "Call load() first" }

        val (inputIds, attentionMask) = tokenizer.encodePair(query, document, maxLength)
        val batchInputIds = arrayOf(inputIds)
        val batchAttentionMask = arrayOf(attentionMask)

        val env = OrtEnvironment.getEnvironment()
        val idsTensor = OnnxTensor.createTensor(env, batchInputIds)
        val maskTensor = OnnxTensor.createTensor(env, batchAttentionMask)

        val inputs = mapOf("input_ids" to idsTensor, "attention_mask" to maskTensor)
        try {
            val result = engine.run(inputs)
            result.use { res ->
                val outputTensor = res.get(0) as OnnxTensor
                val logits = engine.extractFloatData(outputTensor)
                return logits[0][0]
            }
        } finally {
            idsTensor.close()
            maskTensor.close()
        }
    }

    fun computeScores(query: String, documents: List<String>, maxLength: Int = 512): List<Float> {
        check(loaded) { "Call load() first" }
        return documents.map { doc -> computeScore(query, doc, maxLength) }
    }

    fun rerank(
        query: String, documents: List<String>, maxLength: Int = 512
    ): List<Pair<String, Float>> {
        check(loaded) { "Call load() first" }
        return documents.map { doc -> doc to computeScore(query, doc, maxLength) }
            .sortedByDescending { it.second }
    }

    override fun close() {
        engine.close()
        loaded = false
    }
}
