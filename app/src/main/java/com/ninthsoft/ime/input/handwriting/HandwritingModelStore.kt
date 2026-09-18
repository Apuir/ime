package com.ninthsoft.ime.input.handwriting

import android.content.Context
import com.ninthsoft.ime.data.App
import timber.log.Timber
import java.io.File

/**
 * 本地手写模型（ochwpro）的存放与就绪判定。
 *
 * 一对两个文件，**必须成对**：
 * - `ochwpro.onnx`   —— 模型本体（7.0 MB）
 * - `char_index.json` —— 输出下标到汉字的映射表（7356 项，51 KB）
 * 缺任何一个都不可用：模型可能跑出 logits，但没有字符表就没法变成候选字。
 *
 * 设计要点：
 * - 两个文件都随 APK 打进 `assets/handwriting/`，首次使用拷到**内部**
 *   `filesDir/handwriting/`。选内部目录而不是外部：本地引擎是「永远可用」的底线，
 *   不该随外部存储的挂载/卸载状态变化。
 * - 校验用 **sha256**（不同于项目里语音模型的 MD5）：这两个值是上游模型索引
 *   （Xime 的 `xime-index`）直接公布的，用同一算法可以直接与上游对照，
 *   省掉「我算的哈希」和「上游的哈希」之间多出来的一层不确定性。
 * - 校验通过后落一个戳记文件，之后只比对戳记，不重复哈希 7 MB。
 * - 允许**外部同名文件覆盖**：`<外部 files>/model/handwriting/` 下若两个文件都在，
 *   则优先使用。这样用户想换模型或换字符表无需重新打包。
 *   **要求两个都在**：只覆盖其中一个会让索引与模型错位，
 *   而错位的表现是「识别结果整体乱掉」，比直接报错难查得多。
 */
object HandwritingModelStore {

    /** assets 与 filesDir 下的子目录名。 */
    const val DIR_NAME = "handwriting"

    /** 模型文件名。 */
    const val MODEL_FILE_NAME = "ochwpro.onnx"

    /** 字符表文件名。 */
    const val CHAR_INDEX_FILE_NAME = "char_index.json"

    /**
     * 模型 sha256。对应 Xime 模型索引里公布的 `ochwpro.onnx`（7,001,270 字节）。
     */
    const val MODEL_SHA256 =
        "eb04d62a314c7d7bac4e34d6ce0c24137474d30ff94b929115a621385420ce13"

    /** 模型字节数。哈希之外再卡一道大小，能提前发现「拷了一半就崩」的情况。 */
    const val MODEL_SIZE = 7_001_270L

    /** 字符表 sha256，同样取自上游索引。 */
    const val CHAR_INDEX_SHA256 =
        "171cf2ac23731428ac9dd28f64be82dece5f66a0ca3d674ffb3b567b51532176"

    /** 字符表字节数。 */
    const val CHAR_INDEX_SIZE = 51_317L

    private const val STAMP_FILE_NAME = "model.sha256"

    /** 进程内缓存，避免每次调用都去读盘/算哈希。 */
    @Volatile
    private var cachedReady: Boolean = false

    /** 内部目录（模型最终落点）。 */
    fun internalDir(context: Context): File =
        File(context.filesDir, DIR_NAME).apply { mkdirs() }

    /** 外部覆盖目录，沿用项目既有的外部 `model/`（`App.modelDir`，语音模型也在这一层）。 */
    private fun externalDir(): File = File(App.modelDir, DIR_NAME)

    private fun internalFile(context: Context, name: String): File =
        File(internalDir(context), name)

    /**
     * 外部覆盖是否成对可用。成对才认，理由见类注释。
     */
    private fun hasExternalPair(): Boolean {
        val dir = externalDir()
        val model = File(dir, MODEL_FILE_NAME)
        val index = File(dir, CHAR_INDEX_FILE_NAME)
        return model.isFile && model.length() == MODEL_SIZE &&
            index.isFile && index.length() == CHAR_INDEX_SIZE
    }

    /** 当前实际生效的模型文件：外部覆盖优先（且成对），否则用内部的。 */
    fun modelFile(context: Context): File =
        if (hasExternalPair()) File(externalDir(), MODEL_FILE_NAME)
        else internalFile(context, MODEL_FILE_NAME)

    /** 当前实际生效的字符表文件。 */
    fun charIndexFile(context: Context): File =
        if (hasExternalPair()) File(externalDir(), CHAR_INDEX_FILE_NAME)
        else internalFile(context, CHAR_INDEX_FILE_NAME)

    /**
     * 是否两个文件都已就绪。会先看内存缓存与戳记，必要时才真正算一次哈希。
     *
     * **必须在后台线程调用**（首次可能要读 7 MB）。
     */
    fun isReady(context: Context): Boolean {
        if (cachedReady) return true

        if (hasExternalPair()) {
            // 外部覆盖不校验哈希：它是用户手工放进去的，
            // 校验失败会把「用户自己换的模型」误判成不可用。
            cachedReady = true
            return true
        }

        val model = internalFile(context, MODEL_FILE_NAME)
        val index = internalFile(context, CHAR_INDEX_FILE_NAME)
        if (!model.isFile || model.length() != MODEL_SIZE) return false
        if (!index.isFile || index.length() != CHAR_INDEX_SIZE) return false

        val stamp = File(internalDir(context), STAMP_FILE_NAME)
        val stamped = runCatching { stamp.readText().trim() }.getOrNull()
        if (stamped == stampValue()) {
            cachedReady = true
            return true
        }

        val modelHash = sha256Of(model) ?: return false
        if (modelHash != MODEL_SHA256) {
            Timber.w("手写模型 sha256 不匹配：expected=$MODEL_SHA256 actual=$modelHash")
            return false
        }
        val indexHash = sha256Of(index) ?: return false
        if (indexHash != CHAR_INDEX_SHA256) {
            Timber.w("手写字符表 sha256 不匹配：expected=$CHAR_INDEX_SHA256 actual=$indexHash")
            return false
        }

        runCatching { stamp.writeText(stampValue()) }
        cachedReady = true
        return true
    }

    /**
     * 确保两个文件都已在内部目录就绪；需要时从 assets 解出。返回是否可用。
     *
     * 每个文件都先写临时文件再改名，避免中途失败留下半个文件被当成「已就绪」。
     * **必须在后台线程调用。**
     */
    fun ensureExtracted(context: Context): Boolean {
        if (isReady(context)) return true
        if (hasExternalPair()) {
            cachedReady = true
            return true
        }

        val modelOk = extract(
            context = context,
            assetName = MODEL_FILE_NAME,
            target = internalFile(context, MODEL_FILE_NAME),
            expectedSize = MODEL_SIZE,
        )
        val indexOk = extract(
            context = context,
            assetName = CHAR_INDEX_FILE_NAME,
            target = internalFile(context, CHAR_INDEX_FILE_NAME),
            expectedSize = CHAR_INDEX_SIZE,
        )
        if (!modelOk || !indexOk) return false
        return isReady(context)
    }

    private fun extract(
        context: Context,
        assetName: String,
        target: File,
        expectedSize: Long,
    ): Boolean {
        val temp = File(internalDir(context), "$assetName.tmp")
        return try {
            context.assets.open("$DIR_NAME/$assetName").use { input ->
                temp.outputStream().use { output -> input.copyTo(output) }
            }
            if (temp.length() != expectedSize) {
                Timber.e("手写资源解出后大小不符：$assetName ${temp.length()} != $expectedSize")
                temp.delete()
                return false
            }
            if (target.exists()) target.delete()
            if (!temp.renameTo(target)) {
                Timber.e("手写资源改名失败：${temp.absolutePath} -> ${target.absolutePath}")
                temp.delete()
                return false
            }
            true
        } catch (e: Exception) {
            Timber.e(e, "手写资源解包失败：$assetName")
            temp.delete()
            false
        }
    }

    /** 删除已解出的模型与戳记（设置页「删除本地模型」用，也可用来从损坏状态恢复）。 */
    fun deleteExtracted(context: Context) {
        cachedReady = false
        runCatching {
            internalDir(context).listFiles()?.forEach { it.deleteRecursively() }
        }
    }

    private fun stampValue(): String = "$MODEL_SHA256 $CHAR_INDEX_SHA256"

    private fun sha256Of(file: File): String? = runCatching {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(256 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        digest.digest().joinToString("") { "%02x".format(it) }
    }.getOrElse {
        Timber.e(it, "计算手写资源 sha256 失败")
        null
    }

    /** 供设置页展示模型路径。 */
    fun describePath(context: Context): String = modelFile(context).absolutePath
}
