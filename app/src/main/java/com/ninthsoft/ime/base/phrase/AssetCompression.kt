package com.ninthsoft.ime.base.phrase

import java.io.InputStream
import java.io.PushbackInputStream
import java.util.zip.GZIPInputStream

/**
 * 资产可能被 aapt2 动过，读之前先看内容而不是看文件名。
 *
 * **实测（AGP 9 + aapt2）**：放进仓库的 `phrase_index.tsv.gz` 在 APK 里变成了
 * `assets/phrase/phrase_index.tsv`，内容已是**明文 TSV**，条目还是 `Defl:N` 压过的。
 * 也就是说 aapt2 会把 `.gz` 资产就地解压并去掉后缀，即使已经声明了 `noCompress += "gz"`。
 *
 * 这条行为在不同 AGP 版本上并不稳定，所以这里不赌任何一种：
 * 由调用方把两个可能的资产名都试一遍，再按**头两个字节**判断要不要 gunzip。
 * gzip 的魔数是 `1f 8b`；明文 TSV 的首字节是汉字 UTF-8（≥ `0xE0`），两者不会混淆。
 *
 * 用 [PushbackInputStream] 而不是 `mark/reset`：`AssetManager.open()` 返回的流
 * 不保证支持 mark，拿它去试读两个字节会直接抛异常。
 */
object AssetCompression {
    private const val MAGIC_LENGTH = 2
    private const val GZIP_MAGIC_0 = 0x1f
    private const val GZIP_MAGIC_1 = 0x8b

    fun wrapIfGzip(raw: InputStream, bufferSize: Int = DEFAULT_BUFFER_SIZE): InputStream {
        val probe = PushbackInputStream(raw, MAGIC_LENGTH)
        val magic = ByteArray(MAGIC_LENGTH)
        val read = probe.read(magic)
        if (read > 0) probe.unread(magic, 0, read)

        val isGzip = read == MAGIC_LENGTH &&
            (magic[0].toInt() and 0xFF) == GZIP_MAGIC_0 &&
            (magic[1].toInt() and 0xFF) == GZIP_MAGIC_1
        return if (isGzip) GZIPInputStream(probe, bufferSize) else probe
    }

    private const val DEFAULT_BUFFER_SIZE = 64 * 1024
}
