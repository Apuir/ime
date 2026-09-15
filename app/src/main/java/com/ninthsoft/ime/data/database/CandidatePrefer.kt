package com.ninthsoft.ime.data.database

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 用户对某个候选词的**正负反馈**记录。
 *
 * - 正向：候选被选中上屏时 [count] +1、[updatedAt] 更新（见 [CandidatePreferDao.upsert]）；
 * - 负向：上屏后又被删掉、或被长按删除时 [badCount] +1、[lastBadAt] 更新
 *   （见 [CandidatePreferDao.demote]）。
 *
 * 两边的净分由 `base/priority/PreferenceScorer` 按时间衰减后算出，
 * 所以这里只存原始计数与时间戳，不在数据库里做衰减。
 */
@Entity(tableName = "candidate_prefers")
data class CandidatePrefer(
    @PrimaryKey
    val text: String,

    @ColumnInfo(name = "click_count")
    val count: Int = 1,

    /** 被判定为误选的累计次数。 */
    @ColumnInfo(name = "bad_count", defaultValue = "0")
    val badCount: Int = 0,

    val context: String,

    @ColumnInfo(name = "created_at")
    val createdAt: Long = System.currentTimeMillis(),

    /** 最近一次**正向**信号的时间。 */
    @ColumnInfo(name = "updated_at")
    val updatedAt: Long = System.currentTimeMillis(),

    /** 最近一次**负向**信号的时间，0 表示从未误选。 */
    @ColumnInfo(name = "last_bad_at", defaultValue = "0")
    val lastBadAt: Long = 0L,
)
