package com.abhiank.offline

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.RectF
import android.location.Location
import android.os.Bundle
import android.os.Looper
import android.util.Log
import android.widget.Button
import android.widget.ImageButton
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.preference.PreferenceManager
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.mapbox.android.core.location.LocationEngine
import com.mapbox.android.core.location.LocationEngineCallback
import com.mapbox.android.core.location.LocationEngineProvider
import com.mapbox.android.core.location.LocationEngineRequest
import com.mapbox.android.core.location.LocationEngineResult
import com.mapbox.geojson.Feature
import com.mapbox.geojson.Point
import com.mapbox.mapboxsdk.Mapbox
import com.mapbox.mapboxsdk.annotations.Marker
import com.mapbox.mapboxsdk.annotations.MarkerOptions
import com.mapbox.mapboxsdk.camera.CameraUpdateFactory
import com.mapbox.mapboxsdk.geometry.LatLng
import com.mapbox.mapboxsdk.location.LocationComponentActivationOptions
import com.mapbox.mapboxsdk.location.modes.CameraMode
import com.mapbox.mapboxsdk.location.modes.RenderMode
import com.mapbox.mapboxsdk.maps.MapView
import com.mapbox.mapboxsdk.maps.MapboxMap
import com.mapbox.mapboxsdk.style.layers.Property
import com.mapbox.mapboxsdk.style.layers.PropertyFactory.textField
import com.mapbox.mapboxsdk.style.layers.PropertyFactory.visibility
import java.io.IOException
import java.lang.ref.WeakReference
import java.util.Locale

class MainActivity : AppCompatActivity(), SharedPreferences.OnSharedPreferenceChangeListener {

    companion object {
        private const val MBTILES_NAME = "dhaka2.mbtiles"
        private const val PERMISSION_REQUEST_CODE = 100
        private const val ROUTE_TOUCH_BUFFER = 24f
        private const val LOCATION_INTERVAL_MS = 1000L
        private const val LOCATION_FASTEST_INTERVAL_MS = 500L
    }

    private lateinit var mapView: MapView
    private lateinit var map: MapboxMap
    private lateinit var zoomSwitch: SwitchCompat
    private lateinit var debugSwitch: SwitchCompat
    private lateinit var mbtilesLoader: MbtilesStyleLoader
    private lateinit var gpxRenderer: GpxRouteRenderer
    private lateinit var gpxRecorder: GpxRecorder
    private lateinit var startTrackingButton: Button
    private lateinit var stopTrackingButton: Button
    private lateinit var saveTrackButton: Button
    private lateinit var currentLocationButton: FloatingActionButton
    private lateinit var speedometerTextView: TextView
    private lateinit var preferences: SharedPreferences
    private var infoMarker: Marker? = null

    private var isTracking = false
    private var locationComponentActivated = false
    private var currentLocation: Location? = null
    private var lastRecordedTrackLocation: Location? = null

    private lateinit var locationEngine: LocationEngine
    private val locationCallback = MainLocationCallback(this)

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
        startTrackingButton = findViewById(R.id.startTrackingButton)
        stopTrackingButton = findViewById(R.id.stopTrackingButton)
        saveTrackButton = findViewById(R.id.saveTrackButton)
        currentLocationButton = findViewById(R.id.currentLocationButton)
        speedometerTextView = findViewById(R.id.speedometerText)
        preferences = PreferenceManager.getDefaultSharedPreferences(this)
        preferences.registerOnSharedPreferenceChangeListener(this)
        gpxRecorder = GpxRecorder(this)

        configurePermissions()
        configureMap(savedInstanceState)
        configureButtons()
        updateSpeedometer(null)
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
                locationComponentActivated = false
                gpxRenderer.loadFromAssets(defaultRouteAssets)
                setupLocationComponentIfPermitted()
                applyPoiLayerVisibility()
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

        findViewById<Button>(R.id.settingsButton).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
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

        startTrackingButton.setOnClickListener { startTracking() }
        stopTrackingButton.setOnClickListener { stopTracking() }
        saveTrackButton.setOnClickListener { saveCurrentTrack() }
        currentLocationButton.setOnClickListener { focusOnCurrentLocation() }

        updateTrackingButtons()
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

        val landmarkFeatures = map.queryRenderedFeatures(touchRect, *PoiLayerPreferences.layerIds)
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
        if (locationComponentActivated) {
            startLocationUpdates()
        }
    }

    override fun onResume() {
        super.onResume()
        mapView.onResume()
        setupLocationComponentIfPermitted()
    }

    override fun onPause() {
        mapView.onPause()
        super.onPause()
    }

    override fun onStop() {
        mapView.onStop()
        stopLocationUpdates()
        super.onStop()
    }

    override fun onLowMemory() {
        super.onLowMemory()
        mapView.onLowMemory()
    }

    override fun onDestroy() {
        stopLocationUpdates()
        mapView.onDestroy()
        preferences.unregisterOnSharedPreferenceChangeListener(this)
        super.onDestroy()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        mapView.onSaveInstanceState(outState)
    }

    private fun startTracking() {
        if (!hasLocationPermission()) {
            toast(getString(R.string.permission_explanation))
            return
        }
        if (!locationComponentActivated) {
            setupLocationComponentIfPermitted()
        }
        if (!locationComponentActivated) {
            toast("Location unavailable")
            return
        }
        isTracking = true
        gpxRecorder.startNewTrack()
        currentLocation?.let {
            gpxRecorder.addPoint(it)
            lastRecordedTrackLocation = it
        } ?: run {
            lastRecordedTrackLocation = null
        }
        toast("Live tracking started")
        updateTrackingButtons()
    }

    private fun stopTracking() {
        if (!isTracking) return
        isTracking = false
        toast("Live tracking stopped")
        updateTrackingButtons()
    }

    private fun saveCurrentTrack() {
        if (isTracking) {
            toast("Stop tracking before saving")
            return
        }
        if (!gpxRecorder.hasRecordedTrack()) {
            toast("No track data to save yet")
            return
        }
        try {
            val file = gpxRecorder.saveToFile()
            gpxRecorder.clear()
            lastRecordedTrackLocation = null
            val message = file?.let { "Saved GPX to ${it.name}" } ?: "Unable to save GPX"
            toast(message)
        } catch (io: IOException) {
            Log.e("GPX", "Error saving GPX", io)
            toast("Failed to save GPX file")
        }
        updateTrackingButtons()
    }

    private fun focusOnCurrentLocation() {
        if (!::map.isInitialized) return
        val location = currentLocation
        if (location != null) {
            val target = LatLng(location.latitude, location.longitude)
            map.animateCamera(CameraUpdateFactory.newLatLngZoom(target, 16.0))
        } else {
            toast("Waiting for current location")
        }
    }

    private fun updateTrackingButtons() {
        startTrackingButton.isEnabled = !isTracking
        stopTrackingButton.isEnabled = isTracking
        saveTrackButton.isEnabled = !isTracking && gpxRecorder.hasRecordedTrack()
    }

    @SuppressLint("MissingPermission")
    private fun setupLocationComponentIfPermitted() {
        if (!::map.isInitialized) return
        if (!hasLocationPermission()) return
        if (locationComponentActivated) return
        val style = map.style ?: return
        val activationOptions = LocationComponentActivationOptions.builder(this, style).build()
        val locationComponent = map.locationComponent
        locationComponent.activateLocationComponent(activationOptions)
        locationComponent.isLocationComponentEnabled = true
        locationComponent.cameraMode = CameraMode.NONE
        locationComponent.renderMode = RenderMode.COMPASS
        locationComponentActivated = true
        startLocationUpdates()
    }

    @SuppressLint("MissingPermission")
    private fun startLocationUpdates() {
        if (!hasLocationPermission()) return
        if (!::locationEngine.isInitialized) {
            locationEngine = LocationEngineProvider.getBestLocationEngine(this)
        } else {
            locationEngine.removeLocationUpdates(locationCallback)
        }
        val request = LocationEngineRequest.Builder(LOCATION_INTERVAL_MS)
            .setPriority(LocationEngineRequest.PRIORITY_HIGH_ACCURACY)
            .setFastestInterval(LOCATION_FASTEST_INTERVAL_MS)
            .build()
        locationEngine.requestLocationUpdates(request, locationCallback, Looper.getMainLooper())
        locationEngine.getLastLocation(locationCallback)
    }

    private fun stopLocationUpdates() {
        if (::locationEngine.isInitialized) {
            locationEngine.removeLocationUpdates(locationCallback)
        }
    }

    private fun applyPoiLayerVisibility() {
        if (!::map.isInitialized) return
        val style = map.style ?: return
        PoiLayerPreferences.layers.forEach { layer ->
            val layerVisibility = if (PoiLayerPreferences.isLayerEnabled(preferences, layer.id)) {
                Property.VISIBLE
            } else {
                Property.NONE
            }
            style.getLayer(layer.id)?.setProperties(visibility(layerVisibility))
        }
    }

    private fun updateSpeedometer(location: Location?) {
        val displayText = if (location != null && location.hasSpeed()) {
            val speedKmh = location.speed * 3.6f
            getString(R.string.speedometer_value, speedKmh.toDouble())
        } else {
            getString(R.string.speedometer_unavailable)
        }
        speedometerTextView.text = displayText
    }

    private fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences, key: String?) {
        if (key == null) return
        if (key.startsWith(PoiLayerPreferences.preferencePrefix())) {
            applyPoiLayerVisibility()
        }
    }

    private class MainLocationCallback(activity: MainActivity) :
        LocationEngineCallback<LocationEngineResult> {

        private val activityRef = WeakReference(activity)

        override fun onSuccess(result: LocationEngineResult?) {
            val activity = activityRef.get() ?: return
            val location = result?.lastLocation ?: return
            activity.runOnUiThread {
                activity.currentLocation = location
                activity.updateSpeedometer(location)
                if (activity.isTracking) {
                    val previous = activity.lastRecordedTrackLocation
                    if (previous == null || previous.distanceTo(location) >= 1f) {
                        activity.gpxRecorder.addPoint(location)
                        activity.lastRecordedTrackLocation = location
                        activity.updateTrackingButtons()
                    }
                }
            }
        }

        override fun onFailure(exception: Exception) {
            Log.e("Location", "Location update failed", exception)
        }
    }
}
