package com.ninthsoft.ime.input.panel

import android.view.View
import com.ninthsoft.ime.engine.data.CandidatePinYin
import com.ninthsoft.ime.engine.data.EngineMessage

interface IPanel {
    val view: View
    var recording: Boolean

    /**
     * 更新候选列表。
     *
     * [hasMore] 表示还能再要一批（划到底时有效）：为 false 时面板不再向外索要下一批，
     * 免得空转。
     */
    fun setCandidates(list: List<EngineMessage.Candidate>, hasMore: Boolean = true)
    fun refreshTheme()
    fun onFinishInputView(finishingInput: Boolean)
    fun onPossibleCandidatePinYin(pinyins: List<CandidatePinYin>)
    fun exitAddPhraseMode()
}
