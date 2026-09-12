package com.ninthsoft.ime.base.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import timber.log.Timber
import java.util.concurrent.TimeUnit

@Serializable
data class AppVersionInfo(
    val latestVersion: String = "",
    val website: String = "",
)

/**
 * 在线检查更新。
 *
 * [UPDATE_INFO_URL] 留空 = 暂不提供在线检查更新，设置页不会显示「检查更新」入口。
 * 以后把版本信息放到 GitHub Releases 或自己的服务器上时，在这里填完整地址即可，
 * 需要返回这样的 JSON：
 * ```json
 * { "latestVersion": "2.0.1", "website": "https://…/releases" }
 * ```
 * （GitHub 的 releases API 字段名不同，需要自己写个适配接口或者加一层转换。）
 */
object VersionChecker {
    const val UPDATE_INFO_URL = ""

    /** 是否配置了更新地址；未配置时隐藏「检查更新」入口。 */
    val isConfigured: Boolean get() = UPDATE_INFO_URL.isNotBlank()

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private val json = Json { ignoreUnknownKeys = true }

    /** 返回需要打开的更新地址；null = 未配置或检查失败，空串 = 已是最新。 */
    suspend fun check(currentVersion: String): String? {
        if (!isConfigured) return null
        val remote = fetch()?.takeIf { it.latestVersion.isNotBlank() } ?: return null
        return when {
            compareVersions(remote.latestVersion.trim(), currentVersion) > 0 ->
                remote.website.trim().takeIf { it.isNotEmpty() }
            else -> ""
        }
    }

    private suspend fun fetch(): AppVersionInfo? = runCatching {
        withContext(Dispatchers.IO) {
            client.newCall(Request.Builder().url(UPDATE_INFO_URL).build()).execute().use { response ->
                if (!response.isSuccessful) error("HTTP ${response.code}")
                response.body?.string().orEmpty()
            }
        }
    }.onFailure {
        Timber.w(it, "Version check failed")
    }.getOrNull()?.let { raw ->
        runCatching { json.decodeFromString<AppVersionInfo>(raw) }
            .onFailure { Timber.w(it, "Version info malformed") }
            .getOrNull()
    }

    private fun compareVersions(left: String, right: String): Int {
        val a = left.split('.', '-', '_').map { it.toIntOrNull() ?: 0 }
        val b = right.split('.', '-', '_').map { it.toIntOrNull() ?: 0 }
        for (index in 0 until maxOf(a.size, b.size)) {
            val result = (a.getOrElse(index) { 0 }).compareTo(b.getOrElse(index) { 0 })
            if (result != 0) return result
        }
        return 0
    }
}
