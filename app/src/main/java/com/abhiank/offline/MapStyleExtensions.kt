package com.abhiank.offline

import android.graphics.Color
import com.mapbox.geojson.Point
import com.mapbox.geojson.Polygon
import com.mapbox.mapboxsdk.geometry.LatLng
import com.mapbox.mapboxsdk.geometry.LatLngBounds
import com.mapbox.mapboxsdk.maps.MapboxMap
import com.mapbox.mapboxsdk.style.layers.FillLayer
import com.mapbox.mapboxsdk.style.layers.PropertyFactory.fillColor
import com.mapbox.mapboxsdk.style.layers.PropertyFactory.fillOpacity
import com.mapbox.mapboxsdk.style.sources.GeoJsonSource
import com.mapbox.mapboxsdk.maps.Style

fun showBoundsArea(
    loadedMapStyle: Style,
    bounds: LatLngBounds,
    color: Int = Color.RED,
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
