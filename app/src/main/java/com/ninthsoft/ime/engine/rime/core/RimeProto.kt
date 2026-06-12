// SPDX-License-Identifier: Apache-2.0

package com.ninthsoft.ime.engine.rime.core

data class CommitProto(
    val text: String?,
)

data class CandidateProto(
    val text: String,
    val comment: String,
    val label: String,
)

data class CompositionProto(
    val length: Int = 0,
    val cursorPos: Int = 0,
    val selStart: Int = 0,
    val selEnd: Int = 0,
    val preedit: String? = null,
    val commitTextPreview: String? = null,
) {
    internal constructor(text: String) : this(
        length = text.length,
        cursorPos = text.length,
        selStart = text.length,
        selEnd = text.length,
        preedit = text,
    )
}

data class MenuProto(
    val pageSize: Int = 0,
    val pageNumber: Int = 0,
    val isLastPage: Boolean = false,
    val highlightedCandidateIndex: Int = 0,
    val candidates: Array<CandidateProto> = arrayOf(),
    val selectKeys: String? = null,
    val selectLabels: Array<String> = arrayOf(),
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is MenuProto) return false
        return pageSize == other.pageSize &&
            pageNumber == other.pageNumber &&
            isLastPage == other.isLastPage &&
            highlightedCandidateIndex == other.highlightedCandidateIndex &&
            candidates.contentEquals(other.candidates) &&
            selectKeys == other.selectKeys &&
            selectLabels.contentEquals(other.selectLabels)
    }

    override fun hashCode(): Int {
        var result = pageSize
        result = 31 * result + pageNumber
        result = 31 * result + isLastPage.hashCode()
        result = 31 * result + highlightedCandidateIndex
        result = 31 * result + candidates.contentHashCode()
        result = 31 * result + (selectKeys?.hashCode() ?: 0)
        result = 31 * result + selectLabels.contentHashCode()
        return result
    }
}

data class ContextProto(
    val composition: CompositionProto = CompositionProto(),
    val menu: MenuProto = MenuProto(),
    val input: String = "",
    val caretPos: Int = 0,
)

data class StatusProto(
    val schemaId: String = "",
    val schemaName: String = "",
    val isDisabled: Boolean = true,
    val isComposing: Boolean = false,
    val isAsciiMode: Boolean = true,
    val isFullShape: Boolean = false,
    val isSimplified: Boolean = false,
    val isTraditional: Boolean = false,
    val isAsciiPunct: Boolean = true,
)
