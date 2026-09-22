package com.ninthsoft.ime.base.neural

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * 神经模型的分发清单（`manifest.json`）。
 *
 * 模型不随包（int8 约 130 MB），按需下载到 app 私有目录。清单是**下载与校验的唯一依据**：
 * 它同时给出每个文件的字节数与 sha256，所以「下载到一半」「被别的版本覆盖」
 * 「用户手动塞了错文件」这些情况都能在加载前判定，而不是等到推理时崩溃。
 *
 * 字段名与训练侧导出的 `manifest.json` 一一对应，不要在任一侧单方面改名。
 */
@Serializable
data class NeuralModelManifest(
    val version: Int = 0,
    val name: String = "",
    val format: String = "",
    @SerialName("context_tokens") val contextTokens: Int = 0,
    @SerialName("vocab_size") val vocabSize: Int = 0,
    @SerialName("char_vocab_size") val charVocabSize: Int = 0,
    @SerialName("num_layers") val numLayers: Int = 0,
    @SerialName("num_heads") val numHeads: Int = 0,
    @SerialName("head_dim") val headDim: Int = 0,
    val model: NeuralModelFile = NeuralModelFile(),
    @SerialName("word_vocab") val wordVocab: NeuralModelFile = NeuralModelFile(),
    @SerialName("char_vocab") val charVocab: NeuralModelFile = NeuralModelFile(),
    val eval: EvalScores? = null,
) {
    /** 三个必需文件，顺序即下载顺序（小文件在前，大模型最后）。 */
    fun requiredFiles(): List<NeuralModelFile> = listOf(wordVocab, charVocab, model)

    val totalBytes: Long
        get() = requiredFiles().sumOf { it.bytes }

    /**
     * 结构自检。**只校验清单自身是否自洽**，不碰磁盘 ——
     * 磁盘上的实际校验在 [NeuralModelStore]，那里才知道文件路径。
     */
    fun isWellFormed(): Boolean {
        if (version < 1) return false
        if (name.isEmpty()) return false
        if (contextTokens <= 0 || vocabSize <= 0 || charVocabSize <= 0) return false
        if (numLayers <= 0) return false
        return requiredFiles().all { it.isWellFormed() }
    }

    companion object {
        const val FILE_NAME = "manifest.json"

        private val json = Json { ignoreUnknownKeys = true }

        fun parse(text: String): NeuralModelManifest = json.decodeFromString(text)
    }
}

/** 清单里的一个文件条目。 */
@Serializable
data class NeuralModelFile(
    val file: String = "",
    val bytes: Long = 0,
    val sha256: String = "",
) {
    fun isWellFormed(): Boolean =
        file.isNotEmpty() &&
            !file.contains('/') &&
            !file.contains('\\') &&      // 只允许目录内的文件名，防止清单写出目录外
            bytes > 0 &&
            sha256.length == SHA256_HEX_LENGTH
}

/** sha256 的十六进制长度；训练侧写死 64 位小写。 */
private const val SHA256_HEX_LENGTH = 64

/** 训练侧的评测结果，只用于在设置页展示。 */
@Serializable
data class EvalScores(
    val top1: Double = 0.0,
    val top5: Double = 0.0,
    val mrr: Double = 0.0,
)
