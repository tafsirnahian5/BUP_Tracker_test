package com.abhiank.offline

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.preference.ListPreference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.SwitchPreferenceCompat

class SettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        title = getString(R.string.settings_title)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        if (savedInstanceState == null) {
            supportFragmentManager
                .beginTransaction()
                .replace(R.id.settingsContainer, SettingsFragment())
                .commit()
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    class SettingsFragment : PreferenceFragmentCompat() {
        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            val context = preferenceManager.context
            val screen = preferenceManager.createPreferenceScreen(context)

            screen.title = getString(R.string.settings_title)

            val locationCategory = PreferenceCategory(context).apply {
                title = getString(R.string.settings_category_location)
            }
            screen.addPreference(locationCategory)

            val gpsAccuracyPreference = ListPreference(context).apply {
                key = TrackingPreferences.KEY_GPS_ACCURACY
                setDefaultValue(TrackingPreferences.Accuracy.HIGH.prefValue)
                title = getString(R.string.settings_gps_accuracy_title)
                entries = arrayOf(
                    getString(R.string.settings_gps_accuracy_high),
                    getString(R.string.settings_gps_accuracy_balanced),
                    getString(R.string.settings_gps_accuracy_low)
                )
                entryValues = TrackingPreferences.Accuracy.values()
                    .map { it.prefValue }
                    .toTypedArray()
                summaryProvider = ListPreference.SimpleSummaryProvider.getInstance()
            }
            locationCategory.addPreference(gpsAccuracyPreference)

            val powerSavePreference = SwitchPreferenceCompat(context).apply {
                key = TrackingPreferences.KEY_POWER_SAVE
                setDefaultValue(false)
                title = getString(R.string.settings_power_save_title)
                summary = getString(R.string.settings_power_save_summary)
            }
            locationCategory.addPreference(powerSavePreference)

            val poiCategory = PreferenceCategory(context).apply {
                title = getString(R.string.poi_settings_title)
            }
            screen.addPreference(poiCategory)

            PoiLayerPreferences.layers.forEach { layer ->
                val preference = SwitchPreferenceCompat(context).apply {
                    key = PoiLayerPreferences.keyForLayer(layer.id)
                    setDefaultValue(true)
                    title = getString(layer.titleRes)
                }
                poiCategory.addPreference(preference)
            }

            preferenceScreen = screen
        }
    }
}
