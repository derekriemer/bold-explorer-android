package com.boldexplorer.audio

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Persistent, session-scoped log of every audio beacon and announcement.
 *
 * Entries are held newest-first in memory ([entries]) and appended oldest-first
 * to [logFile] in internal storage. [newSession] wipes both. [exportToDownloads]
 * writes the full file to the user's Downloads folder.
 *
 * All file I/O runs on [Dispatchers.IO] via [scope]; the in-memory [StateFlow]
 * is updated immediately on the calling coroutine before the disk write starts.
 */
@Singleton
class AudioEventLog
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) {
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private val fileMutex = Mutex()
        private val logFile: File get() = File(context.filesDir, "audio_log.txt")

        private val _entries = MutableStateFlow<List<AudioLogEntry>>(emptyList())
        val entries: StateFlow<List<AudioLogEntry>> = _entries.asStateFlow()

        init {
            scope.launch {
                fileMutex.withLock {
                    if (!logFile.exists()) return@withLock
                    val lines = logFile.readLines()
                    val parsed = lines.mapNotNull { parseLine(it) }.reversed() // newest-first
                    _entries.value = parsed
                }
            }
        }

        fun append(entry: AudioLogEntry) {
            _entries.value = listOf(entry) + _entries.value
            scope.launch {
                fileMutex.withLock {
                    logFile.appendText(formatLine(entry) + "\n")
                }
            }
        }

        fun newSession() {
            _entries.value = emptyList()
            scope.launch {
                fileMutex.withLock {
                    logFile.writeText("")
                }
            }
        }

        suspend fun exportToDownloads(): Result<String> =
            runCatching {
                val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
                val filename = "bold_explorer_audio_log_$timestamp.txt"
                val header =
                    buildString {
                        appendLine("Bold Explorer Audio Event Log")
                        appendLine("Exported: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())}")
                        appendLine("Columns:  timestampMs | kind | trigger | inputs | outputs | played | note")
                        appendLine("---")
                    }
                val body = fileMutex.withLock { if (logFile.exists()) logFile.readText() else "" }
                val contents = header + body

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val values =
                        ContentValues().apply {
                            put(MediaStore.Downloads.DISPLAY_NAME, filename)
                            put(MediaStore.Downloads.MIME_TYPE, "text/plain")
                            put(MediaStore.Downloads.IS_PENDING, 1)
                        }
                    val resolver = context.contentResolver
                    val uri =
                        resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                            ?: error("Could not create file in Downloads")
                    resolver.openOutputStream(uri)!!.use { it.write(contents.toByteArray()) }
                    values.clear()
                    values.put(MediaStore.Downloads.IS_PENDING, 0)
                    resolver.update(uri, values, null, null)
                } else {
                    @Suppress("DEPRECATION")
                    val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                    dir.mkdirs()
                    File(dir, filename).writeText(contents)
                }

                "Exported ${_entries.value.size} entries → Downloads/$filename"
            }

        // ── Serialization ─────────────────────────────────────────────────────────

        private fun formatLine(e: AudioLogEntry): String {
            fun String.esc() = replace("\n", "\\n").replace("\t", " ")
            return "${e.timestampMs}\t${e.kind.name}\t${e.trigger.esc()}\t" +
                "${e.inputs.esc()}\t${e.outputs.esc()}\t${e.played.esc()}\t${e.note.esc()}"
        }

        private fun parseLine(line: String): AudioLogEntry? {
            if (line.isBlank()) return null
            val cols = line.split("\t")
            if (cols.size < 7) return null
            return runCatching {
                AudioLogEntry(
                    timestampMs = cols[0].toLong(),
                    kind = AudioLogEntry.Kind.valueOf(cols[1]),
                    trigger = cols[2].replace("\\n", "\n"),
                    inputs = cols[3].replace("\\n", "\n"),
                    outputs = cols[4].replace("\\n", "\n"),
                    played = cols[5].replace("\\n", "\n"),
                    note = cols[6].replace("\\n", "\n"),
                )
            }.getOrNull()
        }
    }
