package com.abhiank.offline

import com.mapbox.mapboxsdk.geometry.LatLng
import com.mapbox.mapboxsdk.geometry.LatLngBounds

val DEFAULT_CENTER = LatLng(23.839321875320906, 90.35720721568804)

val DEFAULT_BOUNDS: LatLngBounds = LatLngBounds.Builder()
    .include(LatLng(DEFAULT_CENTER.latitude + 0.05, DEFAULT_CENTER.longitude - 0.05))
    .include(LatLng(DEFAULT_CENTER.latitude - 0.05, DEFAULT_CENTER.longitude + 0.05))
    .build()
