package com.ninthsoft.ime

import com.ninthsoft.ime.base.neural.NeuralCircuitBreaker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 神经联想超时熔断的纯逻辑回归。
 *
 * 对应设计里的两条验收：**450 ms 硬超时**、**连续 5 次超时本次会话熔断停用**。
 * 这两条不是「优化」，是保护：神经前向在低端机/内存吃紧时会长到几百毫秒，
 * 不做熔断就会每次都把预算撞满，键盘持续卡顿。
 *
 * 关键性质是**取消不算超时**：用户继续打字会让上一轮取消，
 * 那是正常交互。把它计入失败会让打字快的人几分钟就被熔断。
 */
class NeuralCircuitBreakerTest {

    @Test
    fun constantsMatchTheDecidedBudget() {
        assertEquals("硬超时按 FUTO 的做法取 450 ms", 450L, NeuralCircuitBreaker.TIMEOUT_MS)
        assertEquals(5, NeuralCircuitBreaker.MAX_CONSECUTIVE_TIMEOUTS)
    }

    @Test
    fun startsAllowed() {
        val breaker = NeuralCircuitBreaker()
        assertTrue(breaker.shouldAttempt())
        assertFalse(breaker.isTripped)
        assertEquals(0, breaker.consecutiveTimeouts)
    }

    /** 边界：第 4 次还可以试，第 5 次熔断。 */
    @Test
    fun tripsExactlyAtTheLimit() {
        val breaker = NeuralCircuitBreaker()
        repeat(4) { breaker.recordTimeout() }
        assertFalse("4 次还不该熔断", breaker.isTripped)
        assertTrue(breaker.shouldAttempt())

        breaker.recordTimeout()
        assertTrue("第 5 次连续超时必须熔断", breaker.isTripped)
        assertFalse(breaker.shouldAttempt())
    }

    /** 一次成功就清零：熔断针对的是「连续」超时，不是累计超时。 */
    @Test
    fun successResetsTheStreak() {
        val breaker = NeuralCircuitBreaker()
        repeat(4) { breaker.recordTimeout() }
        breaker.recordSuccess()
        assertEquals(0, breaker.consecutiveTimeouts)
        assertFalse(breaker.isTripped)

        repeat(4) { breaker.recordTimeout() }
        assertFalse("清零后还要再攒满 5 次", breaker.isTripped)
    }

    @Test
    fun counterDoesNotGrowPastTheLimit() {
        val breaker = NeuralCircuitBreaker()
        repeat(50) { breaker.recordTimeout() }
        assertEquals(NeuralCircuitBreaker.MAX_CONSECUTIVE_TIMEOUTS, breaker.consecutiveTimeouts)
    }

    /** 熔断只能在用户显式重开时恢复，不能靠一次成功「自愈」—— 熔断后根本不会再跑。 */
    @Test
    fun resetReArmsTheBreaker() {
        val breaker = NeuralCircuitBreaker()
        repeat(5) { breaker.recordTimeout() }
        assertTrue(breaker.isTripped)
        breaker.reset()
        assertFalse(breaker.isTripped)
        assertTrue(breaker.shouldAttempt())
    }

    @Test
    fun customLimitIsHonoured() {
        val breaker = NeuralCircuitBreaker(maxConsecutiveTimeouts = 2)
        breaker.recordTimeout()
        assertFalse(breaker.isTripped)
        breaker.recordTimeout()
        assertTrue(breaker.isTripped)
    }
}
