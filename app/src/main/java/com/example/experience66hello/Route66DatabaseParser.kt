package com.example.experience66hello

import android.content.Context
import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * Parses the Arizona Route 66 **POI** CSV from assets (`CUpdated.csv` by default).
 * Handles multiline fields and quoted values properly.
 */
object Route66DatabaseParser {
    private const val TAG = "Route66DatabaseParser"

    /** Asset path under `app/src/main/assets/` for POI rows (replaces legacy `Route_66_Database.csv`). */
    const val POI_DATASET_ASSET_NAME = "CUpdated.csv"
    
    /**
     * Reads and parses the POI CSV from assets.
     * @param fileName almost always [POI_DATASET_ASSET_NAME]; parameter kept for tests or alternate bundles.
     */
    fun parseRoute66Database(context: Context, fileName: String = POI_DATASET_ASSET_NAME): List<Route66DatabaseEntry> {
        val entries = mutableListOf<Route66DatabaseEntry>()
        
        try {
            Log.d(TAG, "Parsing POI CSV: $fileName")
            val inputStream = context.assets.open(fileName)
            // Use larger buffer to handle multiline fields in CSV
            val reader = BufferedReader(InputStreamReader(inputStream), 16384)
            
            // Read the header row to get column names
            val headerLine = reader.readLine()
            if (headerLine == null) {
                Log.e(TAG, "CSV file is empty")
                reader.close()
                inputStream.close()
                return entries
            }
            
            val headers = parseCsvLine(headerLine)
            Log.d(TAG, "CSV headers: ${headers.size} columns")
            
            var lineNumber = 1
            var entryCount = 0
            var currentRecord = StringBuilder()
            var insideQuotes = false
            
            // Read file character by character to properly handle multiline fields
            var char: Int
            while (reader.read().also { char = it } != -1) {
                when (char.toChar()) {
                    '"' -> {
                        insideQuotes = !insideQuotes
                        currentRecord.append(char.toChar())
                    }
                    '\n' -> {
                        if (!insideQuotes) {
                            // We've reached the end of a complete record
                            val record = currentRecord.toString().trim()
                            if (record.isNotBlank()) {
                                lineNumber++
                                try {
                                    val entry = parseDatabaseLine(record, headers)
                                    if (entry != null) {
                                        entries.add(entry)
                                        entryCount++
                                        // Show progress every 1000 entries
                                        if (entryCount % 1000 == 0) {
                                            Log.d(TAG, "Parsed $entryCount entries...")
                                        }
                                    }
                                } catch (e: Exception) {
                                    Log.w(TAG, "Error parsing record $lineNumber: ${e.message}")
                                }
                            }
                            currentRecord = StringBuilder()
                        } else {
                            // This newline is inside a quoted field, so keep it as part of the data
                            currentRecord.append(char.toChar())
                        }
                    }
                    '\r' -> {
                        // Ignore Windows-style carriage returns
                    }
                    else -> {
                        currentRecord.append(char.toChar())
                    }
                }
            }
            
            // Process last record if any
            val lastRecord = currentRecord.toString().trim()
            if (lastRecord.isNotBlank()) {
                try {
                    val entry = parseDatabaseLine(lastRecord, headers)
                    if (entry != null) {
                        entries.add(entry)
                        entryCount++
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Error parsing last record: ${e.message}")
                }
            }
            
            reader.close()
            inputStream.close()
            
            Log.d(TAG, "Successfully parsed ${entries.size} database entries from CSV")
            
        } catch (e: Exception) {
            Log.e(TAG, "Error reading CSV file: ${e.message}", e)
            e.printStackTrace()
        }
        
        return entries
    }
    
    /**
     * Converts a CSV record line into a Route66DatabaseEntry object
     */
    private fun parseDatabaseLine(line: String, headers: List<String>): Route66DatabaseEntry? {
        if (line.isBlank()) return null
        
        val values = normalizeCsvValues(headers, parseCsvLine(line))
        
        // Make sure we have enough values to match all headers
        if (values.size < headers.size) {
            val paddedValues = values.toMutableList()
            while (paddedValues.size < headers.size) {
                paddedValues.add("")
            }
            return createEntry(headers, paddedValues)
        }
        
        return createEntry(headers, values)
    }

    /**
     * Handles rows where Description contains unquoted commas by collapsing overflow columns back
     * into Description so coordinates and URLs still align with their headers.
     */
    private fun normalizeCsvValues(headers: List<String>, rawValues: List<String>): List<String> {
        if (rawValues.size <= headers.size) return rawValues

        val descIdx = headers.indexOf("Description")
        if (descIdx < 0) return rawValues

        // Keep a fixed tail (Latitude..Source) aligned from the right edge of the row.
        // This is resilient when Description has unquoted commas.
        val tailCount = headers.size - descIdx - 1
        if (tailCount <= 0 || rawValues.size <= tailCount) return rawValues

        val prefix = rawValues.take(descIdx)
        val tail = rawValues.takeLast(tailCount)
        val descParts = rawValues.subList(descIdx, rawValues.size - tailCount)
        val description = descParts.joinToString(",").trim()

        return buildList(headers.size) {
            addAll(prefix)
            add(description)
            addAll(tail)
        }
    }
    
    /**
     * Creates a Route66DatabaseEntry from the CSV headers and values
     * Only creates entries that have valid coordinates
     */
    private fun createEntry(headers: List<String>, values: List<String>): Route66DatabaseEntry? {
        try {
            // Helper function to get a value by column name
            val getValue = { headerName: String -> 
                val index = headers.indexOf(headerName)
                if (index >= 0 && index < values.size) values[index].trim() else ""
            }
            
            val objectId = getValue("OBJECTID")
            val locationId = getValue("LocationID")
            val name = getValue("Name")
            
            // Can't create entry without identity + display name.
            // LocationID may be blank for test rows; OBJECTID is used as stable fallback id later.
            if (objectId.isBlank() || name.isBlank()) {
                return null
            }
            
            // Parse coordinates - use all known CSV coordinate columns so every valid POI can render.
            val latitude = listOf(
                getValue("Lat_WGS84"),
                getValue("Latitude"),
                getValue("Y")
            ).firstNotNullOfOrNull { it.toDoubleOrNull() }
            val longitude = listOf(
                getValue("Long_WGS84"),
                getValue("Longitude"),
                getValue("X")
            ).firstNotNullOfOrNull { it.toDoubleOrNull() }
            
            // Skip entries without valid coordinates
            if (latitude == null || longitude == null) {
                return null
            }
            
            return Route66DatabaseEntry(
                objectId = objectId,
                locationId = locationId,
                name = name,
                historicName = getValue("Historic_Name").takeIf { it.isNotBlank() },
                status = getValue("Status").takeIf { it.isNotBlank() },
                statusYear = getValue("Status_Year").takeIf { it.isNotBlank() },
                description = getValue("Description").takeIf { it.isNotBlank() },
                narrative = getValue("Narrative").takeIf { it.isNotBlank() },
                narrativeSrc = getValue("Narrative_Src").takeIf { it.isNotBlank() },
                urlMoreInfo = getValue("URL_MoreInfo").takeIf { it.isNotBlank() },
                urlMoreInfo2 = getValue("URL_MoreInfo2").takeIf { it.isNotBlank() },
                address = getValue("Address").takeIf { it.isNotBlank() },
                city = getValue("City").takeIf { it.isNotBlank() },
                state = getValue("State").takeIf { it.isNotBlank() },
                zip = getValue("ZIP").takeIf { it.isNotBlank() },
                county = getValue("County").takeIf { it.isNotBlank() },
                latitude = latitude,
                longitude = longitude,
                // Keep raw CSV value so UI can distinguish PENDING vs missing.
                imageUrl = getValue("Image_URL").takeIf { it.isNotBlank() },
                imageTitle = getValue("Image_Title").takeIf { it.isNotBlank() },
                source = getValue("Source").takeIf { it.isNotBlank() },
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error creating entry: ${e.message}", e)
            return null
        }
    }
    
    /**
     * Splits a CSV line into fields, properly handling quoted values
     * This allows fields to contain commas and newlines when quoted
     */
    private fun parseCsvLine(line: String): List<String> {
        val result = mutableListOf<String>()
        var current = StringBuilder()
        var insideQuotes = false
        
        for (i in line.indices) {
            val char = line[i]
            
            when {
                char == '"' -> {
                    insideQuotes = !insideQuotes
                }
                char == ',' && !insideQuotes -> {
                    result.add(current.toString())
                    current = StringBuilder()
                }
                else -> {
                    current.append(char)
                }
            }
        }
        
        // Add the last field
        result.add(current.toString())
        
        return result
    }
}
