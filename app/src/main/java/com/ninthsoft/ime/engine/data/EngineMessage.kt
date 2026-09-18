package com.ninthsoft.ime.engine.data

sealed class EngineMessage {
    /**
     * 提交一段文本。[cursorOffset] > 0 时，提交完成后把光标向左回退这么多字符
     * （用于 `（）` 这类成对符号，让光标停在中间）。
     */
    data class Commit(val text: String, val cursorOffset: Int = 0) : EngineMessage()
    data class Composition(val preedit: String, val cursorPos: Int) : EngineMessage()
    data class Candidates(
        val list: List<Candidate>,
        val highlighted: Int,
        val page: Int,
    ) : EngineMessage() {}

    data class Status(val isComposing: Boolean = false) : EngineMessage()
    data class InlinePreedit(val preedit: String) : EngineMessage()

    data class DynamicPreedit(val preedits: List<DynamicPreeditItem>) : EngineMessage() {
        enum class DynamicPreeditType {
            Normal, Secondary,
        }

        class DynamicPreeditItem(val text: String, val type: DynamicPreeditType)
    }

    data class Schema(
        val id: String,
        val name: String,
        val layout: String = "",
        val punctuation: String = "",
        val kind: String = "",
        /**
         * 方案声明的候选类型（`PinYin` / `T9PinYin`…），键盘与方案的耦合点。
         *
         * 它不在 native 返回的 `SchemaItem` 里，由 [com.ninthsoft.ime.engine.RimeEngine.schemasList]
         * 打开方案配置补上；方案切换消息不经过那条路径，因此这里默认空串。
         */
        val candidateKind: String = "",
    ) : EngineMessage()

    data class Depoly(val state: State) : EngineMessage() {
        enum class State { Start, Success, Failure, Finish }
    }

    data class PossibleCandidatePinYin(val possibleCandidatePinYins: List<CandidatePinYin>) :
        EngineMessage()

    data class CandidateMenu(
        val pageSize: Int = 0,
        val pageNumber: Int = 0,
        val isLastPage: Boolean = false,
        val highlightedCandidateIndex: Int = 0,
        val selectKeys: String? = null,
        val selectLabels: List<String> = listOf(),
        val candidates: List<Candidate>,
    ) : EngineMessage() {
        data class Candidate(
            val index: Int, val text: String, val comment: String, val label: String,
            val type: String = "",
        )
    }

    data object Unknown : EngineMessage()

    data class Candidate(
        val index: Int,
        val text: String,
        val comment: String = "",
        val type: String = "",
        var score: Double = 0.0,
    ) {
        companion object {
            const val TYPE_IME_PREDICTION = "imePrediction"
            const val TYPE_USER_PHRASE = "user_phrase"
        }
    }
}
