package com.ninthsoft.ime.base.util

import android.inputmethodservice.InputMethodService
import android.os.SystemClock
import android.view.KeyEvent
import android.view.inputmethod.InputConnection
import android.view.KeyEvent.ACTION_DOWN
import android.view.KeyEvent.ACTION_UP
import android.view.KeyEvent.KEYCODE_CTRL_LEFT
import android.view.KeyEvent.KEYCODE_SHIFT_LEFT
import android.view.KeyEvent.META_CTRL_LEFT_ON
import android.view.KeyEvent.META_CTRL_ON
import android.view.KeyEvent.META_SHIFT_LEFT_ON
import android.view.KeyEvent.META_SHIFT_ON

class InputConnectionUtil {

    companion object {
        /**
         * 发送一个完整的按键（DOWN + UP）。
         *
         * 用于「把按键原样交给应用处理」的场景 —— 例如回车：由应用决定是换行还是提交。
         * 注意不要用 `commitText("\n")` 代替回车，单行输入框会把换行显示成空格。
         */
        fun sendKeyEvent(ic: InputConnection?, keyCode: Int) {
            ic ?: return
            val now = SystemClock.uptimeMillis()
            ic.sendKeyEvent(KeyEvent(now, now, ACTION_DOWN, keyCode, 0))
            ic.sendKeyEvent(KeyEvent(now, now, ACTION_UP, keyCode, 0))
        }

        fun sendCombinationKeyEvent(
            service: InputMethodService, keyCode: Int, ctrl: Boolean = false, shift: Boolean = false
        ) {
            sendCombinationKeyEvent(service.currentInputConnection, keyCode, ctrl, shift)
        }

        fun sendCombinationKeyEvent(
            ic: InputConnection?, keyCode: Int, ctrl: Boolean = false, shift: Boolean = false
        ) {
            ic ?: return
            val now = SystemClock.uptimeMillis()
            var meta = 0
            if (ctrl) meta = meta or META_CTRL_ON or META_CTRL_LEFT_ON
            if (shift) meta = meta or META_SHIFT_ON or META_SHIFT_LEFT_ON
            if (ctrl) ic.sendKeyEvent(
                KeyEvent(
                    now, now, ACTION_DOWN, KEYCODE_CTRL_LEFT, 0, 0
                )
            )
            if (shift) ic.sendKeyEvent(
                KeyEvent(
                    now, now, ACTION_DOWN, KEYCODE_SHIFT_LEFT, 0, 0
                )
            )
            ic.sendKeyEvent(KeyEvent(now, now, ACTION_DOWN, keyCode, 0, meta))
            ic.sendKeyEvent(KeyEvent(now, now, ACTION_UP, keyCode, 0, meta))
            if (shift) ic.sendKeyEvent(
                KeyEvent(
                    now, now, ACTION_UP, KEYCODE_SHIFT_LEFT, 0, 0
                )
            )
            if (ctrl) ic.sendKeyEvent(
                KeyEvent(
                    now, now, ACTION_UP, KEYCODE_CTRL_LEFT, 0, 0
                )
            )
        }
    }

}