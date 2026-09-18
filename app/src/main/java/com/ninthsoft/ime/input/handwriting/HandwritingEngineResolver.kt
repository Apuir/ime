package com.ninthsoft.ime.input.handwriting

/** [HandwritingEngineResolver.decide] 的结论。 */
enum class HwEngineChoice {
    /** 用 Google 引擎（模型确认可用）。 */
    USE_GOOGLE,

    /** 用本地引擎（永远可用）。 */
    USE_LOCAL,

    /** 先尝试下载 Google 模型，再据结果二次决策。 */
    DOWNLOAD_GOOGLE,

    /**
     * 用户显式指定了 Google，但它不可用 —— **不降级**，由 UI 给出提示
     * 并提供「切换到本地引擎」的一键按钮。
     */
    UNAVAILABLE,
}

/**
 * 引擎选择决策（纯逻辑，不碰 Android，因此可以在 JVM 单测里穷举）。
 *
 * 把这层单独抽出来的理由：这是整个模块**最容易出隐性错误**的地方 ——
 * 决策错了不会报错、不会崩，只会表现为「识别准度变了」或者「用户选了 Google 却一直在用本地」。
 *
 * 三态探测（对应 Prompt 的要求：**不能只看 GMS 在不在**）：
 *
 * - [gmsAvailable]：Google Play 服务可用
 * - [googleModelDownloaded]：模型**标记**为已下载（`RemoteModelManager` 的说法）
 * - [googleModelVerified]：模型**真的能用**（跑过一次自检识别）
 *
 * 后两者必须分开。实测踩过：「标记为已下载」但模型残缺（下载中断）时，
 * 每次识别都失败，而 `isModelDownloaded()` 照样返回 true。
 */
object HandwritingEngineResolver {

    /**
     * 首次决策。
     *
     * [HwEngineMode.AUTO] 会降级；[HwEngineMode.GOOGLE] 尊重用户选择、**不降级**，
     * 不可用时给 [HwEngineChoice.UNAVAILABLE] 让 UI 去提示。
     */
    fun decide(
        mode: HwEngineMode,
        gmsAvailable: Boolean,
        googleModelDownloaded: Boolean,
        googleModelVerified: Boolean,
    ): HwEngineChoice = when (mode) {
        HwEngineMode.LOCAL -> HwEngineChoice.USE_LOCAL

        HwEngineMode.GOOGLE -> when {
            !gmsAvailable -> HwEngineChoice.UNAVAILABLE
            googleModelVerified -> HwEngineChoice.USE_GOOGLE
            // 标记为已下载但自检没过：模型残缺，反复自动重下不可控，
            // 交给用户去设置页「删除后重新下载」（那里有显式入口）。
            googleModelDownloaded -> HwEngineChoice.UNAVAILABLE
            else -> HwEngineChoice.DOWNLOAD_GOOGLE
        }

        HwEngineMode.AUTO -> when {
            !gmsAvailable -> HwEngineChoice.USE_LOCAL
            googleModelVerified -> HwEngineChoice.USE_GOOGLE
            // 残缺模型同样不去自动重下：先干净地落到本地，
            // 用户感知到的只是「准度差一点」，而不是转圈或没反应。
            googleModelDownloaded -> HwEngineChoice.USE_LOCAL
            else -> HwEngineChoice.DOWNLOAD_GOOGLE
        }
    }

    /**
     * 下载结束后的二次决策。
     *
     * 参数是「下载 + 自检是否都过了」而不是单看下载返回：
     * 下载返回成功不代表模型完整（`Tasks.await` 超时也不会取消后台下载）。
     */
    fun afterDownload(mode: HwEngineMode, downloadAndVerifySucceeded: Boolean): HwEngineChoice =
        when {
            downloadAndVerifySucceeded -> HwEngineChoice.USE_GOOGLE
            mode == HwEngineMode.GOOGLE -> HwEngineChoice.UNAVAILABLE
            else -> HwEngineChoice.USE_LOCAL
        }
}
