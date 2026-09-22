package com.ninthsoft.ime.base.neural

import com.ninthsoft.ime.base.util.ToastUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import timber.log.Timber
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * 神经模型的按需下载。
 *
 * 模型不随包（int8 约 130 MB），放到 app 私有目录 `model/neural/`。
 * 做法与 [com.ninthsoft.ime.base.speech.ModelDownloader] /
 * [com.ninthsoft.ime.base.ngram.GramModelDownloader] 一致：直连内容作者自己的地址、
 * 按顺序试镜像、写 `.part` 再原子改名、落地前校验 sha256。
 *
 * **与那两个下载器的关键差别**：这里先下 `manifest.json`，从它里面读出**其余文件的
 * 名字、字节数与 sha256** 再逐个下。好处是「换一版模型」只需要换清单一个文件，
 * 客户端不必跟着改代码，也就不会出现「下载地址和校验值各写一份、改漏一处」这种问题。
 *
 * 本地开发路径：模型由 `scripts/nwp/` 在开发机产出，可以直接 `adb push` 到
 * `/sdcard/Android/data/com.ninthsoft.ime/files/model/neural/`，
 * 设置页的「校验并加载」走的是同一套 sha256 校验。
 */
object NeuralModelDownloader {
    private const val TAG = "NeuralModelDownloader"

    /**
     * 模型发布地址前缀。**发布后填入**（例如某个 Release 的下载目录），
     * 留空表示尚未发布 —— 此时设置页不显示「下载」入口，只提供「校验并加载」与「删除」，
     * 免得给用户一个必然失败的按钮。
     */
    private const val MODEL_BASE_URL = ""

    /** 镜像前缀，按顺序尝试；空串表示直连。 */
    private val MIRROR_PREFIXES = listOf("https://gh-proxy.org/", "")

    private const val PART_SUFFIX = ".part"
    private const val BUFFER_SIZE = 64 * 1024
    private const val REPORT_STEP = 256 * 1024L

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    data class Progress(
        val fileName: String,
        val fileIndex: Int,
        val fileCount: Int,
        val downloaded: Long,
        val total: Long,
    )

    /** 是否配置了发布地址。未配置时调用方不应展示下载入口。 */
    val isConfigured: Boolean
        get() = MODEL_BASE_URL.isNotEmpty()

    private fun baseUrls(): List<String> =
        if (MODEL_BASE_URL.isEmpty()) emptyList() else MIRROR_PREFIXES.map { it + MODEL_BASE_URL }

    /**
     * 下载整套模型（清单 + 三个必需文件）并落盘。
     *
     * 返回 true 表示**已通过 sha256 校验**、可以直接加载。任何一步失败都返回 false，
     * 目录里不会留下半个模型：所有写入都是先落 `.part`，校验通过后才改名。
     */
    suspend fun download(onProgress: (Progress) -> Unit = {}): Boolean =
        withContext(Dispatchers.IO) {
            val urls = baseUrls()
            if (urls.isEmpty()) {
                Timber.w("神经模型尚未配置下载地址，跳过下载")
                return@withContext false
            }

            val manifest = fetchManifest(urls) ?: return@withContext false
            if (!manifest.isWellFormed()) {
                ToastUtil.showToast("神经模型清单不完整")
                return@withContext false
            }

            val files = manifest.requiredFiles()
            for ((index, entry) in files.withIndex()) {
                val ok = fetchFile(urls, entry) { done, size ->
                    onProgress(Progress(entry.file, index + 1, files.size, done, size))
                }
                if (!ok) {
                    ToastUtil.showToast("神经模型下载失败：${entry.file}")
                    return@withContext false
                }
            }

            val problem = NeuralModelStore.verify(manifest, force = true)
            if (problem != null) {
                ToastUtil.showToast("神经模型校验失败：$problem")
                return@withContext false
            }
            Timber.i("神经模型下载完成：%s", manifest.name)
            true
        }

    private suspend fun fetchManifest(urls: List<String>): NeuralModelManifest? {
        val target = NeuralModelStore.manifestFile()
        for (url in urls) {
            val link = "$url/${NeuralModelManifest.FILE_NAME}"
            if (!downloadTo(link, target) { _, _ -> }) continue
            val parsed = runCatching { NeuralModelManifest.parse(target.readText()) }
                .onFailure { Timber.w(it, "神经模型清单解析失败：%s", link) }
                .getOrNull()
            if (parsed != null) return parsed
            target.delete()
        }
        ToastUtil.showToast("神经模型清单下载失败")
        return null
    }

    private suspend fun fetchFile(
        urls: List<String>,
        entry: NeuralModelFile,
        onProgress: (Long, Long) -> Unit,
    ): Boolean {
        val target = NeuralModelStore.fileFor(entry)
        for (url in urls) {
            if (!downloadTo("$url/${entry.file}", target, onProgress)) continue
            // 下载完立刻校验：坏文件不留到「加载时才失败」
            if (target.length() != entry.bytes) {
                Timber.w("%s 字节数不符（应为 %d，实际 %d）", entry.file, entry.bytes, target.length())
                target.delete()
                continue
            }
            if (!sha256Hex(target).equals(entry.sha256, ignoreCase = true)) {
                Timber.w("%s sha256 校验失败", entry.file)
                target.delete()
                continue
            }
            return true
        }
        return false
    }

    /** 下载到 `<name>.part` 再改名：半个文件永远不会出现在最终名字上。 */
    private suspend fun downloadTo(
        url: String,
        target: File,
        onProgress: (Long, Long) -> Unit,
    ): Boolean {
        val partial = File(target.parentFile, target.name + PART_SUFFIX)
        partial.delete()
        return try {
            val request = Request.Builder().url(url).build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Timber.w("%s 下载失败：HTTP %d", TAG, response.code)
                    partial.delete()
                    return false
                }
                val body = response.body ?: run {
                    partial.delete()
                    return false
                }
                val total = body.contentLength()
                var downloaded = 0L
                var reported = 0L
                body.byteStream().use { input ->
                    partial.outputStream().buffered(BUFFER_SIZE).use { output ->
                        val buffer = ByteArray(BUFFER_SIZE)
                        while (true) {
                            if (!currentCoroutineContext().isActive) {
                                partial.delete()
                                return false
                            }
                            val read = input.read(buffer)
                            if (read <= 0) break
                            output.write(buffer, 0, read)
                            downloaded += read
                            if (downloaded - reported >= REPORT_STEP) {
                                reported = downloaded
                                onProgress(downloaded, total)
                            }
                        }
                    }
                }
                if (!currentCoroutineContext().isActive) {
                    partial.delete()
                    return false
                }
                onProgress(downloaded, total)
                target.delete()
                if (!partial.renameTo(target)) {
                    Timber.e("%s 改名失败", target.name)
                    partial.delete()
                    return false
                }
                true
            }
        } catch (error: Exception) {
            Timber.w(error, "%s 下载异常：%s", TAG, url)
            partial.delete()
            false
        }
    }

    private fun sha256Hex(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered(BUFFER_SIZE).use { stream ->
            val buffer = ByteArray(BUFFER_SIZE)
            while (true) {
                val read = stream.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
