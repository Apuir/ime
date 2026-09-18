package com.ninthsoft.ime.input.handwriting.ochwpro

import com.ninthsoft.ime.input.handwriting.HwStroke
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * ochwpro 手写模型的输入构造（纯计算，不依赖任何 Android 类，可在 JVM 单测里跑）。
 *
 * 模型出处：Xime 输入法的中文手写模型，ONNX 版 7.0 MB、7356 个字符类。
 * 这份实现**逐行对齐**它的参考推理代码（Xime 仓库的
 * `app/src/main/java/com/kingzcheung/xime/handwriting/HandwritingInference.kt`），
 * 因为预处理属于「错一点就整体掉准确率、而从报错里完全看不出来」的那类代码：
 * 形状对得上、推理能跑通、只是结果全错。
 *
 * 输入张量（已在本机用 onnxruntime 实测确认）：
 * - `input`：`float32[1, 200, 5]`，每点 5 个特征 `[x, y, dx, dy, penDown]`
 * - `mask`：`bool[1, 200]`，前 `length` 位为 1
 * 输出：`logits[1, 7356]`
 *
 * 三个关键约定（都与直觉相悖，务必照着实现）：
 *
 * 1. **坐标不要预先缩放**。模型内部自己做逐轴包围盒归一化，
 *    喂进来的坐标只要是一个稳定的、各点相对关系正确的坐标系即可
 *    （我们用「相对手写区左上角」的像素值，见 `HandwritingCanvasView`）。
 *    外面再套一层「铺满画布」的缩放等于双重归一化，反而错。
 * 2. **归一化是逐轴独立的**（x 除以 x 跨度、y 除以 y 跨度），**不是等比**。
 *    这是该模型训练时的既有约定，改不得 —— 我们只需保证不擅自改动它。
 * 3. **`penDown` 是「抬笔」标志，但取的是反的**：每个点先记 1，
 *    再把**每一笔的最后一个点**改成 0。也就是 0 表示「这一笔到此结束」。
 *
 * 还有一条容易「优化」错的地方：**包围盒与 penDown 都在截断之前、对全部点计算**，
 * 截断只发生在最后一步。先截断再算包围盒看起来更省事，但会让归一化尺度
 * 依赖于「是否有超长笔画」，同一段笔迹在不该变的地方变了。
 */
object OchwproPreprocess {

    /** 模型接受的定长时间步数。超出部分被截断，不足部分零填充。 */
    const val FIXED_LEN = 200

    /** 每点的特征维数：`[x, y, dx, dy, penDown]`。 */
    const val FEATURE_DIM = 5

    /**
     * 每笔最多保留的点数，超出则等距重采样到这个上限。
     *
     * 这不是为了省算力（模型反正固定吃 200 步），而是**训练侧的约定**：
     * 参考实现同样这么做，而一支笔画喂几百个点与只喂 8 个点的特征分布并不一致。
     * 取 8 也顺带保证「多笔 × 每笔 8 点」不会轻易撑爆 200 步预算。
     */
    const val MAX_POINTS_PER_STROKE = 8

    /** [build] 的结果。 */
    class Features(
        /** 长度 `FIXED_LEN * FEATURE_DIM`，已零填充。 */
        val values: FloatArray,
        /** 长度 `FIXED_LEN`，前 [length] 位为 1。 */
        val mask: ByteArray,
        /** 有效步数，即 `min(总点数, FIXED_LEN)`。 */
        val length: Int,
    )

    /**
     * 等距重采样：每笔最多 [MAX_POINTS_PER_STROKE] 点。
     *
     * 取点方式是**按索引等分**（`step = (点数 - 1) / (上限 - 1)`）而不是按弧长，
     * 与参考实现一致。采样点本来在时间上就接近均匀，两者差别很小，
     * 但「与训练数据构造方式一致」本身比「理论上更优」重要。
     *
     * 点数不超过上限的笔**原样返回**（不做插值），因此不会引入新的点。
     */
    fun simplify(strokes: List<HwStroke>): List<HwStroke> = strokes.map { stroke ->
        val count = stroke.pointCount
        if (count <= MAX_POINTS_PER_STROKE) {
            stroke
        } else {
            val step = (count - 1).toFloat() / (MAX_POINTS_PER_STROKE - 1)
            val points = FloatArray(MAX_POINTS_PER_STROKE * 2)
            val sourceTimes = stroke.times
            val times = if (sourceTimes == null) null else LongArray(MAX_POINTS_PER_STROKE)
            for (i in 0 until MAX_POINTS_PER_STROKE) {
                val index = (i * step).roundToInt().coerceIn(0, count - 1)
                points[i * 2] = stroke.points[index * 2]
                points[i * 2 + 1] = stroke.points[index * 2 + 1]
                if (times != null && sourceTimes != null) times[i] = sourceTimes[index]
            }
            HwStroke(points, times)
        }
    }

    /**
     * 把笔迹构造成模型输入。
     *
     * **没有点的笔会被丢弃**，这一点与参考实现不同：上游对空笔的处理有个小缺陷
     * （连着两支笔时，空笔会让赋给上一笔末点的抬笔标志失效），
     * 而空笔在正常路径下本不会出现（落笔必产生一个点），
     * 所以直接丢掉比复刻一个错误更安全。
     */
    fun build(strokes: List<HwStroke>): Features {
        val usable = simplify(strokes.filter { it.pointCount > 0 })

        val values = FloatArray(FIXED_LEN * FEATURE_DIM)
        val mask = ByteArray(FIXED_LEN)

        var totalPoints = 0
        for (stroke in usable) totalPoints += stroke.pointCount
        val length = min(totalPoints, FIXED_LEN)
        if (length == 0) return Features(values, mask, 0)

        // 包围盒：**跨全部笔、且包含会被截断的那些点**，与参考实现一致。
        // 全局而不是逐笔：逐笔各自撑满会丢掉笔与笔之间的相对大小，
        // 而「这个部件相对整字占多大」正是识别要用的信号。
        var minX = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE
        var minY = Float.MAX_VALUE
        var maxY = -Float.MAX_VALUE
        for (stroke in usable) {
            val points = stroke.points
            var i = 0
            while (i + 1 < points.size) {
                val x = points[i]
                val y = points[i + 1]
                if (x < minX) minX = x
                if (x > maxX) maxX = x
                if (y < minY) minY = y
                if (y > maxY) maxY = y
                i += 2
            }
        }

        // 下界取 1 而不是 0：单点、或一条水平/垂直的笔画会让某个跨度为 0，
        // 用它做分母就得到 Inf/NaN，整个序列随之报废。
        val rangeX = max(maxX - minX, 1.0f)
        val rangeY = max(maxY - minY, 1.0f)

        var index = 0
        var prevX = 0f
        var prevY = 0f
        outer@ for (stroke in usable) {
            val points = stroke.points
            val pointCount = points.size / 2
            for (p in 0 until pointCount) {
                if (index >= FIXED_LEN) break@outer
                val x = points[p * 2]
                val y = points[p * 2 + 1]
                val base = index * FEATURE_DIM
                values[base] = (x - minX) / rangeX
                values[base + 1] = (y - minY) / rangeY
                // 第 0 点没有前一个点，差分为 0
                values[base + 2] = if (index == 0) 0f else (x - prevX) / rangeX
                values[base + 3] = if (index == 0) 0f else (y - prevY) / rangeY
                // 先一律记 1，笔末点随后改成 0
                values[base + 4] = 1f
                mask[index] = 1

                prevX = x
                prevY = y
                index++
            }
            // 这一笔的最后一个点：标记抬笔。
            // 空笔已在上方过滤，因此 index-1 必定属于本笔，不会误改上一笔的末点。
            // 若本笔的末点落在截断之外，这里的判断会跳过 —— 与「先算完再截断」等价。
            if (index <= FIXED_LEN && index > 0) {
                values[(index - 1) * FEATURE_DIM + 4] = 0f
            }
        }

        return Features(values, mask, length)
    }
}
