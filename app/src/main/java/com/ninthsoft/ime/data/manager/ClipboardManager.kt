package com.ninthsoft.ime.data.manager

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import androidx.core.content.edit
import com.ninthsoft.ime.data.database.AppDatabase
import com.ninthsoft.ime.data.database.ClipboardDao
import com.ninthsoft.ime.data.database.ClipboardRecord
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import com.ninthsoft.ime.base.util.appScope
import timber.log.Timber

object ClipboardManager {

    var onNewEntry: ((Entry) -> Unit)? = null
    var onContentChanged: (() -> Unit)? = null

    private const val PREFS_NAME = "clipboard_settings"
    private const val KEY_MAX_ENTRIES = "max_entries"
    private const val KEY_RETENTION_DAYS = "retention_days"
    private const val KEY_POLL_INTERVAL_SECONDS = "poll_interval_seconds"

    private const val DEFAULT_MAX_ENTRIES = 100
    private const val DEFAULT_RETENTION_DAYS = 30
    private const val DEFAULT_POLL_INTERVAL_SECONDS = 5

    // ── 设置读写 ──

    fun getMaxEntries(context: Context): Int {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getInt(KEY_MAX_ENTRIES, DEFAULT_MAX_ENTRIES)
    }

    fun setMaxEntries(context: Context, max: Int) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit {
            putInt(KEY_MAX_ENTRIES, max.coerceIn(20, 500))
        }
    }

    fun getRetentionDays(context: Context): Int {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getInt(KEY_RETENTION_DAYS, DEFAULT_RETENTION_DAYS)
    }

    fun setRetentionDays(context: Context, days: Int) {
        val value = days.coerceIn(1, 365)
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit {
            putInt(KEY_RETENTION_DAYS, value)
        }
    }

    fun getPollIntervalSeconds(context: Context): Int =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getInt(KEY_POLL_INTERVAL_SECONDS, DEFAULT_POLL_INTERVAL_SECONDS)
            .coerceIn(1, 60)

    fun setPollIntervalSeconds(context: Context, seconds: Int) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit {
            putInt(KEY_POLL_INTERVAL_SECONDS, seconds.coerceIn(1, 60))
        }
    }

    // ── 历史记录 ──

    data class Entry(
        val text: String,
        val timestamp: Long = System.currentTimeMillis(),
        val cloud: Boolean = false,
    )

    fun getEntries(context: Context): List<Entry> = db(context) { db ->
        val cutoff = System.currentTimeMillis() - getRetentionDays(context) * 86400000L
        db.clipboardDao().getAllActiveSince(cutoff).map { Entry(it.text, it.timestamp, it.cloud) }
    }

    fun addEntry(context: Context, text: String, notify: Boolean = true) {
        if (text.isBlank()) return
        val existing = getEntries(context).firstOrNull { it.text == text }
        val now = System.currentTimeMillis()
        db(context) { db ->
            val dao = db.clipboardDao()
            dao.deleteByText(text)
            dao.insert(ClipboardRecord(text = text, timestamp = now, cloud = false))
            trimExcess(dao, getMaxEntries(context))
            dao.deleteOlderThan(now - getRetentionDays(context) * 86400000L)
        }
        if (notify && existing == null) onNewEntry?.invoke(Entry(text, now))
    }

    private suspend fun trimExcess(dao: ClipboardDao, limit: Int) {
        val excess = dao.count() - limit
        if (excess > 0) dao.deleteOldest(excess)
    }

    fun clearAll(context: Context) {
        db(context) { db -> db.clipboardDao().softDeleteAll() }
        lastCopyText = null
        lastCopyTimestamp = 0L
    }

    fun removeEntry(context: Context, text: String) {
        db(context) { db -> db.clipboardDao().softDeleteByText(text) }
    }

    // ── 系统剪切板监听 ──

    private fun clipText(clip: ClipData, context: Context): String? {
        val item = clip.getItemAt(0) ?: return null
        val text = item.text?.toString() ?: return null
        if (text.isBlank()) return null
        if (text.any { it.code in 0..31 && it != '\t' && it != '\n' && it != '\r' }) return null
        return text
    }

    private fun clipTimestamp(clip: ClipData): Long =
        if (Build.VERSION.SDK_INT >= 33) clip.description?.timestamp?.takeIf { it > 0 } ?: -1L
        else -1L

    fun checkCurrentClipboard(context: Context) {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = cm.primaryClip ?: return
        if (clip.itemCount == 0) return
        val text = clipText(clip, context) ?: return

        val ts = clipTimestamp(clip)
        if (ts > 0) lastClipTimestamp = ts
        lastText = text

        val latest = db(context) { db -> db.clipboardDao().getLatest() }
        if (latest?.text == text) return

        if (getEntries(context).none { it.text == text }) {
            lastCopyText = text
            lastCopyTimestamp = System.currentTimeMillis()
            addEntry(context, text)
            onContentChanged?.invoke()
        }
    }

    fun startMonitoring(context: Context) {
        monitoringJob?.cancel()
        monitoringJob = appScope.launch {
            while (isActive) {
                delay(getPollIntervalSeconds(context) * 1000L)
                if (isActive) checkCurrentClipboard(context)
            }
        }
    }

    fun stopMonitoring(context: Context) {
        monitoringJob?.cancel()
        monitoringJob = null
    }

    private var monitoringJob: Job? = null

    @Volatile private var lastText: String = ""
    @Volatile private var lastClipTimestamp: Long = -1L

    @Volatile var lastCopyText: String? = null
        private set

    @Volatile var lastCopyTimestamp: Long = 0L
        private set

    private fun <T> db(context: Context, block: suspend (com.ninthsoft.ime.data.database.AppDatabase) -> T): T =
        try {
            runBlocking(Dispatchers.IO) {
                val db = AppDatabase.getInstance(context)
                block(db)
            }
        } catch (e: Exception) {
            Timber.e(e, "clipboard database operation failed")
            null as T
        }
}
