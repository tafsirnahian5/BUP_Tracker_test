package com.abhiank.offline

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.graphics.Color
import android.net.Uri
import androidx.appcompat.widget.SwitchCompat
import org.maplibre.android.gestures.StandardScaleGestureDetector
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.Style
import java.io.File

class MbtilesStyleLoader(
    private val context: Context,
    private val map: MapLibreMap,
    private val zoomSwitch: SwitchCompat,
    private val debugSwitch: SwitchCompat
) {

    var bounds: LatLngBounds = DEFAULT_BOUNDS
        private set

    var minZoomLevel: Double = 0.0
        private set

    fun loadFromAssets(assetName: String) {
        val mbtilesFile = getFileFromAssets(context, assetName)
        loadFromFile(mbtilesFile)
    }

    fun loadFromFile(mbtilesFile: File) {
        val styleJsonInputStream = context.assets.open("bright.json")
        val styleFile = File(context.filesDir.absolutePath, "bright.json")
        copyStreamToFile(styleJsonInputStream, styleFile)

        bounds = resolveBounds(mbtilesFile)
        minZoomLevel = resolveMinZoom(mbtilesFile).toDouble()

        val newFileStr = styleFile.inputStream().readToString()
            .replace("___FILE_URI___", "mbtiles:///${mbtilesFile.absolutePath}")

        styleFile.writeText(newFileStr)

        debugSwitch.isEnabled = false
        zoomSwitch.isEnabled = false

        map.setStyle(Style.Builder().fromUri(Uri.fromFile(styleFile).toString())) { style ->
            debugSwitch.isEnabled = true
            map.isDebugActive = debugSwitch.isChecked
            if (debugSwitch.isChecked) {
                showBoundsArea(style, bounds, Color.RED, "source-id-1", "layer-id-1", 0.25f)
            }
        }

        map.animateCamera(
            CameraUpdateFactory.newLatLngZoom(DEFAULT_CENTER, 15.0),
            object : MapLibreMap.CancelableCallback {
                override fun onCancel() {}

                override fun onFinish() {
                    if (minZoomLevel == 0.0) {
                        minZoomLevel = map.cameraPosition.zoom
                    }

                    zoomSwitch.isEnabled = true
                    if (zoomSwitch.isChecked) {
                        map.setMinZoomPreference(minZoomLevel)
                        map.limitViewToBounds(bounds)
                    }

                    map.addOnScaleListener(object : MapLibreMap.OnScaleListener {
                        override fun onScaleBegin(detector: StandardScaleGestureDetector) {}

                        override fun onScale(detector: StandardScaleGestureDetector) {
                            if (zoomSwitch.isChecked) {
                                map.limitViewToBounds(bounds)
                            }
                        }

                        override fun onScaleEnd(detector: StandardScaleGestureDetector) {}
                    })

                    map.addOnCameraIdleListener {
                        if (zoomSwitch.isChecked) {
                            map.limitViewToBounds(bounds)
                        }
                    }
                }
            }
        )
    }

    private fun resolveBounds(file: File): LatLngBounds {
        return try {
            SQLiteDatabase.openDatabase(file.absolutePath, null, SQLiteDatabase.OPEN_READONLY).use { db ->
                db.query(
                    "metadata",
                    arrayOf("name", "value"),
                    "name=?",
                    arrayOf("bounds"),
                    null,
                    null,
                    null
                ).use { cursor ->
                    if (cursor.moveToFirst()) {
                        val boundsStr = cursor.getString(1).split(",")
                        if (boundsStr.size == 4) {
                            return LatLngBounds.Builder()
                                .include(LatLng(boundsStr[1].toDouble(), boundsStr[0].toDouble()))
                                .include(LatLng(boundsStr[3].toDouble(), boundsStr[2].toDouble()))
                                .build()
                        }
                    }
                    DEFAULT_BOUNDS
                }
            }
        } catch (e: Exception) {
            DEFAULT_BOUNDS
        }
    }

    private fun resolveMinZoom(file: File): Int {
        return try {
            SQLiteDatabase.openDatabase(file.absolutePath, null, SQLiteDatabase.OPEN_READONLY).use { db ->
                db.query(
                    "metadata",
                    arrayOf("name", "value"),
                    "name=?",
                    arrayOf("minzoom"),
                    null,
                    null,
                    null
                ).use { cursor ->
                    if (cursor.moveToFirst()) {
                        cursor.getString(1).toIntOrNull()?.let { return it }
                    }
                    0
                }
            }
        } catch (e: Exception) {
            0
        }
    }
}
