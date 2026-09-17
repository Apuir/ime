package com.ninthsoft.ime.input

import android.view.inputmethod.InputConnection
import com.ninthsoft.ime.data.manager.CandidateManager
import com.ninthsoft.ime.engine.data.EngineMessage

/**
 * 「输入框实时上屏」控制器。
 *
 * 引擎在组合输入过程中会发出 [EngineMessage.Composition]（`preedit` 为原始输入，
 * 例如 ni'hao）与 [EngineMessage.Candidates]（候选列表）。本控制器根据设置里的上屏模式，
 * 把其中一份内容以 composing text 的形式写入真实输入框：
 *
 *  - [CandidateManager.PREVIEW_MODE_NONE]：不写任何内容（默认，等同旧行为）；
 *  - [CandidateManager.PREVIEW_MODE_RAW]：写入原始输入；
 *  - [CandidateManager.PREVIEW_MODE_FIRST_CANDIDATE]：写入当前第一个候选词。
 *
 * 预览文本始终是 composing 状态，因此引擎正式提交候选词时，`commitText` 会自然替换掉它。
 */
class LivePreviewController(private val service: ImeInputMethodService) {

    /** 引擎当前组合的原始输入（Rime preedit）。 */
    private var preedit: String = ""

    /** 当前候选列表的第一个候选词。 */
    private var firstCandidate: String = ""

    /** 本控制器写入输入框、且尚未被替换的预览文本；空串表示当前没有预览。 */
    private var shownPreview: String = ""

    /**
     * 本控制器写入的预览（组合）在输入框里占据的位置区间，闭区间。
     *
     * 宿主靠它把「我们自己写预览造成的光标变化」和「用户手动移动光标」区分开：
     * 回调里的光标只要还在这个区间内，就认为是我们自己造成的（见 [SelectionMath]）。
     * 为 null 表示当前没有预览，或者写入前连光标位置都读不到。
     */
    var previewRange: IntRange? = null
        private set

    private fun inputConnection(): InputConnection? = service.activeInputConnection()

    /** 组合内容变化（原始输入）。 */
    fun onPreedit(text: String) {
        preedit = text
        refresh()
    }

    /**
     * 候选列表变化，取第一个候选作为「首候选上屏」的内容。
     *
     * 预测候选（[EngineMessage.Candidate.TYPE_IME_PREDICTION]）属于上一段已提交文本的
     * 联想结果，不属于当前输入组合，忽略即可，否则会把联想词错误地当成组合内容上屏。
     */
    fun onCandidates(list: List<EngineMessage.Candidate>) {
        val first = list.firstOrNull()
        if (first != null && first.type == EngineMessage.Candidate.TYPE_IME_PREDICTION) return
        firstCandidate = first?.text.orEmpty()
        refresh()
    }

    /**
     * 引擎已正式提交一段文本。真实输入框里的 composing 区由 `commitText` 负责替换，
     * 这里只需同步内部状态，避免下次旧状态造成的误清除。
     */
    fun onCommitted() {
        clearState()
    }

    /** 用户在设置里切换了上屏模式，立即对当前组合生效。 */
    fun onPreviewModeChanged() {
        refresh()
    }

    /**
     * 切换输入方案或收起键盘前调用：决定已上屏的预览内容是留还是丢。
     *
     * - 打开 [CandidateManager.isCommitPreviewOnSwitch]：结束 composing，预览文本正式留在输入框；
     * - 关闭：删除预览文本，输入框什么都不留下。
     */
    fun finalizeForKeyboardSwitch() {
        val preview = shownPreview
        clearState()
        if (preview.isEmpty()) return
        val ic = inputConnection() ?: return
        if (CandidateManager.isCommitPreviewOnSwitch(service)) {
            ic.finishComposingText()
        } else {
            ic.setComposingText("", 1)
            ic.finishComposingText()
        }
    }

    private fun clearState() {
        preedit = ""
        firstCandidate = ""
        shownPreview = ""
        previewRange = null
    }

    private fun desiredPreview(): String = when (CandidateManager.getPreviewMode(service)) {
        CandidateManager.PREVIEW_MODE_RAW -> preedit
        CandidateManager.PREVIEW_MODE_FIRST_CANDIDATE -> firstCandidate
        else -> ""
    }

    /**
     * 把当前预览「定为正式文本」：结束 composing 但**不删除**内容，输入框里显示的
     * 候选词 / 拼音就此成为普通文本（即用户说的「直接输入完毕」）。
     *
     * 与 [finalizeForKeyboardSwitch] 的区别：后者按设置决定留还是丢，用于切换键盘 / 方案的场景；
     * 这里用于「用户把光标点到别处了」——他并没有表达要丢弃，所以一律保留。
     */
    fun commitPreview() {
        val preview = shownPreview
        clearState()
        if (preview.isEmpty()) return
        inputConnection()?.finishComposingText()
    }

    private fun refresh() {
        val target = desiredPreview()
        if (target == shownPreview) return
        val ic = inputConnection() ?: return
        if (target.isEmpty()) {
            ic.setComposingText("", 1)
            ic.finishComposingText()
            previewRange = null
        } else {
            // `setComposingText` 替换的是**现有组合区**，插入点是组合区**起点**，
            // 而写入前光标停在组合区**末尾**（上一轮的落点）。组合区起点与范围的计算
            // 收在 SelectionMath.compositionRange 里，那里有单测钉着 —— 这段曾经算错，
            // 导致每打一个字母就结束一次组合。
            val cursorBefore = ic.getTextBeforeCursor(Int.MAX_VALUE, 0)?.length ?: -1
            ic.setComposingText(target, 1)
            // 位置不可信时留 null：宿主会跳过判定，而不是拿错位置去误伤用户输入。
            previewRange = SelectionMath.compositionRange(
                cursorBefore = cursorBefore,
                previousPreviewLength = shownPreview.length,
                newPreviewLength = target.length,
            )
        }
        shownPreview = target
    }
}
