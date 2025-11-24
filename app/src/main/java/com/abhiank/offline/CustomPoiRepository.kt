package com.abhiank.offline

import android.content.Context
import com.mapbox.geojson.Feature
import com.mapbox.geojson.FeatureCollection
import com.mapbox.geojson.Point
import java.io.File

class CustomPoiRepository(context: Context) {
    companion object {
        const val SOURCE_ID = "user-poi-source"
        const val LAYER_ID = "user-poi-layer"
        const val ICON_ID = "user-poi-icon"
        private const val STORAGE_FILE_NAME = "user_pois.geojson"
        private const val PROPERTY_TITLE = "title"
        private const val PROPERTY_DESCRIPTION = "description"
    }

    private val storageFile: File = File(context.filesDir, STORAGE_FILE_NAME)

    fun loadPois(): FeatureCollection {
        if (!storageFile.exists()) {
            return FeatureCollection.fromFeatures(emptyList())
        }
        return runCatching { FeatureCollection.fromJson(storageFile.readText()) }.
            getOrDefault(FeatureCollection.fromFeatures(emptyList()))
    }

    fun savePois(featureCollection: FeatureCollection) {
        storageFile.writeText(featureCollection.toJson())
    }

    fun addPoi(point: Point, title: String, description: String?): FeatureCollection {
        val existing = loadPois().features()?.toMutableList() ?: mutableListOf()
        val newFeature = Feature.fromGeometry(point).apply {
            addStringProperty(PROPERTY_TITLE, title)
            description?.takeIf { it.isNotBlank() }?.let { addStringProperty(PROPERTY_DESCRIPTION, it) }
        }
        existing.add(newFeature)
        return FeatureCollection.fromFeatures(existing).also { savePois(it) }
    }
}
