package com.ninthsoft.ime.engine.data

import com.ninthsoft.ime.engine.event.KeyEvent


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

    data class Key(val key: KeyEvent.CodeEvent) : EngineMessage()

    data class InlinePreedit(val preedit: String) : EngineMessage()

    data class Schema(val id: String, val name: String, val layout: String = "") : EngineMessage()

    data class RerankedCandidate(val best: Candidate) : EngineMessage()
    data object RerankStarted : EngineMessage()

    data class PossibleCandidatePinYin(val possibleCandidatePinYins: Array<CandidatePinYin>) :
        EngineMessage() {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false
            other as PossibleCandidatePinYin
            return possibleCandidatePinYins.contentEquals(other.possibleCandidatePinYins)
        }

        override fun hashCode(): Int {
            return possibleCandidatePinYins.contentHashCode()
        }
    }

    data class CandidateMenu(
        val pageSize: Int = 0,
        val pageNumber: Int = 0,
        val isLastPage: Boolean = false,
        val highlightedCandidateIndex: Int = 0,
        val selectKeys: String? = null,
        val selectLabels: Array<String> = arrayOf(),
        val candidates: Array<Candidate>,
    ) : EngineMessage() {
        data class Candidate(
            val index: Int, val text: String, val comment: String, val label: String
        )

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false
            other as CandidateMenu
            return candidates.contentEquals(other.candidates)
        }

        override fun hashCode(): Int {
            return candidates.contentHashCode()
        }
    }

    data object Unknown : EngineMessage()
    data class Candidate(
        val index: Int,
        val text: String,
        val comment: String = "",
    )
}
