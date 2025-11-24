package com.abhiank.offline

import android.Manifest
import android.app.Activity
import android.os.Build
import androidx.appcompat.app.AlertDialog
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.preference.PreferenceManager

object PermissionHelper {
    private const val PREF_PERMISSION_EDUCATION_SHOWN = "pref_permission_education_shown"

    fun hasForegroundLocation(activity: Activity): Boolean =
        ContextCompat.checkSelfPermission(
            activity,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED

    fun hasBackgroundLocation(activity: Activity): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return true
        return ContextCompat.checkSelfPermission(
            activity,
            Manifest.permission.ACCESS_BACKGROUND_LOCATION
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
    }

    fun requestForegroundLocation(activity: Activity, requestCode: Int) {
        ActivityCompat.requestPermissions(
            activity,
            arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            ),
            requestCode
        )
    }

    fun requestBackgroundLocation(activity: Activity, requestCode: Int) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        ActivityCompat.requestPermissions(
            activity,
            arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION),
            requestCode
        )
    }

    fun maybeShowPermissionEducation(activity: Activity, onContinue: () -> Unit) {
        val preferences = PreferenceManager.getDefaultSharedPreferences(activity)
        if (preferences.getBoolean(PREF_PERMISSION_EDUCATION_SHOWN, false)) {
            onContinue()
            return
        }

        AlertDialog.Builder(activity)
            .setTitle(R.string.permission_education_title)
            .setMessage(R.string.permission_education_message)
            .setPositiveButton(R.string.permission_education_continue) { _, _ ->
                preferences.edit().putBoolean(PREF_PERMISSION_EDUCATION_SHOWN, true).apply()
                onContinue()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }
}
