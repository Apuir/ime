package com.ninthsoft.ime.data.database

import androidx.room.Dao
import androidx.room.Query

@Dao
interface CandidatePreferDao {

    @Query("SELECT * FROM candidate_prefers WHERE text = :text LIMIT 1")
    suspend fun get(text: String): CandidatePrefer?

    @Query("SELECT * FROM candidate_prefers WHERE text IN (:texts)")
    suspend fun getAllByTextIn(texts: List<String>): List<CandidatePrefer>

    /**
     * 正向信号：候选被选中上屏。
     *
     * [now] 落在 `updated_at`（最近一次**正向**时间），供时间衰减使用。
     */
    @Query(
        """
        INSERT INTO candidate_prefers (text, context, click_count, bad_count, created_at, updated_at, last_bad_at)
        VALUES (:text, :context, 1, 0, :now, :now, 0)
        ON CONFLICT (text) DO UPDATE SET
            click_count = click_count + 1,
            context = :context,
            updated_at = :now
        """
    )
    suspend fun upsert(text: String, context: String, now: Long = System.currentTimeMillis())

    /**
     * 负向信号：用户选过之后又删掉（误选），或长按把候选删掉。
     *
     * 只动 `bad_count` 与 `last_bad_at`，**不碰** `click_count` / `updated_at` ——
     * 那样会把「这个词曾经被正常用过」这段历史抹掉。
     */
    @Query(
        """
        INSERT INTO candidate_prefers (text, context, click_count, bad_count, created_at, updated_at, last_bad_at)
        VALUES (:text, '', 0, 1, :now, :now, :now)
        ON CONFLICT (text) DO UPDATE SET
            bad_count = bad_count + 1,
            last_bad_at = :now
        """
    )
    suspend fun demote(text: String, now: Long = System.currentTimeMillis())

    /** 清空学习数据（设置页「重置学习数据」）。 */
    @Query("DELETE FROM candidate_prefers")
    suspend fun clearAll()

    @Query("SELECT COUNT(*) FROM candidate_prefers")
    suspend fun count(): Int
}
