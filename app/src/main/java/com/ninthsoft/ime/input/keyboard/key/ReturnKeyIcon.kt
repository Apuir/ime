package com.ninthsoft.ime.input.keyboard.key

import android.view.inputmethod.EditorInfo
import com.ninthsoft.ime.R

/**
 * 回车键该显示哪枚图标。
 *
 * 键盘与手写面板共用这一份映射：同一个输入框里，回车键的画法不该因为用的是哪个键盘而不同
 * （手写面板曾经固定显示放大镜，在聊天框里就成了错的）。
 *
 * 判据是**当前 [EditorInfo]**，不是键盘实例缓存的那枚图标 —— 键盘重建后那个缓存会滞后
 * （从搜索框切到聊天框，图标还是放大镜）。
 */
fun returnKeyIcon(info: EditorInfo, empty: Boolean, isComposing: Boolean): Int {
    if (empty || isComposing) return R.drawable.ic_keyboard_return
    return when (info.imeOptions and EditorInfo.IME_MASK_ACTION) {
        EditorInfo.IME_ACTION_SEARCH -> R.drawable.ic_keyboard_search
        EditorInfo.IME_ACTION_SEND -> R.drawable.ic_keyboard_send
        EditorInfo.IME_ACTION_GO -> R.drawable.ic_keyboard_go
        EditorInfo.IME_ACTION_PREVIOUS -> R.drawable.ic_keyboard_arrow_left
        EditorInfo.IME_ACTION_NEXT -> R.drawable.ic_keyboard_arrow_right
        EditorInfo.IME_ACTION_DONE -> R.drawable.ic_keyboard_done
        else -> R.drawable.ic_keyboard_return
    }
}
