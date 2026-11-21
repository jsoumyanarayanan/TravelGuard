package com.travelguard.app.data.remote

import com.travelguard.app.domain.model.Destination
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * Free OpenStreetMap Nominatim geocoding client.
 * Supports place search, reverse geocoding, and direct coordinate parsing ("lat, lon").
 */
class NominatimGeocodingClient(
    private val baseUrl: String = "https://nominatim.openstreetmap.org"
) {

    sealed class SearchResult {
        data class Success(val destinations: List<Destination>) : SearchResult()
        data class Error(val message: String) : SearchResult()
    }

    fun parseCoordinates(query: String): Destination? {
        val parts = query.trim().split(",", " ").filter { it.isNotBlank() }
        if (parts.size != 2) return null
        val lat = parts[0].toDoubleOrNull() ?: return null
        val lon = parts[1].toDoubleOrNull() ?: return null
        if (lat !in -90.0..90.0 || lon !in -180.0..180.0) return null
        return Destination(
            name = String.format("%.4f, %.4f", lat, lon),
            address = "Custom coordinates",
            latitude = lat,
            longitude = lon
        )
    }

    suspend fun search(query: String): SearchResult = withContext(Dispatchers.IO) {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return@withContext SearchResult.Success(emptyList())

        parseCoordinates(trimmed)?.let { coordDest ->
            return@withContext SearchResult.Success(listOf(coordDest))
        }

        try {
            val encoded = URLEncoder.encode(trimmed, StandardCharsets.UTF_8.name())
            val url = URL("$baseUrl/search?q=$encoded&format=jsonv2&limit=5&addressdetails=1")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 8000
                readTimeout = 8000
                setRequestProperty("User-Agent", "TravelGuard-Android/1.0")
            }

            val code = conn.responseCode
            if (code == 429) {
                return@withContext SearchResult.Error("Search rate-limited by Nominatim. Try again in a moment or tap the map.")
            }
            if (code !in 200..299) {
                return@withContext SearchResult.Error("Search unavailable (HTTP $code). You can still tap the map or enter coordinates.")
            }

            val body = conn.inputStream.bufferedReader().use { it.readText() }
            val arr = JSONArray(body)
            val list = ArrayList<Destination>(arr.length())
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val displayName = obj.optString("display_name", "Selected location")
                val shortName = obj.optString("name", "").ifBlank {
                    displayName.substringBefore(",").trim()
                }
                val lat = obj.optString("lat").toDoubleOrNull() ?: continue
                val lon = obj.optString("lon").toDoubleOrNull() ?: continue
                list.add(
                    Destination(
                        name = shortName,
                        address = displayName,
                        latitude = lat,
                        longitude = lon
                    )
                )
            }
            SearchResult.Success(list)
        } catch (e: Exception) {
            SearchResult.Error("Offline or search unavailable. Tap the map or enter lat, lon coordinates.")
        }
    }

    suspend fun reverseGeocode(lat: Double, lon: Double): String? = withContext(Dispatchers.IO) {
        try {
            val url = URL("$baseUrl/reverse?lat=$lat&lon=$lon&format=jsonv2&zoom=14")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 6000
                readTimeout = 6000
                setRequestProperty("User-Agent", "TravelGuard-Android/1.0")
            }
            if (conn.responseCode !in 200..299) return@withContext null
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            val obj = JSONObject(body)
            val address = obj.optJSONObject("address")
            val locality = listOfNotNull(
                address?.optString("suburb")?.takeIf { it.isNotBlank() },
                address?.optString("city")?.takeIf { it.isNotBlank() }
                    ?: address?.optString("town")?.takeIf { it.isNotBlank() }
                    ?: address?.optString("village")?.takeIf { it.isNotBlank() },
                address?.optString("state")?.takeIf { it.isNotBlank() }
            ).distinct().joinToString(", ")
            locality.ifBlank { obj.optString("display_name").substringBefore(",").ifBlank { null } }
        } catch (_: Exception) {
            null
        }
    }
}
