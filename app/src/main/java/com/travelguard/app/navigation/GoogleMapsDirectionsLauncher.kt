package com.travelguard.app.navigation

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.travelguard.app.domain.model.Destination
import com.travelguard.app.domain.model.LocationSample
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Locale

object GoogleMapsDirectionsLauncher {

    /**
     * Builds a real Google Maps directions URL using actual GPS coordinates when available.
     * Never injects hardcoded placeholder coordinates.
     */
    fun buildDirectionsUrl(
        origin: LocationSample?,
        destination: Destination,
        travelMode: String = "driving"
    ): String {
        val destParam = String.format(
            Locale.US,
            "%.6f,%.6f",
            destination.latitude,
            destination.longitude
        )
        val encodedDest = URLEncoder.encode(destParam, StandardCharsets.UTF_8.name())
        val encodedMode = URLEncoder.encode(travelMode, StandardCharsets.UTF_8.name())

        return if (origin != null) {
            val originParam = String.format(
                Locale.US,
                "%.6f,%.6f",
                origin.latitude,
                origin.longitude
            )
            val encodedOrigin = URLEncoder.encode(originParam, StandardCharsets.UTF_8.name())
            "https://www.google.com/maps/dir/?api=1&origin=$encodedOrigin&destination=$encodedDest&travelmode=$encodedMode"
        } else {
            "https://www.google.com/maps/dir/?api=1&destination=$encodedDest&travelmode=$encodedMode"
        }
    }

    sealed class LaunchResult {
        data object LaunchedApp : LaunchResult()
        data object LaunchedBrowser : LaunchResult()
        data class Failed(val reason: String) : LaunchResult()
    }

    fun openDirections(
        context: Context,
        origin: LocationSample?,
        destination: Destination?
    ): LaunchResult {
        if (destination == null || !destination.isValid()) {
            return LaunchResult.Failed("Select a valid destination first.")
        }
        val url = buildDirectionsUrl(origin, destination)
        val uri = Uri.parse(url)

        val mapsIntent = Intent(Intent.ACTION_VIEW, uri).apply {
            setPackage("com.google.android.apps.maps")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            context.startActivity(mapsIntent)
            return LaunchResult.LaunchedApp
        } catch (_: ActivityNotFoundException) {
            // Fall through to browser
        }

        val browserIntent = Intent(Intent.ACTION_VIEW, uri).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return try {
            context.startActivity(browserIntent)
            LaunchResult.LaunchedBrowser
        } catch (_: ActivityNotFoundException) {
            LaunchResult.Failed("No maps app or web browser is available to open directions.")
        }
    }
}
