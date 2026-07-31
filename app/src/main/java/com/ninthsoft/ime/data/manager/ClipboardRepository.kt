package com.ninthsoft.ime.data.manager

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import androidx.core.content.edit
import timber.log.Timber
import org.json.JSONArray
import org.json.JSONObject

object ClipboardRepository {

    var onNewEntry: ((Entry) -> Unit)? = null
    var onContentChanged: (() -> Unit)? = null

    private const val PREFS_NAME = "clipboard_settings"
    private const val KEY_ENTRIES = "entries"
    private const val KEY_CLOUD_SYNC = "cloud_sync"
    private const val KEY_MAX_ENTRIES = "max_entries"
    private const val KEY_RETENTION_DAYS = "retention_days"
    private const val KEY_SHOW_TIMESTAMP = "show_timestamp"

    private const val DEFAULT_MAX_ENTRIES = 100
    private const val DEFAULT_RETENTION_DAYS = 30

    // ── 设置读写 ──

    fun isCloudSyncEnabled(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_CLOUD_SYNC, false)
    }

    fun setCloudSyncEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit {
            putBoolean(KEY_CLOUD_SYNC, enabled)
        }
    }

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
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit {
            putInt(KEY_RETENTION_DAYS, days.coerceIn(1, 365))
        }
    }

    fun isShowTimestamp(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_SHOW_TIMESTAMP, true)
    }

    fun setShowTimestamp(context: Context, show: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit {
            putBoolean(KEY_SHOW_TIMESTAMP, show)
        }
    }

    // ── 历史记录 ──

    data class Entry(
        val text: String,
        val timestamp: Long = System.currentTimeMillis(),
        val cloud: Boolean = false,
    )

    fun getEntries(context: Context): List<Entry> {
        val json = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_ENTRIES, null) ?: return emptyList()
        return try {
            val arr = JSONArray(json)
            (0 until arr.length()).map { i ->
                val obj = arr.getJSONObject(i)
                Entry(
                    obj.getString("text"), obj.getLong("timestamp"), obj.optBoolean("cloud", false)
                )
            }.sortedByDescending { it.timestamp }
        } catch (e: Exception) {
            Timber.e(e, "Failed to load clipboard entries")
            emptyList()
        }
    }

    fun addEntry(context: Context, text: String) {
        if (text.isBlank()) return
        val entries = getEntries(context).toMutableList()
        val existing = entries.firstOrNull { it.text == text }
        entries.removeAll { it.text == text }
        entries.add(Entry(text))
        val max = getMaxEntries(context)
        if (entries.size > max) {
            entries.sortByDescending { it.timestamp }
            entries.subList(max, entries.size).clear()
        }
        saveEntries(context, entries)
        if (existing == null) onNewEntry?.invoke(Entry(text))
    }

    fun clearAll(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit {
            remove(KEY_ENTRIES)
        }
    }

    fun removeEntry(context: Context, text: String) {
        val entries = getEntries(context).toMutableList()
        entries.removeAll { it.text == text }
        saveEntries(context, entries)
    }

    private fun saveEntries(context: Context, entries: List<Entry>) {
        val arr = JSONArray()
        entries.forEach { entry ->
            val obj = JSONObject()
            obj.put("text", entry.text)
            obj.put("timestamp", entry.timestamp)
            if (entry.cloud) obj.put("cloud", true)
            arr.put(obj)
        }
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit {
            putString(KEY_ENTRIES, arr.toString())
        }
    }

    // ── 系统剪切板监听 ──

    fun checkCurrentClipboard(context: Context) {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = cm.primaryClip ?: return
        if (clip.itemCount == 0) return
        val text = clip.getItemAt(0).coerceToText(context).toString()
        if (text.isBlank()) return
        lastText = text

        val clipTimestamp = if (Build.VERSION.SDK_INT >= 33) {
            clip.description?.timestamp?.takeIf { it > 0 } ?: -1L
        } else -1L
        if (clipTimestamp > 0) lastClipTimestamp = clipTimestamp

        val entries = getEntries(context)
        if (entries.none { it.text == text }) {
            lastCopyText = text
            lastCopyTimestamp = System.currentTimeMillis()
            addEntry(context, text)
        }
    }

    fun startMonitoring(context: Context) {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        try {
            cm.addPrimaryClipChangedListener(clipListener)
        } catch (e: Exception) {
            Timber.e(e, "Failed to register clipboard listener")
        }
    }

    fun stopMonitoring(context: Context) {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        try {
            cm.removePrimaryClipChangedListener(clipListener)
        } catch (_: Exception) {
        }
    }

    private val clipListener = ClipboardManager.OnPrimaryClipChangedListener {
        onClipChanged()
    }

    @Volatile
    private var lastText: String = ""

    @Volatile
    private var lastClipTimestamp: Long = -1L

    @Volatile
    var lastCopyText: String? = null
        private set

    @Volatile
    var lastCopyTimestamp: Long = 0L
        private set

    private fun onClipChanged() {
        val appContext = com.ninthsoft.ime.base.util.appContext
        val cm = appContext.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = cm.primaryClip ?: return
        if (clip.itemCount == 0) return

        val clipTimestamp = if (Build.VERSION.SDK_INT >= 33) {
            clip.description?.timestamp?.takeIf { it > 0 } ?: -1L
        } else -1L
        if (clipTimestamp > 0) {
            if (clipTimestamp == lastClipTimestamp) return
            lastClipTimestamp = clipTimestamp
        }

        val text = clip.getItemAt(0).coerceToText(appContext).toString()
        if (text.isBlank()) return
        if (clipTimestamp <= 0 && text == lastText) return
        lastText = text

        lastCopyText = text
        lastCopyTimestamp = System.currentTimeMillis()

        addEntry(appContext, text)
        onContentChanged?.invoke()
    }
}
