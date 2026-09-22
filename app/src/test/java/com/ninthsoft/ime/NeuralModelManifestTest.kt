package com.ninthsoft.ime

import com.ninthsoft.ime.base.neural.NeuralModelManifest
import kotlinx.serialization.SerializationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 模型清单（`manifest.json`）的解析与自洽性回归。
 *
 * 清单是**下载与校验的唯一依据**：它同时给出文件名字、字节数与 sha256。
 * 所以这里压的重点不是「能解析」，而是**该拒绝的必须拒绝** ——
 * 清单缺 sha256、字节数为 0、或文件名里带路径分隔符时，
 * 放行会让「下载了什么」与「校验了什么」对不上，甚至写出模型目录之外。
 */
class NeuralModelManifestTest {

    private val sha = "a".repeat(64)

    private val goodJson = """
        {
          "version": 1,
          "name": "ime-nwp-zh",
          "format": "onnx-int8",
          "context_tokens": 256,
          "vocab_size": 30000,
          "char_vocab_size": 21128,
          "num_layers": 12,
          "num_heads": 12,
          "head_dim": 64,
          "model": {"file": "nwp.int8.onnx", "bytes": 136000000, "sha256": "$sha"},
          "word_vocab": {"file": "word_vocab.txt", "bytes": 500000, "sha256": "$sha"},
          "char_vocab": {"file": "char2id.json", "bytes": 120000, "sha256": "$sha"},
          "eval": {"top1": 0.31, "top5": 0.55, "mrr": 0.42}
        }
    """.trimIndent()

    @Test
    fun parsesTheContractWithTheTrainingSide() {
        val manifest = NeuralModelManifest.parse(goodJson)
        assertEquals(1, manifest.version)
        assertEquals("ime-nwp-zh", manifest.name)
        assertEquals(256, manifest.contextTokens)
        assertEquals(30000, manifest.vocabSize)
        assertEquals(21128, manifest.charVocabSize)
        assertEquals(12, manifest.numLayers)
        assertEquals("nwp.int8.onnx", manifest.model.file)
        assertEquals(136000000L, manifest.model.bytes)
        assertEquals(0.31, manifest.eval?.top1 ?: 0.0, 1e-9)
        assertTrue(manifest.isWellFormed())
    }

    /** 下载顺序：小文件在前，130 MB 的模型最后 —— 网络差时先失败在便宜的地方。 */
    @Test
    fun requiredFilesAreOrderedSmallFirst() {
        val files = NeuralModelManifest.parse(goodJson).requiredFiles().map { it.file }
        assertEquals(listOf("word_vocab.txt", "char2id.json", "nwp.int8.onnx"), files)
    }

    @Test
    fun totalBytesSumsAllRequiredFiles() {
        assertEquals(136000000L + 500000L + 120000L, NeuralModelManifest.parse(goodJson).totalBytes)
    }

    /** 前向兼容：训练侧以后加字段不该让老客户端解析失败。 */
    @Test
    fun unknownKeysAreIgnored() {
        val json = goodJson.replaceFirst("{", """{"future_field": [1, 2, 3],""")
        assertTrue(NeuralModelManifest.parse(json).isWellFormed())
    }

    @Test
    fun rejectsMissingOrMalformedSha256() {
        assertFalse(NeuralModelManifest.parse(goodJson.replace(sha, "abc")).isWellFormed())
    }

    @Test
    fun rejectsZeroByteFiles() {
        assertFalse(
            NeuralModelManifest.parse(goodJson.replace("\"bytes\": 500000", "\"bytes\": 0"))
                .isWellFormed()
        )
    }

    /** 文件名不能带路径分隔符，否则清单能把文件写到模型目录之外。 */
    @Test
    fun rejectsPathTraversalInFileName() {
        assertFalse(
            NeuralModelManifest.parse(
                goodJson.replace("\"file\": \"nwp.int8.onnx\"", "\"file\": \"../nwp.onnx\"")
            ).isWellFormed()
        )
        assertFalse(
            NeuralModelManifest.parse(
                goodJson.replace("\"file\": \"word_vocab.txt\"", "\"file\": \"a/b.txt\"")
            ).isWellFormed()
        )
    }

    @Test
    fun rejectsIncompleteMetadata() {
        assertFalse(
            "版本号为 0 说明清单没被正确生成",
            NeuralModelManifest.parse(goodJson.replace("\"version\": 1", "\"version\": 0"))
                .isWellFormed(),
        )
        assertFalse(
            NeuralModelManifest.parse(goodJson.replace("\"context_tokens\": 256", "\"context_tokens\": 0"))
                .isWellFormed()
        )
        assertFalse(
            NeuralModelManifest.parse(goodJson.replace("\"num_layers\": 12", "\"num_layers\": 0"))
                .isWellFormed()
        )
        assertFalse(
            NeuralModelManifest.parse(goodJson.replace("\"name\": \"ime-nwp-zh\"", "\"name\": \"\""))
                .isWellFormed()
        )
    }

    /** 坏 JSON 必须抛出来（调用方按「模型损坏」处理），不能静默地当成默认值。 */
    @Test(expected = SerializationException::class)
    fun garbageJsonThrows() {
        NeuralModelManifest.parse("not json at all")
    }
}
