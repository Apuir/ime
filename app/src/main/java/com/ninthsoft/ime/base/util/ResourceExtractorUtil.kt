package com.ninthsoft.ime.base.util

import android.content.Context
import timber.log.Timber
import java.io.File
import java.io.InputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

object ResourceExtractorUtil {

    private val SKIP_PATTERNS = listOf(
        "__MACOSX",
        ".DS_Store",
        "PaxHeader",
    )

    /**
     * 这个资源包是手工打的，用 `zip -r resource.zip resource/` 那种打法会多出一层 `resource/`，
     * 而解压结果必须是 `<外部目录>/shared` 和 `<外部目录>/model`，所以这里统一把这层壳剥掉。
     * 压缩包本来就以 shared/ 和 model/ 开头时，这个前缀不会命中，等于什么都不做。
     */
    private const val WRAPPER_PREFIX = "resource/"
    private const val WRAPPER_DIR = "resource"

    fun extract(context: Context, assetName: String, destDir: File) {
        Timber.d("Extracting %s to: %s", assetName, destDir.absolutePath)
        context.assets.open(assetName).use { input ->
            extractZip(input, destDir)
        }
    }

    private fun extractZip(input: InputStream, destDir: File) {
        ZipInputStream(input).use { zip ->
            var fileCount = 0
            val destCanonicalPath = destDir.canonicalPath
            var entry: ZipEntry? = zip.nextEntry

            while (entry != null) {
                val rawName = entry.name.trimEnd('/')
                // 壳目录自身映射成空名，交给下面的 shouldSkip 丢掉
                val name = if (rawName == WRAPPER_DIR) {
                    ""
                } else {
                    rawName.removePrefix(WRAPPER_PREFIX)
                }
                val simpleName = File(name).name

                if (shouldSkip(name, simpleName)) {
                    Timber.d("  skipped: %s", name)
                    zip.closeEntry()
                    entry = zip.nextEntry
                    continue
                }

                val destFile = File(destDir, name).canonicalFile

                if (!destFile.path.startsWith(destCanonicalPath)) {
                    Timber.w("  skipped illegal path: %s", name)
                    zip.closeEntry()
                    entry = zip.nextEntry
                    continue
                }

                if (entry.isDirectory) {
                    destFile.mkdirs()
                } else {
                    destFile.parentFile?.mkdirs()
                    destFile.outputStream().use { out ->
                        zip.copyTo(out, 64 * 1024)
                    }
                    fileCount++
                    Timber.d("  extracted: %s", name)
                }

                zip.closeEntry()
                entry = zip.nextEntry
            }
            Timber.d("Zip extraction complete: %d files", fileCount)
        }
    }

    private fun shouldSkip(path: String, simpleName: String): Boolean {
        if (simpleName.isEmpty()) return true
        if (simpleName.startsWith("._")) return true
        return path.split('/').any { segment ->
            SKIP_PATTERNS.contains(segment)
        }
    }
}