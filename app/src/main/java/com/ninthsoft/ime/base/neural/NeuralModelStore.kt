package com.ninthsoft.ime.base.neural

import com.ninthsoft.ime.data.App
import timber.log.Timber
import java.io.File
import java.security.MessageDigest

/**
 * 神经模型在设备上的落盘位置与完整性校验。
 *
 * 模型不随包（int8 约 130 MB），放在 app 私有外部目录 `model/neural/`，
 * 与语音模型 `model/speech/`、手写 `model/handwriting/` 同一层。
 *
 * 校验分两步，顺序不能反：
 * 1. **先比字节数** —— 一次 `stat` 就能发现「根本没下完」这类绝大多数问题，
 *    比读完 130 MB 算 sha256 便宜几个数量级；
 * 2. **再算 sha256** —— 只有字节数对得上时才值得花这个时间，结果写进戳文件，
 *    后续启动直接信任（否则每次冷启动都要读 130 MB，键盘启动会明显变慢）。
 */
object NeuralModelStore {
    const val DIR_NAME = "neural"

    private const val STAMP_FILE = ".verified"
    private const val BUFFER_SIZE = 64 * 1024

    fun dir(): File = File(App.modelDir, DIR_NAME).also { it.mkdirs() }

    fun manifestFile(): File = File(dir(), NeuralModelManifest.FILE_NAME)

    fun readManifest(): NeuralModelManifest? {
        val file = manifestFile()
        if (!file.isFile) return null
        return runCatching { NeuralModelManifest.parse(file.readText()) }
            .onFailure { Timber.w(it, "神经模型清单解析失败") }
            .getOrNull()
    }

    fun fileFor(entry: NeuralModelFile): File = File(dir(), entry.file)

    /** 模型文件是否都已存在（**不校验内容**，只用于「要不要显示下载按钮」这类判断）。 */
    fun isPresent(manifest: NeuralModelManifest): Boolean =
        manifest.requiredFiles().all { fileFor(it).isFile }

    /**
     * 校验模型文件。返回 `null` 表示可用，否则返回给人看的失败原因。
     *
     * [force] 为 true 时跳过戳文件、强制重算 sha256（用户手动替换了模型时用）。
     */
    fun verify(manifest: NeuralModelManifest, force: Boolean = false): String? {
        if (!manifest.isWellFormed()) return "模型清单不完整"

        val directory = dir()
        val expected = manifest.requiredFiles()
        for (entry in expected) {
            val file = File(directory, entry.file)
            if (!file.isFile) return "缺少文件 ${entry.file}"
            if (file.length() != entry.bytes) {
                return "${entry.file} 大小不符（应为 ${entry.bytes}，实际 ${file.length()}）"
            }
        }

        if (!force && stampMatches(directory, expected)) return null

        for (entry in expected) {
            val actual = sha256Hex(File(directory, entry.file))
            if (!actual.equals(entry.sha256, ignoreCase = true)) {
                return "${entry.file} 校验失败（文件已损坏或被改动）"
            }
        }
        writeStamp(directory, expected)
        return null
    }

    fun delete() {
        val directory = dir()
        val removed = directory.listFiles()?.count { it.delete() } ?: 0
        Timber.i("已删除神经模型，共 %d 个文件", removed)
    }

    /** 人类可读的大小，设置页直接用。 */
    fun formatBytes(bytes: Long): String = when {
        bytes <= 0L -> "0 MB"
        bytes >= 1024L * 1024 * 1024 -> String.format("%.2f GB", bytes / 1024.0 / 1024 / 1024)
        else -> String.format("%.1f MB", bytes / 1024.0 / 1024)
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

    /**
     * 戳文件内容形如 `<文件名> <sha256> <字节数>`，每行一个文件。
     * 三项任一变化都视为过期：字节数是比 sha256 更便宜的「文件被换过」的证据。
     */
    private fun stampMatches(directory: File, expected: List<NeuralModelFile>): Boolean {
        val stamp = File(directory, STAMP_FILE)
        if (!stamp.isFile) return false
        val lines = runCatching { stamp.readLines() }.getOrNull() ?: return false
        if (lines.size != expected.size) return false
        return expected.zip(lines).all { (entry, line) ->
            line == "${entry.file} ${entry.sha256} ${entry.bytes}"
        }
    }

    private fun writeStamp(directory: File, expected: List<NeuralModelFile>) {
        val text = expected.joinToString("\n") { "${it.file} ${it.sha256} ${it.bytes}" }
        runCatching { File(directory, STAMP_FILE).writeText(text) }
            .onFailure { Timber.w(it, "写入神经模型校验戳失败") }
    }
}
