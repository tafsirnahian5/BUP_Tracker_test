package com.abhiank.offline

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.database.sqlite.SQLiteDatabase
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.Switch
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.mapbox.android.gestures.StandardScaleGestureDetector
import com.mapbox.geojson.Point
import com.mapbox.geojson.Polygon
import com.mapbox.mapboxsdk.Mapbox
import com.mapbox.mapboxsdk.camera.CameraUpdateFactory
import com.mapbox.mapboxsdk.geometry.LatLng
import com.mapbox.mapboxsdk.geometry.LatLngBounds
import com.mapbox.mapboxsdk.maps.MapView
import com.mapbox.mapboxsdk.maps.MapboxMap
import com.mapbox.mapboxsdk.maps.Style
import com.mapbox.mapboxsdk.style.layers.FillLayer
import com.mapbox.mapboxsdk.style.layers.LineLayer
import com.mapbox.mapboxsdk.style.layers.PropertyFactory
import com.mapbox.mapboxsdk.style.layers.PropertyFactory.*
import com.mapbox.mapboxsdk.style.sources.GeoJsonSource
import com.mapbox.mapboxsdk.style.sources.VectorSource
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import androidx.core.graphics.drawable.toBitmap
import java.io.*

class MainActivity : AppCompatActivity() {

    companion object {
        const val MBTILES_NAME = "dhaka2.mbtiles"
        private const val GPX_SOURCE_ID = "gpx-source"
        private const val GPX_LAYER_ID = "gpx-layer"
    }

    private val mapView: MapView by lazy { findViewById(R.id.mapView) }

    private lateinit var map: MapboxMap
    private lateinit var bounds: LatLngBounds
    private var minZoomLevel: Double = 0.0

    private lateinit var zoomSwitch: SwitchCompat

    // --------------------------------------------------------------
    // GPX-related fields
    // --------------------------------------------------------------
    private val gpxFilePicker = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.data?.let { uri -> loadGpxFile(uri) }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Mapbox.getInstance(this)
        setContentView(R.layout.activity_main)

        // --------------------------------------------------------------
        // Existing language-switch helper (kept unchanged)
        // --------------------------------------------------------------
        fun changeLanguage(lang: String) {
            map.getStyle { style ->
                style.getLayer("waterway-name")?.setProperties(textField(lang))
                style.getLayer("water-name-lakeline")?.setProperties(textField(lang))
                style.getLayer("water-name")?.setProperties(textField(lang))
                style.getLayer("poi-level-3")?.setProperties(textField(lang))
                style.getLayer("poi-level-2")?.setProperties(textField(lang))
                style.getLayer("poi-level-1")?.setProperties(textField(lang))

                style.getLayer("highway-name-path")?.setProperties(textField(lang))
                style.getLayer("highway-name-minor")?.setProperties(textField(lang))
                style.getLayer("highway-name-major")?.setProperties(textField(lang))

                style.getLayer("place-other")?.setProperties(textField(lang))
                style.getLayer("place-village")?.setProperties(textField(lang))
                style.getLayer("place-town")?.setProperties(textField(lang))
                style.getLayer("place-city")?.setProperties(textField(lang))
                style.getLayer("place-city-capital")?.setProperties(textField(lang))

                style.getLayer("place-country-3")?.setProperties(textField(lang))
                style.getLayer("place-country-2")?.setProperties(textField(lang))
                style.getLayer("place-country-1")?.setProperties(textField(lang))

                style.getLayer("place-continent")?.setProperties(textField(lang))
            }
        }

        // --------------------------------------------------------------
        // Switches
        // --------------------------------------------------------------
        zoomSwitch = findViewById(R.id.lockZoomSwitch)
        zoomSwitch.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) map.setMinZoomPreference(minZoomLevel) else map.setMinZoomPreference(0.0)
        }

        findViewById<SwitchCompat>(R.id.debugModeSwitch).setOnCheckedChangeListener { _, isChecked ->
            map.isDebugActive = isChecked
            if (isChecked) {
                showBoundsArea(map.style!!, bounds, Color.RED, "source-id-1", "layer-id-1", 0.25f)
            } else {
                map.style?.let {
                    it.removeLayer("layer-id-1")
                    it.removeSource("source-id-1")
                }
            }
        }

        // --------------------------------------------------------------
        // Map initialisation
        // --------------------------------------------------------------
        mapView.onCreate(savedInstanceState)
        mapView.getMapAsync { mbMap ->
            map = mbMap

            map.addOnCameraMoveListener {
                Log.d("zoom", map.cameraPosition.zoom.toString())
            }

            // Load default MBTiles from assets
            showMbTilesMap(getFileFromAssets(this, MBTILES_NAME))

            changeLanguage("{name_en}")
        }

        // --------------------------------------------------------------
        // MBTiles picker (existing)
        // --------------------------------------------------------------
        findViewById<Button>(R.id.pickFileButton).setOnClickListener {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "*/*"
                putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/gpx+xml", "text/xml"))
            }
            filePickerReturnResult.launch(intent)
        }

        // --------------------------------------------------------------
        // NEW: GPX picker button
        // --------------------------------------------------------------
        findViewById<Button>(R.id.loadGpxButton).setOnClickListener {
            openGpxFilePicker()
        }
    }

    // -------------------------------------------------------------------------
    // Existing MBTiles picker result
    // -------------------------------------------------------------------------
    private val filePickerReturnResult =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK && result.data != null) {
                val uri = result.data!!.data!!
                // Mapbox needs a real file path, not a content-uri, so copy to cache
                val file = uriToCacheFile(uri, "picked.mbtiles")
                showMbTilesMap(file)
            }
        }

    // -------------------------------------------------------------------------
    // GPX file picker
    // -------------------------------------------------------------------------
    private fun openGpxFilePicker() {
        val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
            type = "*/*"
            addCategory(Intent.CATEGORY_OPENABLE)
        }
        gpxFilePicker.launch(intent)
    }

    // -------------------------------------------------------------------------
    // Load & draw GPX
    // -------------------------------------------------------------------------
    private fun loadGpxFile(uri: Uri) {
        try {
            contentResolver.openInputStream(uri)?.use { inputStream ->
                val points = parseGpxManually(inputStream)
                if (points.isNotEmpty()) {
                    drawGpxRoute(points)
                } else {
                    toast("No track points found in GPX")
                }
            }
        } catch (e: Exception) {
            Log.e("GPX", "Error loading GPX: ${e.message}", e)
            toast("Error loading GPX file")
        }
    }

    /** Very small GPX parser – only <trkpt lat="…" lon="…"/> */
    private fun parseGpxManually(inputStream: InputStream): List<LatLng> {
        val points = mutableListOf<LatLng>()
        val parser = XmlPullParserFactory.newInstance().newPullParser().apply {
            setInput(inputStream, null)
        }
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG && parser.name == "trkpt") {
                val lat = parser.getAttributeValue(null, "lat")?.toDoubleOrNull()
                val lon = parser.getAttributeValue(null, "lon")?.toDoubleOrNull()
                if (lat != null && lon != null) {
                    points.add(LatLng(lat, lon))
                }
            }
            event = parser.next()
        }
        return points
    }

    /** Draw (or replace) the red GPX polyline with start & end markers */
    private fun drawGpxRoute(points: List<LatLng>) {
        map.getStyle { style ->
            // Remove previous GPX layers/sources if they exist
            style.removeLayer(GPX_LAYER_ID)
            style.removeSource(GPX_SOURCE_ID)
            style.removeImage("start-icon")
            style.removeImage("end-icon")
            style.removeLayer("start-layer")
            style.removeLayer("end-layer")
            style.removeSource("start-source")
            style.removeSource("end-source")

            // Create line geometry
            val linePoints = points.map { Point.fromLngLat(it.longitude, it.latitude) }
            val lineString = com.mapbox.geojson.LineString.fromLngLats(linePoints)

            // Add source for the route
            style.addSource(GeoJsonSource(GPX_SOURCE_ID, lineString))

            // Add line layer
            style.addLayer(
                LineLayer(GPX_LAYER_ID, GPX_SOURCE_ID).withProperties(
                    lineColor(Color.RED),
                    lineWidth(5f),
                    lineOpacity(1f)
                )
            )

            // -----------------------------
            // Add Start and End Markers
            // -----------------------------
            if (points.size >= 2) {
                val startPoint = Point.fromLngLat(points.first().longitude, points.first().latitude)
                val endPoint = Point.fromLngLat(points.last().longitude, points.last().latitude)

                // Add custom icons (optional: replace with your own drawables)
                val startBitmap = ContextCompat.getDrawable(this, android.R.drawable.presence_online)!!
                val endBitmap = ContextCompat.getDrawable(this, android.R.drawable.presence_busy)!!

                style.addImage("start-icon", startBitmap.toBitmap())
                style.addImage("end-icon", endBitmap.toBitmap())

                // Add GeoJSON sources for markers
                style.addSource(GeoJsonSource("start-source", startPoint))
                style.addSource(GeoJsonSource("end-source", endPoint))

                // Add layers for markers
                style.addLayer(
                    com.mapbox.mapboxsdk.style.layers.SymbolLayer("start-layer", "start-source").withProperties(
                        iconImage("start-icon"),
                        iconAllowOverlap(true),
                        iconIgnorePlacement(true),
                        iconSize(1.2f)
                    )
                )

                style.addLayer(
                    com.mapbox.mapboxsdk.style.layers.SymbolLayer("end-layer", "end-source").withProperties(
                        iconImage("end-icon"),
                        iconAllowOverlap(true),
                        iconIgnorePlacement(true),
                        iconSize(1.2f)
                    )
                )
            }

            // Zoom to the route
            if (points.size > 1) {
                val routeBounds = LatLngBounds.Builder().apply {
                    points.forEach { include(it) }
                }.build()
                map.easeCamera(CameraUpdateFactory.newLatLngBounds(routeBounds, 80), 800)
            } else {
                map.easeCamera(CameraUpdateFactory.newLatLngZoom(points[0], 15.0), 600)
            }

            toast("GPX route loaded")
        }
    }


    // -------------------------------------------------------------------------
    // Existing MBTiles handling (unchanged except tiny helper for content-uri)
    // -------------------------------------------------------------------------
    private fun showMbTilesMap(mbtilesFile: File) {
        val styleJsonInputStream = assets.open("bright.json")
        val dir = File(filesDir.absolutePath)
        val styleFile = File(dir, "bright.json")
        copyStreamToFile(styleJsonInputStream, styleFile)

        bounds = getLatLngBounds(mbtilesFile)
        minZoomLevel = getMinZoom(mbtilesFile).toDouble()

        val newFileStr = styleFile.inputStream().readToString()
            .replace("___FILE_URI___", "mbtiles:///${mbtilesFile.absolutePath}")

        FileWriter(styleFile).use { writer ->
            BufferedWriter(writer).use { it.write(newFileStr) }
        }

        map.setStyle(Style.Builder().fromUri(Uri.fromFile(styleFile).toString())) { style -> }

        map.animateCamera(
            CameraUpdateFactory.newLatLngBounds(bounds, 0),
            object : MapboxMap.CancelableCallback {
                override fun onCancel() {}
                override fun onFinish() {
                    if (minZoomLevel == 0.0) minZoomLevel = map.cameraPosition.zoom
                    if (zoomSwitch.isChecked) {
                        map.setMinZoomPreference(minZoomLevel)
                        map.limitViewToBounds(bounds)
                    }

                    map.addOnScaleListener(object : MapboxMap.OnScaleListener {
                        override fun onScaleBegin(detector: StandardScaleGestureDetector) {}
                        override fun onScale(detector: StandardScaleGestureDetector) {
                            if (zoomSwitch.isChecked) map.limitViewToBounds(bounds)
                        }
                        override fun onScaleEnd(detector: StandardScaleGestureDetector) {}
                    })

                    map.addOnCameraIdleListener {
                        if (zoomSwitch.isChecked) map.limitViewToBounds(bounds)
                    }
                }
            })
    }

    // -------------------------------------------------------------------------
    // Helper: copy a content:// uri to a cache file (used for MBTiles picker)
    // -------------------------------------------------------------------------
    private fun uriToCacheFile(uri: Uri, fileName: String): File {
        val cacheFile = File(cacheDir, fileName)
        contentResolver.openInputStream(uri)?.use { input ->
            FileOutputStream(cacheFile).use { output ->
                input.copyTo(output)
            }
        }
        return cacheFile
    }

    // -------------------------------------------------------------------------
    // Existing utility functions (unchanged)
    // -------------------------------------------------------------------------
    private fun showMapCenter() {
        val mapCenter = View(this).apply {
            layoutParams = FrameLayout.LayoutParams(15, 15, Gravity.CENTER)
            setBackgroundColor(Color.GREEN)
        }
        mapView.addView(mapCenter)
    }
}

// -------------------------------------------------------------------------
// All the helper/extension functions from the original file (unchanged)
// -------------------------------------------------------------------------
fun copyStreamToFile(inputStream: InputStream, outputFile: File) {
    inputStream.use { input ->
        FileOutputStream(outputFile).use { output ->
            val buffer = ByteArray(4 * 1024)
            var byteCount: Int
            while (input.read(buffer).also { byteCount = it } >= 0) {
                output.write(buffer, 0, byteCount)
            }
            output.flush()
        }
    }
}

@Throws(IOException::class)
fun getFileFromAssets(context: Context, fileName: String): File =
    File(context.cacheDir, fileName).also { file ->
        if (!file.exists()) {
            file.outputStream().use { cache ->
                context.assets.open(fileName).use { inputStream -> inputStream.copyTo(cache) }
            }
        }
    }

fun getLatLngBounds(file: File): LatLngBounds {
    val db = SQLiteDatabase.openDatabase(file.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
    val cursor = db.query("metadata", arrayOf("name", "value"), "name=?", arrayOf("bounds"), null, null, null)
    cursor.moveToFirst()
    val boundsStr = cursor.getString(1).split(",")
    cursor.close()
    db.close()
    return LatLngBounds.Builder()
        .include(LatLng(boundsStr[1].toDouble(), boundsStr[0].toDouble()))
        .include(LatLng(boundsStr[3].toDouble(), boundsStr[2].toDouble()))
        .build()
}

fun getMinZoom(file: File): Int {
    val db = SQLiteDatabase.openDatabase(file.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
    val cursor = db.query("metadata", arrayOf("name", "value"), "name=?", arrayOf("minzoom"), null, null, null)
    cursor.moveToFirst()
    val minZoom = cursor.getString(1).toInt()
    cursor.close()
    db.close()
    return minZoom
}

fun InputStream.readToString(): String {
    val r = BufferedReader(InputStreamReader(this))
    val sb = StringBuilder()
    var line: String?
    while (r.readLine().also { line = it } != null) sb.append(line).append('\n')
    return sb.toString()
}

fun Context.convertDpToPixel(dp: Int): Float =
    dp * (resources.displayMetrics.densityDpi / 160f)

fun showBoundsArea(
    loadedMapStyle: Style,
    bounds: LatLngBounds,
    color: Int,
    sourceId: String,
    layerId: String,
    opacity: Float
) {
    val outerPoints = mutableListOf<Point>().apply {
        add(Point.fromLngLat(bounds.northWest.longitude, bounds.northWest.latitude))
        add(Point.fromLngLat(bounds.northEast.longitude, bounds.northEast.latitude))
        add(Point.fromLngLat(bounds.southEast.longitude, bounds.southEast.latitude))
        add(Point.fromLngLat(bounds.southWest.longitude, bounds.southWest.latitude))
        add(Point.fromLngLat(bounds.northWest.longitude, bounds.northWest.latitude))
    }

    loadedMapStyle.removeLayer(layerId)
    loadedMapStyle.removeSource(sourceId)

    loadedMapStyle.addSource(GeoJsonSource(sourceId, Polygon.fromLngLats(mutableListOf(outerPoints))))
    loadedMapStyle.addLayer(
        FillLayer(layerId, sourceId).withProperties(
            fillColor(color),
            fillOpacity(opacity)
        )
    )
}

fun MapboxMap.limitViewToBounds(bounds: LatLngBounds) {
    val visible = projection.visibleRegion.latLngBounds
    val newHeight = bounds.latitudeSpan - visible.latitudeSpan
    val newWidth = bounds.longitudeSpan - visible.longitudeSpan

    val leftTop = LatLng(
        bounds.latNorth - (bounds.latitudeSpan - newHeight) / 2,
        bounds.lonEast - (bounds.longitudeSpan - newWidth) / 2 - newWidth
    )
    val rightBottom = LatLng(
        bounds.latNorth - (bounds.latitudeSpan - newHeight) / 2 - newHeight,
        bounds.lonEast - (bounds.longitudeSpan - newWidth) / 2
    )

    val newBounds = LatLngBounds.Builder()
        .include(leftTop)
        .include(rightBottom)
        .build()

    setLatLngBoundsForCameraTarget(newBounds)
}

// -------------------------------------------------------------------------
// Tiny toast helper
// -------------------------------------------------------------------------
private fun Activity.toast(message: String) {
    Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
}