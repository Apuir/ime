package com.ninthsoft.ime.input

/**
 * 「光标是不是被用户挪出组合区了」的纯计算部分。
 *
 * ### 为什么需要它
 *
 * 开了「输入框实时上屏」之后，输入法每敲一个键都会往输入框写一次 composing 预览
 * （见 [LivePreviewController]）。而**写预览本身也会触发系统的 selection 回调**
 * （`InputMethodService.onUpdateSelection`），回调里分不出「这次变化是我写的」还是
 * 「用户把光标点到别处了」。分不出来的后果就是用户点完别处、再打字时，输入法还挂在
 * 上一次的组合上，继续往旧位置后面接着输入。
 *
 * ### 为什么比的是「组合区范围」而不是「精确光标位置」
 *
 * 这里踩过一次坑：最初比的是「预期光标位置」，结果**第二个字母必被打断**。
 * `setComposingText` 替换的是现有组合区，插入点是组合区**起点**，而写入前光标停在
 * 组合区**末尾** —— 把两者混为一谈，位置从第二个字母起就永远对不上，判定误以为用户
 * 挪走了光标，于是每打一个字就结束一次组合（表现为「打不了字，只出得来单个字母」）。
 *
 * 即使算对了，精确比对也扛不住竞态：系统回调是异步的，连打时上一拍的回调会晚到，
 * 拿它跟新的预期值比同样会对不上。改成「光标还在不在组合区范围里」之后：
 *
 *  - 连打时组合区从起点向后连续扩展，滞后的回调必然仍落在区间内 → 不会误判；
 *  - 用户点到组合区之外 → 一次就能判出来。
 *
 * 单独放一个不依赖 Android 的 object，是为了能在 JVM 单测里钉住边界
 * （回调链路本身在 Service 与真实输入框之间，需要真机，见 `SelectionMathTest`）。
 */
object SelectionMath {

    /**
     * 光标是否已经不落在输入法自己写入的组合区里了。
     *
     * @param newSelStart      系统回调报告的新选区起点。
     * @param newSelEnd        系统回调报告的新选区终点（普通光标时与起点相等）。
     * @param compositionStart 组合区起点（含）。
     * @param compositionEnd   组合区终点（含），即写入预览后光标应处的位置。
     */
    fun isCursorAwayFromComposition(
        newSelStart: Int,
        newSelEnd: Int,
        compositionStart: Int,
        compositionEnd: Int,
    ): Boolean {
        // 没有可用的组合区（还没写过预览，或写入前拿到的光标位置无效）：
        // 没有任何依据判断这次回调是谁引起的，一律不动 —— 宁可漏判，
        // 也不能把用户正在打的字打断。
        if (compositionEnd <= compositionStart) return false

        // 我们写预览时从不产生选区，出现选区就说明是用户自己拖出来的。
        if (newSelStart != newSelEnd) return true

        return newSelStart < compositionStart || newSelStart > compositionEnd
    }

    /**
     * 算出写入预览后，组合区在输入框里占据的范围（闭区间）。
     *
     * `setComposingText` 替换的是**现有组合区**，插入点是组合区**起点**；而 [cursorBefore]
     * 是**写入前**的光标位置，它停在上一轮组合区的**末尾**。所以起点必须减掉上一轮的预览长度。
     *
     * ⚠️ 这里正是出过错的地方：直接用 `cursorBefore + 新长度` 当预期光标，等于把起点算成了
     * 「上一轮的落点」，打完**第二个字母**起位置就永远对不上 —— 组合每打一个字被结束一次。
     * 单测里 `组合区起点要减掉上一轮预览的长度` 就是钉这个的。
     *
     * @param cursorBefore          写入前的光标位置；读不到时传负数。
     * @param previousPreviewLength 上一轮写入的预览长度（首次写入传 0）。
     * @param newPreviewLength      本轮要写入的预览长度。
     * @return 组合区闭区间；位置不可信时返回 null，宿主会据此跳过判定。
     */
    fun compositionRange(
        cursorBefore: Int,
        previousPreviewLength: Int,
        newPreviewLength: Int,
    ): IntRange? {
        if (cursorBefore < 0 || previousPreviewLength < 0 || newPreviewLength <= 0) return null
        val start = cursorBefore - previousPreviewLength
        if (start < 0) return null
        return start..(start + newPreviewLength)
    }
}
