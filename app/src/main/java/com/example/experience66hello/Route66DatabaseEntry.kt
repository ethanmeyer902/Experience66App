package com.example.experience66hello

import com.mapbox.geojson.Point

/**
 * Represents a single entry from the Route 66 Database CSV file
 * Contains all the POI information including location, narrative, and URLs
 */
data class Route66DatabaseEntry(
    val objectId: String,
    val locationId: String,
    val name: String,
    val historicName: String?,
    val status: String?,
    val statusYear: String?,
    val description: String?,
    val narrative: String?,
    val narrativeSrc: String?,
    val urlMoreInfo: String?,
    val urlMoreInfo2: String?,
    val address: String?,
    val city: String?,
    val state: String?,
    val zip: String?,
    val county: String?,
    val latitude: Double?,
    val longitude: Double?
) {
    /**
     * Converts this database entry to a Route66Landmark
     * Only works if the entry has valid coordinates
     */
    fun toLandmark(): Route66Landmark? {
        if (latitude == null || longitude == null) return null
        
        // Create ID from locationId, replacing dashes and spaces with underscores
        val id = locationId.lowercase().replace("-", "_").replace(" ", "_")
        
        // Use cleaned CSV description first, then fallback to legacy narrative/historic fields.
        val landmarkDescription = description?.takeIf { it.isNotBlank() }
            ?: narrative?.takeIf { it.isNotBlank() }
            ?: historicName?.takeIf { it.isNotBlank() }
            ?: name
        
        return Route66Landmark(
            id = id,
            name = name,
            description = landmarkDescription,
            latitude = latitude,
            longitude = longitude,
            radiusMeters = 600f, // Default radius as specified
        )
    }
    
    fun toPoint(): Point? {
        return if (latitude != null && longitude != null) {
            Point.fromLngLat(longitude, latitude)
        } else {
            null
        }
    }
    
    /**
     * Checks if this entry matches a search query
     * Searches in name, historic name, city, narrative, and location ID
     */
    fun matchesSearch(searchTerm: String): Boolean {
        val term = searchTerm.lowercase()
        return name.lowercase().contains(term) ||
               historicName?.lowercase()?.contains(term) == true ||
               city?.lowercase()?.contains(term) == true ||
               narrative?.lowercase()?.contains(term) == true ||
               locationId.lowercase().contains(term)
    }
}
