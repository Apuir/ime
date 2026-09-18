package com.ninthsoft.ime.input.handwriting

import android.content.Context
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.digitalink.recognition.DigitalInkRecognition
import com.google.mlkit.vision.digitalink.recognition.DigitalInkRecognitionModel
import com.google.mlkit.vision.digitalink.recognition.DigitalInkRecognizer
import com.google.mlkit.vision.digitalink.recognition.DigitalInkRecognizerOptions
import com.google.mlkit.vision.digitalink.recognition.Ink
import com.google.mlkit.vision.digitalink.recognition.RecognitionContext
import com.google.mlkit.vision.digitalink.recognition.WritingArea
import timber.log.Timber

/**
 * Google 方案（ML Kit 数字墨水识别）。Gboard 同款技术，中文准确率明显好于本地方案。
 *
 * 三个必须接受的限制（Prompt 里点到，这里逐条落到实现上）：
 *
 * 1. **模型不能随 APK 打包**，只能运行时下载（`MlKitSupport`）。
 * 2. **依赖 Google Play 服务**，无 GMS 设备完全不可用。
 * 3. **要联网下载约 20 MB 模型**，国内网络下这一步很可能失败或极慢 → 必须降级。
 *
 * [load] 会**依次**要求四件事成立：GMS 可用、模型标记为已下载、client 建得起来、
 * **自检能跑通真实的一次识别**。最后一条是踩过坑之后补上的，见 [selfTest]。
 */
class MlKitEngine : HandwritingEngine {

    override val displayName: String = NAME

    private var recognizer: DigitalInkRecognizer? = null

    @Volatile
    private var scoresAvailable: Boolean = false

    @Volatile
    override var lastError: String? = null
        private set

    /**
     * 建 client 时就要定死「最多要几个候选」（选项是构造期固定的），
     * 所以这里取一个够用的上限，每次识别再按调用方要的个数裁剪。
     */
    private val maxResultCount = 10

    override fun load(context: Context): Boolean {
        lastError = null
        if (recognizer != null) return true

        if (!MlKitSupport.isPlayServicesAvailable(context)) {
            lastError = "无 Google Play 服务"
            Timber.i("Google 手写不可用：无 Google Play 服务")
            return false
        }
        val model: DigitalInkRecognitionModel = MlKitSupport.simplifiedChineseModel() ?: run {
            lastError = "构造模型标识失败"
            return false
        }
        if (!MlKitSupport.isModelDownloaded()) {
            lastError = "中文模型尚未下载"
            Timber.i("Google 手写不可用：中文模型尚未下载")
            return false
        }

        val client = runCatching {
            scoresAvailable = model.providesScores()
            DigitalInkRecognition.getClient(
                DigitalInkRecognizerOptions.builder(model)
                    .setMaxResultCount(maxResultCount)
                    .build()
            )
        }.getOrElse { error ->
            lastError = "创建识别器失败：${error.message ?: error.javaClass.simpleName}"
            Timber.e(error, "创建 Google 手写识别器失败")
            null
        } ?: return false

        // 关键一步：「标记为已下载」不等于「模型完整」。
        // 下载中断会留下残缺模型，isModelDownloaded 照样返回 true，
        // 但之后每次识别都失败。用一次合成笔迹的真实识别把它挡在这里。
        if (!selfTest(client)) {
            runCatching { client.close() }
            return false
        }

        recognizer = client
        Timber.i("Google 手写已就绪（providesScores=$scoresAvailable）")
        return true
    }

    override fun isAvailable(): Boolean = recognizer != null

    /** 该模型是否真的会给出置信度。文本识别模型通常为 false。 */
    fun providesScores(): Boolean = scoresAvailable

    /**
     * 构造识别上下文。
     *
     * **`preContext` 是必填项，漏掉会让 `build()` 直接抛**
     * `IllegalStateException: Missing required properties: preContext`
     * —— `RecognitionContext` 是 AutoValue 生成的，`getPreContext()` 标了非空。
     * 这一条官方文档没有明说（文档示例里恰好都传了 preContext），
     * 是实测报错才暴露出来的。
     *
     * 语义上 preContext 是「紧邻当前笔画之前已输入的文本」，用于消歧
     * （官方举例：`argument` 比 `argnment` 常见）。手写面板是单字输入，
     * 本来没有前文，所以显式填空串 —— 但**不能省略**，省略就是崩。
     * 将来接 Rime 联想时，这里可以填输入框里已有的文本，进一步提准。
     */
    private fun buildRecognitionContext(width: Float, height: Float): RecognitionContext =
        RecognitionContext.builder()
            .setPreContext("")
            .setWritingArea(WritingArea(width, height))
            .build()

    /**
     * 用一段合成笔迹做一次真实识别，验证模型确实可用。
     *
     * 只看「调用有没有抛错」，**不看认出什么** —— 合成笔迹本来就不是任何真实的字，
     * 返回什么候选都正常。目的是把「模型残缺」这一类故障在加载期就暴露出来，
     * 从而能干净地降级到本地引擎，而不是让用户写了半天字一个候选都没有。
     */
    private fun selfTest(client: DigitalInkRecognizer): Boolean {
        val ink = Ink.builder().apply {
            val stroke = Ink.Stroke.builder()
            // 一条从左上到右下的斜线，落在自检画布内
            for (step in 0..4) {
                val ratio = step / 4f
                stroke.addPoint(
                    Ink.Point.create(
                        SELF_TEST_AREA * (0.2f + 0.6f * ratio),
                        SELF_TEST_AREA * (0.2f + 0.6f * ratio),
                    )
                )
            }
            addStroke(stroke.build())
        }.build()

        return runCatching {
            Tasks.await(client.recognize(ink, buildRecognitionContext(SELF_TEST_AREA, SELF_TEST_AREA)))
            true
        }.getOrElse { error ->
            lastError = "模型自检失败（模型可能不完整，建议删除后重新下载）：" +
                (error.message ?: error.javaClass.simpleName)
            Timber.w(error, "Google 手写模型自检失败")
            false
        }
    }

    override fun recognize(
        strokes: List<HwStroke>,
        writingAreaWidth: Int,
        writingAreaHeight: Int,
        nbest: Int,
    ): List<HwCandidate> {
        lastError = null
        val client = recognizer ?: run {
            lastError = "引擎未就绪"
            return emptyList()
        }
        if (nbest <= 0) {
            lastError = "nbest <= 0"
            return emptyList()
        }
        if (StrokeMath.isEmpty(strokes)) {
            lastError = "笔迹为空"
            return emptyList()
        }

        val inkBuilder = Ink.builder()
        var strokeCount = 0
        for (stroke in strokes) {
            // 空笔要跳过：ML Kit 侧同样不接受没有点的笔画
            if (stroke.points.isEmpty()) continue
            val strokeBuilder = Ink.Stroke.builder()
            val points = stroke.points
            val times = stroke.times
            var index = 0
            var pointIndex = 0
            while (index + 1 < points.size) {
                val time = times?.getOrNull(pointIndex)
                strokeBuilder.addPoint(
                    if (time != null) {
                        Ink.Point.create(points[index], points[index + 1], time)
                    } else {
                        Ink.Point.create(points[index], points[index + 1])
                    }
                )
                index += 2
                pointIndex++
            }
            inkBuilder.addStroke(strokeBuilder.build())
            strokeCount++
        }
        if (strokeCount == 0 || inkBuilder.isEmpty) {
            lastError = "笔迹为空"
            return emptyList()
        }

        // 手写区尺寸必须传：官方说明「符号的含义部分取决于它相对于书写区域的大小」，
        // 漏传会白丢准确率。单位与坐标一致（都是 px）。
        // 注意上下文构造本身也可能抛（preContext 必填），所以一并放进 runCatching ——
        // 早先它在 runCatching 之外，异常会直接穿出 recognize()，表现为「报一大堆错」。
        val result = runCatching {
            val recognitionContext = buildRecognitionContext(
                writingAreaWidth.toFloat(),
                writingAreaHeight.toFloat(),
            )
            Tasks.await(client.recognize(inkBuilder.build(), recognitionContext))
        }.getOrElse { error ->
            lastError = "识别调用失败：${error.message ?: error.javaClass.simpleName}"
            Timber.w(error, "Google 手写识别失败")
            return emptyList()
        }

        val candidates = result.candidates.take(nbest).map { candidate ->
            // score 是 java.lang.Float（可空）—— 文本识别模型不保证给分数。
            // 没有就按位次兜底并标记出来，避免下游把「假分数」当成真实置信度。
            val score = candidate.score
            HwCandidate(
                text = candidate.text,
                score = score ?: 0f,
                scoreIsFallback = score == null,
            )
        }
        if (candidates.isEmpty()) {
            lastError = "识别返回 0 个候选"
        }
        return candidates
    }

    override fun close() {
        runCatching { recognizer?.close() }
        recognizer = null
        lastError = null
    }

    companion object {
        const val NAME = "Google"

        /** 自检用的画布边长（与自检笔迹的坐标系一致即可，不必与真实手写区相同）。 */
        private const val SELF_TEST_AREA = 300f
    }
}
