package com.abhiank.offline

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.widget.Button
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.mapbox.mapboxsdk.Mapbox
import com.mapbox.mapboxsdk.maps.MapView
import com.mapbox.mapboxsdk.maps.MapboxMap
import com.mapbox.mapboxsdk.style.layers.PropertyFactory.textField

class MainActivity : AppCompatActivity() {

    companion object {
        private const val MBTILES_NAME = "dhaka2.mbtiles"
        private const val PERMISSION_REQUEST_CODE = 100
    }

    private lateinit var mapView: MapView
    private lateinit var map: MapboxMap
    private lateinit var zoomSwitch: SwitchCompat
    private lateinit var debugSwitch: SwitchCompat
    private lateinit var mbtilesLoader: MbtilesStyleLoader
    private lateinit var gpxRenderer: GpxRouteRenderer

    private val gpxFilePicker = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.data?.let { uri ->
                if (::gpxRenderer.isInitialized) {
                    gpxRenderer.loadFromUri(uri)
                } else {
                    toast("Map not ready yet")
                }
            }
        }
    }

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

            mbtilesLoader.loadFromAssets(MBTILES_NAME)
            changeLanguage("{name_en}")
        }
    }

    private fun configureButtons() {
        findViewById<Button>(R.id.pickFileButton).setOnClickListener {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "*/*"
                putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/gpx+xml", "text/xml"))
            }
            filePickerReturnResult.launch(intent)
        }

        findViewById<Button>(R.id.loadGpxButton).setOnClickListener {
            openGpxFilePicker()
        }
    }

    private fun configureSwitches() {
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

    private fun openGpxFilePicker() {
        val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
            type = "*/*"
            addCategory(Intent.CATEGORY_OPENABLE)
        }
        gpxFilePicker.launch(intent)
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
