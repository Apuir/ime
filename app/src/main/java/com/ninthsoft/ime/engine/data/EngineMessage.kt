package com.ninthsoft.ime.engine.data

sealed class EngineMessage {
    data class Commit(val text: String) : EngineMessage()
    data class Composition(val preedit: String, val cursorPos: Int) : EngineMessage()
    data class Candidates(
        val list: List<Candidate>,
        val highlighted: Int,
        val page: Int,
    ) : EngineMessage()

    data class Status(val schemaName: String, val isAsciiMode: Boolean) : EngineMessage()
    data object CompositionEnd : EngineMessage()

    data object Unknown : EngineMessage()
    data class Candidate(
        val text: String,
        val comment: String = "",
    )
}
