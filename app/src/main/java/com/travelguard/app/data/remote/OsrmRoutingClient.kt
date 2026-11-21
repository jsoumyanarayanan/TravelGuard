package com.travelguard.app.data.remote

import com.travelguard.app.domain.model.LatLngPoint
import com.travelguard.app.domain.model.RouteData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

/**
 * Free OSRM routing client (no API key required).
 */
class OsrmRoutingClient(
    private val baseUrl: String = "https://router.project-osrm.org"
) {

    sealed class RouteResult {
        data class Success(val route: RouteData) : RouteResult()
        data class Unavailable(val reason: String) : RouteResult()
    }

    suspend fun fetchRoute(
        originLat: Double,
        originLon: Double,
        destLat: Double,
        destLon: Double
    ): RouteResult = withContext(Dispatchers.IO) {
        try {
            val coords = String.format(
                Locale.US,
                "%.6f,%.6f;%.6f,%.6f",
                originLon,
                originLat,
                destLon,
                destLat
            )
            val url = URL("$baseUrl/route/v1/driving/$coords?overview=full&geometries=geojson")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 9000
                readTimeout = 9000
                setRequestProperty("User-Agent", "TravelGuard-Android/1.0")
            }

            if (conn.responseCode !in 200..299) {
                return@withContext RouteResult.Unavailable("Routing service returned HTTP ${conn.responseCode}.")
            }

            val body = conn.inputStream.bufferedReader().use { it.readText() }
            val json = JSONObject(body)
            if (json.optString("code") != "Ok") {
                return@withContext RouteResult.Unavailable("No drivable route found between these points.")
            }

            val routes = json.getJSONArray("routes")
            if (routes.length() == 0) {
                return@withContext RouteResult.Unavailable("No route returned.")
            }

            val firstRoute = routes.getJSONObject(0)
            val distanceMeters = firstRoute.getDouble("distance")
            val durationSeconds = firstRoute.getDouble("duration").toLong()
            val coordsArr = firstRoute.getJSONObject("geometry").getJSONArray("coordinates")
            val points = ArrayList<LatLngPoint>(coordsArr.length())
            for (i in 0 until coordsArr.length()) {
                val pt = coordsArr.getJSONArray(i)
                points.add(LatLngPoint(latitude = pt.getDouble(1), longitude = pt.getDouble(0)))
            }

            RouteResult.Success(
                RouteData(
                    points = points,
                    totalDistanceMeters = distanceMeters,
                    estimatedDurationSeconds = durationSeconds,
                    retrievedAtMillis = System.currentTimeMillis(),
                    originLatitude = originLat,
                    originLongitude = originLon,
                    destinationLatitude = destLat,
                    destinationLongitude = destLon
                )
            )
        } catch (_: Exception) {
            RouteResult.Unavailable("Route unavailable (offline or routing service unreachable).")
        }
    }
}
