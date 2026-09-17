package com.ninthsoft.ime.input.keyboard.key

import kotlin.math.abs
import kotlin.math.max

/**
 * 「上滑输入」触发判定的纯计算部分。
 *
 * 单独放一个不依赖 Android 的 object，一是让 [CustomGestureView] 里的状态机保持轻，
 * 二是能在 JVM 单测里直接钉住边界（状态机本身跑在 View 里，需要真机，见 `SwipeUpThresholdTest`）。
 *
 * ### 阈值为什么锚定「按键高度」而不是固定 dp
 *
 * 26 键、九键、15 键的键高各不相同，键盘高度本身还是用户可拖动的；写死一个 dp 值，
 * 换台机器或换个键盘高度手感就变（旧实现用的是 `2 × touchSlop`，而 touchSlop 由厂商 overlay 定，
 * 真机上是 8～12dp 浮动，同一个 App 在不同手机上阈值能从 16dp 飘到 24dp）。
 *
 * 锚定「当前按键的高度」之后：26 键按 26 键的键高、九键按九键的键高，键盘调大调小也跟着走，
 * 不需要按键盘类型分别标定。默认 [DEFAULT_RATIO] = 1.0，即**滑出整整一个按键的高度**才算上滑。
 *
 * 这个量级有实测支撑：`docs/DEVELOPMENT.md` 9.3.6 记录的真实上滑日志是
 * `dy=222px / h=157px ≈ 1.4 个键高`，说明「一个键高」不会把正常上滑挡在门外。
 */
object SwipeUpMath {

    /** 默认系数：1.0 = 整整一个按键的高度。 */
    const val DEFAULT_RATIO = 1.0f

    /** 设置项允许的范围：低于 0.4 太灵敏，高于 1.5 在小键上几乎滑不出来。 */
    const val MIN_RATIO = 0.4f
    const val MAX_RATIO = 1.5f

    /**
     * 方向系数默认值：纵向位移须 ≥ 横向位移 × 该值（等价于与垂直轴夹角 ≤ atan(1/1.5) ≈ 34°）。
     *
     * ### 为什么除了距离还要卡方向
     *
     * 手指上滑几乎不可能走成一条纯垂直线，总带横向偏移；反过来，**横滑时也难免带纵向位移**。
     * 只看纵向距离的话，后者会被误判成上滑 —— 用户从 `q` 斜着划到 `e`，纵向一过阈值就出符号。
     * 加上这个条件后，只有「纵向占主导」才算上滑；斜着滑只要纵向更多，仍然照常触发。
     *
     * 参照仓输入法的 `tangentThreshold`（横向/纵向的正切上限，默认 tan15° ≈ 0.268，
     * 也就是纵向须达横向的 3.73 倍）。本值 1.5 对应 33.7°，**比它宽松** ——
     * 单键上滑的活动范围本来就只有一个键帽，卡到 15° 会让斜着上滑很难触发。
     */
    const val DEFAULT_DIRECTION_TAN = 1.5f

    /** 方向系数的合法范围：0.5 太宽松（≈63°，几乎等于不卡），4.0 太严格（≈14°）。 */
    const val MIN_DIRECTION_TAN = 0.5f
    const val MAX_DIRECTION_TAN = 4.0f

    /**
     * 触发距离的兜底下限，单位是 touchSlop 的倍数（2 倍 ≈ 16dp）。
     *
     * 只有键盘被拖到极小的悬浮场景才会碰到它 —— 正常键盘的键高在 50～80dp，远大于这个下限。
     * 有它才不会出现「键高测出来是 0 或极小 → 阈值趋近 0 → 一碰就触发」。
     */
    const val MIN_DISTANCE_SLOP = 2f

    /** 把系数夹到合法区间，且挡住 NaN。偏好读出来的值一律先过这里。 */
    fun clampRatio(ratio: Float): Float = when {
        ratio.isNaN() -> DEFAULT_RATIO
        ratio < MIN_RATIO -> MIN_RATIO
        ratio > MAX_RATIO -> MAX_RATIO
        else -> ratio
    }

    /** 同上，方向系数的夹取。 */
    fun clampDirectionTan(value: Float): Float = when {
        value.isNaN() -> DEFAULT_DIRECTION_TAN
        value < MIN_DIRECTION_TAN -> MIN_DIRECTION_TAN
        value > MAX_DIRECTION_TAN -> MAX_DIRECTION_TAN
        else -> value
    }

    /**
     * 本次手势实际需要的触发距离（px）。
     *
     * @param keyHeight   当前按键的实际高度（px）。未测量时传 0，此时退化成 [minDistance]。
     * @param ratio       比例系数，1.0 = 整整一个键高。
     * @param minDistance 兜底下限（px）。
     */
    fun threshold(keyHeight: Float, ratio: Float, minDistance: Float): Float {
        val scaled = if (keyHeight.isFinite() && keyHeight > 0f) keyHeight * ratio else 0f
        return max(scaled, minDistance)
    }

    /**
     * 这次位移算不算「上滑」——**唯一判据**，View 里不再另写一套。
     *
     * 两个条件**同时**满足才算：
     * 1. **距离**：纵向位移 ≥ `键高 × ratio`（下限见 [threshold]）；
     * 2. **方向**：纵向位移 ≥ 横向位移 × `directionTan`，即纵向占主导。
     *
     * @param dx           横向累计位移（内部取绝对值，符号无关）。
     * @param dy           纵向累计位移，**上滑为正**（调用处传 `downY - y`，
     *                     注意与 `MotionEvent` 的坐标方向相反）。
     * @param keyHeight    当前按键的实际高度（px）。
     * @param ratio        距离系数，1.0 = 整整一个键高。
     * @param directionTan 方向系数，纵向须达横向的这么多倍才算「纵向主导」。
     * @param minDistance  距离的兜底下限（px）。
     */
    fun isSwipeUp(
        dx: Float,
        dy: Float,
        keyHeight: Float,
        ratio: Float,
        directionTan: Float,
        minDistance: Float,
    ): Boolean {
        // 向下滑 / 原地抖动 / NaN：`dy <= 0f` 与 `>=` 对 NaN 都为 false，这里显式挡掉前者。
        if (dy <= 0f) return false
        if (dy < threshold(keyHeight, ratio, minDistance)) return false
        return dy >= abs(dx) * directionTan
    }
}
