package com.abhiank.offline

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class SplashActivity : AppCompatActivity() {

    private val locationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
            val granted = permissions.values.all { it }
            if (granted) {
                requestBackgroundPermissionIfNeeded()
            } else {
                toast(getString(R.string.permission_explanation))
            }
        }

    private val backgroundPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            finishOnboarding()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val preferences = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val onboardingComplete = preferences.getBoolean(KEY_ONBOARDING_COMPLETE, false)
        val shouldShowOnboarding = !onboardingComplete || !hasLocationPermission()
        if (!shouldShowOnboarding) {
            goToChooser()
            return
        }
        setContentView(R.layout.activity_splash)
        findViewById<Button>(R.id.getStartedButton).setOnClickListener {
            if (hasLocationPermission()) {
                requestBackgroundPermissionIfNeeded()
            } else {
                requestLocationPermissions()
            }
        }
    }

    private fun requestLocationPermissions() {
        locationPermissionLauncher.launch(
            arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            )
        )
    }

    private fun requestBackgroundPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_BACKGROUND_LOCATION
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            backgroundPermissionLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        } else {
            finishOnboarding()
        }
    }

    private fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

    private fun finishOnboarding() {
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_ONBOARDING_COMPLETE, true)
            .apply()
        goToChooser()
    }

    private fun goToChooser() {
        startActivity(Intent(this, ChooserActivity::class.java))
        finish()
    }

    companion object {
        private const val PREFS_NAME = "bup_tracker_prefs"
        private const val KEY_ONBOARDING_COMPLETE = "onboarding_complete"
    }
}
