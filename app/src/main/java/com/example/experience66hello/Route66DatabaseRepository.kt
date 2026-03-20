package com.example.experience66hello

import android.content.Context
import android.util.Log

/**
 * Manages the Route 66 POI database loaded from CSV
 * Provides access to landmarks and helps match them with archive items
 */
class Route66DatabaseRepository(private val context: Context) {
    
    private var databaseEntries: List<Route66DatabaseEntry> = emptyList()
    private var landmarks: List<Route66Landmark> = emptyList()
    private var isLoaded = false
    
    companion object {
        private const val TAG = "Route66DatabaseRepo"
    }
    
    /**
     * Loads the Route 66 database from CSV file
     * Parses POI data and converts entries with valid coordinates to landmarks
     */
    fun loadDatabase() {
        if (isLoaded) return
        
        try {
            Log.d(TAG, "Loading Route 66 Database from CSV...")
            val startTime = System.currentTimeMillis()
            
            databaseEntries = Route66DatabaseParser.parseRoute66Database(context)
            
            val parseTime = System.currentTimeMillis() - startTime
            Log.d(TAG, "CSV parsing took ${parseTime}ms")
            
            // Convert entries to landmarks (only those with valid coordinates)
            val convertStartTime = System.currentTimeMillis()
            landmarks = databaseEntries
                .mapNotNull { it.toLandmark() }
                .distinctBy { landmark ->
                    // Keep only unique, valid POIs from the cleaned dataset.
                    val latKey = String.format("%.5f", landmark.latitude)
                    val lonKey = String.format("%.5f", landmark.longitude)
                    "${landmark.id}|${landmark.name.lowercase()}|$latKey|$lonKey"
                }
            
            val convertTime = System.currentTimeMillis() - convertStartTime
            Log.d(TAG, "Landmark conversion took ${convertTime}ms")
            
            isLoaded = true
            Log.d(TAG, "Loaded ${databaseEntries.size} database entries, ${landmarks.size} valid landmarks")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load database: ${e.message}", e)
            e.printStackTrace()
            isLoaded = false
            // Initialize with empty list to prevent crashes
            landmarks = emptyList()
            databaseEntries = emptyList()
        }
    }
    
    /**
     * Get all landmarks from the database
     */
    fun getAllLandmarks(): List<Route66Landmark> {
        if (!isLoaded) {
            loadDatabase()
        }
        return landmarks
    }
    
    /**
     * Find landmark by ID
     */
    fun findLandmarkById(id: String): Route66Landmark? {
        if (!isLoaded) {
            loadDatabase()
        }
        return landmarks.find { it.id == id }
    }
    
    /**
     * Search landmarks by name or location
     */
    fun searchLandmarks(query: String): List<Route66Landmark> {
        if (!isLoaded) {
            loadDatabase()
        }
        
        if (query.isBlank()) return emptyList()
        
        val searchTerm = query.lowercase().trim()
        return landmarks.filter { landmark ->
            landmark.name.lowercase().contains(searchTerm) ||
            landmark.id.lowercase().contains(searchTerm) ||
            landmark.description.lowercase().contains(searchTerm)
        }
    }
    
    /**
     * Find database entry for a landmark
     */
    fun findDatabaseEntryForLandmark(landmark: Route66Landmark): Route66DatabaseEntry? {
        if (!isLoaded) {
            loadDatabase()
        }
        
        return databaseEntries.find { entry ->
            entry.locationId.lowercase() == landmark.id.replace("_", "-") ||
            (entry.latitude != null && entry.longitude != null &&
             entry.latitude == landmark.latitude &&
             entry.longitude == landmark.longitude)
        }
    }
    
    /**
     * Match CONTENTdm archive items to a landmark using name, city, and location
     */
    fun matchArchiveItemsToLandmark(
        landmark: Route66Landmark,
        archiveItems: List<Route66ArchiveItem>
    ): List<Route66ArchiveItem> {
        val matchedItems = mutableListOf<Route66ArchiveItem>()
        val dbEntry = findDatabaseEntryForLandmark(landmark)
        
        // Extract search terms from landmark and database entry
        val searchTerms = mutableListOf<String>()
        searchTerms.add(landmark.name.lowercase())
        searchTerms.add(landmark.id.lowercase())
        
        dbEntry?.let { entry ->
            entry.city?.lowercase()?.let { searchTerms.add(it) }
            entry.name.lowercase().split(" ", "-", "_", "(", ")").forEach { word ->
                if (word.length > 3) searchTerms.add(word)
            }
        }
        
        // Match archive items
        archiveItems.forEach { item ->
            val callLower = item.callNumber.lowercase()
            val contentDmLower = item.contentDmNumber.lowercase()
            val itemNumberLower = item.itemNumber.lowercase()
            
            // Check if any search term matches
            val matches = searchTerms.any { term ->
                callLower.contains(term) ||
                contentDmLower.contains(term) ||
                itemNumberLower.contains(term)
            }
            
            if (matches && !matchedItems.contains(item)) {
                matchedItems.add(item)
            }
        }
        
        Log.d(TAG, "Matched ${matchedItems.size} archive items for ${landmark.name}")
        return matchedItems
    }
    
    /**
     * Get total count of landmarks
     */
    fun getLandmarkCount(): Int {
        if (!isLoaded) {
            loadDatabase()
        }
        return landmarks.size
    }
}
