package com.ninthsoft.ime.base.neural

/**
 * 神经联想的超时熔断。
 *
 * 依据是 FUTO Keyboard 的做法（源码级核实）：单次前向 **450 ms 硬超时**，
 * **连续 5 次超时就在本次会话内停用**、回落 n-gram。理由是这类超时几乎总是
 * 设备状态问题（大核被占、内存吃紧），继续每次都撞 450 ms 只会让键盘持续卡顿。
 *
 * 纯状态机，不持有协程也不碰 Android，便于单测。
 *
 * 计数器只在**超时**时累加：用户继续打字导致的取消不算失败 —— 那是正常交互，
 * 把它算进去会让快速打字的人几分钟就被熔断。
 */
class NeuralCircuitBreaker(
    private val maxConsecutiveTimeouts: Int = MAX_CONSECUTIVE_TIMEOUTS,
) {
    var consecutiveTimeouts: Int = 0
        private set

    /** 熔断后本次会话不再尝试；[reset] 是唯一的恢复入口（重开开关 / 重新加载模型）。 */
    val isTripped: Boolean
        get() = consecutiveTimeouts >= maxConsecutiveTimeouts

    fun shouldAttempt(): Boolean = !isTripped

    fun recordSuccess() {
        consecutiveTimeouts = 0
    }

    fun recordTimeout() {
        if (consecutiveTimeouts < maxConsecutiveTimeouts) consecutiveTimeouts++
    }

    fun reset() {
        consecutiveTimeouts = 0
    }

    companion object {
        /** 单次前向的硬超时。超过就放弃这一轮，绝不阻塞键盘。 */
        const val TIMEOUT_MS = 450L

        /** 连续超时上限，达到即熔断。 */
        const val MAX_CONSECUTIVE_TIMEOUTS = 5
    }
}
