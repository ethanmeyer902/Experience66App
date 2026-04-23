package com.example.experience66hello

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.app.ActivityCompat
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingClient
import com.google.android.gms.location.GeofencingRequest
import com.google.android.gms.location.LocationServices

/**
 * Handles geofence registration and monitoring for Route 66 landmarks
 * Notifies the app when users enter, exit, or spend time near POIs
 */
class GeofenceManager(private val context: Context) {
    
    companion object {
        const val TAG = "GeofenceManager"
        private const val GEOFENCE_EXPIRATION_MS = Geofence.NEVER_EXPIRE
        private const val LOITERING_DELAY_MS = 30000 // Wait 30 seconds before triggering DWELL event
    }
    
    private val geofencingClient: GeofencingClient = LocationServices.getGeofencingClient(context)
    
    private val geofencePendingIntent: PendingIntent by lazy {
        val intent = Intent(context, GeofenceBroadcastReceiver::class.java).apply {
            setPackage(context.packageName)
        }
        PendingIntent.getBroadcast(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        )
    }
    
    /**
     * Registers all Route 66 landmarks as geofences
     * Limited to 100 geofences (Google's maximum) to prevent performance issues
     */
    fun registerAllGeofences(
        onSuccess: () -> Unit = {},
        onFailure: (Exception) -> Unit = {}
    ) {
        if (!hasLocationPermission()) {
            Log.e(TAG, "Location permission not granted")
            onFailure(SecurityException("Location permission required"))
            return
        }
        
        // Limit geofences to prevent performance issues (Google allows max 100 geofences)
        // Filter to Arizona landmarks and limit to 100
        val arizonaLandmarks = ArizonaLandmarks.landmarks.filter { landmark ->
            landmark.latitude in 31.0..37.0 && landmark.longitude in -115.0..-108.85
        }
        
        val landmarksToRegister = if (arizonaLandmarks.size > 100) {
            Log.w(TAG, "Too many landmarks (${arizonaLandmarks.size}), registering first 100 geofences")
            arizonaLandmarks.take(100)
        } else {
            arizonaLandmarks
        }
        
        val geofenceList = landmarksToRegister.map { landmark ->
            createGeofence(landmark)
        }
        if (geofenceList.isEmpty()) {
            Log.e(TAG, "No landmarks available -> not registering geofences yet.")
            onFailure(IllegalStateException("No landmarks loaded. Geofences not registered."))
            return
        }
        val geofencingRequest = GeofencingRequest.Builder()
            .setInitialTrigger(GeofencingRequest.INITIAL_TRIGGER_ENTER)
            .addGeofences(geofenceList)
            .build()
        
        try {
            // Remove previously registered geofences first so stale/demo entries are not kept.
            geofencingClient.removeGeofences(geofencePendingIntent)
                .addOnCompleteListener {
                    geofencingClient.addGeofences(geofencingRequest, geofencePendingIntent)
                        .addOnSuccessListener {
                            Log.d(TAG, "Successfully registered ${geofenceList.size} geofences")
                            onSuccess()
                        }
                        .addOnFailureListener { e ->
                            Log.e(TAG, "Failed to register geofences: ${e.message}")
                            onFailure(e)
                        }
                }
        } catch (e: SecurityException) {
            Log.e(TAG, "Security exception: ${e.message}")
            onFailure(e)
        }
    }
    
    /**
     * Creates a geofence object for a landmark
     * Triggers when user enters, exits, or spends time in the area
     */
    private fun createGeofence(landmark: Route66Landmark): Geofence {
        return Geofence.Builder()
            .setRequestId(landmark.id)
            .setCircularRegion(
                landmark.latitude,
                landmark.longitude,
                landmark.radiusMeters
            )
            .setExpirationDuration(GEOFENCE_EXPIRATION_MS)
            .setTransitionTypes(
                Geofence.GEOFENCE_TRANSITION_ENTER or
                Geofence.GEOFENCE_TRANSITION_EXIT or
                Geofence.GEOFENCE_TRANSITION_DWELL
            )
            .setLoiteringDelay(LOITERING_DELAY_MS)
            .build()
    }
    
    /**
     * Removes all registered geofences
     * Useful when the app is closing or needs to reset
     */
    fun removeAllGeofences(
        onSuccess: () -> Unit = {},
        onFailure: (Exception) -> Unit = {}
    ) {
        geofencingClient.removeGeofences(geofencePendingIntent)
            .addOnSuccessListener {
                Log.d(TAG, "All geofences removed")
                onSuccess()
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "Failed to remove geofences: ${e.message}")
                onFailure(e)
            }
    }
    
    private fun hasLocationPermission(): Boolean {
        return ActivityCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
    }
}
