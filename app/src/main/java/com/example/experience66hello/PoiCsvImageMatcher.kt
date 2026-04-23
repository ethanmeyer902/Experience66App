package com.example.experience66hello

/**
 * Matches the in-app POI name (keyword) to a row in [Route66DatabaseParser.POI_DATASET_ASSET_NAME] (CUpdated.csv)
 * so we can use that row's [Route66DatabaseEntry.imageUrl].
 * Primary key for lookup: **CSV Name** (and Image_Title) vs the app display name / landmark name.
 */
object PoiCsvImageMatcher {

    fun normalizeKey(text: String): String =
        text.lowercase()
            .replace(Regex("\\s+"), " ")
            .trim()

    /**
     * Picks the CSV row whose **Name** or **Image_Title** best matches [poiNameKeyword].
     * If [preferLocationId] is set (e.g. map id AZ011 → `az011`), prefer a row with that LocationID when scores tie.
     */
    fun findBestMatchingEntry(
        entries: List<Route66DatabaseEntry>,
        poiNameKeyword: String,
        preferLocationId: String?
    ): Route66DatabaseEntry? {
        val key = normalizeKey(poiNameKeyword.trim())
        if (key.isEmpty()) return null

        val preferNorm = preferLocationId?.lowercase()?.trim()?.replace("_", "-")

        // 1) Exact Name match
        entries.find { normalizeKey(it.name) == key }?.let { return it }

        // 2) Exact Image_Title match
        entries.find { t -> t.imageTitle?.let { normalizeKey(it) == key } == true }?.let { return it }

        // 3) Name contains keyword or keyword contains name (handles "Two Guns" vs "Two Guns Route 66")
        entries.filter {
            val n = normalizeKey(it.name)
            n.contains(key) || key.contains(n)
        }.maxByOrNull { scoreRow(it, key, preferNorm) }
            ?.let { return it }

        // 4) All significant tokens from keyword appear in name + imageTitle
        val tokens = key.split(" ").filter { it.length >= 3 }.distinct()
        if (tokens.isNotEmpty()) {
            entries.filter { e ->
                val hay = normalizeKey("${e.name} ${e.imageTitle.orEmpty()}")
                tokens.all { hay.contains(it) }
            }.maxByOrNull { scoreRow(it, key, preferNorm) }
                ?.let { return it }
        }

        return null
    }

    private fun scoreRow(e: Route66DatabaseEntry, key: String, preferLocationId: String?): Int {
        var s = 0
        val n = normalizeKey(e.name)
        val tit = e.imageTitle?.let { normalizeKey(it) } ?: ""
        if (n == key) s += 100
        if (tit == key) s += 90
        if (n.contains(key)) s += 50
        if (key.contains(n) && n.length >= 6) s += 40
        val loc = e.locationId.lowercase().replace("_", "-")
        if (preferLocationId != null && loc == preferLocationId) s += 30
        return s
    }
}
