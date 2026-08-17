package com.ninthsoft.ime.base.util

import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import timber.log.Timber
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.io.OutputStream

/**
 * .tar.bz2 解包工具：针对 BZip2 的流特性进行了多层缓存优化，大幅提升解压速度。
 */
object TarBz2Extractor {

    private const val BUFFER = 64 * 1024
    private const val STREAM_BUFFER = 256 * 1024
    private const val REPORT_STEP = 256 * 1024L
    private const val LOG_STEP = 16 * 1024 * 1024L

    fun extract(
        archive: File,
        destDir: File,
        onProgress: (current: Long, total: Long) -> Unit = { _, _ -> },
        filter: (entryName: String) -> Boolean = { true },
    ) {
        destDir.mkdirs()
        val destPath =
            destDir.canonicalPath.let { if (it.endsWith(File.separator)) it else "$it${File.separator}" }
        val total = archive.length()

        val counting = CountingInputStream(FileInputStream(archive))
        val state = ProgressState()

        counting.use { cis ->
            // 【关键修复】BZip2 必须包裹在 BufferedInputStream 中，否则每次读取都是灾难性的慢
            val bufferedIn = BufferedInputStream(cis, STREAM_BUFFER)
            BZip2CompressorInputStream(bufferedIn).use { bzIn ->
                // TarArchiveInputStream 同样需要高效的缓冲输入流
                val tarBufferedIn = BufferedInputStream(bzIn, STREAM_BUFFER)
                TarArchiveInputStream(tarBufferedIn).use { tarIn ->
                    var entry = tarIn.nextEntry
                    while (entry != null) {
                        val entryName = entry.name
                        val outFile = File(destDir, entryName)
                        val outPath = outFile.canonicalPath

                        // 严格路径穿越防护
                        if (!outPath.startsWith(destPath) && outPath != destDir.canonicalPath) {
                            Timber.w("Skip unsafe archive entry: %s", entryName)
                        } else {
                            val shouldExtract = filter(entryName)
                            if (entry.isDirectory) {
                                if (shouldExtract) outFile.mkdirs()
                            } else {
                                if (shouldExtract) {
                                    outFile.parentFile?.mkdirs()
                                    BufferedOutputStream(
                                        outFile.outputStream(), STREAM_BUFFER
                                    ).use { os ->
                                        pump(tarIn, os, counting, total, onProgress, state)
                                    }
                                } else {
                                    // 过滤条目：纯消费跳过，不写盘
                                    pump(tarIn, null, counting, total, onProgress, state)
                                }
                            }
                        }
                        entry = tarIn.nextEntry
                    }
                }
            }
        }
        onProgress(counting.bytesRead.coerceAtMost(total), total)
    }

    private fun pump(
        tarIn: TarArchiveInputStream,
        out: OutputStream?,
        counting: CountingInputStream,
        total: Long,
        onProgress: (Long, Long) -> Unit,
        state: ProgressState,
    ) {
        val buffer = ByteArray(BUFFER)
        var n: Int
        while (tarIn.read(buffer).also { n = it } != -1) {
            out?.write(buffer, 0, n)
            val currentBytes = counting.bytesRead

            if (currentBytes - state.lastReported >= REPORT_STEP) {
                state.lastReported = currentBytes
                onProgress(currentBytes, total)
            }
            if (currentBytes - state.lastLogged >= LOG_STEP) {
                state.lastLogged = currentBytes
                Timber.d("Extracting... %.1f%%", currentBytes * 100f / total)
            }
        }
        out?.flush()
    }

    private data class ProgressState(
        var lastReported: Long = 0L,
        var lastLogged: Long = 0L,
    )

    private class CountingInputStream(private val src: InputStream) : InputStream() {
        var bytesRead = 0L
            private set

        override fun read(): Int {
            return src.read().also { if (it >= 0) bytesRead++ }
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            return src.read(b, off, len).also { if (it > 0) bytesRead += it }
        }

        override fun close() = src.close()
    }
}