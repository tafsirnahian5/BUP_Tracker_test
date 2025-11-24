package com.abhiank.offline

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.RectF
import android.location.Location
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.content.ContextCompat
import androidx.appcompat.widget.SwitchCompat
import androidx.core.graphics.drawable.toBitmap
import androidx.preference.PreferenceManager
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.mapbox.geojson.Feature
import com.mapbox.geojson.Point
import org.maplibre.android.MapLibre
import org.maplibre.android.annotations.Marker
import org.maplibre.android.annotations.MarkerOptions
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.location.LocationComponentActivationOptions
import org.maplibre.android.location.modes.CameraMode
import org.maplibre.android.location.modes.RenderMode
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory.iconAllowOverlap
import org.maplibre.android.style.layers.PropertyFactory.iconAnchor
import org.maplibre.android.style.layers.PropertyFactory.iconImage
import org.maplibre.android.style.layers.PropertyFactory.iconSize
import org.maplibre.android.style.layers.PropertyFactory.textField
import org.maplibre.android.style.layers.PropertyFactory.textOffset
import org.maplibre.android.style.layers.PropertyFactory.visibility
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import java.io.IOException
import java.util.Locale

class MainActivity : AppCompatActivity(), SharedPreferences.OnSharedPreferenceChangeListener {

    companion object {
        private const val MBTILES_NAME = "dhaka2.mbtiles"
        private const val PERMISSION_REQUEST_CODE = 100
        private const val BACKGROUND_PERMISSION_REQUEST_CODE = 101
        private const val ROUTE_TOUCH_BUFFER = 24f
    }

    private lateinit var mapView: MapView
    private lateinit var map: MapLibreMap
    // UI controls
    private lateinit var zoomSwitch: SwitchCompat
    private lateinit var debugSwitch: SwitchCompat
    private lateinit var mbtilesLoader: MbtilesStyleLoader
    private lateinit var gpxRenderer: GpxRouteRenderer
    private lateinit var gpxRecorder: GpxRecorder
    private lateinit var startTrackingButton: Button
    private lateinit var stopTrackingButton: Button
    private lateinit var saveTrackButton: Button
    private lateinit var currentLocationButton: FloatingActionButton
    private lateinit var addPoiButton: FloatingActionButton
    private lateinit var speedometerTextView: TextView
    private lateinit var preferences: SharedPreferences
    private lateinit var customPoiRepository: CustomPoiRepository
    private var infoMarker: Marker? = null

    // Tracking state
    private var isTracking = false
    private var hasGpsLock = false
    private var locationComponentActivated = false
    private var currentLocation: Location? = null
    private var lastRecordedTrackLocation: Location? = null

    // Location plumbing
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private var locationRequest: LocationRequest? = null
    private var isRequestingLocationUpdates = false
    private val fusedLocationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            val location = result.lastLocation ?: return
            handleLocationUpdate(location)
        }
    }

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

    // region Lifecycle
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        MapLibre.getInstance(this, getString(R.string.maplibre_access_token))
        setContentView(R.layout.activity_main)

        mapView = findViewById(R.id.mapView)
        zoomSwitch = findViewById(R.id.lockZoomSwitch)
        debugSwitch = findViewById(R.id.debugModeSwitch)
        startTrackingButton = findViewById(R.id.startTrackingButton)
        stopTrackingButton = findViewById(R.id.stopTrackingButton)
        saveTrackButton = findViewById(R.id.saveTrackButton)
        currentLocationButton = findViewById(R.id.currentLocationButton)
        addPoiButton = findViewById(R.id.addPoiButton)
        speedometerTextView = findViewById(R.id.speedometerText)
        preferences = PreferenceManager.getDefaultSharedPreferences(this)
        preferences.registerOnSharedPreferenceChangeListener(this)
        gpxRecorder = GpxRecorder(this)
        customPoiRepository = CustomPoiRepository(this)
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        updateLocationRequest()

        configureMap(savedInstanceState)
        configureButtons()
        updateSpeedometer(null)
    }

    // region Permissions & requests
    private fun configurePermissions(requestBackground: Boolean = false) {
        PermissionHelper.maybeShowPermissionEducation(this) {
            when {
                !hasLocationPermission() ->
                    PermissionHelper.requestForegroundLocation(this, PERMISSION_REQUEST_CODE)
                requestBackground && !PermissionHelper.hasBackgroundLocation(this) ->
                    PermissionHelper.requestBackgroundLocation(this, BACKGROUND_PERMISSION_REQUEST_CODE)
                else -> setupLocationComponentIfPermitted()
            }
        }
    }

    // region Map configuration
    private fun configureMap(savedInstanceState: Bundle?) {
        mapView.onCreate(savedInstanceState)
        mapView.getMapAsync { mapLibreMap ->
            map = mapLibreMap
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
                setupCustomPoiLayer()
            }
            mbtilesLoader.loadFromAssets(MBTILES_NAME)
            map.addOnMapClickListener { handleMapClick(it) }
            map.addOnMapLongClickListener {
                promptAddCustomPoi(it)
                true
            }
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

        findViewById<Button>(R.id.historyButton).setOnClickListener {
            startActivity(Intent(this, HistoryActivity::class.java))
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
        addPoiButton.setOnClickListener {
            if (::map.isInitialized) {
                promptAddCustomPoi(map.cameraPosition.target)
            }
        }

        updateTrackingButtons()
    }
    // endregion

    // region Map interactions
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

    // endregion

    // region Location + tracking
    private fun buildLocationRequest(): LocationRequest {
        val powerSaveEnabled = TrackingPreferences.isPowerSaveEnabled(preferences)
        val gpsAccuracy = TrackingPreferences.getGpsAccuracy(preferences)
        val interval = TrackingPreferences.locationIntervalMillis(powerSaveEnabled)
        val fastestInterval = TrackingPreferences.locationFastestIntervalMillis(powerSaveEnabled)
        return LocationRequest.Builder(interval)
            .setMinUpdateIntervalMillis(fastestInterval)
            .setPriority(gpsAccuracy.priority)
            .build()
    }

    private fun updateLocationRequest() {
        locationRequest = buildLocationRequest()
        if (isRequestingLocationUpdates) {
            restartLocationUpdates()
        }
        hasGpsLock = currentLocation?.let { isLocationAccurateForLock(it) } ?: false
        updateTrackingButtons()
        updateSpeedometer(currentLocation)
    }

    private fun restartLocationUpdates() {
        stopLocationUpdates(clearTrackingState = false)
        if (locationComponentActivated) {
            startLocationUpdates()
        }
    }

    private fun gpsLockThresholdMeters(): Float =
        TrackingPreferences.getGpsAccuracy(preferences).lockThresholdMeters

    private fun isLocationAccurateForLock(location: Location): Boolean =
        location.hasAccuracy() && location.accuracy <= gpsLockThresholdMeters()

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
        configurePermissions(requestBackground = isTracking)
        setupLocationComponentIfPermitted()
        if (!hasLocationPermission()) {
            hasGpsLock = false
            currentLocation = null
        }
        updateTrackingButtons()
        updateSpeedometer(currentLocation)
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

    // endregion

    private fun startTracking() {
        configurePermissions(requestBackground = true)
        if (!hasLocationPermission()) {
            toast(getString(R.string.message_location_permission_required))
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            !PermissionHelper.hasBackgroundLocation(this)
        ) {
            toast(getString(R.string.message_background_location_needed))
            return
        }
        if (!hasGpsLock) {
            toast(getString(R.string.message_waiting_for_gps_lock))
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
            toast(getString(R.string.message_focused_on_location))
        } else {
            toast("Waiting for current location")
        }
    }

    private fun updateTrackingButtons() {
        startTrackingButton.isEnabled = !isTracking && hasGpsLock && hasLocationPermission()
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
        if (!::fusedLocationClient.isInitialized) {
            fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        }
        val request = locationRequest ?: buildLocationRequest().also { locationRequest = it }
        fusedLocationClient.requestLocationUpdates(request, fusedLocationCallback, mainLooper)
        isRequestingLocationUpdates = true
        fusedLocationClient.lastLocation.addOnSuccessListener { location ->
            location?.let { handleLocationUpdate(it) }
        }
    }

    private fun stopLocationUpdates(clearTrackingState: Boolean = true) {
        if (::fusedLocationClient.isInitialized) {
            fusedLocationClient.removeLocationUpdates(fusedLocationCallback)
        }
        isRequestingLocationUpdates = false
        if (clearTrackingState && !isTracking) {
            hasGpsLock = false
            updateTrackingButtons()
            updateSpeedometer(null)
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

    private fun setupCustomPoiLayer() {
        val style = map.style ?: return
        if (style.getImage(CustomPoiRepository.ICON_ID) == null) {
            style.addImage(CustomPoiRepository.ICON_ID, loadPoiBitmap())
        }
        if (style.getSource(CustomPoiRepository.SOURCE_ID) == null) {
            style.addSource(GeoJsonSource(CustomPoiRepository.SOURCE_ID, customPoiRepository.loadPois()))
        }
        if (style.getLayer(CustomPoiRepository.LAYER_ID) == null) {
            val layer = SymbolLayer(CustomPoiRepository.LAYER_ID, CustomPoiRepository.SOURCE_ID)
                .withProperties(
                    iconImage(CustomPoiRepository.ICON_ID),
                    iconSize(0.9f),
                    iconAllowOverlap(true),
                    iconAnchor(Property.ICON_ANCHOR_BOTTOM),
                    textField("{title}"),
                    textOffset(arrayOf(0f, -1.2f))
                )
            style.addLayer(layer)
        }
        refreshCustomPoiLayer()
    }

    private fun refreshCustomPoiLayer() {
        if (!::map.isInitialized) return
        map.getStyle { style ->
            style.getSourceAs<GeoJsonSource>(CustomPoiRepository.SOURCE_ID)?.setGeoJson(
                customPoiRepository.loadPois()
            )
        }
    }

    private fun promptAddCustomPoi(point: LatLng) {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_add_poi, null)
        val titleInput = dialogView.findViewById<EditText>(R.id.poiTitleInput)
        val descriptionInput = dialogView.findViewById<EditText>(R.id.poiDescriptionInput)

        AlertDialog.Builder(this)
            .setTitle(R.string.label_add_custom_poi)
            .setView(dialogView)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val title = titleInput.text.toString().trim()
                val description = descriptionInput.text.toString().trim().takeIf { it.isNotEmpty() }
                if (title.isEmpty()) {
                    toast(getString(R.string.message_poi_title_required))
                    return@setPositiveButton
                }
                val featureCollection = customPoiRepository.addPoi(
                    Point.fromLngLat(point.longitude, point.latitude),
                    title,
                    description
                )
                refreshCustomPoiLayer()
                toast(getString(R.string.message_poi_saved, title))
                Log.d("POI", "Saved ${featureCollection.features()?.size ?: 0} points")
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun loadPoiBitmap(): Bitmap {
        val drawable = AppCompatResources.getDrawable(this, R.drawable.ic_poi_pin)
        return drawable?.toBitmap() ?: Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
    }

    private fun updateSpeedometer(location: Location?) {
        val displayText = when {
            !hasLocationPermission() -> getString(R.string.message_location_permission_required)
            !hasGpsLock -> getString(R.string.message_waiting_for_gps_lock)
            location != null && location.hasSpeed() -> {
                val speedKmh = location.speed * 3.6f
                getString(R.string.speedometer_value, speedKmh.toDouble())
            }
            else -> getString(R.string.speedometer_unavailable)
        }
        speedometerTextView.text = displayText
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        when (requestCode) {
            PERMISSION_REQUEST_CODE -> handleForegroundPermissionResult(permissions, grantResults)
            BACKGROUND_PERMISSION_REQUEST_CODE -> handleBackgroundPermissionResult()
        }
    }

    private fun handleForegroundPermissionResult(
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        val locationGranted = permissions.indices.any { index ->
            permissions[index] == Manifest.permission.ACCESS_FINE_LOCATION &&
                grantResults.getOrNull(index) == PackageManager.PERMISSION_GRANTED
        }

        if (locationGranted) {
            setupLocationComponentIfPermitted()
        } else {
            hasGpsLock = false
            currentLocation = null
            toast(getString(R.string.message_location_permission_required))
        }

        updateTrackingButtons()
        updateSpeedometer(currentLocation)
    }

    private fun handleBackgroundPermissionResult() {
        if (PermissionHelper.hasBackgroundLocation(this)) {
            toast(getString(R.string.message_gps_lock_acquired))
        } else {
            toast(getString(R.string.message_background_location_needed))
        }
    }

    private fun hasLocationPermission(): Boolean =
        PermissionHelper.hasForegroundLocation(this)

    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences, key: String?) {
        if (key == null) return
        when {
            key.startsWith(PoiLayerPreferences.preferencePrefix()) -> applyPoiLayerVisibility()
            key == TrackingPreferences.KEY_GPS_ACCURACY ||
                key == TrackingPreferences.KEY_POWER_SAVE -> updateLocationRequest()
        }
    }

    private fun handleLocationUpdate(location: Location) {
        currentLocation = location
        updateSpeedometer(location)
        if (locationComponentActivated) {
            map.locationComponent.forceLocationUpdate(location)
        }
        if (!hasGpsLock && isLocationAccurateForLock(location)) {
            hasGpsLock = true
            updateSpeedometer(location)
            toast(getString(R.string.message_gps_lock_acquired))
            updateTrackingButtons()
        }

        if (isTracking) {
            val previous = lastRecordedTrackLocation
            if (previous == null || previous.distanceTo(location) >= 1f) {
                gpxRecorder.addPoint(location)
                lastRecordedTrackLocation = location
                updateTrackingButtons()
            }
        } else if (hasGpsLock) {
            updateTrackingButtons()
        }
    }
    // endregion
}
