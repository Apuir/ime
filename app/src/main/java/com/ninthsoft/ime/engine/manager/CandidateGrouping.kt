package com.ninthsoft.ime.engine.manager

import com.ninthsoft.ime.engine.data.EngineMessage.Candidate

/**
 * 候选的**字长分组 + 分批展示**。
 *
 * 解决的问题：九键打 `h h h h`（输入码 `4444`）时引擎回的是「按分数排好的一整串」候选，
 * 四个字、三个字、两个字、单字混在一起，而且长词霸榜。用户要的是：**字多的在前、字少的在后，
 * 每组只留常用的那几个**，而且**数量不设上限**、一次只给一批。
 *
 * 分组顺序（刻意不是单纯的字数降序）：
 *
 * ```
 * 4 字 → 5 字及以上 → 3 字 → 2 字 → 1 字
 * ```
 *
 * 5 字以上那些是补全出来的长词（`translator/enable_completion`，关掉它 5 字以上会整组消失），
 * 既不该顶在四字词前面，也不该被当成「更长的词」压住三字词，所以夹在两者之间。
 *
 * **引擎的首选固定在最前**（`candidates[0]`，见 [apply]）：它既是引擎自己认为最可能的那个词，
 * 也是**按空格上屏的目标**，跟 [CandidateRerankManager] 不改动它出于同一个理由。
 * 代价是它可能比后面的词短 —— 这是全列表里唯一一处「字长顺序」让位给「所见即所得」的地方。
 * 实际输入下它几乎总是最长的那类词（要覆盖整个输入码才排得到第一），所以通常看不出来。
 *
 * **每批要凑够 [GroupingConfig.batchSize] 条**（默认 50，一屏铺得满）。第 k 批的构成是：
 *
 * ```
 * [引擎首选] + 4 字×6 + 5 字以上×3 + 3 字×6 + 2 字×6 + 单字补齐到 50
 * ```
 *
 * 多字组取的是**自己组内第 `step(k-1)+1 .. step·k` 条**，按引擎顺序数（引擎顺序就是常用度顺序，
 * `CandidateProto` 里没有词频字段，能用的常用度信号只有位次）。凑不满的部分全部由单字组承担：
 * 它本来就不限条数，而且排在最后，正好补在尾巴上 —— 这样整批依然是严格「字多的在前」。
 *
 * 划到底再要一批，就是各组往后各取同样多条，**追加在列表末尾**。
 * 为什么是「追加在末尾」而不是「全局严格按字长排序、每组容量随批次放宽」：后者会把新
 * 出现的四字词插到三字词前面，用户正划到列表底部，新候选却出现在他头顶，等于白要一批。
 *
 * ## ⚠️ 分组只能整理**已经取回来的**候选，凑满一批必须先把候选池挖深
 *
 * 引擎给长词排得很前（长词覆盖掉的输入码更多，分数天然更高），短词可能深到离谱。
 * 2026-09-22 用 `scripts/rime-probe` 实测九键 `4444`（共 6754 条候选）：
 *
 * | 字长 | 4 字 | 5 字以上 | 3 字 | 2 字 | 1 字 |
 * |------|------|---------|------|------|------|
 * | 首次出现位次 | **1** | 90 | **136** | **729** | **2458** |
 *
 * 而 App 一次只从引擎取 100 条（native 的 `kBulkCandidateLimit`）—— **前 100 条里
 * 89 条是四字词，剩下 11 条是五/六/七字，三字、双字、单字一条都没有**。
 * 那么一批最多只能凑出 `1 + 6 + 3 = 10` 条 —— 连屏幕都铺不满，更别说单字双字。
 *
 * 所以**「凑满 50」和「单字双字出得来」是同一件事的两面：池子必须挖到单字那一层**
 * （四码输入大约要读到第 2500 条）。挖深由 `RimeEngine` 异步分块做，见那边的
 * `schedulePoolDeepening()`；本类只负责「现有池子里能凑多少就凑多少」。
 *
 * 纯计算，不依赖 Android，见 `app/src/test/java/.../CandidateGroupingTest.kt`。
 */
data class GroupingConfig(
    /**
     * 每批要凑够多少条。
     *
     * 候选面板一屏能铺几十条，一批只有十几条就会「铺不满屏幕」。多字组各自有配额，
     * 凑满这一批的事交给**单字组** —— 它本来就不限条数，而且排在最后，正好补在尾巴上。
     */
    val batchSize: Int = 50,

    /** 2~4 字组每批每组给几条。 */
    val multiCharStep: Int = 6,

    /** 5 字及以上组每批每组给几条。 */
    val longStep: Int = 3,
) {
    init {
        require(batchSize > 0) { "batchSize must be positive" }
        require(multiCharStep > 0) { "multiCharStep must be positive" }
        require(longStep > 0) { "longStep must be positive" }
    }
}

object CandidateGrouping {
    /** 分组序号，同时也是每批内的输出顺序：4 字 → 5 字及以上 → 3 字 → 2 字 → 1 字。 */
    private const val GROUP_FOUR = 0
    private const val GROUP_LONG = 1
    private const val GROUP_THREE = 2
    private const val GROUP_TWO = 3
    private const val GROUP_SINGLE = 4
    private const val GROUP_COUNT = 5

    /** 文本的码点数。用码点而不是 `length`：emoji / 生僻字是代理对，`length` 会算成 2。 */
    fun charCount(text: String): Int = text.codePointCount(0, text.length)

    fun groupIndex(charCount: Int): Int = when {
        charCount == 4 -> GROUP_FOUR
        charCount > 4 -> GROUP_LONG
        charCount == 3 -> GROUP_THREE
        charCount == 2 -> GROUP_TWO
        else -> GROUP_SINGLE
    }

    /**
     * 把 [candidates]（引擎顺序的完整候选池）整理成**展示顺序**。
     *
     * [batch] 从 1 开始：第 k 批包含前 k 批的全部内容（已展示的候选不会消失、不会换位置），
     * 只是在末尾多一组「各组再往后取一批」。
     *
     * 因此本函数是**幂等累积**的：`apply(pool, k+1)` 的前缀恒等于 `apply(pool, k)`。
     *
     * 每批的构成：`[引擎首选] + 4 字×多字配额 + 5 字以上×配额 + 3 字×配额 + 2 字×配额 +
     * 单字补齐到 [GroupingConfig.batchSize]`。多字组凑不满的部分全部由单字承担，
     * 所以「字多的在字少的前面」在整批里依然是严格的。
     */
    fun apply(
        candidates: List<Candidate>,
        batch: Int = 1,
        config: GroupingConfig = GroupingConfig(),
    ): List<Candidate> {
        if (candidates.isEmpty()) return candidates
        // 引擎的首选固定在最前，并且不再参与分组（否则会在自己那一组里出现第二次）。
        val head = candidates[0]
        val buckets = group(candidates.subList(1, candidates.size))
        val batches = batch.coerceAtLeast(1)
        val out = ArrayList<Candidate>(config.batchSize * batches + 1)
        out.add(head)

        // 单字组的游标要跨批次累加：每批取多少取决于那一批多字组实际给出多少条，
        // 不是定值，所以不能用「第 k 段」算偏移。
        var singleCursor = 0
        for (current in 1..batches) {
            var taken = 0
            for (group in 0 until GROUP_COUNT) {
                if (group == GROUP_SINGLE) continue
                val bucket = buckets[group] ?: continue
                val range = batchSlice(bucket.size, limitOf(group, config), current) ?: continue
                for (i in range) {
                    out.add(bucket[i])
                    taken++
                }
            }

            val singles = buckets[GROUP_SINGLE] ?: continue
            val need = (config.batchSize - taken).coerceAtLeast(0)
            val end = minOf(singleCursor + need, singles.size)
            for (i in singleCursor until end) out.add(singles[i])
            singleCursor = end
        }
        return out
    }

    /** 当前批次凑满了没有（用来决定还要不要继续往深里挖候选池）。 */
    fun isBatchFilled(
        candidates: List<Candidate>,
        batch: Int = 1,
        config: GroupingConfig = GroupingConfig(),
    ): Boolean = apply(candidates, batch, config).size >= config.batchSize * batch.coerceAtLeast(1)

    /**
     * 第 [batch] + 1 批会多出候选吗？用来告诉前端「还有下一批」。
     *
     * 只看已经拿到的候选池，**不管引擎那边还没拉的部分** —— 调用方自己知道有没有。
     */
    fun hasMoreInBatch(
        candidates: List<Candidate>,
        batch: Int,
        config: GroupingConfig = GroupingConfig(),
    ): Boolean = apply(candidates, batch + 1, config).size > apply(candidates, batch, config).size

    /** 按字长分组，组内保持引擎顺序。 */
    private fun group(candidates: List<Candidate>): Array<MutableList<Candidate>?> {
        val buckets = arrayOfNulls<MutableList<Candidate>>(GROUP_COUNT)
        for (candidate in candidates) {
            val index = groupIndex(charCount(candidate.text))
            val bucket = buckets[index] ?: ArrayList<Candidate>().also { buckets[index] = it }
            bucket.add(candidate)
        }
        return buckets
    }

    private fun limitOf(group: Int, config: GroupingConfig): Int = when (group) {
        GROUP_LONG -> config.longStep
        else -> config.multiCharStep
    }

    /**
     * 第 [batch] 批要从「size 条同组候选」里取哪一段。
     *
     * 返回 null 表示这一组这一批没货（已经取完了）。
     */
    private fun batchSlice(size: Int, limit: Int, batch: Int): IntRange? {
        if (size <= 0 || batch < 1) return null
        val from = (batch - 1).toLong() * limit
        if (from >= size) return null
        val to = minOf(from + limit, size.toLong())
        return from.toInt() until to.toInt()
    }
}
