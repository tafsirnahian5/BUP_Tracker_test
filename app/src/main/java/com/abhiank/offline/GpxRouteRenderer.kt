package com.abhiank.offline

import android.content.Context
import android.graphics.Color
import android.net.Uri
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import com.mapbox.geojson.Point
import com.mapbox.mapboxsdk.camera.CameraUpdateFactory
import com.mapbox.mapboxsdk.geometry.LatLng
import com.mapbox.mapboxsdk.geometry.LatLngBounds
import com.mapbox.mapboxsdk.maps.MapboxMap
import com.mapbox.mapboxsdk.style.layers.LineLayer
import com.mapbox.mapboxsdk.style.layers.PropertyFactory.iconAllowOverlap
import com.mapbox.mapboxsdk.style.layers.PropertyFactory.iconIgnorePlacement
import com.mapbox.mapboxsdk.style.layers.PropertyFactory.iconImage
import com.mapbox.mapboxsdk.style.layers.PropertyFactory.iconSize
import com.mapbox.mapboxsdk.style.layers.PropertyFactory.lineColor
import com.mapbox.mapboxsdk.style.layers.PropertyFactory.lineOpacity
import com.mapbox.mapboxsdk.style.layers.PropertyFactory.lineWidth
import com.mapbox.mapboxsdk.style.layers.SymbolLayer
import com.mapbox.mapboxsdk.style.sources.GeoJsonSource
import java.io.InputStream
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory

class GpxRouteRenderer(
    private val context: Context,
    private val map: MapboxMap
) {

    fun loadFromUri(uri: Uri) {
        try {
            context.contentResolver.openInputStream(uri)?.use { inputStream ->
                val points = parseGpx(inputStream)
                if (points.isNotEmpty()) {
                    drawRoute(points)
                } else {
                    context.toast("No track points found in GPX")
                }
            } ?: context.toast("Unable to read GPX file")
        } catch (e: Exception) {
            Log.e("GPX", "Error loading GPX: ${e.message}", e)
            context.toast("Error loading GPX file")
        }
    }

    private fun parseGpx(inputStream: InputStream): List<LatLng> {
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

    private fun drawRoute(points: List<LatLng>) {
        map.getStyle { style ->
            style.removeLayer(GPX_LAYER_ID)
            style.removeSource(GPX_SOURCE_ID)
            style.removeImage(START_ICON_ID)
            style.removeImage(END_ICON_ID)
            style.removeLayer(START_LAYER_ID)
            style.removeLayer(END_LAYER_ID)
            style.removeSource(START_SOURCE_ID)
            style.removeSource(END_SOURCE_ID)

            val linePoints = points.map { Point.fromLngLat(it.longitude, it.latitude) }
            val lineString = com.mapbox.geojson.LineString.fromLngLats(linePoints)

            style.addSource(GeoJsonSource(GPX_SOURCE_ID, lineString))

            style.addLayer(
                LineLayer(GPX_LAYER_ID, GPX_SOURCE_ID).withProperties(
                    lineColor(Color.RED),
                    lineWidth(5f),
                    lineOpacity(1f)
                )
            )

            if (points.size >= 2) {
                val startPoint = Point.fromLngLat(points.first().longitude, points.first().latitude)
                val endPoint = Point.fromLngLat(points.last().longitude, points.last().latitude)

                val startDrawable = ContextCompat.getDrawable(context, android.R.drawable.presence_online)
                val endDrawable = ContextCompat.getDrawable(context, android.R.drawable.presence_busy)

                if (startDrawable != null && endDrawable != null) {
                    style.addImage(START_ICON_ID, startDrawable.toBitmap())
                    style.addImage(END_ICON_ID, endDrawable.toBitmap())

                    style.addSource(GeoJsonSource(START_SOURCE_ID, startPoint))
                    style.addSource(GeoJsonSource(END_SOURCE_ID, endPoint))

                    style.addLayer(
                        SymbolLayer(START_LAYER_ID, START_SOURCE_ID).withProperties(
                            iconImage(START_ICON_ID),
                            iconAllowOverlap(true),
                            iconIgnorePlacement(true),
                            iconSize(1.2f)
                        )
                    )

                    style.addLayer(
                        SymbolLayer(END_LAYER_ID, END_SOURCE_ID).withProperties(
                            iconImage(END_ICON_ID),
                            iconAllowOverlap(true),
                            iconIgnorePlacement(true),
                            iconSize(1.2f)
                        )
                    )
                }
            }

            if (points.size > 1) {
                val routeBounds = LatLngBounds.Builder().apply {
                    points.forEach { include(it) }
                }.build()
                map.easeCamera(CameraUpdateFactory.newLatLngBounds(routeBounds, 80), 800)
            } else {
                map.easeCamera(CameraUpdateFactory.newLatLngZoom(points.first(), 15.0), 600)
            }

            context.toast("GPX route loaded")
        }
    }

    companion object {
        private const val GPX_SOURCE_ID = "gpx-source"
        private const val GPX_LAYER_ID = "gpx-layer"
        private const val START_SOURCE_ID = "start-source"
        private const val END_SOURCE_ID = "end-source"
        private const val START_LAYER_ID = "start-layer"
        private const val END_LAYER_ID = "end-layer"
        private const val START_ICON_ID = "start-icon"
        private const val END_ICON_ID = "end-icon"
    }
}
