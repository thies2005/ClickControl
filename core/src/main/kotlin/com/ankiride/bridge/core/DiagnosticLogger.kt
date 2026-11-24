package com.ankiride.bridge.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class LogCategory {
    SYSTEM,
    PERMS,
    BLE,
    PACKET,
    ROUTER,
    ANKI,
    MYWHOOSH
}

enum class LogLevel {
    INFO,
    WARN,
    ERROR
}

data class LogEntry(
    val id: Long,
    val timestampMs: Long,
    val formattedTime: String,
    val category: LogCategory,
    val level: LogLevel,
    val message: String
)

object DiagnosticLogger {
    const val MAX_LOG_ENTRIES = 500
    const val DEFAULT_MAX_FILE_BYTES = 1_048_576L
    private var nextId = 1L
    private val lock = Any()
    private val timeFormatter = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private val fileTimeFormatter = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
    private var logFile: File? = null
    private var maxLogFileBytes: Long = DEFAULT_MAX_FILE_BYTES

    private val _logs = MutableStateFlow<List<LogEntry>>(emptyList())
    val logs: StateFlow<List<LogEntry>> = _logs.asStateFlow()

    private val _lastIssue = MutableStateFlow<LogEntry?>(null)
    val lastIssue: StateFlow<LogEntry?> = _lastIssue.asStateFlow()

    fun configureLogFile(file: File?, maxBytes: Long = DEFAULT_MAX_FILE_BYTES) {
        synchronized(lock) {
            maxLogFileBytes = maxBytes.coerceAtLeast(4096L)
            if (file == null) {
                logFile = null
                return
            }
            try {
                file.parentFile?.let { dir ->
                    if (!dir.exists()) dir.mkdirs()
                    runCatching {
                        if ("posix" in dir.toPath().fileSystem.supportedFileAttributeViews()) {
                            Files.setPosixFilePermissions(dir.toPath(), PosixFilePermissions.fromString("rwx------"))
                        }
                    }
                }
                if (!file.exists()) {
                    file.createNewFile()
                    runCatching {
                        if ("posix" in file.toPath().fileSystem.supportedFileAttributeViews()) {
                            Files.setPosixFilePermissions(file.toPath(), PosixFilePermissions.fromString("rw-------"))
                        }
                    }
                }
                logFile = file
            } catch (_: Exception) {
                logFile = null
            }
        }
    }

    fun logFilePath(): String? = synchronized(lock) { logFile?.absolutePath }

    fun dismissLastIssue() {
        _lastIssue.value = null
    }

    fun log(
        category: LogCategory,
        message: String,
        level: LogLevel = LogLevel.INFO,
        nowMs: Long = System.currentTimeMillis()
    ) {
        synchronized(lock) {
            val formatted = synchronized(timeFormatter) {
                timeFormatter.format(Date(nowMs))
            }
            val fileFormatted = synchronized(fileTimeFormatter) {
                fileTimeFormatter.format(Date(nowMs))
            }
            val entry = LogEntry(
                id = nextId++,
                timestampMs = nowMs,
                formattedTime = formatted,
                category = category,
                level = level,
                message = message
            )
            val current = _logs.value
            val updated = if (current.size >= MAX_LOG_ENTRIES) {
                current.drop(current.size - MAX_LOG_ENTRIES + 1) + entry
            } else {
                current + entry
            }
            _logs.value = updated
            if (level == LogLevel.WARN || level == LogLevel.ERROR) {
                _lastIssue.value = entry
            }
            appendToFileLocked("[$fileFormatted] [${level.name}] [${category.name}] $message")
        }
    }

    private fun appendToFileLocked(line: String) {
        val target = logFile ?: return
        try {
            if (target.exists() && target.length() >= maxLogFileBytes) {
                val backup = File(target.parentFile, "${target.name}.1")
                if (backup.exists()) backup.delete()
                target.renameTo(backup)
                target.createNewFile()
            }
            FileOutputStream(target, true).use { fos ->
                OutputStreamWriter(fos, StandardCharsets.UTF_8).use { writer ->
                    writer.write(line)
                    writer.write("\n")
                }
            }
        } catch (_: Exception) {
        }
    }

    fun readRecentFileTail(maxLines: Int = 200): String {
        val target = synchronized(lock) { logFile } ?: return ""
        return try {
            if (!target.exists()) return ""
            target.readLines(StandardCharsets.UTF_8).takeLast(maxLines).joinToString("\n")
        } catch (_: Exception) {
            ""
        }
    }

    fun info(category: LogCategory, message: String) = log(category, message, LogLevel.INFO)
    fun warn(category: LogCategory, message: String) = log(category, message, LogLevel.WARN)
    fun error(category: LogCategory, message: String) = log(category, message, LogLevel.ERROR)

    fun clear() {
        synchronized(lock) {
            _logs.value = emptyList()
            _lastIssue.value = null
            logFile?.let { file ->
                runCatching {
                    FileOutputStream(file, false).close()
                    val backup = File(file.parentFile, "${file.name}.1")
                    if (backup.exists()) backup.delete()
                }
            }
        }
    }

    fun formatForClipboard(filterCategory: LogCategory? = null): String {
        val snapshot = _logs.value.let { list ->
            if (filterCategory == null) list else list.filter { it.category == filterCategory }
        }
        val pathNote = logFilePath()?.let { "File: $it\n" } ?: ""
        if (snapshot.isEmpty()) return "${pathNote}[ClickControl Log Empty]"
        val sb = StringBuilder()
        sb.appendLine("=== ClickControl Diagnostic Log (${snapshot.size} entries) ===")
        if (pathNote.isNotEmpty()) sb.append(pathNote)
        for (item in snapshot) {
            sb.append('[')
                .append(item.formattedTime)
                .append("] [")
                .append(item.level.name)
                .append("] [")
                .append(item.category.name)
                .append("] ")
                .appendLine(item.message)
        }
        return sb.toString()
    }
}
