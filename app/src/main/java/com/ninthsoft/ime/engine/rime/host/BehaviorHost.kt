package com.ninthsoft.ime.engine.rime.host

import com.ninthsoft.ime.base.util.PinYin
import com.ninthsoft.ime.engine.IBehaviorHost
import com.ninthsoft.ime.engine.behavior.Backspace
import com.ninthsoft.ime.engine.behavior.IBehavior
import com.ninthsoft.ime.engine.behavior.InputString
import com.ninthsoft.ime.engine.behavior.Reset
import com.ninthsoft.ime.engine.data.CandidatePinYin
import com.ninthsoft.ime.engine.rime.behavior.RimeBehavior
import com.ninthsoft.ime.engine.rime.core.IRimeJob

class BehaviorHost(val rimeJob: IRimeJob) : IBehaviorHost {
    val behaviorQueue = ArrayDeque<IBehavior>()

    val builder = StringBuilder()

    override fun flowed(behavior: IBehavior): Boolean {
        if (behavior is RimeBehavior) {
            behavior.withRimeJob(rimeJob)
            behavior.invoke()
        }
        when (behavior) {
            is Backspace -> behaviorQueue.removeLastOrNull()
            is Reset -> resetState()
            else -> behaviorQueue.addLast(behavior)
        }
        return true
    }

    override fun resetState() {
        behaviorQueue.clear()
        builder.clear()
    }

    fun possiblePinYin(): Array<CandidatePinYin> {
        behaviorQueue.toList().forEach {
            when (it) {
                is InputString -> {
                    builder.append(it.sequence)
                }

                else -> {}
            }
        }
        val combinations = PinYin.possibleCombinations(builder.toString())
        val candidatePinYin = combinations.map { CandidatePinYin(it, 0) }.toTypedArray()
        builder.clear()
        return candidatePinYin
    }
}