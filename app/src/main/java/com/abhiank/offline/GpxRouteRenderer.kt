package com.abhiank.offline

import android.content.Context
import android.graphics.Color
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import com.mapbox.geojson.Feature
import com.mapbox.geojson.FeatureCollection
import com.mapbox.geojson.LineString
import com.mapbox.geojson.Point
import com.mapbox.mapboxsdk.camera.CameraUpdateFactory
import com.mapbox.mapboxsdk.geometry.LatLng
import com.mapbox.mapboxsdk.geometry.LatLngBounds
import com.mapbox.mapboxsdk.maps.MapboxMap
import com.mapbox.mapboxsdk.style.layers.LineLayer
import com.mapbox.mapboxsdk.style.layers.Property
import com.mapbox.mapboxsdk.style.layers.PropertyFactory.iconAllowOverlap
import com.mapbox.mapboxsdk.style.layers.PropertyFactory.iconIgnorePlacement
import com.mapbox.mapboxsdk.style.layers.PropertyFactory.iconImage
import com.mapbox.mapboxsdk.style.layers.PropertyFactory.iconSize
import com.mapbox.mapboxsdk.style.layers.PropertyFactory.lineColor
import com.mapbox.mapboxsdk.style.layers.PropertyFactory.lineOpacity
import com.mapbox.mapboxsdk.style.layers.PropertyFactory.lineWidth
import com.mapbox.mapboxsdk.style.layers.SymbolLayer
import com.mapbox.mapboxsdk.style.sources.GeoJsonSource
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.IOException
import java.io.InputStream
import kotlin.math.max

class GpxRouteRenderer(
    private val context: Context,
    private val map: MapboxMap
) {

    private val routes = mutableListOf<RouteData>()

    fun loadFromAssets(assetNames: List<String>) {
        val parsedRoutes = assetNames.mapNotNull { assetName ->
            try {
                context.assets.open(assetName).use { stream ->
                    parseGpx(stream, assetName.substringAfterLast('/'))
                }
            } catch (io: IOException) {
                Log.e("GPX", "Unable to read asset $assetName", io)
                null
            }
        }

        if (parsedRoutes.isEmpty()) {
            context.toast("No GPX routes found in assets")
            return
        }

        routes.clear()
        routes.addAll(parsedRoutes)
        renderRoutes(adjustCamera = true)
        context.toast("Loaded ${routes.size} route${if (routes.size > 1) "s" else ""}")
    }

    private fun renderRoutes(adjustCamera: Boolean) {
        if (routes.isEmpty()) {
            return
        }

        map.getStyle { style ->
            val routeFeatures = routes.map { route ->
                Feature.fromGeometry(LineString.fromLngLats(route.points.map { point ->
                    Point.fromLngLat(point.longitude, point.latitude)
                })).apply {
                    addStringProperty(ROUTE_NAME_PROPERTY, route.name)
                    addNumberProperty(ROUTE_DISTANCE_KM_PROPERTY, route.distanceKm)
                }
            }

            val startFeatures = routes.mapNotNull { route ->
                route.points.firstOrNull()?.let { firstPoint ->
                    Feature.fromGeometry(Point.fromLngLat(firstPoint.longitude, firstPoint.latitude)).apply {
                        addStringProperty(ROUTE_NAME_PROPERTY, route.name)
                    }
                }
            }

            val endFeatures = routes.mapNotNull { route ->
                route.points.lastOrNull()?.let { lastPoint ->
                    Feature.fromGeometry(Point.fromLngLat(lastPoint.longitude, lastPoint.latitude)).apply {
                        addStringProperty(ROUTE_NAME_PROPERTY, route.name)
                    }
                }
            }

            val routeSource = style.getSourceAs<GeoJsonSource>(ROUTE_SOURCE_ID)
            if (routeSource == null) {
                style.addSource(GeoJsonSource(ROUTE_SOURCE_ID, FeatureCollection.fromFeatures(routeFeatures)))
            } else {
                routeSource.setGeoJson(FeatureCollection.fromFeatures(routeFeatures))
            }

            val startSource = style.getSourceAs<GeoJsonSource>(START_SOURCE_ID)
            if (startSource == null) {
                style.addSource(GeoJsonSource(START_SOURCE_ID, FeatureCollection.fromFeatures(startFeatures)))
            } else {
                startSource.setGeoJson(FeatureCollection.fromFeatures(startFeatures))
            }

            val endSource = style.getSourceAs<GeoJsonSource>(END_SOURCE_ID)
            if (endSource == null) {
                style.addSource(GeoJsonSource(END_SOURCE_ID, FeatureCollection.fromFeatures(endFeatures)))
            } else {
                endSource.setGeoJson(FeatureCollection.fromFeatures(endFeatures))
            }

            if (style.getImage(START_ICON_ID) == null) {
                ContextCompat.getDrawable(context, android.R.drawable.presence_online)?.let {
                    style.addImage(START_ICON_ID, it.toBitmap())
                }
            }
            if (style.getImage(END_ICON_ID) == null) {
                ContextCompat.getDrawable(context, android.R.drawable.presence_busy)?.let {
                    style.addImage(END_ICON_ID, it.toBitmap())
                }
            }

            if (style.getLayer(ROUTE_LAYER_ID) == null) {
                style.addLayer(
                    LineLayer(ROUTE_LAYER_ID, ROUTE_SOURCE_ID).withProperties(
                        lineColor(Color.parseColor("#d84315")),
                        lineWidth(5f),
                        lineOpacity(0.95f),
                        lineCap(Property.LINE_CAP_ROUND),
                        lineJoin(Property.LINE_JOIN_ROUND)
                    )
                )
            }

            if (style.getLayer(START_LAYER_ID) == null) {
                style.addLayer(
                    SymbolLayer(START_LAYER_ID, START_SOURCE_ID).withProperties(
                        iconImage(START_ICON_ID),
                        iconAllowOverlap(true),
                        iconIgnorePlacement(true),
                        iconSize(1.15f)
                    )
                )
            }

            if (style.getLayer(END_LAYER_ID) == null) {
                style.addLayer(
                    SymbolLayer(END_LAYER_ID, END_SOURCE_ID).withProperties(
                        iconImage(END_ICON_ID),
                        iconAllowOverlap(true),
                        iconIgnorePlacement(true),
                        iconSize(1.15f)
                    )
                )
            }

            if (adjustCamera) {
                adjustCameraToRoutes()
            }
        }
    }

    private fun adjustCameraToRoutes() {
        val allPoints = routes.flatMap { it.points }
        if (allPoints.isEmpty()) {
            return
        }

        if (allPoints.size == 1) {
            map.easeCamera(CameraUpdateFactory.newLatLngZoom(allPoints.first(), 15.0), 800)
            return
        }

        val boundsBuilder = LatLngBounds.Builder()
        allPoints.forEach { boundsBuilder.include(it) }
        val bounds = boundsBuilder.build()

        val padding = (max(
            context.resources.displayMetrics.widthPixels,
            context.resources.displayMetrics.heightPixels
        ) * 0.12).toInt()

        map.easeCamera(CameraUpdateFactory.newLatLngBounds(bounds, padding), 1000)
    }

    private fun parseGpx(inputStream: InputStream, fallbackName: String): RouteData? {
        val parser = XmlPullParserFactory.newInstance().newPullParser().apply {
            setInput(inputStream, null)
        }

        val points = mutableListOf<LatLng>()
        var routeName: String? = null
        var captureName = false

        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> {
                    when (parser.name) {
                        "trkpt" -> {
                            val lat = parser.getAttributeValue(null, "lat")?.toDoubleOrNull()
                            val lon = parser.getAttributeValue(null, "lon")?.toDoubleOrNull()
                            if (lat != null && lon != null) {
                                points.add(LatLng(lat, lon))
                            }
                        }
                        "name" -> if (routeName == null) {
                            captureName = true
                        }
                    }
                }

                XmlPullParser.TEXT -> if (captureName && routeName == null) {
                    routeName = parser.text?.trim()?.takeIf { it.isNotEmpty() }
                }

                XmlPullParser.END_TAG -> if (parser.name == "name") {
                    captureName = false
                }
            }
            event = parser.next()
        }

        if (points.isEmpty()) {
            return null
        }

        return RouteData(routeName ?: fallbackName, points)
    }

    private data class RouteData(
        val name: String,
        val points: List<LatLng>
    ) {
        val distanceKm: Double = calculateDistanceKm(points)

        companion object {
            private fun calculateDistanceKm(points: List<LatLng>): Double {
                if (points.size < 2) return 0.0
                var total = 0.0
                for (index in 0 until points.lastIndex) {
                    total += points[index].distanceTo(points[index + 1])
                }
                return total / 1000.0
            }
        }
    }

    companion object {
        const val ROUTE_SOURCE_ID = "gpx-route-source"
        const val ROUTE_LAYER_ID = "gpx-route-layer"
        const val START_SOURCE_ID = "gpx-start-source"
        const val END_SOURCE_ID = "gpx-end-source"
        const val START_LAYER_ID = "gpx-start-layer"
        const val END_LAYER_ID = "gpx-end-layer"
        const val ROUTE_NAME_PROPERTY = "route_name"
        const val ROUTE_DISTANCE_KM_PROPERTY = "route_distance_km"
        private const val START_ICON_ID = "gpx-start-icon"
        private const val END_ICON_ID = "gpx-end-icon"
    }
}
