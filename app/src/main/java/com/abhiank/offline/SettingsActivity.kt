package com.abhiank.offline

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.SwitchPreferenceCompat

class SettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
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
            screen.title = getString(R.string.poi_settings_title)
            PoiLayerPreferences.layers.forEach { layer ->
                val preference = SwitchPreferenceCompat(context).apply {
                    key = PoiLayerPreferences.keyForLayer(layer.id)
                    setDefaultValue(true)
                    title = getString(layer.titleRes)
                }
                screen.addPreference(preference)
            }
            preferenceScreen = screen
        }
    }
}
