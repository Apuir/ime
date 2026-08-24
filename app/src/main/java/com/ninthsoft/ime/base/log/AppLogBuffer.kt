package com.ninthsoft.ime.base.log

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import timber.log.Timber
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.system.exitProcess

data class AppLogEntry(
    val time: String,
    val priority: Int,
    val tag: String,
    val message: String,
)

object AppLogBuffer {
    private const val MAX_ENTRIES = 1000
    private const val CRASH_LOG_NAME = "last-crash.log"
    private val lock = Any()
    private val formatter = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private val _entries = MutableStateFlow<List<AppLogEntry>>(emptyList())
    val entries: StateFlow<List<AppLogEntry>> = _entries.asStateFlow()
    private var crashFile: File? = null
    private var installed = false

    fun install(context: android.content.Context) {
        synchronized(lock) {
            if (installed) return
            installed = true
            crashFile = File(context.applicationContext.filesDir, CRASH_LOG_NAME)
            loadLastCrash()
            Timber.plant(BufferTree())
            val previous = Thread.getDefaultUncaughtExceptionHandler()
            Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
                recordCrash(thread, throwable)
                if (previous != null) {
                    previous.uncaughtException(thread, throwable)
                } else {
                    android.os.Process.killProcess(android.os.Process.myPid())
                    exitProcess(10)
                }
            }
        }
    }

    fun clear() {
        synchronized(lock) {
            _entries.value = emptyList()
            crashFile?.delete()
        }
    }

    private fun loadLastCrash() {
        val file = crashFile ?: return
        if (!file.isFile) return
        val message = runCatching { file.readText() }.getOrNull() ?: return
        if (message.isBlank()) return
        _entries.value = listOf(
            AppLogEntry(
                time = "--:--:--.---",
                priority = Log.ERROR,
                tag = "CRASH",
                message = message,
            )
        )
    }

    private fun recordCrash(thread: Thread, throwable: Throwable) {
        val message = "Uncaught exception in ${thread.name}\n${Log.getStackTraceString(throwable)}"
        runCatching { crashFile?.writeText(message) }
        synchronized(lock) {
            _entries.value = (_entries.value + AppLogEntry(
                time = synchronized(formatter) { formatter.format(Date()) },
                priority = Log.ERROR,
                tag = "CRASH",
                message = message,
            )).takeLast(MAX_ENTRIES)
        }
    }

    private class BufferTree : Timber.Tree() {
        override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
            val fullMessage = if (t == null) message else "$message\n${Log.getStackTraceString(t)}"
            val entry = AppLogEntry(
                time = synchronized(formatter) { formatter.format(Date()) },
                priority = priority,
                tag = tag ?: "APP",
                message = fullMessage,
            )
            synchronized(lock) {
                _entries.value = buildList {
                    addAll(_entries.value.takeLast(MAX_ENTRIES - 1))
                    add(entry)
                }
            }
        }
    }
}
