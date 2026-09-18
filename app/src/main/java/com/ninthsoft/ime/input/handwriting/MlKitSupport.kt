package com.ninthsoft.ime.input.handwriting

import android.content.Context
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.vision.digitalink.recognition.DigitalInkRecognitionModel
import com.google.mlkit.vision.digitalink.recognition.DigitalInkRecognitionModelIdentifier
import timber.log.Timber
import java.util.concurrent.TimeUnit

/**
 * ML Kit 数字墨水识别的「可用性探测 + 模型管理」。
 *
 * 这里把三件事分开了，因为它们的失败原因完全不同，合并之后排查会瞎：
 *
 * 1. **GMS 在不在** —— 无 GMS 设备上整条路走不通（`GoogleApiAvailability`）。
 * 2. **模型下载没下载** —— GMS 在 ≠ 模型在。数字墨水模型**不能随 APK 打包**，
 *    只能由 Google Play 服务管理或运行时下载，这是最容易翻车的一环。
 * 3. **下载能不能成功** —— 国内网络下这一步经常失败或极慢，所以带超时。
 *
 * 所有阻塞式调用（`Tasks.await`）**必须在后台线程**执行，
 * 调用方见 `HandwritingEngineHolder` 的单线程执行器。
 *
 * 关于 import 路径：ML Kit 的公开文档给的是 `com.google.mlkit.vision.digitalink.*`，
 * 但 19.0.0 的实际构件里类在 `...digitalink.recognition.*`（结果类在
 * `...digitalink.common.*`）。这里的路径是从 AAR 反查出来的，不是抄文档。
 */
object MlKitSupport {

    /**
     * **自动探测**路径的下载等待上限（秒）。
     *
     * 用在「打开手写面板时发现模型没下载」这个场景：必须短暂，因为用户在等面板，
     * 不能让他盯着卡住。超时就判定本次不可用并降级到本地引擎。
     */
    const val PROBE_TIMEOUT_SECONDS = 15L

    /**
     * **用户主动点「下载」**时的等待上限（秒）。
     *
     * 明显更长：模型约 20 MB，走代理/VPN 时 15 秒根本下不完。
     * 这里只是「等待上限」，不是「下载时限」—— `Tasks.await` 超时**不会取消下载**，
     * ML Kit 会在后台（WorkManager）继续下完，下次进来就是已下载状态。
     * 所以超时只影响这一次的回显，不会真的把事情搞坏。
     */
    const val MANUAL_DOWNLOAD_TIMEOUT_SECONDS = 120L

    @Volatile
    private var cachedModel: DigitalInkRecognitionModel? = null

    /**
     * 简体中文对应的模型。
     *
     * 直接用官方常量 [DigitalInkRecognitionModelIdentifier.ZH_HANI_CN]，
     * **不走 `fromLanguageTag("zh-CN")`**：常量没有标签解析歧义（`zh-CN` 的 script
     * 推断、大小写、是否需要写成 `zh-Hani-CN` 都不必操心），也就少一类失败。
     */
    fun simplifiedChineseModel(): DigitalInkRecognitionModel? {
        cachedModel?.let { return it }
        val model = runCatching {
            DigitalInkRecognitionModel.builder(
                DigitalInkRecognitionModelIdentifier.ZH_HANI_CN
            ).build()
        }.getOrElse { error ->
            Timber.e(error, "构造简体中文手写模型失败")
            null
        }
        cachedModel = model
        return model
    }

    /** 第 1 态：Google Play 服务是否可用。 */
    fun isPlayServicesAvailable(context: Context): Boolean = runCatching {
        GoogleApiAvailability.getInstance()
            .isGooglePlayServicesAvailable(context) == ConnectionResult.SUCCESS
    }.getOrElse { error ->
        Timber.w(error, "GMS 可用性探测异常")
        false
    }

    /**
     * 该模型是否提供置信度分数。
     *
     * ML Kit 明文提供了这个查询，所以**不用猜** —— 文本识别模型通常不提供分数，
     * 只有形状分类类模型才提供。UI 与准确率统计据此决定分数能不能用。
     */
    fun providesScores(): Boolean = simplifiedChineseModel()?.providesScores() ?: false

    /** 第 2 态：模型是否已在本地。阻塞调用。 */
    fun isModelDownloaded(): Boolean {
        val model = simplifiedChineseModel() ?: return false
        return runCatching {
            Tasks.await(RemoteModelManager.getInstance().isModelDownloaded(model))
        }.getOrElse { error ->
            Timber.w(error, "查询手写模型下载状态失败")
            false
        }
    }

    /**
     * 触发下载并等待，带超时。返回是否成功。
     *
     * **超时不会取消下载**（ML Kit 在后台继续），只是本次回显为失败。
     * 因此调用方提示文案要写成「本次未完成」，而不是「下载失败」。
     *
     * 不约束网络类型（不加 `requireWifi()`）：这是用户主动点「下载」才走的路径，
     * 明确的操作意图已经构成同意；加 wifi 约束反而会让"人在外面想用"直接卡死。
     * 代价是可能走流量，模型约 20 MB。
     */
    fun downloadModel(
        timeoutSeconds: Long = MANUAL_DOWNLOAD_TIMEOUT_SECONDS,
    ): Boolean {
        val model = simplifiedChineseModel() ?: return false
        return runCatching {
            val task = RemoteModelManager.getInstance()
                .download(model, DownloadConditions.Builder().build())
            Tasks.await(task, timeoutSeconds, TimeUnit.SECONDS)
            true
        }.getOrElse { error ->
            Timber.w(error, "手写模型下载等待超时或失败（${timeoutSeconds}s）")
            false
        }
    }

    /** 删除已下载的模型（设置页「删除」用）。 */
    fun deleteModel(): Boolean {
        val model = simplifiedChineseModel() ?: return false
        return runCatching {
            Tasks.await(RemoteModelManager.getInstance().deleteDownloadedModel(model))
            true
        }.getOrElse { error ->
            Timber.w(error, "删除手写模型失败")
            false
        }
    }

    /** 供日志与探针页展示。 */
    fun describeAvailability(context: Context): String = buildString {
        append("gms=").append(isPlayServicesAvailable(context))
        append(" downloaded=").append(isModelDownloaded())
        append(" providesScores=").append(providesScores())
    }
}
