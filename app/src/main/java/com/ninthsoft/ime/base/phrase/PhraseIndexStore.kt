package com.ninthsoft.ime.base.phrase

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import java.io.RandomAccessFile
import java.nio.channels.FileChannel

/**
 * 短语索引的装载。
 *
 * 索引是构建产物（`scripts/phrase-index/` 产出），**随包发布**：它只有十几 MB，
 * 而且是「打「床前明月光」立刻出「疑是地上霜」」这类能力的全部依据，
 * 让用户先下个东西才能用不划算。
 *
 * 索引不能直接从资产查询（要按偏移表随机访问），所以先落到 app 私有目录再 mmap。
 * 落盘用「长度做版本戳」跳过重复工作：索引只会随版本变化，
 * 没必要每次冷启动都重写 24 MB。
 *
 * 资产不存在时返回 null，功能整体降级（不是错误）：裁剪包/老包都可能没有它。
 */
object PhraseIndexStore {
    /**
     * 资产路径候选，按顺序试。
     *
     * 两个名字都留着是因为 aapt2 会把 `.gz` 资产解压并去掉后缀（见 [AssetCompression]），
     * 打包后的实际名字取决于 AGP 版本，不能只认一个。
     */
    private val ASSET_PATHS = listOf(
        "phrase/phrase_index.tsv.gz",
        "phrase/phrase_index.tsv",
    )

    private const val CACHE_DIR = "phrase"
    private const val CACHE_FILE = "phrase_index.tsv"
    private const val STAMP_FILE = "phrase_index.stamp"
    private const val BUFFER_SIZE = 64 * 1024

    suspend fun load(context: Context): PhraseIndex? = withContext(Dispatchers.IO) {
        try {
            val dataFile = materialize(context) ?: return@withContext null
            RandomAccessFile(dataFile, "r").use { file ->
                val mapped = file.channel.map(
                    FileChannel.MapMode.READ_ONLY, 0, dataFile.length()
                )
                val index = PhraseIndex.build(mapped)
                Timber.i(
                    "短语索引已加载：%d 条记录 / %.1f MB（mmap）",
                    index.recordCount,
                    dataFile.length() / 1024.0 / 1024.0,
                )
                index
            }
        } catch (error: Exception) {
            // 资产缺失或损坏都不该让联想整体不可用
            Timber.w(error, "短语索引不可用，跳过短语补全")
            null
        }
    }

    /** 把资产落到 app 私有目录并返回可直接 mmap 的文件。 */
    private fun materialize(context: Context): File? {
        val assetPath = ASSET_PATHS.firstOrNull { path ->
            runCatching { context.assets.open(path).close() }.isSuccess
        } ?: return null

        val directory = File(context.filesDir, CACHE_DIR).also { it.mkdirs() }
        val target = File(directory, CACHE_FILE)
        val stamp = File(directory, STAMP_FILE)
        // 版本戳用资产在 APK 里的长度：索引一换长度必变。
        // 取不到长度（资产被压缩存放时 openFd 会失败）就退回用资产名，
        // 那等于「换名字才重写」—— 保守但不会用错数据。
        val version = runCatching { context.assets.openFd(assetPath).use { it.length.toString() } }
            .getOrDefault(assetPath)
        if (target.isFile && target.length() > 0L && stamp.isFile &&
            runCatching { stamp.readText() }.getOrNull() == version
        ) {
            return target
        }

        val partial = File(directory, "$CACHE_FILE.tmp")
        partial.delete()
        context.assets.open(assetPath).use { raw ->
            // 内容嗅探：打包后可能是 gzip，也可能已被 aapt2 解开
            AssetCompression.wrapIfGzip(raw, BUFFER_SIZE).use { stream ->
                partial.outputStream().buffered(BUFFER_SIZE).use { output ->
                    stream.copyTo(output, BUFFER_SIZE)
                }
            }
        }
        // 先落到 .tmp 再改名：不会留下半个索引让下一次误判为「已就绪」
        target.delete()
        if (!partial.renameTo(target)) {
            partial.delete()
            return null
        }
        runCatching { stamp.writeText(version) }
        return target
    }
}
