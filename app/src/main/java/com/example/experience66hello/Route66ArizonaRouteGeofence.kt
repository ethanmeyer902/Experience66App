package com.example.experience66hello

import android.content.Context
import android.location.Location
import android.util.Log
import com.mapbox.geojson.FeatureCollection
import com.mapbox.geojson.LineString
import com.mapbox.geojson.MultiLineString

/**
 * Calibrates each POI's geofence radius using the highlighted Route 66 line in [route66.geojson]
 * (Arizona corridor only). POIs farther from the polyline get a larger radius (capped at the
 * Geofencing API maximum of 10 km).
 */
object Route66ArizonaRouteGeofence {

    private const val TAG = "Route66AzRouteGeofence"
    private const val MAX_GEOFENCE_RADIUS_M = 10_000f
    private const val ROUTE_OVERLAP_BUFFER_M = 120f
    private const val MIN_GEOFENCE_RADIUS_M = 120f
    private const val CIRCLE_GAP_M = 30f

    @Volatile
    private var cachedRouteVertices: List<Pair<Double, Double>>? = null

    fun withCalibratedRadii(context: Context, landmarks: List<Route66Landmark>): List<Route66Landmark> {
        if (landmarks.isEmpty()) return landmarks
        val verts = loadArizonaRouteVertices(context)
        if (verts.isEmpty()) {
            Log.w(TAG, "No Arizona Route 66 vertices parsed; keeping default radii")
            return landmarks
        }
        val desiredById = landmarks.associate { lm ->
            val dMin = minDistanceToVerticesMeters(lm.latitude, lm.longitude, verts)
            // Make every circle reach the route corridor; farther POIs get proportionally larger circles.
            lm.id to radiusForSeparationFromRoute(dMin)
        }
        val nonOverlapCapById = computeNonOverlapCaps(landmarks)
        return landmarks.map { lm ->
            val desired = desiredById[lm.id] ?: MIN_GEOFENCE_RADIUS_M
            val nonOverlapCap = nonOverlapCapById[lm.id] ?: MAX_GEOFENCE_RADIUS_M
            // Route overlap is mandatory for reliable trigger coverage while traveling the corridor.
            // Apply non-overlap cap only when it still preserves the route-reaching radius.
            val finalRadius = if (nonOverlapCap >= desired) {
                kotlin.math.min(desired, nonOverlapCap)
            } else {
                desired
            }.coerceIn(1f, MAX_GEOFENCE_RADIUS_M)
            if (nonOverlapCap + 0.5f < desired) {
                Log.d(
                    TAG,
                    "Route-overlap priority for ${lm.id}: desired=${"%.1f".format(desired)}m cap=${"%.1f".format(nonOverlapCap)}m final=${"%.1f".format(finalRadius)}m"
                )
            }
            lm.copy(radiusMeters = finalRadius)
        }
    }

    private fun radiusForSeparationFromRoute(dMinMeters: Float): Float {
        val neededToTouchRoute = dMinMeters + ROUTE_OVERLAP_BUFFER_M
        return neededToTouchRoute
            .coerceAtLeast(MIN_GEOFENCE_RADIUS_M)
            .coerceAtMost(MAX_GEOFENCE_RADIUS_M)
    }

    /**
     * Pairwise cap so geofence circles do not overlap each other:
     * radius <= (nearest-center-distance / 2) - gap.
     */
    private fun computeNonOverlapCaps(landmarks: List<Route66Landmark>): Map<String, Float> {
        if (landmarks.size <= 1) {
            return landmarks.associate { it.id to MAX_GEOFENCE_RADIUS_M }
        }
        val row = FloatArray(1)
        val caps = HashMap<String, Float>(landmarks.size)
        for (i in landmarks.indices) {
            val a = landmarks[i]
            var nearest = Float.MAX_VALUE
            for (j in landmarks.indices) {
                if (i == j) continue
                val b = landmarks[j]
                Location.distanceBetween(a.latitude, a.longitude, b.latitude, b.longitude, row)
                if (row[0] < nearest) nearest = row[0]
            }
            val cap = if (nearest == Float.MAX_VALUE) {
                MAX_GEOFENCE_RADIUS_M
            } else {
                kotlin.math.max(1f, (nearest / 2f) - CIRCLE_GAP_M)
            }
            caps[a.id] = cap
        }
        return caps
    }

    private fun minDistanceToVerticesMeters(lat: Double, lng: Double, verts: List<Pair<Double, Double>>): Float {
        val r = FloatArray(1)
        var min = Float.MAX_VALUE
        for ((vlat, vlng) in verts) {
            Location.distanceBetween(lat, lng, vlat, vlng, r)
            if (r[0] < min) min = r[0]
        }
        return if (min == Float.MAX_VALUE) 0f else min
    }

    private fun loadArizonaRouteVertices(context: Context): List<Pair<Double, Double>> {
        cachedRouteVertices?.let { return it }
        synchronized(this) {
            cachedRouteVertices?.let { return it }
            val out = ArrayList<Pair<Double, Double>>(8192)
            try {
                val json = context.assets.open("route66.geojson").bufferedReader().use { it.readText() }
                val fc = FeatureCollection.fromJson(json)
                val features = fc.features() ?: return emptyList()
                featureLoop@ for (f in features) {
                    when (val g = f.geometry()) {
                        is MultiLineString -> {
                            for (line in g.coordinates()) {
                                line.forEachIndexed { i, pt ->
                                    if (i % 2 != 0) return@forEachIndexed
                                    val lng = pt.longitude()
                                    val lat = pt.latitude()
                                    if (lat in 31.0..37.0 && lng in -115.0..-108.85) {
                                        out.add(lat to lng)
                                    }
                                }
                            }
                        }
                        is LineString -> {
                            g.coordinates().forEachIndexed { i, pt ->
                                if (i % 2 != 0) return@forEachIndexed
                                val lng = pt.longitude()
                                val lat = pt.latitude()
                                if (lat in 31.0..37.0 && lng in -115.0..-108.85) {
                                    out.add(lat to lng)
                                }
                            }
                        }
                        else -> Unit
                    }
                    if (out.size >= 12_000) break@featureLoop
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to parse route66.geojson for geofence sizing", e)
                return emptyList()
            }
            cachedRouteVertices = out
            Log.d(TAG, "Cached ${out.size} Arizona Route 66 sample vertices for geofence sizing")
            return out
        }
    }
}
