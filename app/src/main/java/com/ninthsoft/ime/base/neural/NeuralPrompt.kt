package com.ninthsoft.ime.base.neural

import com.ninthsoft.ime.base.util.TextUtil

/**
 * 神经模型的上下文准备。
 *
 * 与现有 n-gram 链路最大的差别是**上下文长度**：n-gram 只看最后几个字，
 * 神经模型要一整段（调研定为 S=128 词 ≈ 210 汉字）。这里负责三件事，
 * 全部是纯函数，便于单测：
 *
 * 1. **截断到预算**：超长文本取尾部窗口，不是从头截 —— 预测下一个词只依赖紧邻的上文。
 * 2. **对齐句子边界**：窗口左端多半切在半句话中间，丢掉这个残句，
 *    否则模型会把「半个词」当成真实上文（FUTO 的 prompt 截断保护就是这个作用）。
 * 3. **清洗垃圾串**：宿主 App 里可能有 URL / base64 / 密钥这种长 ASCII 串。
 *    中文词表模型对它一无所知，留着只会挤掉真正有用的中文上文。
 */
object NeuralPrompt {
    /**
     * 端侧上下文的**上限**（汉字数），不是实际使用的预算。
     *
     * 真正喂多少由 `min(manifest.context_tokens, MAX_CHARS)` 决定 ——
     * `manifest.context_tokens` 是模型训练时接受的输入长度上限，**它才是权威**：
     * 训练与端侧必须用同一个预算，喂长了模型没见过、喂短了白丢上下文。
     * 这里留 210 只作为「端侧最多愿意喂多少」的护栏（对应设计里的「3–4 句」）。
     */
    const val MAX_CHARS = 210

    /** 低于这个长度不值得前向一次，也避免「半句话」污染 KV cache。 */
    const val MIN_CHARS = 2

    /** 句子边界：优先在这里断开。换行不在其中 —— [sanitize] 已把它归一成空格。 */
    private val SENTENCE_ENDS = charArrayOf('。', '！', '？', '!', '?', '…')

    /** 从句边界：句子断点找不到时的退路。 */
    private val CLAUSE_ENDS = charArrayOf('，', '、', '；', '：', ',', ';', ':')

    /**
     * 超过这个长度的连续 ASCII 字母/数字/符号串视为垃圾（URL、base64、token）。
     * 定 24 是因为正常的中英混排里很少出现更长的无空格 ASCII 串。
     */
    private const val MAX_ASCII_RUN = 24

    /**
     * 产出送入模型的上下文。返回值可能短于 [MIN_CHARS] 甚至为空，
     * 调用方用 [isUsable] 判断是否值得推理。
     */
    fun build(committed: String, maxChars: Int = MAX_CHARS): String {
        val cleaned = sanitize(committed)
        if (cleaned.isEmpty()) return ""

        val total = cleaned.codePointCount(0, cleaned.length)
        if (total <= maxChars) return cleaned

        val start = cleaned.offsetByCodePoints(0, total - maxChars)
        return trimmedToBoundary(cleaned.substring(start))
    }

    /**
     * 丢掉开头那个被截断的残句。
     *
     * 只在**确实截断过**之后调用：文本没超预算时整段都是真上文，一个字符都不该丢。
     *
     * 只有切完还剩得下 [MIN_CHARS] 才丢 —— 否则（整段都没有句末标点）宁可保留硬切口，
     * 也不要把上下文清空。
     */
    private fun trimmedToBoundary(window: String): String {
        dropLeadingFragment(window, SENTENCE_ENDS)?.let { return it }
        return dropLeadingFragment(window, CLAUSE_ENDS) ?: window
    }

    private fun dropLeadingFragment(window: String, ends: CharArray): String? {
        val cut = indexOfFirst(window, ends)
        if (cut < 0) return null
        val rest = window.substring(cut + 1)
        return if (rest.codePointCount(0, rest.length) >= MIN_CHARS) rest else null
    }

    /** 判断这段上下文是否值得送进模型：必须有中文，且不能以符号/字母结尾。 */
    fun isUsable(context: String): Boolean {
        if (context.codePointCount(0, context.length) < MIN_CHARS) return false
        if (!containsCjk(context)) return false
        val last = context.last()
        return !TextUtil.isSymbol(last) && !TextUtil.isAlphabet(last)
    }

    /**
     * 归一化空白与控制字符，并剔除长 ASCII 串。
     *
     * 换行统一成空格（不是删除）：它是天然的句子边界，[trimmedToBoundary] 还要用。
     */
    fun sanitize(text: String): String {
        if (text.isEmpty()) return ""
        val stripped = stripLongAsciiRuns(text)
        val out = StringBuilder(stripped.length)
        var pendingSpace = false
        for (char in stripped) {
            when {
                char == '\n' || char == '\r' || char == '\t' || char == ' ' -> pendingSpace = true
                char.isISOControl() -> Unit
                else -> {
                    if (pendingSpace && out.isNotEmpty()) out.append(' ')
                    pendingSpace = false
                    out.append(char)
                }
            }
        }
        return out.toString().trim()
    }

    /**
     * 把超过 [MAX_ASCII_RUN] 的连续 ASCII「词字符」整段删掉。
     * 用替换成空格而不是直接拼掉，避免把两段中文粘成一个不存在的词。
     */
    private fun stripLongAsciiRuns(text: String): String {
        if (text.isEmpty()) return text
        val out = StringBuilder(text.length)
        var runStart = -1
        var index = 0
        while (index <= text.length) {
            val isRunChar = index < text.length && isAsciiWordChar(text[index])
            if (isRunChar) {
                if (runStart < 0) runStart = index
            } else {
                if (runStart >= 0 && index - runStart > MAX_ASCII_RUN) {
                    out.append(' ')
                } else if (runStart >= 0) {
                    out.append(text, runStart, index)
                }
                runStart = -1
                if (index < text.length) out.append(text[index])
            }
            index++
        }
        return out.toString()
    }

    private fun isAsciiWordChar(char: Char): Boolean =
        char in 'a'..'z' || char in 'A'..'Z' || char in '0'..'9' ||
            char == '+' || char == '/' || char == '=' || char == '_' ||
            char == '-' || char == '.' || char == '%'

    private fun containsCjk(text: String): Boolean {
        for (char in text) {
            if (isCjk(char)) return true
        }
        return false
    }

    private fun isCjk(char: Char): Boolean = when (char.code) {
        in 0x3400..0x4DBF -> true      // 扩展 A
        in 0x4E00..0x9FFF -> true      // 基本区
        in 0xF900..0xFAFF -> true      // 兼容表意
        else -> false
    }

    private fun indexOfFirst(text: String, targets: CharArray): Int {
        for (index in text.indices) {
            if (text[index] in targets) return index
        }
        return -1
    }
}
