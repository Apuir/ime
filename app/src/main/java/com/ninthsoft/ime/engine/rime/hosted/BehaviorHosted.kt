package com.ninthsoft.ime.engine.rime.hosted

import com.ninthsoft.ime.base.util.PinYin
import com.ninthsoft.ime.engine.IBehaviorHosted
import com.ninthsoft.ime.engine.data.CandidatePinYin
import com.ninthsoft.ime.engine.rime.core.RimeApi
import kotlinx.coroutines.channels.Channel

class BehaviorHosted : IBehaviorHosted {
    val behaviorQueue = ArrayDeque<IBehaviorHosted.Behavior>()

    val builder = StringBuilder()

    override fun process(behavior: IBehaviorHosted.Behavior) {
        when (behavior) {
            is IBehaviorHosted.Behavior.Deletion -> {
                behaviorQueue.removeLastOrNull()
            }

            is IBehaviorHosted.Behavior.Reset -> {
                behaviorQueue.clear()
            }

            else -> behaviorQueue.addLast(behavior)
        }
    }

    fun possiblePinYin(): Array<CandidatePinYin> {
        behaviorQueue.toList().forEach {
            when (it) {
                is IBehaviorHosted.Behavior.Input -> {
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