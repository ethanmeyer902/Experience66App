package com.example.experience66hello

/**
 * Centralized keyword overrides and simple search hints for CPA searches.
 * - useTerm: replacement keyword to use instead of the POI name
 * - imagesOnly: bias search towards images by appending "photograph" to the term
 * - disableFallback: if true, do not open CPA search for this entry
 *
 * Note: CPA collection scoping is already enforced by the app's CPA URL.
 */
object SearchKeywordOverrides {

    data class Override(
        val useTerm: String,
        val imagesOnly: Boolean = false,
        val disableFallback: Boolean = false
    )

    private val map: Map<String, Override> = mapOf(
        // Route 66 focused overrides
        "Lupton Route 66 Crossing" to Override("Lupton Route 66"),
        "Painted Desert Inn" to Override("Painted Desert Inn Route 66"),
        "Petrified Forest Route 66 Segment" to Override("Petrified Forest Route 66"),
        "Wigwam Motel" to Override("Wigwam Motel"),
        "Holbrook Historic District" to Override("Holbrook Route 66"),
        "Jack Rabbit Trading Post" to Override("Jack Rabbit Trading Post"),
        "Winslow Historic District" to Override("Winslow Route 66"),
        "La Posada Hotel" to Override("La Posada Hotel"),
        "Two Guns Ghost Town" to Override("Two Guns Route 66"),
        "Canyon Diablo Bridge" to Override("Canyon Diablo Bridge Route 66"),
        "Winona Historic Community" to Override("Winona Route 66"),
        "Flagstaff Route 66 Historic District" to Override("Flagstaff Route 66"),
        "Museum Club" to Override("Museum Club Route 66"),
        "Bellemont Historic Community" to Override("Bellemont Route 66"),
        "Ash Fork Historic District" to Override("Ash Fork Route 66"),
        "Williams Historic Route 66 District" to Override("Williams Route 66"),
        "Seligman Historic District" to Override("Seligman Route 66"),
        "Peach Springs" to Override("Peach Springs Route 66"),
        // Hackberry – either works; keep base
        "Hackberry General Store" to Override("Hackberry General Store"),
        "Kingman Historic District" to Override("Kingman Route 66"),
        // Images-only hints
        "Cool Springs Station" to Override("Cool Springs", imagesOnly = true),
        "Sitgreaves Pass" to Override("Sitgreaves Pass", imagesOnly = true),
        "Oatman Historic District" to Override("Oatman Route 66"),
        "Topock Route 66 Bridge" to Override("Topock Route 66 Bridge"),
        // Nearby attractions – keep CPA, leave as-is
        "Walnut Canyon National Monument" to Override("Walnut Canyon National Monument"),
        "Meteor Crater" to Override("Meteor Crater"),
        "Montezuma Castle" to Override("Montezuma Castle"),
        // Question / remove entries – disable fallback
        "Montezuma Well" to Override("Montezuma Well", disableFallback = true),
        "Presidio San Agustin del Tucson" to Override("Presidio San Agustin del Tucson", disableFallback = true),
        "Tucson Barrio Historico" to Override("Tucson Barrio Historico", disableFallback = true),
        "Papago Park Cultural Sites" to Override("Papago Park", disableFallback = false),
        "Heritage Square" to Override("Heritage Square Flagstaff"),
        "Jerome Historic District" to Override("Jerome Arizona"),
        "Prescott Courthouse Plaza" to Override("Prescott Courthouse Plaza", disableFallback = true),
        "Mingus Mountain Mining Region" to Override("Mingus Mountain Mining Region", disableFallback = true),
        "Arizona State Capitol" to Override("Arizona State Capitol", disableFallback = true),
        "San Xavier del Bac Mission" to Override("San Xavier del Bac Mission"),
        "Tombstone Historic District" to Override("Tombstone (Arizona)"),
        "Bisbee Historic District" to Override("Bisbee (Arizona)"),
        "Fort Huachuca" to Override("Fort Huachuca"),
        "Yuma Territorial Prison" to Override("Yuma Territorial Prison"),
        "Yuma Crossing Historic District" to Override("Yuma Crossing Historic District")
    )

    /**
     * Returns an override for a given POI name, if available.
     */
    fun forPoiName(name: String): Override? {
        return map[name] ?: map.entries.firstOrNull { (key, _) ->
            key.equals(name, ignoreCase = true)
        }?.value
    }

    /**
     * Returns an override for a given POI id, if any key matches the id as a name.
     * (Conservative: id-based override is not assumed.)
     */
    fun forPoiId(id: String): Override? = null
}
