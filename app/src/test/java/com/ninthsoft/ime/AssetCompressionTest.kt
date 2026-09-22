package com.ninthsoft.ime

import com.ninthsoft.ime.base.phrase.AssetCompression
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream

/**
 * 资产压缩嗅探的回归。
 *
 * 这一段是被真实打包行为逼出来的：仓库里放的是 gzip，但 **aapt2 会在打包时把它解开并
 * 去掉 `.gz` 后缀**（实测 APK 里是明文 TSV），而不同 AGP 版本行为又不一致。
 * 所以「按内容而不是按文件名判断」是唯一稳的做法 —— 这条判断要是错了，
 * 短语补全会在真机上整体静默失效（查询返回空，不报错）。
 */
class AssetCompressionTest {

    private val plain = "一心一\t一心一意\t100\n床前明月光\t疑是地上霜\t80\n"

    private fun gzip(text: String): ByteArray {
        val out = ByteArrayOutputStream()
        GZIPOutputStream(out).use { it.write(text.toByteArray(Charsets.UTF_8)) }
        return out.toByteArray()
    }

    private fun readAll(bytes: ByteArray): String =
        AssetCompression.wrapIfGzip(ByteArrayInputStream(bytes)).use {
            it.readBytes().toString(Charsets.UTF_8)
        }

    @Test
    fun gzippedAssetIsDecompressed() {
        assertEquals(plain, readAll(gzip(plain)))
    }

    /** aapt2 已经解开的情况：内容就是明文，不能再套一层 gunzip。 */
    @Test
    fun alreadyPlainAssetIsPassedThrough() {
        assertEquals(plain, readAll(plain.toByteArray(Charsets.UTF_8)))
    }

    /** 首字节是汉字 UTF-8（≥0xE0），与 gzip 魔数 1f 8b 不可能混淆。 */
    @Test
    fun cjkFirstByteIsNotMistakenForGzip() {
        assertEquals("床", readAll("床".toByteArray(Charsets.UTF_8)))
    }

    @Test
    fun shortAndEmptyInputsAreHandled() {
        assertEquals("", readAll(ByteArray(0)))
        assertEquals("a", readAll(byteArrayOf('a'.code.toByte())))
        // 只有一个字节、恰好是 gzip 魔数的前半：不能当成 gzip 去解，必须原样返回
        val single = AssetCompression.wrapIfGzip(ByteArrayInputStream(byteArrayOf(0x1f)))
            .use { it.readBytes() }
        assertEquals(1, single.size)
        assertEquals(0x1f.toByte(), single[0])
    }

    /** 嗅探不能吃掉内容：试读的两个字节必须吐回流里。 */
    @Test
    fun probeDoesNotConsumeTheStream() {
        // 前两个字节是明文 '1' 'f'（0x31 0x66），不是 gzip 魔数 1f 8b
        val payload = "1f8b看起来像魔数但不是"
        assertEquals(payload, readAll(payload.toByteArray(Charsets.UTF_8)))
    }
}
