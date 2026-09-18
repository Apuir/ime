package com.ninthsoft.ime.input.keyboard.key

import android.view.View

/**
 * 按键气泡的宿主：能在一个**窗口内部**把气泡画出来的 View。
 *
 * 为什么是宿主的接口而不是 `PopupWindow`：气泡要有「从按键里长出来」的样子，
 * 就得比键盘顶行还高（顶行的气泡主体压在顶栏上）。而 IME 窗口是
 * `MATCH_PARENT × WRAP_CONTENT` —— 窗口上边界就是顶栏上沿，`PopupWindow` 是它的子窗口，
 * 画不出窗口之外。所以气泡改由**窗口自己**在 `dispatchDraw` 里画（见
 * `KeyboardWindowView`），既不会被裁剪，也不必再跟窗口坐标系较劲。
 *
 * 借鉴对象是 Xime（`ui/keyboard/SwipeBubble.kt`）：气泡主体悬在按键上方、
 * 一条**与按键同宽的竖条**压住按键本身，两者是同一条 `Path`，看起来和键帽是一体的。
 */
interface KeyBubbleHost {

    /**
     * 在 [anchor] 这个按键上方画一个气泡。内容与颜色全部取自 [controller]。
     *
     * 返回 null 表示这次画不了（按键还没测量、窗口没有可用空间等），调用方应当
     * 退回普通点击，不要把这次触摸吞掉。
     */
    fun showKeyBubble(anchor: View, controller: HasKeyBubble): KeyBubble?
}

/**
 * 一个已经显示出来的气泡。
 *
 * 手势层只依赖这个接口：[itemStep] / [contentLeft] / [contentWidth] 是**屏幕坐标**，
 * 手指滑到哪一项就把哪一项高亮（见 `CustomGestureView.moveBubbleSelection`）。
 */
interface KeyBubble {

    val isShowing: Boolean

    val itemCount: Int

    val selectedIndex: Int

    /** 相邻两项中心的屏幕间距（px）。 */
    val itemStep: Float

    /** 气泡内容区左边缘的屏幕 x（px）。 */
    val contentLeft: Int

    /** 气泡内容区宽度（px）。 */
    val contentWidth: Int

    /** 切换高亮项，返回是否真的变了（调用方据此决定要不要补一次触感）。 */
    fun selectIndex(index: Int): Boolean

    fun dismiss()
}
