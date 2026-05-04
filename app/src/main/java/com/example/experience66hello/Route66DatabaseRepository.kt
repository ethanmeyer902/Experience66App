package com.example.experience66hello

import android.content.Context
import android.util.Log

/**
 * Manages Arizona Route 66 POIs loaded from [Route66DatabaseParser.POI_DATASET_ASSET_NAME] (`CUpdated.csv`).
 * Provides access to landmarks and helps match them with archive items.
 */
class Route66DatabaseRepository(private val context: Context) {
    private val testingPoiName = "Testing POI"
    private val testingPoiRadiusMeters = 0.01f // 0.7 mile
    private val routeOverlapOverrideIds = setOf("az027", "az028") // Cool Springs, Sitgreaves Pass
    private val routeOverlapOverrideRadiusMeters = 26f * Route66Landmark.ONE_MILE_IN_METERS
    
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
            Log.d(TAG, "Loading POIs from ${Route66DatabaseParser.POI_DATASET_ASSET_NAME}…")
            val startTime = System.currentTimeMillis()
            
            val parsedEntries = Route66DatabaseParser.parseRoute66Database(
                context,
                Route66DatabaseParser.POI_DATASET_ASSET_NAME
            )
            databaseEntries = cleanArizonaPoiEntries(parsedEntries)
            
            val parseTime = System.currentTimeMillis() - startTime
            Log.d(TAG, "CSV parsing took ${parseTime}ms")
            
            // Convert entries to landmarks (only those with valid coordinates)
            val convertStartTime = System.currentTimeMillis()
            landmarks = databaseEntries
                .asSequence()
                .mapNotNull { it.toLandmark() }
                .toList()

            landmarks = try {
                Route66ArizonaRouteGeofence.withCalibratedRadii(context, landmarks)
            } catch (e: Exception) {
                Log.w(TAG, "Route-based geofence radii skipped: ${e.message}")
                landmarks
            }
            landmarks = landmarks.map { lm ->
                if (lm.name.equals(testingPoiName, ignoreCase = true)) {
                    lm.copy(radiusMeters = testingPoiRadiusMeters)
                } else {
                    lm
                }
            }
            landmarks = landmarks.map { lm ->
                lm.copy(
                    radiusMeters = (lm.radiusMeters + Route66Landmark.ONE_MILE_IN_METERS)
                        .coerceAtMost(Route66Landmark.MAX_ANDROID_GEOFENCE_RADIUS_METERS)
                )
            }
            landmarks = landmarks.map { lm ->
                if (routeOverlapOverrideIds.contains(lm.id.lowercase())) {
                    lm.copy(
                        radiusMeters = routeOverlapOverrideRadiusMeters
                    )
                } else {
                    lm
                }
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
     * Keeps only valid Arizona POIs from CUpdated and removes duplicates by LocationID.
     * If duplicate rows exist, prefer the one with a usable image URL / richer description.
     */
    private fun cleanArizonaPoiEntries(entries: List<Route66DatabaseEntry>): List<Route66DatabaseEntry> {
        val valid = entries.filter { entry ->
            val state = entry.state?.trim()?.uppercase()
            val isArizona = state == null || state == "AZ"
            val isTestingPoi = entry.name.equals(testingPoiName, ignoreCase = true)
            val hasIdentity = entry.name.isNotBlank() &&
                (entry.locationId.isNotBlank() || (isTestingPoi && entry.objectId.isNotBlank()))
            val hasCoordinates = entry.latitude != null && entry.longitude != null
            val status = entry.status?.trim()?.uppercase().orEmpty()
            val isActive = status.isBlank() || status == "ACTIVE"
            isArizona && hasIdentity && hasCoordinates && isActive
        }

        return valid
            .groupBy { it.locationId.trim().uppercase() }
            .values
            .map { dupes ->
                dupes.maxWithOrNull(
                    compareBy<Route66DatabaseEntry> { candidate ->
                        val hasHttpImage = candidate.imageUrl?.startsWith("http", ignoreCase = true) == true
                        if (hasHttpImage) 1 else 0
                    }.thenBy { candidate ->
                        candidate.description?.length ?: 0
                    }
                ) ?: dupes.first()
            }
            .sortedBy { it.locationId.trim().uppercase() }
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
        
        val fromEntries = LinkedHashMap<String, Route66Landmark>()
        databaseEntries.filter { it.matchesSearch(query) }.forEach { entry ->
            entry.toLandmark()?.let { lm -> fromEntries.putIfAbsent(lm.id, lm) }
        }
        val merged = LinkedHashMap<String, Route66Landmark>()
        landmarks.filter { landmarkMatchesSearchQuery(it, query) }
            .forEach { merged[it.id] = it }
        fromEntries.values.forEach { merged.putIfAbsent(it.id, it) }
        return merged.values.toList()
    }

    /**
     * Same multi-token rules as [Route66DatabaseEntry.matchesSearch], applied to in-memory landmarks
     * (name, id, CSV-backed description). Used by map search fallback when the DB list is empty.
     */
    fun landmarkMatchesSearchQuery(landmark: Route66Landmark, rawQuery: String): Boolean {
        val q = rawQuery.trim().lowercase()
        if (q.isEmpty()) return false
        val hay = "${landmark.name} ${landmark.description} ${landmark.id}".lowercase()
        if (hay.contains(q)) return true
        val tokens = q.split(Regex("\\s+")).map { it.trim() }.filter { it.length >= 2 }
        if (tokens.isEmpty()) return false
        return tokens.all { hay.contains(it) }
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
     * CSV row for this map landmark id ([LocationID], e.g. AZ013 → `az013`).
     */
    fun findDatabaseEntryByLandmarkId(landmarkId: String): Route66DatabaseEntry? {
        if (!isLoaded) {
            loadDatabase()
        }
        val idNorm = landmarkId.lowercase().trim().replace("_", "-")
        return databaseEntries.find { e ->
            e.locationId.lowercase().replace("_", "-") == idNorm
        } ?: databaseEntries.find { e ->
            e.locationId.lowercase().replace("_", "").trim() == idNorm.replace("-", "")
        }
    }

    /**
     * Looks up [Route66DatabaseEntry.imageUrl] for the landmark id (same row as coordinates in CUpdated CSV).
     */
    fun findImageUrlForLandmarkId(landmarkId: String): String? {
        return findDatabaseEntryByLandmarkId(landmarkId)?.imageUrl?.trim()?.takeIf { url ->
            url.startsWith("http", ignoreCase = true) && !url.equals("PENDING", ignoreCase = true)
        }
    }

    /**
     * True if the card title / landmark name shares meaningful tokens with the CSV row (name, image title, description).
     * Used to validate the image URL belongs to the same POI the user is viewing (e.g. search keyword vs row).
     */
    fun csvRowMatchesCardKeywords(entry: Route66DatabaseEntry, cardTitle: String, lmName: String?): Boolean {
        val hay = "${entry.name} ${entry.imageTitle} ${entry.description}".lowercase()
        val hints = listOf(cardTitle, lmName.orEmpty())
            .flatMap { it.lowercase().split(Regex("\\W+")) }
            .map { it.trim() }
            .filter { it.length >= 3 }
            .distinct()
        if (hints.isEmpty()) return true
        return hints.any { hay.contains(it) }
    }

    /**
     * Resolves [Route66DatabaseEntry.imageUrl] for the POI card.
     *
     * Uses the CSV row for this landmark’s [LocationID] ([landmarkId] e.g. `az011` → `AZ011`) so
     * [Image_URL] always matches the same POI row in CUpdated.csv.
     * If no row/url is present (including PENDING rows parsed as null), caller should use local fallback image.
     */
    fun resolveCsvImageUrlForPoi(landmarkId: String, lm: Route66Landmark?, cardTitle: String): String? {
        if (!isLoaded) {
            loadDatabase()
        }
        val byLandmarkId = findDatabaseEntryByLandmarkId(landmarkId)
            ?: lm?.let { findDatabaseEntryForLandmark(it) }

        fun httpImageUrl(e: Route66DatabaseEntry?): String? =
            e?.imageUrl?.trim()?.takeIf { u ->
                u.startsWith("http", ignoreCase = true) && !u.equals("PENDING", ignoreCase = true)
            }

        return httpImageUrl(byLandmarkId)
    }
    
    /**
     * Builds a [Route66ArchiveItem] from the POI row's CONTENTdm image URL when present.
     */
    fun archiveItemFromPoiContentDm(entry: Route66DatabaseEntry): Route66ArchiveItem? {
        val url = entry.imageUrl?.trim()?.takeIf { it.startsWith("http", ignoreCase = true) } ?: return null
        val id = extractCpaNumericId(url) ?: return null
        return Route66ArchiveItem(
            callNumber = "POI:${entry.locationId}",
            contentDmNumber = id,
            itemNumber = id,
            referenceUrl = url
        )
    }

    private fun extractCpaNumericId(url: String): String? {
        val m = Regex("""/cpa/(\d+)""").find(url) ?: return null
        return m.groupValues.getOrNull(1)
    }

    /** CPA / CONTENTdm numeric id parsed from the POI row's [Route66DatabaseEntry.imageUrl], if any. */
    fun contentDmNumericIdFromPoiImage(entry: Route66DatabaseEntry?): String? =
        entry?.imageUrl?.let { extractCpaNumericId(it) }

    /**
     * Match CONTENTdm archive items to a landmark using name, city, CPA id from POI image URL, and location.
     */
    fun matchArchiveItemsToLandmark(
        landmark: Route66Landmark,
        archiveItems: List<Route66ArchiveItem>
    ): List<Route66ArchiveItem> {
        val matchedItems = mutableListOf<Route66ArchiveItem>()
        val dbEntry = findDatabaseEntryForLandmark(landmark)
        val cdmFromPoiImage = dbEntry?.imageUrl?.let { extractCpaNumericId(it) }
        
        // Extract search terms from landmark and database entry
        val searchTerms = mutableListOf<String>()
        // Apply keyword override for better CPA matching
        val override = SearchKeywordOverrides.forPoiName(landmark.name)
        val primaryTerm = (override?.useTerm ?: landmark.name).lowercase()
        searchTerms.add(primaryTerm)
        searchTerms.add(landmark.id.lowercase())
        
        dbEntry?.let { entry ->
            entry.city?.lowercase()?.let { searchTerms.add(it) }
            entry.imageTitle?.lowercase()?.let { searchTerms.add(it) }
            entry.name.lowercase().split(" ", "-", "_", "(", ")").forEach { word ->
                if (word.length > 3) searchTerms.add(word)
            }
            entry.description?.lowercase()?.split(Regex("\\W+"))
                ?.filter { it.length > 4 }
                ?.distinct()
                ?.take(16)
                ?.forEach { searchTerms.add(it) }
        }
        
        // Match archive items
        archiveItems.forEach { item ->
            val callLower = item.callNumber.lowercase()
            val contentDmLower = item.contentDmNumber.lowercase()
            val itemNumberLower = item.itemNumber.lowercase()
            
            // Check if any search term matches
            val matchesCpaImage = cdmFromPoiImage != null &&
                (item.contentDmNumber == cdmFromPoiImage || item.itemNumber == cdmFromPoiImage)
            val matches = matchesCpaImage || searchTerms.any { term ->
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
