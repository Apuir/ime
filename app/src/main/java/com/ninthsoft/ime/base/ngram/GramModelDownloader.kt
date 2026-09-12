package com.ninthsoft.ime.base.ngram

import com.ninthsoft.ime.base.util.ToastUtil
import com.ninthsoft.ime.engine.rime.data.DataManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import timber.log.Timber
import java.io.File
import java.util.concurrent.TimeUnit

object GramModelDownloader {
    private const val BUFFER_SIZE = 32 * 1024
    private const val REPORT_STEP = 256 * 1024L
    private const val PART_SUFFIX = ".part"

    /**
     * 随包携带的方案（万象拼音）在 `grammar/language` 里声明的名字。
     * 模型按 `<language>.gram` 存放，所以这个值同时就是模型文件名。
     */
    private const val KNOWN_GRAMMAR_LANGUAGE = "wanxiang-lts-zh-hans"

    /**
     * 语法模型下载地址，按顺序尝试。
     *
     * 模型是万象拼音（amzxyz/rime-wanxiang）发布的 LTS 语法模型，这里直接指向作者的发布地址，
     * 不再经过任何中间服务器。CNB 国内速度更好所以放前面，GitHub 作为备用。
     * 以后有自建服务器时，把这里换成自己的地址即可。
     */
    private val GRAMMAR_MODEL_URLS = listOf(
        "https://cnb.cool/amzxyz/rime-wanxiang/-/releases/download/model/" +
            "$KNOWN_GRAMMAR_LANGUAGE.gram",
        "https://github.com/amzxyz/RIME-LMDG/releases/download/LTS/" +
            "$KNOWN_GRAMMAR_LANGUAGE.gram",
    )

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    suspend fun download(
        language: String,
        onProgress: (downloaded: Long, total: Long) -> Unit = { _, _ -> },
    ): Boolean = withContext(Dispatchers.IO) {
        val name = language.trim()
        if (name.isEmpty()) return@withContext false
        if (name != KNOWN_GRAMMAR_LANGUAGE) {
            // 只发布了这一个模型；文件仍按 <language>.gram 存放，与引擎读取规则保持一致。
            Timber.w("No grammar model published for language=%s", name)
        }

        val target = File(DataManager.sharedDataDir, "$name.gram")
        val partial = File(target.parentFile, target.name + PART_SUFFIX)

        for ((index, url) in GRAMMAR_MODEL_URLS.withIndex()) {
            if (!currentCoroutineContext().isActive) return@withContext false
            partial.delete()
            Timber.i("Grammar model download start (mirror %d): %s", index, url)
            if (downloadFile(url, partial, onProgress)) {
                target.delete()
                if (!partial.renameTo(target)) {
                    partial.delete()
                    Timber.e("Failed to finalize grammar model: %s", target.absolutePath)
                    return@withContext false
                }
                return@withContext target.isFile && target.length() > 0L
            }
            partial.delete()
            Timber.w("Grammar model mirror %d failed, trying next", index)
        }
        false
    }

    private suspend fun downloadFile(
        url: String,
        target: File,
        onProgress: (Long, Long) -> Unit,
    ): Boolean {
        return runCatching {
            client.newCall(Request.Builder().url(url).build()).execute().use { response ->
                if (!response.isSuccessful) {
                    Timber.w("Grammar model download failed: HTTP %d", response.code)
                    ToastUtil.showToast("语法模型下载失败：HTTP ${response.code}")
                    return false
                }
                val body = response.body ?: return false
                val total = body.contentLength()
                body.byteStream().use { input ->
                    target.outputStream().use { output ->
                        val buffer = ByteArray(BUFFER_SIZE)
                        var downloaded = 0L
                        var reported = 0L
                        while (true) {
                            if (!currentCoroutineContext().isActive) return false
                            val count = input.read(buffer)
                            if (count < 0) break
                            if (count == 0) continue
                            output.write(buffer, 0, count)
                            downloaded += count
                            if (downloaded - reported >= REPORT_STEP ||
                                (total in 1 downTo downloaded)
                            ) {
                                onProgress(downloaded, total)
                                reported = downloaded
                            }
                        }
                        onProgress(downloaded, total)
                    }
                }
                true
            }
        }.onFailure {
            Timber.e(it, "Grammar model download failed")
            if (it !is kotlinx.coroutines.CancellationException) {
                ToastUtil.showToast("语法模型下载失败：${it.message ?: "网络错误"}")
            }
        }.getOrDefault(false)
    }
}
