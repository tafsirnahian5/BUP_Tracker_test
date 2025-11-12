package com.abhiank.offline

import android.content.SharedPreferences

object PoiLayerPreferences {
    data class Layer(val id: String, val titleRes: Int)

    private const val PREF_PREFIX = "poi_layer_"

    val layers = listOf(
        Layer("poi-level-3", R.string.poi_layer_level_3),
        Layer("poi-level-2", R.string.poi_layer_level_2),
        Layer("poi-level-1", R.string.poi_layer_level_1),
        Layer("poi-other", R.string.poi_layer_other),
        Layer("poi-railway", R.string.poi_layer_railway),
        Layer("poi-transit", R.string.poi_layer_transit),
        Layer("poi-label", R.string.poi_layer_label),
        Layer("place-hamlet", R.string.poi_layer_hamlet),
        Layer("place-village", R.string.poi_layer_village),
        Layer("place-town", R.string.poi_layer_town),
        Layer("place-city", R.string.poi_layer_city),
        Layer("place-city-capital", R.string.poi_layer_city_capital),
        Layer("place-other", R.string.poi_layer_place_other)
    )

    val layerIds: Array<String> = layers.map { it.id }.toTypedArray()

    fun keyForLayer(layerId: String): String = "$PREF_PREFIX$layerId"

    fun isLayerEnabled(preferences: SharedPreferences, layerId: String): Boolean =
        preferences.getBoolean(keyForLayer(layerId), true)

    fun preferencePrefix(): String = PREF_PREFIX
}
