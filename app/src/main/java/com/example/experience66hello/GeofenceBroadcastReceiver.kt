package com.example.experience66hello

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.os.Build
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingEvent
import java.util.Locale

/**
 * Broadcast receiver for geofence transition events
 */
class GeofenceBroadcastReceiver : BroadcastReceiver() {

    companion object {
        const val TAG = "GeofenceReceiver"
        const val ACTION_GEOFENCE_EVENT = "com.example.experience66hello.ACTION_GEOFENCE_EVENT"
        const val EXTRA_LANDMARK_ID = "landmark_id"
        const val EXTRA_LANDMARK_NAME = "landmark_name"
        const val EXTRA_TRANSITION_TYPE = "transition_type"
        const val EXTRA_TIMESTAMP = "timestamp"
        const val EXTRA_TRIGGER_LAT = "trigger_lat"
        const val EXTRA_TRIGGER_LNG = "trigger_lng"
        const val EXTRA_TRIGGER_TO_POI_METERS = "trigger_to_poi_meters"
        const val EXTRA_NOTIFICATION_ACTION = "notification_action"

        const val ACTION_SHOW = "show"
        const val ACTION_LISTEN = "listen"
        const val ACTION_MORE = "more"
        const val ACTION_NAVIGATE = "navigate"

        private const val CHANNEL_ID = "geofence_poi_alerts"
        private const val CHANNEL_NAME = "Route 66 POI Alerts"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val pendingResult = goAsync()
        val appContext = context.applicationContext
        try {
            ensureLandmarksLoaded(appContext)
            val geofencingEvent = GeofencingEvent.fromIntent(intent)

            if (geofencingEvent == null) {
                Log.e(TAG, "GeofencingEvent is null")
                return
            }

            if (geofencingEvent.hasError()) {
                Log.e(TAG, "Geofencing error: ${geofencingEvent.errorCode}")
                return
            }

        val geofenceTransition = geofencingEvent.geofenceTransition
        val triggeringGeofences = geofencingEvent.triggeringGeofences ?: return
        val triggerLocation = geofencingEvent.triggeringLocation

        val transitionType = when (geofenceTransition) {
            Geofence.GEOFENCE_TRANSITION_ENTER -> "ENTER"
            Geofence.GEOFENCE_TRANSITION_EXIT -> "EXIT"
            Geofence.GEOFENCE_TRANSITION_DWELL -> "DWELL"
            else -> "UNKNOWN"
        }

        for (geofence in triggeringGeofences) {
            val landmarkId = geofence.requestId
            val landmark = ArizonaLandmarks.findById(landmarkId)
            val triggerToPoiMeters = computeTriggerToPoiDistanceMeters(landmark, triggerLocation)

            Log.d(TAG, "Geofence $transitionType: ${landmark?.name ?: landmarkId}")

            // Broadcast the event to MainActivity with explicit package
            val broadcastIntent = Intent(ACTION_GEOFENCE_EVENT).apply {
                setPackage(context.packageName) // Explicit package for Android 14+
                putExtra(EXTRA_LANDMARK_ID, landmarkId)
                putExtra(EXTRA_LANDMARK_NAME, landmark?.name ?: "Unknown")
                putExtra(EXTRA_TRANSITION_TYPE, transitionType)
                putExtra(EXTRA_TIMESTAMP, System.currentTimeMillis())
                if (triggerLocation != null) {
                    putExtra(EXTRA_TRIGGER_LAT, triggerLocation.latitude)
                    putExtra(EXTRA_TRIGGER_LNG, triggerLocation.longitude)
                }
                if (triggerToPoiMeters != null) {
                    putExtra(EXTRA_TRIGGER_TO_POI_METERS, triggerToPoiMeters)
                }
            }
            context.sendBroadcast(broadcastIntent)

            if (transitionType == "ENTER") {
                showPoiUpAheadNotification(
                    context = appContext,
                    landmarkId = landmarkId,
                    landmarkName = landmark?.name ?: "Unknown POI",
                    triggerToPoiMeters = triggerToPoiMeters,
                    triggerLocation = triggerLocation
                )
            }
        }
        } finally {
            pendingResult.finish()
        }
    }

    /**
     * Geofence delivery can start the process before [MainActivity] runs; [ArizonaLandmarks] would
     * otherwise be empty and notifications would show wrong distance / "Unknown" names.
     */
    private fun ensureLandmarksLoaded(context: Context) {
        if (ArizonaLandmarks.landmarks.isNotEmpty()) return
        try {
            val repo = Route66DatabaseRepository(context)
            repo.loadDatabase()
            ArizonaLandmarks.initialize(repo.getAllLandmarks())
            Log.d(TAG, "Loaded ${ArizonaLandmarks.landmarks.size} landmarks inside geofence receiver")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load landmarks for geofence notification", e)
        }
    }

    private fun showPoiUpAheadNotification(
        context: Context,
        landmarkId: String,
        landmarkName: String,
        triggerToPoiMeters: Float?,
        triggerLocation: Location?
    ) {
        ensureNotificationChannel(context)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ActivityCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            Log.w(TAG, "POST_NOTIFICATIONS not granted; skipping background notification")
            return
        }

        val distanceText = formatDistance(triggerToPoiMeters ?: 0f)
        // User story: [POI] up ahead [distance]
        val contentText = "$landmarkName up ahead $distanceText"

        val showIntent = createMainActivityIntent(
            context,
            landmarkId,
            landmarkName,
            ACTION_SHOW,
            triggerLocation,
            triggerToPoiMeters
        )
        val listenIntent = createMainActivityIntent(
            context,
            landmarkId,
            landmarkName,
            ACTION_LISTEN,
            triggerLocation,
            triggerToPoiMeters
        )
        val moreIntent = createMainActivityIntent(
            context,
            landmarkId,
            landmarkName,
            ACTION_MORE,
            triggerLocation,
            triggerToPoiMeters
        )
        val navigateIntent = createMainActivityIntent(
            context,
            landmarkId,
            landmarkName,
            ACTION_NAVIGATE,
            triggerLocation,
            triggerToPoiMeters
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Historical point up-ahead")
            .setContentText(contentText)
            .setStyle(NotificationCompat.BigTextStyle().bigText(contentText))
            .setTicker(contentText)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_NAVIGATION)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setDefaults(android.app.Notification.DEFAULT_SOUND or android.app.Notification.DEFAULT_VIBRATE)
            .setAutoCancel(true)
            .setOnlyAlertOnce(false)
            .setContentIntent(showIntent)
            .addAction(0, "Listen", listenIntent)
            .addAction(0, "More info", moreIntent)
            .addAction(0, "Navigate", navigateIntent)
            .build()

        try {
            // Use a non-negative id (some OEMs mishandle negative notification ids).
            val notifyId = landmarkId.hashCode() and 0x7fff_ffff
            NotificationManagerCompat.from(context).notify(notifyId, notification)
        } catch (e: SecurityException) {
            Log.e(TAG, "Cannot post geofence notification", e)
        }
    }

    private fun createMainActivityIntent(
        context: Context,
        landmarkId: String,
        landmarkName: String,
        action: String,
        triggerLocation: Location?,
        triggerToPoiMeters: Float?
    ): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(EXTRA_LANDMARK_ID, landmarkId)
            putExtra(EXTRA_LANDMARK_NAME, landmarkName)
            putExtra(EXTRA_NOTIFICATION_ACTION, action)
            putExtra(EXTRA_TRANSITION_TYPE, "ENTER")
            putExtra(EXTRA_TIMESTAMP, System.currentTimeMillis())
            if (triggerLocation != null) {
                putExtra(EXTRA_TRIGGER_LAT, triggerLocation.latitude)
                putExtra(EXTRA_TRIGGER_LNG, triggerLocation.longitude)
            }
            if (triggerToPoiMeters != null) {
                putExtra(EXTRA_TRIGGER_TO_POI_METERS, triggerToPoiMeters)
            }
        }
        val requestCode = (landmarkId + action).hashCode()
        return PendingIntent.getActivity(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun ensureNotificationChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val existing = manager.getNotificationChannel(CHANNEL_ID)
        if (existing != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            CHANNEL_NAME,
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "POI alerts while traveling on Route 66"
        }
        manager.createNotificationChannel(channel)
    }

    private fun computeTriggerToPoiDistanceMeters(
        landmark: Route66Landmark?,
        triggerLocation: Location?
    ): Float? {
        if (landmark == null || triggerLocation == null) return null
        val result = FloatArray(1)
        Location.distanceBetween(
            triggerLocation.latitude,
            triggerLocation.longitude,
            landmark.latitude,
            landmark.longitude,
            result
        )
        return result[0]
    }

    private fun formatDistance(distanceMeters: Float): String {
        val feet = distanceMeters * 3.28084f
        val miles = distanceMeters / 1609.344f
        return if (distanceMeters < 160.9344f) {
            "${feet.toInt()} ft"
        } else {
            String.format(Locale.US, "%.2f mi", miles)
        }
    }
}

