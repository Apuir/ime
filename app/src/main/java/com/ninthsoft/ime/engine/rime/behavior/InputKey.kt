package com.ninthsoft.ime.engine.rime.behavior

import com.ninthsoft.ime.engine.behavior.InputKey

class InputKey(
    override val code: Int, override val modifiers: Int, override val isVirtual: Boolean
) : InputKey(code, modifiers, isVirtual), RimeBehavior by RimeBehavior.Impl() {
    override fun invoke() {
        job?.sendJob { processKey(code, modifiers.toUInt(), isVirtual) }
    }
}
