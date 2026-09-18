package com.ninthsoft.ime.input.handwriting

/**
 * 笔迹的纯计算层：**不依赖任何 Android 类**，因此可以在 JVM 单测里直接跑。
 *
 * 这里只留两个「一眼看着简单、真机上错了很难查」的判定。
 * 曾经这里还有 `flatten`（多笔压平成一个数组）与 `fitToArea`（等比铺满画布），
 * 两者都是为 zinnia 这个已移除的本地引擎服务的：
 * - `flatten` 是为「一次 JNI 调用传完所有点」设计的，而当前的本地引擎
 *   （ONNX，见 [com.ninthsoft.ime.input.handwriting.ochwpro.OchwproPreprocess]）
 *   与 Google 引擎都按「笔」组织输入，不需要它；
 * - `fitToArea` 的前提是「模型用绝对坐标做特征」，而 ochwpro 自带逐轴包围盒归一化，
 *   再缩放一次等于双重归一化。它同时还假设过手写区非正方形，那个假设也已不成立。
 * 失去调用者的代码会被下一个人当成「还在用的约定」照抄，所以一并删掉。
 */
object StrokeMath {

    /**
     * 相邻采样点的最小间距（像素）。比它还近的点直接丢弃。
     *
     * 作用有两个：一是抑制手指静止时的抖动噪声（人手静止也会持续产生 MOVE 事件），
     * 二是把点数压下来 —— 识别耗时和点数成正比。
     *
     * 取 1f 而不是更大：`getHistoricalX/Y` 取回的中间点本来就密，
     * 真正的降采样交给特征提取，这里只清掉「同一个点重复上报」的情况。
     */
    const val MIN_POINT_DISTANCE = 1.0f

    /**
     * 是否保留这个采样点。
     *
     * [hasPrevious] 为 false 表示这是**一笔的第一个点**，无条件保留
     * （否则一笔的起点会被间距判定吃掉，笔画会缺头）。
     */
    fun shouldKeepPoint(
        hasPrevious: Boolean,
        lastX: Float,
        lastY: Float,
        x: Float,
        y: Float,
        minDistance: Float = MIN_POINT_DISTANCE,
    ): Boolean {
        if (!hasPrevious) return true
        val dx = x - lastX
        val dy = y - lastY
        return dx * dx + dy * dy >= minDistance * minDistance
    }

    /** 笔迹是否为空（没有任何一笔带点）。 */
    fun isEmpty(strokes: List<HwStroke>): Boolean = strokes.none { it.pointCount > 0 }
}
