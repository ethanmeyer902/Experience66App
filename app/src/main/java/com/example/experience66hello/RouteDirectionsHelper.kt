package com.example.experience66hello

import android.util.Log
import com.mapbox.geojson.Point
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import kotlin.math.roundToInt

object RouteDirectionsHelper {

    private const val TAG = "RouteDirectionsHelper"

    data class RouteData(
        val geometry: String,
        val distanceMeters: Double,
        val durationSeconds: Double
    )

    fun fetchRouteData(
        accessToken: String,
        origin: Point,
        destination: Point
    ): RouteData? {
        val urlString =
            "https://api.mapbox.com/directions/v5/mapbox/driving/" +
                    "${origin.longitude()},${origin.latitude()};" +
                    "${destination.longitude()},${destination.latitude()}" +
                    "?geometries=polyline6&overview=full&access_token=$accessToken"

        var connection: HttpURLConnection? = null

        return try {
            val url = URL(urlString)
            connection = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 15000
                readTimeout = 15000
            }

            val stream = if (connection.responseCode in 200..299) {
                connection.inputStream
            } else {
                connection.errorStream
            } ?: return null

            val response = BufferedReader(InputStreamReader(stream)).use { reader ->
                reader.readText()
            }

            if (connection.responseCode !in 200..299) {
                Log.e(TAG, "Directions API error: $response")
                return null
            }

            val json = JSONObject(response)
            val routes = json.optJSONArray("routes") ?: return null
            if (routes.length() == 0) return null

            val firstRoute = routes.getJSONObject(0)

            val geometry = firstRoute.optString("geometry", "")
            val distance = firstRoute.optDouble("distance", -1.0)
            val duration = firstRoute.optDouble("duration", -1.0)

            if (geometry.isBlank() || distance < 0 || duration < 0) {
                return null
            }

            RouteData(
                geometry = geometry,
                distanceMeters = distance,
                durationSeconds = duration
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to fetch route", e)
            null
        } finally {
            connection?.disconnect()
        }
    }

    fun formatMiles(distanceMeters: Double): String {
        val miles = distanceMeters / 1609.344
        return String.format(Locale.US, "%.1f mi", miles)
    }

    fun formatDuration(durationSeconds: Double): String {
        val totalMinutes = (durationSeconds / 60.0).roundToInt()
        val hours = totalMinutes / 60
        val minutes = totalMinutes % 60

        return if (hours > 0) {
            "${hours} hr ${minutes} min"
        } else {
            "${minutes} min"
        }
    }
}