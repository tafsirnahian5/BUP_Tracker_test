package com.abhiank.offline

import android.content.Context
import android.location.Location
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

class GpxRecorder(private val context: Context) {

    companion object {
        fun tracksDirectory(context: Context): File =
            File(context.filesDir, "tracks")
    }

    private val trackPoints = mutableListOf<TrackPoint>()
    private val timeFormatter = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }

    fun startNewTrack() {
        trackPoints.clear()
    }

    fun addPoint(location: Location) {
        trackPoints.add(
            TrackPoint(
                latitude = location.latitude,
                longitude = location.longitude,
                altitude = if (location.hasAltitude()) location.altitude else null,
                timestamp = location.time
            )
        )
    }

    fun hasRecordedTrack(): Boolean = trackPoints.size > 1

    fun clear() {
        trackPoints.clear()
    }

    @Throws(IOException::class)
    fun saveToFile(fileName: String? = null): File? {
        if (trackPoints.isEmpty()) {
            return null
        }
        val directory = tracksDirectory(context)
        if (!directory.exists() && !directory.mkdirs()) {
            throw IOException("Unable to create directory: ${'$'}directory")
        }
        val safeFileName = fileName ?: buildDefaultFileName()
        val outputFile = File(directory, safeFileName)
        outputFile.writeText(buildGpxDocument())
        return outputFile
    }

    private fun buildDefaultFileName(): String {
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        return "bup_track_${'$'}timestamp.gpx"
    }

    private fun buildGpxDocument(): String {
        val name = "BUP Live Track"
        val builder = StringBuilder()
        builder.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        builder.append("<gpx version=\"1.1\" creator=\"BUP Tracker\" xmlns=\"http://www.topografix.com/GPX/1/1\">\n")
        builder.append("  <trk>\n")
        builder.append("    <name>").append(name).append("</name>\n")
        builder.append("    <trkseg>\n")
        trackPoints.forEach { point ->
            builder.append("      <trkpt lat=\"")
                .append(point.latitude)
                .append("\" lon=\"")
                .append(point.longitude)
                .append("\">")
                .append('\n')
            point.altitude?.let {
                builder.append("        <ele>").append(it).append("</ele>\n")
            }
            builder.append("        <time>")
                .append(timeFormatter.format(Date(point.timestamp)))
                .append("</time>\n")
            builder.append("      </trkpt>\n")
        }
        builder.append("    </trkseg>\n")
        builder.append("  </trk>\n")
        builder.append("</gpx>\n")
        return builder.toString()
    }

    private data class TrackPoint(
        val latitude: Double,
        val longitude: Double,
        val altitude: Double?,
        val timestamp: Long
    )
}
