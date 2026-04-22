package com.example.experience66hello

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.app.ActivityCompat

/**
 * Re-registers geofences after a device reboot. Google Play Services clears geofences on boot;
 * without this, POI enter notifications would not fire until the user opens the app again.
 */
class GeofenceBootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        val isRestoreTrigger = action == Intent.ACTION_BOOT_COMPLETED ||
            action == Intent.ACTION_LOCKED_BOOT_COMPLETED ||
            action == Intent.ACTION_MY_PACKAGE_REPLACED
        if (!isRestoreTrigger) return
        val app = context.applicationContext
        if (ActivityCompat.checkSelfPermission(app, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) {
            Log.d(TAG, "Fine location not granted after boot; skipping geofence restore")
            return
        }
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q &&
            ActivityCompat.checkSelfPermission(app, Manifest.permission.ACCESS_BACKGROUND_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) {
            Log.w(
                TAG,
                "ACCESS_BACKGROUND_LOCATION not granted after boot; geofence re-add may not receive transitions when the app is not running"
            )
        }
        try {
            val repo = Route66DatabaseRepository(app)
            repo.loadDatabase()
            ArizonaLandmarks.initialize(repo.getAllLandmarks())
            GeofenceManager(app).registerAllGeofences(
                onSuccess = { Log.d(TAG, "Geofences restored after boot") },
                onFailure = { e -> Log.e(TAG, "Geofence restore failed: ${e.message}", e) }
            )
        } catch (e: Exception) {
            Log.e(TAG, "Boot geofence setup failed", e)
        }
    }

    companion object {
        private const val TAG = "GeofenceBootReceiver"
    }
}
