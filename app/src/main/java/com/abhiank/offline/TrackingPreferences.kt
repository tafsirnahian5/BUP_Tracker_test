package com.abhiank.offline

import android.content.SharedPreferences
import com.google.android.gms.location.Priority

object TrackingPreferences {
    const val KEY_GPS_ACCURACY = "gps_accuracy_mode"
    const val KEY_POWER_SAVE = "power_save_mode"

    private const val DEFAULT_GPS_ACCURACY = "high"
    private const val POWER_SAVE_INTERVAL_MS = 5000L
    private const val POWER_SAVE_FASTEST_INTERVAL_MS = 2500L
    private const val NORMAL_INTERVAL_MS = 1000L
    private const val NORMAL_FASTEST_INTERVAL_MS = 500L

    enum class Accuracy(val prefValue: String, val priority: Int, val lockThresholdMeters: Float) {
        HIGH("high", Priority.PRIORITY_HIGH_ACCURACY, 25f),
        BALANCED("balanced", Priority.PRIORITY_BALANCED_POWER_ACCURACY, 50f),
        LOW("low", Priority.PRIORITY_LOW_POWER, 100f)
    }

    fun getGpsAccuracy(preferences: SharedPreferences): Accuracy {
        val value = preferences.getString(KEY_GPS_ACCURACY, DEFAULT_GPS_ACCURACY)
        return Accuracy.values().firstOrNull { it.prefValue == value } ?: Accuracy.HIGH
    }

    fun isPowerSaveEnabled(preferences: SharedPreferences): Boolean =
        preferences.getBoolean(KEY_POWER_SAVE, false)

    fun locationIntervalMillis(powerSaveEnabled: Boolean): Long =
        if (powerSaveEnabled) POWER_SAVE_INTERVAL_MS else NORMAL_INTERVAL_MS

    fun locationFastestIntervalMillis(powerSaveEnabled: Boolean): Long =
        if (powerSaveEnabled) POWER_SAVE_FASTEST_INTERVAL_MS else NORMAL_FASTEST_INTERVAL_MS
}
