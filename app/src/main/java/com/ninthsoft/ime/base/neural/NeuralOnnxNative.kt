package com.ninthsoft.ime.base.neural

import timber.log.Timber

/**
 * 神经下一词预测的 native 绑定。
 *
 * 与 [com.ninthsoft.ime.input.handwriting.ochwpro.HandwritingOnnxNative] 同一套约定：
 * `System.loadLibrary` 只加载本模块自己的桥，ONNX Runtime 由桥内部 `dlopen`
 * 复用 APK 里那份 `libonnxruntime.so`（见 `app/src/main/cpp/neural_jni/`）。
 *
 * 这里**只做转发**，不做任何决策 —— 超时、熔断、上下文截断、top-k 全在
 * [NeuralPredictor] 与 [NeuralPrompt] / [NeuralTopK] 里，那些是纯逻辑、可单测；
 * native 侧不可测，所以让它尽可能薄。
 *
 * KV cache 由 native 持有：Kotlin 只说「把这几个字追加到缓存后面」
 * （[nativePrefill]）、「把缓存退回 N 个字」（[nativeTruncate]）、「清空」（[nativeReset]）。
 * 这样回滚是 O(1)，不必把几十 MB 的缓存搬到 Java 堆上。
 */
internal object NeuralOnnxNative {
    @Volatile
    var loadFailedReason: String? = null
        private set

    private val available: Boolean = runCatching {
        System.loadLibrary("neural_jni")
    }.fold(
        onSuccess = { true },
        onFailure = { error ->
            loadFailedReason = error.message
            Timber.e(error, "神经联想 native 库加载失败")
            false
        },
    )

    val isAvailable: Boolean
        get() = available

    /**
     * 建立会话并预分配 KV cache。
     *
     * [maxContextTokens] 是**字符预算**（模型能接受的最大 input_ids 长度），
     * 取自 manifest 的 `context_tokens`。[intraOpThreads] 用来近似「只绑大核」——
     * ONNX Runtime 没有核心亲和性接口，只能靠限制线程数，再靠异步调用不占按键路径。
     */
    external fun nativeLoad(modelPath: String, maxContextTokens: Int, intraOpThreads: Int): Boolean

    /** 把 [ids] 追加到缓存之后跑一次前向；首次调用即整段预填。 */
    external fun nativePrefill(ids: IntArray): Boolean

    /** 取最后一个位置的 logits，长度等于词表大小。 */
    external fun nativeLogits(out: FloatArray): Boolean

    external fun nativeCachedLen(): Int

    /** 把缓存回滚到 [len] 个字符（O(1)，只改长度计数器）。 */
    external fun nativeTruncate(len: Int): Boolean

    external fun nativeReset()

    external fun nativeClose()

    external fun nativeLastError(): String

    external fun nativeRuntimeVersion(): String

    /** 模型结构摘要（层数/头数/词表大小），用于设置页显示与排障。 */
    external fun nativeDescribe(): String
}
