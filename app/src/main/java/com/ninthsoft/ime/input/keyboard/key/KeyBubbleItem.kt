package com.ninthsoft.ime.input.keyboard.key

/**
 * 按键气泡里的一项：气泡内容与它对应的输入动作。
 *
 * 把「显示什么」和「输入什么」绑在一起，是因为同一个按键的主键 / 次级符号 / 大写字母
 * 走的是不同的引擎动作：26 键的字母走 [KeyboardAction.KeySequenceAction]（交给 Rime 组词），
 * 符号与数字走 [KeyboardAction.CommitAction]（直接上屏并经过标点转换）。
 */
data class KeyBubbleItem(
    val label: String,
    val action: KeyboardAction,
)
