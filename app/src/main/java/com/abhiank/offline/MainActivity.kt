package com.abhiank.offline

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.RectF
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.ImageButton
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.mapbox.geojson.Feature
import com.mapbox.geojson.Point
import com.mapbox.mapboxsdk.Mapbox
import com.mapbox.mapboxsdk.camera.CameraUpdateFactory
import com.mapbox.mapboxsdk.annotations.Marker
import com.mapbox.mapboxsdk.annotations.MarkerOptions
import com.mapbox.mapboxsdk.geometry.LatLng
import com.mapbox.mapboxsdk.maps.MapView
import com.mapbox.mapboxsdk.maps.MapboxMap
import com.mapbox.mapboxsdk.style.layers.PropertyFactory.textField
import java.util.Locale

class MainActivity : AppCompatActivity() {

    companion object {
        private const val MBTILES_NAME = "dhaka2.mbtiles"
        private const val PERMISSION_REQUEST_CODE = 100
        private const val ROUTE_TOUCH_BUFFER = 24f
        private val LANDMARK_LAYER_IDS = arrayOf(
            "poi-level-3",
            "poi-level-2",
            "poi-level-1",
            "poi-other",
            "poi-railway",
            "poi-transit",
            "poi-label",
            "place-hamlet",
            "place-village",
            "place-town",
            "place-city",
            "place-city-capital",
            "place-other"
        )
    }

    private lateinit var mapView: MapView
    private lateinit var map: MapboxMap
    private lateinit var zoomSwitch: SwitchCompat
    private lateinit var debugSwitch: SwitchCompat
    private lateinit var mbtilesLoader: MbtilesStyleLoader
    private lateinit var gpxRenderer: GpxRouteRenderer
    private var infoMarker: Marker? = null

    private val defaultRouteAssets = listOf(
        "routes/bup_main_loop.gpx",
        "routes/bup_shuttle_loop.gpx"
    )

    private val filePickerReturnResult =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK && result.data != null) {
                val uri = result.data!!.data!!
                val file = uriToCacheFile(uri, "picked.mbtiles")
                if (::mbtilesLoader.isInitialized) {
                    mbtilesLoader.loadFromFile(file)
                } else {
                    toast("Map not ready yet")
                }
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Mapbox.getInstance(this)
        setContentView(R.layout.activity_main)

        mapView = findViewById(R.id.mapView)
        zoomSwitch = findViewById(R.id.lockZoomSwitch)
        debugSwitch = findViewById(R.id.debugModeSwitch)

        configurePermissions()
        configureMap(savedInstanceState)
        configureButtons()
    }

    private fun configurePermissions() {
        val permissionsNeeded = arrayOf(
            Manifest.permission.READ_EXTERNAL_STORAGE,
            Manifest.permission.WRITE_EXTERNAL_STORAGE
        )
        val missingPermissions = permissionsNeeded.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missingPermissions.isNotEmpty()) {
            ActivityCompat.requestPermissions(
                this,
                missingPermissions.toTypedArray(),
                PERMISSION_REQUEST_CODE
            )
        }
    }

    private fun configureMap(savedInstanceState: Bundle?) {
        mapView.onCreate(savedInstanceState)
        mapView.getMapAsync { mapboxMap ->
            map = mapboxMap
            map.addOnCameraMoveListener {
                Log.d("zoom", map.cameraPosition.zoom.toString())
            }

            mbtilesLoader = MbtilesStyleLoader(
                context = this,
                map = map,
                zoomSwitch = zoomSwitch,
                debugSwitch = debugSwitch
            )
            configureSwitches()

            gpxRenderer = GpxRouteRenderer(this, map)

            map.addOnStyleLoadedListener {
                gpxRenderer.loadFromAssets(defaultRouteAssets)
            }
            mbtilesLoader.loadFromAssets(MBTILES_NAME)
            map.addOnMapClickListener { handleMapClick(it) }
            changeLanguage("{name_en}")
        }
    }

    private fun configureButtons() {
        findViewById<Button>(R.id.pickFileButton).setOnClickListener {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "*/*"
                putExtra(
                    Intent.EXTRA_MIME_TYPES,
                    arrayOf(
                        "application/octet-stream",
                        "application/vnd.sqlite3",
                        "application/x-sqlite3"
                    )
                )
            }
            filePickerReturnResult.launch(intent)
        }

        findViewById<ImageButton>(R.id.zoomInButton).setOnClickListener {
            if (::map.isInitialized) {
                map.animateCamera(CameraUpdateFactory.zoomIn())
            }
        }

        findViewById<ImageButton>(R.id.zoomOutButton).setOnClickListener {
            if (::map.isInitialized) {
                map.animateCamera(CameraUpdateFactory.zoomOut())
            }
        }
    }

    private fun configureSwitches() {
        zoomSwitch.isChecked = false
        zoomSwitch.isEnabled = false
        zoomSwitch.setOnCheckedChangeListener { _, isChecked ->
            if (!::map.isInitialized || !::mbtilesLoader.isInitialized) return@setOnCheckedChangeListener
            val minZoom = mbtilesLoader.minZoomLevel
            if (isChecked) {
                map.setMinZoomPreference(minZoom)
            } else {
                map.setMinZoomPreference(0.0)
                map.setLatLngBoundsForCameraTarget(null)
            }
        }

        debugSwitch.isEnabled = false
        debugSwitch.setOnCheckedChangeListener { _, isChecked ->
            val style = map.style ?: return@setOnCheckedChangeListener
            map.isDebugActive = isChecked
            if (isChecked) {
                val overlayColor = ContextCompat.getColor(this, android.R.color.holo_red_dark)
                showBoundsArea(
                    style,
                    mbtilesLoader.bounds,
                    overlayColor,
                    sourceId = "source-id-1",
                    layerId = "layer-id-1",
                    opacity = 0.25f
                )
            } else {
                style.removeLayer("layer-id-1")
                style.removeSource("source-id-1")
            }
        }
    }

    private fun changeLanguage(lang: String) {
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

    private fun handleMapClick(point: LatLng): Boolean {
        if (!::map.isInitialized) return false

        val screenPoint = map.projection.toScreenLocation(point)
        val touchRect = RectF(
            screenPoint.x - ROUTE_TOUCH_BUFFER,
            screenPoint.y - ROUTE_TOUCH_BUFFER,
            screenPoint.x + ROUTE_TOUCH_BUFFER,
            screenPoint.y + ROUTE_TOUCH_BUFFER
        )

        val routeFeatures = map.queryRenderedFeatures(touchRect, GpxRouteRenderer.ROUTE_LAYER_ID)
        if (routeFeatures.isNotEmpty()) {
            val feature = routeFeatures.first()
            val routeName = feature.getStringPropertySafely(GpxRouteRenderer.ROUTE_NAME_PROPERTY) ?: "Route"
            val distanceKm = feature.getNumberPropertySafely(GpxRouteRenderer.ROUTE_DISTANCE_KM_PROPERTY)
            val snippet = distanceKm?.takeIf { it > 0 }?.let {
                String.format(Locale.US, "Length: %.2f km", it)
            }
            showPopup(point, routeName, snippet)
            return true
        }

        val landmarkFeatures = map.queryRenderedFeatures(touchRect, *LANDMARK_LAYER_IDS)
        val landmarkFeature = landmarkFeatures.firstOrNull { it.geometry() is Point }
        if (landmarkFeature != null) {
            val geometryPoint = landmarkFeature.geometry() as? Point
            val landmarkPosition = geometryPoint?.let { LatLng(it.latitude(), it.longitude()) } ?: point
            val title = landmarkFeature.getStringPropertySafely("name_en")
                ?: landmarkFeature.getStringPropertySafely("name")
                ?: "Landmark"
            val snippet = landmarkFeature.getStringPropertySafely("class")
                ?: landmarkFeature.getStringPropertySafely("type")
            showPopup(landmarkPosition, title, snippet)
            return true
        }

        clearPopup()
        return false
    }

    private fun showPopup(position: LatLng, title: String, snippet: String?) {
        infoMarker?.let { map.removeMarker(it) }
        val markerOptions = MarkerOptions()
            .position(position)
            .title(title)
        snippet?.takeIf { it.isNotBlank() }?.let { markerOptions.snippet(it) }
        infoMarker = map.addMarker(markerOptions)
        infoMarker?.let { map.selectMarker(it) }
    }

    private fun clearPopup() {
        infoMarker?.let { existing ->
            map.removeMarker(existing)
        }
        infoMarker = null
    }

    private fun Feature.getStringPropertySafely(key: String): String? =
        try {
            if (hasProperty(key)) getStringProperty(key)?.takeIf { it.isNotEmpty() } else null
        } catch (exception: Exception) {
            null
        }

    private fun Feature.getNumberPropertySafely(key: String): Double? =
        try {
            properties()?.get(key)?.asDouble
        } catch (exception: Exception) {
            null
        }

    override fun onStart() {
        super.onStart()
        mapView.onStart()
    }

    override fun onResume() {
        super.onResume()
        mapView.onResume()
    }

    override fun onPause() {
        mapView.onPause()
        super.onPause()
    }

    override fun onStop() {
        mapView.onStop()
        super.onStop()
    }

    override fun onLowMemory() {
        super.onLowMemory()
        mapView.onLowMemory()
    }

    override fun onDestroy() {
        mapView.onDestroy()
        super.onDestroy()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        mapView.onSaveInstanceState(outState)
    }
}
