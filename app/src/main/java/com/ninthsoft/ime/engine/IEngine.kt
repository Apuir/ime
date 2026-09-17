package com.ninthsoft.ime.engine

import android.content.Context
import android.inputmethodservice.InputMethodService
import android.view.inputmethod.InputConnection
import com.ninthsoft.ime.engine.data.CandidatePinYin
import com.ninthsoft.ime.engine.data.EngineMessage
import com.ninthsoft.ime.engine.event.KeyEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job

interface IEngine {
    fun initialize(context: Context)
    fun finalize()
    fun processKey(service: InputMethodService, key: KeyEvent): Unit?
    fun selectCandidate(candidate: EngineMessage.Candidate)
    fun schemasList(): List<EngineMessage.Schema>
    fun clear(service: InputMethodService)
    fun resetComposition()
    fun selectSchema(schemaId: String)
    fun selectCandidatePinYin(pinYin: CandidatePinYin)
    fun segement()
    fun undo(service: InputMethodService)
    fun redo(service: InputMethodService)
    fun commit(text: String, cursorOffset: Int = 0)
    fun resortCandidates(candidates: List<EngineMessage.Candidate>): Unit?

    /**
     * 「忘记 / 删除」某个候选。
     *
     * 传整个候选而不是序号：除了让引擎把它从用户词典里删掉（`delete_candidate`），
     * 还要按文本记一条**负反馈**（误选降权），只有序号拿不到文本。
     */
    fun deleteCandidate(candidate: EngineMessage.Candidate): Unit?
    fun predict(commit: String = "")
    fun reload()
    fun onStartInputView(ic: InputConnection)
    fun onFinishInputView()
    fun onInputCleared()

    /**
     * 取消当前展示的联想（预测）候选。
     *
     * 与 [clear] 的区别：只清掉预测结果，不碰输入框内容、也不动 Rime 组合 ——
     * 候选面板右侧那个「叉」走的就是这条路径。
     */
    fun dismissPrediction()
    fun observeMessages(scope: CoroutineScope, onMessage: suspend (EngineMessage) -> Unit): Job
}
