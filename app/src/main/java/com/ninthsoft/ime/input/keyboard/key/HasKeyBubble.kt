package com.ninthsoft.ime.input.keyboard.key

/**
 * 能提供「按键气泡」内容的 View。
 *
 * `CustomGestureView` 只负责「什么时候弹、划到第几项、抬手提交哪一项」，
 * 气泡里有什么、用什么颜色，全部由这个接口提供 —— 这样按键的具体映射
 * （26 键的字母/符号/大写，九键的数字/字母）留在键盘实现层，手势层不依赖它。
 */
interface HasKeyBubble {

    /** 气泡里从左到右的各项；为空表示这个键不弹气泡。 */
    val bubbleItems: List<KeyBubbleItem>

    /** 气泡背景色（跟随主题的键帽背景）。 */
    val bubbleBackgroundColor: Int

    /** 气泡里高亮项的背景色（跟随主题的强调色）。 */
    val bubbleSelectedBackgroundColor: Int

    /** 气泡未选中项的文字色。 */
    val bubbleTextColor: Int

    /** 气泡高亮项的文字色。 */
    val bubbleSelectedTextColor: Int

    /** 气泡圆角半径（px）。跟键帽用同一个值，气泡看起来才和键盘是一套。 */
    val bubbleCornerRadius: Float

    /** 气泡描边颜色；不描边时给透明色。 */
    val bubbleStrokeColor: Int

    /** 气泡描边厚度（px）。 */
    val bubbleStrokeWidth: Int
}
