package com.ninthsoft.ime.input.handwriting

import android.content.Context
import android.os.SystemClock
import com.ninthsoft.ime.input.handwriting.ochwpro.HandwritingOnnxNative
import com.ninthsoft.ime.input.handwriting.ochwpro.OchwproDecode
import com.ninthsoft.ime.input.handwriting.ochwpro.OchwproPreprocess
import org.json.JSONObject
import timber.log.Timber
import java.io.File

/**
 * 本地手写引擎：onnxruntime（复用语音模块已有的那份）+ ochwpro 模型
 * （StrokeTransformer，7356 类）。
 *
 * 为什么是它（而不是早先 vendor 进 NDK 的 zinnia）：
 * zinnia 是 2008 年的线性 SVM + 手工 8 方向特征，6763 个类挤在约 490 个特征上，
 * 形近字必然混淆，实测准确率不可用。ochwpro 是 1.72M 参数的 Transformer，
 * 在 CASIA-OLHWDB 上 top-1 约 90%，**而且体积反而小得多（7.0 MB vs 26.8 MB）**。
 * 换掉它同时删掉了 `cpp/zinnia/`、原来的 JNI 与一处上游源码补丁。
 *
 * 这个引擎承担 Prompt 里「永远可用」的底线：模型随 APK 打包、零下载，
 * 飞行模式下从冷启动到可用不依赖任何网络与 GMS。
 *
 * 推理经由 [HandwritingOnnxNative]（C API 直调），**不引入额外的 native 库** ——
 * 体积与依赖的取舍见该对象的注释。
 */
class OnnxEngine : HandwritingEngine {

    override val displayName: String = "本地"

    @Volatile
    override var lastError: String? = null
        private set

    private var loaded = false
    private var chars: List<String> = emptyList()

    /**
     * 输出缓冲，复用而不是每次识别新分配（7356 个 float ≈ 29 KB，
     * 连续书写时这个分配频率会体现在 GC 上）。
     *
     * 复用的前提是**识别串行**：接口约定 [load] / [recognize] 都在调用方的
     * 单线程执行器上跑（见 [HandwritingEngine] 的说明）。
     */
    private var logitsBuffer: FloatArray? = null

    /** [chars] 的规模，供设置页/探针页展示覆盖范围。 */
    val charCount: Int get() = chars.size

    /** 实际链到的 ONNX Runtime 版本，供诊断展示（失败时为空）。 */
    val runtimeVersion: String
        get() = if (HandwritingOnnxNative.isAvailable) {
            runCatching { HandwritingOnnxNative.nativeRuntimeVersion() }.getOrDefault("")
        } else {
            ""
        }

    /** native 库是否可用。不可用时 [loadFailedReason] 说明原因。 */
    val nativeAvailable: Boolean get() = HandwritingOnnxNative.isAvailable

    val nativeFailureReason: String? get() = HandwritingOnnxNative.loadFailedReason

    override fun load(context: Context): Boolean {
        lastError = null
        if (loaded) return true

        // native 库不可用是最先要报的一类故障：它几乎只有一种成因
        // （语音模块的 sherpa AAR 被移除），单独说清楚比让它以
        // UnsatisfiedLinkError 的形式抛出去有用得多。
        if (!HandwritingOnnxNative.isAvailable) {
            lastError = "手写 native 库未加载：${HandwritingOnnxNative.loadFailedReason ?: "未知原因"}"
            return false
        }

        if (!HandwritingModelStore.isReady(context) &&
            !HandwritingModelStore.ensureExtracted(context)
        ) {
            lastError = "本地模型未就绪：assets/${HandwritingModelStore.DIR_NAME}/ " +
                "下的模型或字符表缺失/校验失败"
            return false
        }

        val started = SystemClock.elapsedRealtime()

        val loadedChars = runCatching {
            readCharIndex(HandwritingModelStore.charIndexFile(context))
        }.getOrElse { error ->
            lastError = "读取字符表失败：${error.message ?: error.javaClass.simpleName}"
            Timber.e(error, "读取手写字符表失败")
            return false
        }
        if (loadedChars.isEmpty()) {
            lastError = "字符表为空（${HandwritingModelStore.CHAR_INDEX_FILE_NAME}）"
            return false
        }

        val modelPath = HandwritingModelStore.modelFile(context).absolutePath
        val ok = runCatching { HandwritingOnnxNative.nativeLoad(modelPath) }
            .getOrElse { error ->
                lastError = "建立 ONNX 会话异常：${error.message ?: error.javaClass.simpleName}"
                Timber.e(error, "建立 ONNX 会话异常：$modelPath")
                false
            }
        if (!ok) {
            lastError = "建立 ONNX 会话失败：${HandwritingOnnxNative.nativeLastError()}"
            return false
        }

        chars = loadedChars
        logitsBuffer = FloatArray(loadedChars.size)
        loaded = true

        Timber.i(
            "本地手写模型已加载：%dms path=%s 字符数=%d onnxruntime=%s",
            SystemClock.elapsedRealtime() - started,
            modelPath,
            chars.size,
            runtimeVersion,
        )
        return true
    }

    override fun isAvailable(): Boolean = loaded

    /**
     * [writingAreaWidth] / [writingAreaHeight] **故意不使用**。
     *
     * 这两个参数是给「用绝对坐标做特征」的引擎（Google ML Kit）准备的。
     * ochwpro 自带逐轴包围盒归一化，喂进来的坐标只需是稳定的相对坐标系，
     * 手写区的绝对尺寸不进特征；把尺寸乘进去反而是错的。
     * 接口保留这两个参数是为了让调用方不必区分引擎类型。
     */
    @Suppress("UNUSED_PARAMETER")
    override fun recognize(
        strokes: List<HwStroke>,
        writingAreaWidth: Int,
        writingAreaHeight: Int,
        nbest: Int,
    ): List<HwCandidate> {
        lastError = null
        if (nbest <= 0) return emptyList()
        if (!loaded) {
            lastError = "本地引擎未加载"
            return emptyList()
        }
        if (StrokeMath.isEmpty(strokes)) {
            lastError = "笔迹为空"
            return emptyList()
        }

        val features = OchwproPreprocess.build(strokes)
        if (features.length == 0) {
            lastError = "笔迹为空"
            return emptyList()
        }

        val logits = logitsBuffer?.takeIf { it.size == chars.size } ?: FloatArray(chars.size)
            .also { logitsBuffer = it }

        val ok = runCatching {
            HandwritingOnnxNative.nativeRun(features.values, features.mask, logits)
        }.getOrElse { error ->
            lastError = "本地识别异常：${error.message ?: error.javaClass.simpleName}"
            Timber.e(error, "本地手写识别异常")
            false
        }
        if (!ok) {
            lastError = "本地识别失败：${HandwritingOnnxNative.nativeLastError()}"
            return emptyList()
        }

        val scored = OchwproDecode.topK(logits, nbest)
        if (scored.isEmpty()) {
            lastError = "模型输出无法解码（可能全是 NaN）"
            return emptyList()
        }

        // 去重：字符表里同一个字可能出现多次（不同字形的类），
        // 不去重会让候选条出现两个一模一样的字，挤掉真正的第二候选。
        val seen = HashSet<String>(scored.size * 2)
        val result = ArrayList<HwCandidate>(scored.size)
        for (item in scored) {
            val text = chars.getOrNull(item.index) ?: continue
            if (text.isEmpty()) continue
            if (!seen.add(text)) continue
            // 概率已经是真正的概率（OchwproDecode 里做过 softmax），可直接展示与比较
            result.add(HwCandidate(text = text, score = item.probability, scoreIsFallback = false))
        }
        return result
    }

    override fun close() {
        if (HandwritingOnnxNative.isAvailable) {
            runCatching { HandwritingOnnxNative.nativeClose() }
        }
        chars = emptyList()
        logitsBuffer = null
        loaded = false
    }

    /** 读字符表。格式为 `{"chars": ["一", "丁", ...]}`，下标即模型输出下标。 */
    private fun readCharIndex(file: File): List<String> {
        val text = file.readText().trimStart('\uFEFF')
        val array = JSONObject(text).getJSONArray("chars")
        return List(array.length()) { array.optString(it, "") }
    }
}
