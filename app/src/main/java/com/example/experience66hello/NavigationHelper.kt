package com.example.experience66hello

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.mapbox.geojson.Point

object NavigationHelper {

    fun startNavigation(context: Context, landmark: Route66Landmark) {
        startNavigation(
            context = context,
            destination = Point.fromLngLat(landmark.longitude, landmark.latitude),
            destinationName = landmark.name
        )
    }

    fun startNavigation(
        context: Context,
        destination: Point,
        destinationName: String = "Destination"
    ) {
        val prefs = context.getSharedPreferences(AppSettings.PREFS_NAME, Context.MODE_PRIVATE)
        val mode = prefs.getString(
            AppSettings.KEY_NAVIGATION_MODE,
            AppSettings.VALUE_NAVIGATION_IN_APP
        )

        if (mode == AppSettings.VALUE_NAVIGATION_GOOGLE_MAPS) {
            openGoogleMaps(context, destination)
        } else {
            openInAppPreview(context, destination, destinationName)
        }
    }

    fun openGoogleMaps(context: Context, destination: Point) {
        val lat = destination.latitude()
        val lon = destination.longitude()

        val gmmIntentUri = Uri.parse("google.navigation:q=$lat,$lon&mode=d")
        val mapIntent = Intent(Intent.ACTION_VIEW, gmmIntentUri).apply {
            setPackage("com.google.android.apps.maps")
        }

        if (mapIntent.resolveActivity(context.packageManager) != null) {
            context.startActivity(mapIntent)
        } else {
            val browserUri = Uri.parse(
                "https://www.google.com/maps/dir/?api=1&destination=$lat,$lon&travelmode=driving"
            )
            context.startActivity(Intent(Intent.ACTION_VIEW, browserUri))
        }
    }

    private fun openInAppPreview(
        context: Context,
        destination: Point,
        destinationName: String
    ) {
        val intent = Intent(context, RoutePreviewActivity::class.java).apply {
            putExtra(RoutePreviewActivity.EXTRA_DESTINATION_LAT, destination.latitude())
            putExtra(RoutePreviewActivity.EXTRA_DESTINATION_LON, destination.longitude())
            putExtra(RoutePreviewActivity.EXTRA_DESTINATION_NAME, destinationName)
        }
        context.startActivity(intent)
    }
}