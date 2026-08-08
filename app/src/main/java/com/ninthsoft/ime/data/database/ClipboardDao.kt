package com.ninthsoft.ime.data.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

@Dao
interface ClipboardDao {

    @Insert
    suspend fun insert(record: ClipboardRecord): Long

    @Query("SELECT * FROM clipboard_records WHERE deleted = 0 ORDER BY timestamp DESC")
    suspend fun getAllActive(): List<ClipboardRecord>

    @Query("SELECT * FROM clipboard_records ORDER BY timestamp DESC LIMIT 1")
    suspend fun getLatest(): ClipboardRecord?

    @Query("SELECT COUNT(*) FROM clipboard_records")
    suspend fun count(): Int

    @Query("UPDATE clipboard_records SET deleted = 1 WHERE text = :text AND deleted = 0")
    suspend fun softDeleteByText(text: String)

    @Query("UPDATE clipboard_records SET deleted = 1 WHERE deleted = 0")
    suspend fun softDeleteAll()

    @Query("DELETE FROM clipboard_records WHERE text = :text")
    suspend fun deleteByText(text: String)

    @Query("DELETE FROM clipboard_records WHERE id IN (SELECT id FROM clipboard_records ORDER BY timestamp ASC LIMIT :n)")
    suspend fun deleteOldest(n: Int)

    @Query("DELETE FROM clipboard_records WHERE timestamp < :cutoff")
    suspend fun deleteOlderThan(cutoff: Long)
}