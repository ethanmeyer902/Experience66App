package com.example.experience66hello

import com.mapbox.geojson.Point

/**
 * Represents a single POI row from **`CUpdated.csv`** (see [Route66DatabaseParser.POI_DATASET_ASSET_NAME]).
 * Contains location, narrative, and URLs for map, list, and geofencing.
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
    val longitude: Double?,
    /** CONTENTdm image API URL from cleaned POI CSV (optional column). */
    val imageUrl: String? = null,
    val imageTitle: String? = null,
    val source: String? = null,
) {
    /**
     * Converts this database entry to a Route66Landmark
     * Only works if the entry has valid coordinates
     */
    fun toLandmark(): Route66Landmark? {
        if (latitude == null || longitude == null) return null
        
        // Create ID from locationId, fallback to objectId for test rows that omit LocationID.
        val id = locationId
            .takeIf { it.isNotBlank() }
            ?.lowercase()
            ?.replace("-", "_")
            ?.replace(" ", "_")
            ?: "obj_$objectId"
        
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
            radiusMeters = Route66Landmark.GEOFENCE_RADIUS_METERS,
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
     * Combined text from the CUpdated.csv row for keyword search (coordinates stay on lat/lon fields).
     */
    private fun searchHaystack(): String {
        return listOfNotNull(
            name,
            historicName,
            description,
            narrative,
            city,
            county,
            state,
            locationId,
            objectId,
            imageTitle,
            source,
            status,
        ).joinToString(" ").lowercase()
    }

    /**
     * Matches the user search query against this POI row.
     * Supports multi-word queries: every token (length ≥ 2) must appear somewhere in the haystack,
     * so searches use the longer Description and Image_Title keywords from the CSV, not only the title.
     */
    fun matchesSearch(rawQuery: String): Boolean {
        val q = rawQuery.trim().lowercase()
        if (q.isEmpty()) return false
        val hay = searchHaystack()
        if (hay.contains(q)) return true
        val tokens = q.split(Regex("\\s+")).map { it.trim() }.filter { it.length >= 2 }
        if (tokens.isEmpty()) return false
        return tokens.all { token -> hay.contains(token) }
    }
}
