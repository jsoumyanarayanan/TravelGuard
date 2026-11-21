package com.travelguard.app.navigation

import com.travelguard.app.domain.model.Destination
import com.travelguard.app.domain.model.LocationSample
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GoogleMapsDirectionsLauncherTest {

    @Test
    fun buildDirectionsUrl_includesEncodedOriginAndDestination() {
        val origin = LocationSample(13.0827, 80.2707, 10f, 5f, 0f, 1_000L)
        val dest = Destination("Airport", "Chennai", 12.9941, 80.1709)

        val url = GoogleMapsDirectionsLauncher.buildDirectionsUrl(origin, dest)
        assertTrue(url.startsWith("https://www.google.com/maps/dir/?api=1"))
        assertTrue(url.contains("origin=13.082700%2C80.270700"))
        assertTrue(url.contains("destination=12.994100%2C80.170900"))
        assertTrue(url.contains("travelmode=driving"))
    }

    @Test
    fun buildDirectionsUrl_omitsOriginWhenLocationUnavailable() {
        val dest = Destination("Airport", "Chennai", 12.9941, 80.1709)
        val url = GoogleMapsDirectionsLauncher.buildDirectionsUrl(null, dest)
        assertFalse(url.contains("origin="))
        assertTrue(url.contains("destination=12.994100%2C80.170900"))
    }
}
