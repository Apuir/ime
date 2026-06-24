package com.ninthsoft.ime.input.panel

import android.view.View
import com.ninthsoft.ime.engine.data.EngineMessage

interface IPanel {
    val view: View
    fun setCandidates(list: List<EngineMessage.Candidate>)
    fun showRerankAnimation()
    fun setRerankedCandidate(candidate: EngineMessage.Candidate)
    fun refreshTheme()
}
