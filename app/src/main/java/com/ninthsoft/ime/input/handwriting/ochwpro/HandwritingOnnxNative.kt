package com.ninthsoft.ime.input.handwriting.ochwpro

import timber.log.Timber

/**
 * ochwpro 模型推理的 native 入口（C API 直调 ONNX Runtime）。
 *
 * 为什么不是 ONNX Runtime 的 Java SDK：本项目的 APK 里**已经**有一份
 * `libonnxruntime.so`（语音模块的 sherpa-onnx AAR 提供，且它的 jni 库动态依赖它），
 * 而 onnxruntime-android 的 AAR 会再提供一份同名库、版本又对不上 ——
 * 两者在同一个 APK 里无法共存。所以这里直接复用那一份，
 * 详细推演见 `app/src/main/cpp/handwriting_jni/onnx_handwriting_jni.cc` 的文件头。
 *
 * 由此带来的**隐藏耦合**：本地手写引擎依赖语音模块的 AAR 存在。
 * 若 sherpa-onnx 被移除，[isAvailable] 会为 false 且 [loadFailedReason] 说明原因，
 * [nativeLoad] 也会给出明确的 dlopen 失败信息，不会静默出错。
 */
internal object HandwritingOnnxNative {

    /** 动态库加载失败的原因；正常时为 null。 */
    @Volatile
    var loadFailedReason: String? = null
        private set

    private val available: Boolean = runCatching {
        System.loadLibrary("handwriting_jni")
    }.fold(
        onSuccess = { true },
        onFailure = { error ->
            loadFailedReason = error.message ?: error.javaClass.simpleName
            Timber.e(error, "手写 native 库加载失败")
            false
        },
    )

    /** 动态库是否可用。为 false 时调用下面任何 external 方法都会抛 [UnsatisfiedLinkError]。 */
    val isAvailable: Boolean get() = available

    /** 建立会话。可重复调用，已加载时直接返回 true。 */
    external fun nativeLoad(modelPath: String): Boolean

    /**
     * 跑一次推理。三个数组都是**调用方持有**的，native 侧写入 [outLogits]。
     *
     * 尺寸必须固定（模型是定长输入）：[input] 为 `200 * 5`，[mask] 为 `200`，
     * [outLogits] 的长度等于字符表规模。任一处不符，native 侧会直接返回 false
     * 并给出可读原因，而不是产出一批看起来正常、实则错位的分数。
     */
    external fun nativeRun(input: FloatArray, mask: ByteArray, outLogits: FloatArray): Boolean

    /** 释放会话（保留 ORT 句柄，便于重新加载）。 */
    external fun nativeClose()

    /** 最近一次失败原因。 */
    external fun nativeLastError(): String

    /** 实际链到的 ONNX Runtime 版本，供诊断展示。 */
    external fun nativeRuntimeVersion(): String
}
