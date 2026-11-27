package com.fastup.util

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class LogLevel {
    DEBUG, INFO, WARN, ERROR
}

data class LogEntry(
    val id: Long,
    val timestamp: Long,
    val level: LogLevel,
    val tag: String,
    val message: String
) {
    val formattedTime: String
        get() = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()).format(Date(timestamp))
}

object AppLogger {
    private const val MAX_LOGS = 1000
    private var sequence = 0L

    private val _logs = MutableStateFlow<List<LogEntry>>(emptyList())
    val logs: StateFlow<List<LogEntry>> = _logs.asStateFlow()

    fun log(level: LogLevel, tag: String, message: String) {
        when (level) {
            LogLevel.DEBUG -> Log.d(tag, message)
            LogLevel.INFO -> Log.i(tag, message)
            LogLevel.WARN -> Log.w(tag, message)
            LogLevel.ERROR -> Log.e(tag, message)
        }

        val entry = LogEntry(
            id = ++sequence,
            timestamp = System.currentTimeMillis(),
            level = level,
            tag = tag,
            message = message
        )

        synchronized(this) {
            val current = _logs.value
            val updated = if (current.size >= MAX_LOGS) {
                current.drop(current.size - MAX_LOGS + 1) + entry
            } else {
                current + entry
            }
            _logs.value = updated
        }
    }

    fun d(tag: String, message: String) = log(LogLevel.DEBUG, tag, message)
    fun i(tag: String, message: String) = log(LogLevel.INFO, tag, message)
    fun w(tag: String, message: String) = log(LogLevel.WARN, tag, message)
    fun e(tag: String, message: String, throwable: Throwable? = null) {
        val fullMsg = if (throwable != null) "$message: ${throwable.message}\n${Log.getStackTraceString(throwable)}" else message
        log(LogLevel.ERROR, tag, fullMsg)
    }

    fun clear() {
        synchronized(this) {
            _logs.value = emptyList()
        }
    }
}
