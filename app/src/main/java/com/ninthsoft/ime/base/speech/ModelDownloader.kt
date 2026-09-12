package com.ninthsoft.ime.base.speech

import com.ninthsoft.ime.base.util.TarBz2ExtractorUtil
import com.ninthsoft.ime.base.util.ToastUtil
import com.ninthsoft.ime.data.App
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import timber.log.Timber
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.ArrayDeque
import java.util.concurrent.TimeUnit

object ModelDownloader {
    private const val STAGE_DIR = ".extract-stage"
    private const val DEFAULT_ARCHIVE_NAME = "model.tar.bz2"
    private const val BUFFER_SIZE = 32768
    private const val REPORT_STEP = 256 * 1024L
    private const val CONNECT_TIMEOUT_SECONDS = 30L
    private const val READ_TIMEOUT_SECONDS = 60L
    private const val TOKENS_FILE = "tokens.txt"
    private const val ENCODER_NAME = "encoder"
    private const val DECODER_NAME = "decoder"
    private const val JOINER_NAME = "joiner"
    private const val BIN_EXTENSION = "bin"
    private const val ONNX_EXTENSION = "onnx"
    private const val SO_EXTENSION = "so"
    private const val DOWNLOAD_FILE_INDEX = 1
    private const val DOWNLOAD_FILE_COUNT = 1

    private val MODEL_EXTENSIONS = setOf(BIN_EXTENSION, ONNX_EXTENSION, SO_EXTENSION)
    private val MODEL_COMPONENTS = listOf(ENCODER_NAME, DECODER_NAME, JOINER_NAME)

    /** 官方模型文件名与 md5，来自 k2-fsa/sherpa-onnx 的 asr-models 发布。 */
    private const val SPEECH_MODEL_FILE =
        "sherpa-onnx-x-asr-160ms-streaming-zipformer-transducer-zh-en-punct-int8-2026-06-05.tar.bz2"
    private const val SPEECH_MODEL_MD5 = "b822fa8bc747b5f18eff2bb6deef4390"

    /**
     * 语音识别模型的下载地址，按顺序尝试。
     *
     * 模型是 k2-fsa/sherpa-onnx 官方发布的流式 zipformer 中英标点 int8 模型，这里直接指向
     * 作者的发布地址，不再经过任何中间服务器。国内直连 GitHub Releases 通常很慢，所以优先
     * 走 gh-proxy 镜像，失败了再回退官方地址。
     *
     * 以后有自建服务器时，把这里换成自己的地址即可。
     */
    private val SPEECH_MODEL_URLS = listOf(
        "https://gh-proxy.org/https://github.com/k2-fsa/sherpa-onnx/releases/download/" +
            "asr-models/$SPEECH_MODEL_FILE",
        "https://github.com/k2-fsa/sherpa-onnx/releases/download/" +
            "asr-models/$SPEECH_MODEL_FILE",
    )
    private val client =
        OkHttpClient.Builder().connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS).build()

    data class Progress(
        val fileIndex: Int,
        val fileCount: Int,
        val fileName: String,
        val downloaded: Long,
        val total: Long,
    )

    private data class ModelFiles(
        val tokens: File,
        val encoder: File,
        val decoder: File,
        val joiner: File,
    )

    suspend fun download(
        onProgress: (Progress) -> Unit = {},
        onExtract: (current: Long, total: Long) -> Unit = { _, _ -> },
    ): Boolean = withContext(Dispatchers.IO) {
        val tempDir = App.downloadDir
        val modelDir = App.speechModelDir
        val archiveName = SPEECH_MODEL_FILE.ifBlank { DEFAULT_ARCHIVE_NAME }
        val archiveFile = File(tempDir, archiveName)
        val stageDir = File(tempDir, STAGE_DIR)
        if (!currentCoroutineContext().isActive) return@withContext false
        if (!ensureArchive(archiveFile, onProgress)) return@withContext false
        if (!currentCoroutineContext().isActive) return@withContext false
        extractAndInstall(archiveFile, stageDir, modelDir, onExtract)
    }

    private data class DownloadResult(val md5: String)

    private suspend fun ensureArchive(
        archiveFile: File,
        onProgress: (Progress) -> Unit,
    ): Boolean {
        // 已经下过且校验通过就直接复用，不必重下。
        if (archiveFile.isFile && md5Of(archiveFile).equals(SPEECH_MODEL_MD5, true)) {
            Timber.i("Reusing cached archive: %s", archiveFile.absolutePath)
            return true
        }
        archiveFile.delete()
        for ((index, url) in SPEECH_MODEL_URLS.withIndex()) {
            if (!currentCoroutineContext().isActive) return false
            Timber.i("Speech model download start (mirror %d): %s", index, url)
            val result = downloadFile(url, archiveFile) { read, total ->
                onProgress(
                    Progress(DOWNLOAD_FILE_INDEX, DOWNLOAD_FILE_COUNT, archiveFile.name, read, total)
                )
            }
            if (result != null && result.md5.equals(SPEECH_MODEL_MD5, true)) {
                Timber.i(
                    "Speech model archive ready: %s (%d bytes)",
                    archiveFile.absolutePath,
                    archiveFile.length()
                )
                return true
            }
            Timber.w("Speech model mirror %d failed, trying next", index)
            archiveFile.delete()
        }
        return false
    }

    private fun extractAndInstall(
        archiveFile: File,
        stageDir: File,
        modelDir: File,
        onExtract: (Long, Long) -> Unit,
    ): Boolean {
        return runCatching {
            stageDir.deleteRecursively()
            check(stageDir.mkdirs()) { "Failed to create staging directory: ${stageDir.absolutePath}" }
            Timber.i("Extracting speech model: %s", archiveFile.name)
            TarBz2ExtractorUtil.extract(archiveFile, stageDir, onExtract, ::shouldExtract)
            val model = findModel(stageDir) ?: error("Speech model is incomplete")
            Timber.i(
                "Model files located: tokens=%s encoder=%s decoder=%s joiner=%s",
                model.tokens.name,
                model.encoder.name,
                model.decoder.name,
                model.joiner.name
            )
            installModel(model, modelDir)
            stageDir.deleteRecursively()
            archiveFile.delete()
            true
        }.onFailure { e ->
            Timber.e(e, "Failed to extract/install speech model")
            // 失败时清理阶段目录残存，避免后续解压受到干扰
            runCatching { stageDir.deleteRecursively() }
        }.getOrDefault(false)
    }

    private fun shouldExtract(name: String): Boolean {
        val fileName = name.substringAfterLast('/')
        if (fileName.equals(TOKENS_FILE, true)) return true
        return MODEL_COMPONENTS.any { isModelFile(fileName, it) }
    }

    private fun isModelFile(fileName: String, component: String): Boolean {
        val lowerName = fileName.lowercase()
        return lowerName.contains(component) && MODEL_EXTENSIONS.any { lowerName.endsWith(".$it") }
    }

    private fun findModel(root: File): ModelFiles? {
        val tokens = findFile(root) { it.name.equals(TOKENS_FILE, true) }
        val encoder = findModelFile(root, ENCODER_NAME)
        val decoder = findModelFile(root, DECODER_NAME)
        val joiner = findModelFile(root, JOINER_NAME)
        if (tokens == null || encoder == null || decoder == null || joiner == null) {
            Timber.w(
                "Incomplete speech model: tokens=%s encoder=%s decoder=%s joiner=%s",
                tokens,
                encoder,
                decoder,
                joiner
            )
            return null
        }
        return ModelFiles(tokens, encoder, decoder, joiner)
    }

    private fun findModelFile(root: File, component: String): File? =
        findFile(root) { isModelFile(it.name, component) }

    private fun findFile(root: File, predicate: (File) -> Boolean): File? {
        val queue = ArrayDeque<File>()
        queue.add(root)
        while (queue.isNotEmpty()) {
            val file = queue.removeFirst()
            if (file.isDirectory) {
                file.listFiles()?.forEach(queue::addLast)
            } else if (predicate(file)) {
                return file
            }
        }
        return null
    }

    private fun installModel(model: ModelFiles, modelDir: File) {
        val parent = modelDir.parentFile
            ?: error("Model directory has no parent: ${modelDir.absolutePath}")
        parent.mkdirs()
        // 先把完整的新模型组装到同目录临时目录，确认完整后再整体替换目标，
        // 避免把半套文件写进最终目录。
        val staging = File(parent, modelDir.name + ".tmp")
        staging.deleteRecursively()
        check(staging.mkdirs()) { "Failed to create install staging: ${staging.absolutePath}" }
        moveTo(staging, model.tokens)
        moveTo(staging, model.encoder)
        moveTo(staging, model.decoder)
        moveTo(staging, model.joiner)
        // 替换前再次校验新模型已完整
        if (findModel(staging) == null) {
            staging.deleteRecursively()
            throw IOException("New model is incomplete before installation")
        }
        if (modelDir.exists()) modelDir.deleteRecursively()
        if (!staging.renameTo(modelDir)) {
            staging.deleteRecursively()
            throw IOException("Failed to move new model into place: $staging")
        }
        Timber.i("Speech model installed: %s", modelDir.absolutePath)
    }

    private fun moveTo(destination: File, source: File) {
        val target = File(destination, source.name)
        if (target.exists()) target.delete()
        source.copyTo(target, overwrite = true)
        source.delete()
    }

    private suspend fun downloadFile(
        url: String,
        target: File,
        onRead: (Long, Long) -> Unit,
    ): DownloadResult? {
        val request = Request.Builder().url(url).build()
        return try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Timber.w("Model download failed: HTTP %d", response.code)
                    ToastUtil.showToast("语音模型下载失败：HTTP ${response.code}")
                    return null
                }
                val body = response.body ?: return null
                val total = body.contentLength()
                val digest = MessageDigest.getInstance("MD5")
                target.outputStream().use { output ->
                    body.byteStream().use { input ->
                        val buffer = ByteArray(BUFFER_SIZE)
                        var downloaded = 0L
                        var lastReported = 0L
                        while (true) {
                            if (!currentCoroutineContext().isActive) return null
                            val count = input.read(buffer)
                            if (count < 0) break
                            output.write(buffer, 0, count)
                            digest.update(buffer, 0, count)
                            downloaded += count
                            if (downloaded - lastReported >= REPORT_STEP || (total in 1 downTo downloaded)) {
                                onRead(downloaded, total)
                                lastReported = downloaded
                            }
                            if (total in 1 downTo downloaded) break
                        }
                    }
                }
                DownloadResult(digest.digest().joinToString("") { "%02x".format(it) })
            }
        } catch (e: Throwable) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Timber.e(e, "Speech model download failed")
            ToastUtil.showToast("语音模型下载失败：${e.message ?: "网络错误"}")
            null
        }
    }

    private fun md5Of(file: File): String {
        val digest = MessageDigest.getInstance("MD5")
        file.inputStream().use { input ->
            val buffer = ByteArray(BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
