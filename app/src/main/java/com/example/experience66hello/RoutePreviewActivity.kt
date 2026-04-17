package com.example.experience66hello

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.app.ActivityCompat
import com.google.android.gms.location.LocationServices
import com.mapbox.geojson.Feature
import com.mapbox.geojson.LineString
import com.mapbox.geojson.Point
import com.mapbox.maps.CameraOptions
import com.mapbox.maps.EdgeInsets
import com.mapbox.maps.MapView
import com.mapbox.maps.Style
import com.mapbox.maps.extension.style.layers.addLayer
import com.mapbox.maps.extension.style.layers.generated.lineLayer
import com.mapbox.maps.extension.style.layers.properties.generated.LineCap
import com.mapbox.maps.extension.style.layers.properties.generated.LineJoin
import com.mapbox.maps.extension.style.sources.addSource
import com.mapbox.maps.extension.style.sources.generated.geoJsonSource
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

class RoutePreviewActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_DESTINATION_LAT = "destination_lat"
        const val EXTRA_DESTINATION_LON = "destination_lon"
        const val EXTRA_DESTINATION_NAME = "destination_name"

        private const val ROUTE_SOURCE_ID = "route-preview-source"
        private const val ROUTE_LAYER_ID = "route-preview-layer"
        private const val TAG = "RoutePreviewActivity"
    }

    private lateinit var mapView: MapView
    private lateinit var destinationNameText: TextView
    private lateinit var buttonOpenGoogleMaps: Button

    private val fusedLocationClient by lazy {
        LocationServices.getFusedLocationProviderClient(this)
    }

    private val locationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                loadRoutePreview()
            } else {
                Toast.makeText(
                    this,
                    "Location permission is needed to preview the route",
                    Toast.LENGTH_LONG
                ).show()
                finish()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        val prefs = getSharedPreferences(AppSettings.PREFS_NAME, MODE_PRIVATE)
        val darkModeEnabled = prefs.getBoolean(AppSettings.KEY_DARK_MODE, false)

        AppCompatDelegate.setDefaultNightMode(
            if (darkModeEnabled) AppCompatDelegate.MODE_NIGHT_YES
            else AppCompatDelegate.MODE_NIGHT_NO
        )

        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_route_preview)

        mapView = findViewById(R.id.mapViewRoutePreview)
        destinationNameText = findViewById(R.id.textDestinationName)
        buttonOpenGoogleMaps = findViewById(R.id.buttonOpenGoogleMaps)

        val buttonBack = findViewById<ImageButton>(R.id.buttonBack)
        buttonBack.setOnClickListener { finish() }

        val destination = getDestinationPoint()
        val destinationName = intent.getStringExtra(EXTRA_DESTINATION_NAME).orEmpty()
            .ifBlank { "Destination" }

        destinationNameText.text = destinationName

        buttonOpenGoogleMaps.setOnClickListener {
            if (destination != null) {
                NavigationHelper.openGoogleMaps(this, destination)
            }
        }

        mapView.mapboxMap.loadStyle(Style.STANDARD) {
            checkLocationPermissionAndContinue()
        }
    }

    private fun checkLocationPermissionAndContinue() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            == PackageManager.PERMISSION_GRANTED
        ) {
            loadRoutePreview()
        } else {
            locationPermissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }

    private fun loadRoutePreview() {
        val destination = getDestinationPoint()
        if (destination == null) {
            Toast.makeText(this, "No destination provided", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) {
            Toast.makeText(this, "Location permission missing", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        fusedLocationClient.lastLocation
            .addOnSuccessListener { location ->
                if (location == null) {
                    Toast.makeText(
                        this,
                        "Current location not available yet. Try again in a moment.",
                        Toast.LENGTH_LONG
                    ).show()
                    finish()
                    return@addOnSuccessListener
                }

                val origin = Point.fromLngLat(location.longitude, location.latitude)

                mapView.mapboxMap.setCamera(
                    CameraOptions.Builder()
                        .center(origin)
                        .zoom(12.0)
                        .build()
                )

                fetchAndDrawRoute(origin, destination)
            }
            .addOnFailureListener {
                Toast.makeText(this, "Could not get your current location", Toast.LENGTH_LONG).show()
                finish()
            }
    }

    private fun fetchAndDrawRoute(origin: Point, destination: Point) {
        Thread {
            val geometry = fetchRouteGeometry(origin, destination)

            runOnUiThread {
                if (geometry.isNullOrBlank()) {
                    Toast.makeText(
                        this,
                        "Could not load route preview",
                        Toast.LENGTH_LONG
                    ).show()

                    val fallbackCamera = mapView.mapboxMap.cameraForCoordinates(
                        listOf(origin, destination),
                        EdgeInsets(180.0, 80.0, 180.0, 80.0),
                        null,
                        null
                    )
                    mapView.mapboxMap.setCamera(fallbackCamera)
                    return@runOnUiThread
                }

                drawRouteGeometry(geometry, origin, destination)
            }
        }.start()
    }

    private fun fetchRouteGeometry(origin: Point, destination: Point): String? {
        val accessToken = getString(R.string.mapbox_access_token)

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

            routes.getJSONObject(0).optString("geometry", null)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to fetch route", e)
            null
        } finally {
            connection?.disconnect()
        }
    }

    private fun drawRouteGeometry(polyline6: String, origin: Point, destination: Point) {
        val lineString = LineString.fromPolyline(polyline6, 6)

        mapView.mapboxMap.getStyle()?.let { style ->
            style.addSource(
                geoJsonSource(ROUTE_SOURCE_ID) {
                    feature(Feature.fromGeometry(lineString))
                }
            )

            style.addLayer(
                lineLayer(ROUTE_LAYER_ID, ROUTE_SOURCE_ID) {
                    lineCap(LineCap.ROUND)
                    lineJoin(LineJoin.ROUND)
                    lineWidth(6.0)
                    lineColor("#FF7A00")
                    lineOpacity(0.9)
                }
            )
        }

        val routePoints = mutableListOf<Point>()
        routePoints.add(origin)
        routePoints.addAll(lineString.coordinates())
        routePoints.add(destination)

        val camera = mapView.mapboxMap.cameraForCoordinates(
            routePoints,
            EdgeInsets(180.0, 80.0, 180.0, 80.0),
            null,
            null
        )
        mapView.mapboxMap.setCamera(camera)
    }

    private fun getDestinationPoint(): Point? {
        if (!intent.hasExtra(EXTRA_DESTINATION_LAT) || !intent.hasExtra(EXTRA_DESTINATION_LON)) {
            return null
        }

        val lat = intent.getDoubleExtra(EXTRA_DESTINATION_LAT, 0.0)
        val lon = intent.getDoubleExtra(EXTRA_DESTINATION_LON, 0.0)
        return Point.fromLngLat(lon, lat)
    }
}