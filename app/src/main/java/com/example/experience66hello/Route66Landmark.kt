package com.example.experience66hello

import com.mapbox.geojson.Point

/**
 * Represents a Route 66 landmark (POI) with its location and geofence radius
 */
data class Route66Landmark(
    val id: String,
    val name: String,
    val description: String,
    val latitude: Double,
    val longitude: Double,
    val radiusMeters: Float = GEOFENCE_RADIUS_METERS,
    val narrative: String? = null
    ) {
    /**
     * Converts the landmark's coordinates to a Mapbox Point
     */
    fun toPoint(): Point = Point.fromLngLat(longitude, latitude)

    companion object {
        /** Same radius for every POI; large enough to intersect Route 66 while driving (max ~10 km on Android). */
        const val GEOFENCE_RADIUS_METERS = 5000f
    }
}

/**
 * Stores all Route 66 landmarks loaded from the database CSV
 * Initialized by MainActivity when the CSV is loaded
 */
object ArizonaLandmarks {
    var landmarks: List<Route66Landmark> = emptyList()
        private set
    
    /**
     * Initializes the landmarks list with data from the database
     */
    fun initialize(landmarks: List<Route66Landmark>) {
        this.landmarks = landmarks.map { lm ->
            lm.copy(radiusMeters = Route66Landmark.GEOFENCE_RADIUS_METERS)
        }
    }
    
    fun findById(id: String): Route66Landmark? = landmarks.find { it.id == id }
    
    fun findByName(name: String): Route66Landmark? = landmarks.find { 
        it.name.equals(name, ignoreCase = true) 
    }
}
