package com.ninthsoft.ime.input.handwriting

import android.content.Context

/**
 * 手写引擎偏好。
 *
 * - [AUTO]：优先 Google（准），不可用则**静默降级**到本地（永远可用）。
 * - [GOOGLE] / [LOCAL]：尊重用户选择，**不做自动降级**；GOOGLE 不可用时由 UI 明确提示。
 */
enum class HwEngineMode {
    AUTO, GOOGLE, LOCAL;

    companion object {
        fun fromValue(value: Int): HwEngineMode = entries.getOrElse(value) { AUTO }
    }
}

/**
 * 一笔笔迹，坐标扁平存放 `[x0, y0, x1, y1, ...]`。
 *
 * 刻意不用 `List<PointF>`：一笔常有两三百个点，识别是高频路径，
 * 扁平 `FloatArray` 免去装箱与逐点对象分配（连续书写时这一点直接体现在 GC 上）。
 *
 * [times] 是与之等长的每点时间戳（`MotionEvent.getEventTime()`），**可为 null**。
 * ML Kit 是在线识别，`Ink.Point.create(x, y, t)` 支持带时间，官方也允许省略；
 * 我们有这个信息就一并传下去。本地引擎（ochwpro）的特征里不含时间，会忽略它。
 */
class HwStroke(val points: FloatArray, val times: LongArray? = null) {
    val pointCount: Int get() = points.size / 2
}

/**
 * 一个手写候选。
 *
 * [scoreIsFallback] 用来区分「引擎给的真实置信度」与「按位次兜底的假分数」。
 * ML Kit 的文本识别并不保证返回分数（官方只承诺形状分类器提供分数），
 * 两者混在一起会让准确率统计与候选排序失去意义。
 *
 * 另注意：**两个引擎的分数不同量纲**（Zinnia 是 SVM margin，可为负、无上界；
 * ML Kit 若提供则约为 0..1），任何跨引擎的排序或比较都必须先归一化。
 */
class HwCandidate(
    val text: String,
    val score: Float,
    val scoreIsFallback: Boolean = false,
)

/**
 * 手写识别引擎。
 *
 * 实现约定（违反会在真机上表现为 ANR 或首字卡顿）：
 * - [load] 与 [recognize] **都不允许在主线程调用**。调用方见 `HandwritingEngineHolder` 里的
 *   单线程执行器。
 * - [recognize] 的 [writingAreaWidth] / [writingAreaHeight] 必须与笔迹坐标**同单位**，
 *   且是手写区**实际尺寸**。ML Kit 官方明确说明「告知识别器书写区域的宽度和高度可以提高
 *   准确率」——漏传这一对数值会白丢准确率。
 * - [close] 必须释放 native / SDK 句柄，且要可重复调用。
 */
interface HandwritingEngine {
    /** 用于在 UI 上显示当前生效的引擎，取值 `Google` / `本地`。 */
    val displayName: String

    /**
     * 最近一次失败的原因；没有失败时为 null。
     *
     * 存在的理由：引擎「不可用」和「可用但识别不出来」是两种完全不同的故障，
     * 而它们对用户的表现都是「写了字没反应」。没有这个字段就只能靠猜。
     * 逐条清空：每次 [recognize] 开始与 [load] 开始都要重置。
     */
    val lastError: String?

    /** 加载模型。返回是否可用。可重复调用，已加载时直接返回 true。 */
    fun load(context: Context): Boolean

    /** 当前是否已就绪（不触发加载）。 */
    fun isAvailable(): Boolean

    /** 识别。失败返回空列表，不抛异常（原因写进 [lastError]）。 */
    fun recognize(
        strokes: List<HwStroke>,
        writingAreaWidth: Int,
        writingAreaHeight: Int,
        nbest: Int,
    ): List<HwCandidate>

    fun close()
}
