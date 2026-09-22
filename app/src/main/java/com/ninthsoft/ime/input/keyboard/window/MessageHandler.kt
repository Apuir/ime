package com.ninthsoft.ime.input.keyboard.window

import android.inputmethodservice.InputMethodService
import com.ninthsoft.ime.engine.EngineFactory
import com.ninthsoft.ime.engine.data.EngineMessage
import com.ninthsoft.ime.input.ImeInputMethodService
import com.ninthsoft.ime.input.LivePreviewController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber

class MessageHandler(
    private val service: InputMethodService,
) {
    private var window: KeyboardWindow? = null

    private val livePreview: LivePreviewController?
        get() = (service as? ImeInputMethodService)?.livePreview

    fun attach(window: KeyboardWindow) {
        this.window = window
    }

    suspend fun handle(message: EngineMessage) {
        when (message) {
            is EngineMessage.Commit -> {
                val ic = (service as ImeInputMethodService).activeInputConnection()
                if (ic != null) {
                    ic.commitText(message.text, 1)
                    if (message.cursorOffset > 0) {
                        // 成对符号：提交后把光标向左回退到符号中间。
                        // 拿不到光标前缀时不做处理，避免误把光标移到开头。
                        val end = ic.getTextBeforeCursor(Int.MAX_VALUE, 0)?.length
                        if (end != null && end >= message.cursorOffset) {
                            val pos = end - message.cursorOffset
                            ic.setSelection(pos, pos)
                        }
                    }
                }
                // 正式提交后，输入框里的预览 composing 已被 commitText 替换。
                livePreview?.onCommitted()
            }

            is EngineMessage.Candidates -> {
                livePreview?.onCandidates(message.list)
                window?.setCandidates(message.list, message.hasMore)
            }

            is EngineMessage.Composition -> {
                // RimeEngine 对 InlinePreedit 只在内部消化，这里以 Composition.preedit 作为
                // 原始输入上屏的来源。
                livePreview?.onPreedit(message.preedit)
            }

            is EngineMessage.InlinePreedit -> {
                livePreview?.onPreedit(message.preedit)
            }

            is EngineMessage.Depoly -> {
                Timber.d("EngineMessage.Depoly")
                if (message.state == EngineMessage.Depoly.State.Finish) {
                    window?.onDepolyFinished()
                }
            }

            is EngineMessage.PossibleCandidatePinYin -> {
                window?.onPossibleCandidatePinYin(message.possibleCandidatePinYins)
            }

            is EngineMessage.DynamicPreedit -> {
                window?.updateDynamicPreedit(message.preedits)
            }

            else -> {}
        }
    }
}
