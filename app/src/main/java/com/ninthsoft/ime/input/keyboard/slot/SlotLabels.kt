package com.ninthsoft.ime.input.keyboard.slot

import android.content.Context
import androidx.annotation.StringRes
import com.ninthsoft.ime.R
import com.ninthsoft.ime.engine.data.EngineMessage

/** 输入方式基础名（九键 / 26键 / 15键 / 手写）。序号由 [KeyboardSlotPlan.plan] 拼接。 */
@StringRes
fun SlotInputMethod.labelRes(): Int = when (this) {
    SlotInputMethod.T9 -> R.string.slot_method_t9
    SlotInputMethod.Qwerty -> R.string.slot_method_qwerty
    SlotInputMethod.T15 -> R.string.slot_method_t15
    SlotInputMethod.Handwriting -> R.string.slot_method_handwriting
}

fun SlotInputMethod.label(context: Context): String = context.getString(labelRes())

/**
 * 用当前可用方案算出中文槽的平铺列表。
 *
 * 设置页与键盘里的切换弹窗都走这里，保证两处看到的是同一份列表。
 */
fun buildChineseSlotItems(
    context: Context,
    schemas: List<EngineMessage.Schema>,
): List<SlotSwitchItem> = KeyboardSlotPlan.plan(
    schemas = schemas,
    supportedKeyboards = KeyboardSlotPlan.APP_SLOT_KEYBOARDS,
    displayLabelOf = { context.getString(it.labelRes()) },
)
