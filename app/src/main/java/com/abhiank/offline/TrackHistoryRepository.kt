package com.abhiank.offline

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class RecordedTrack(
    val file: File,
    val displayName: String,
    val lastModified: Date,
    val sizeBytes: Long
)

object TrackHistoryRepository {

    fun loadTracks(context: Context): List<RecordedTrack> {
        val directory = GpxRecorder.tracksDirectory(context)
        if (!directory.exists()) {
            return emptyList()
        }
        val files = directory.listFiles { file -> file.extension.equals("gpx", ignoreCase = true) }
            ?.sortedByDescending(File::lastModified)
            ?: return emptyList()
        return files.map { file ->
            RecordedTrack(
                file = file,
                displayName = buildDisplayName(file),
                lastModified = Date(file.lastModified()),
                sizeBytes = file.length()
            )
        }
    }

    private fun buildDisplayName(file: File): String {
        val rawName = file.nameWithoutExtension
        if (rawName.isBlank()) {
            return file.name
        }
        val words = rawName.split('_')
            .filter { it.isNotBlank() }
            .joinToString(" ") { word ->
                word.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() }
            }
        return words.ifEmpty { file.name }
    }
}

fun Date.asDisplayString(): String {
    val formatter = SimpleDateFormat("MMM d, yyyy h:mm a", Locale.getDefault())
    return formatter.format(this)
}

fun Long.asReadableSize(): String {
    if (this <= 0) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB")
    var value = toDouble()
    var unitIndex = 0
    while (value >= 1024 && unitIndex < units.lastIndex) {
        value /= 1024
        unitIndex++
    }
    return String.format(Locale.getDefault(), "%.1f %s", value, units[unitIndex])
}
