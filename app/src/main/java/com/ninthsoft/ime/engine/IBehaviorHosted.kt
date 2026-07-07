package com.ninthsoft.ime.engine

interface IBehaviorHosted {

    sealed class Behavior(val behavior: String) {
        data object Selection : Behavior("selection")
        data object Segmentation : Behavior("segmentation")
        data object Reset : Behavior("reset")
        data object Deletion : Behavior("deletion")
        data class Input(val sequence: String) : Behavior("input")
        data class InputKey(val code: Int, val modifiers: Int, val isVirtual: Boolean) :
            Behavior("inputKey")

        data class Confirmtation(val code: Int) : Behavior("confirmtation")
    }

    fun process(behavior: Behavior)
}