package com.travelguard.app.domain.geo

import com.travelguard.app.domain.model.LatLngPoint
import com.travelguard.app.domain.model.LocationSample
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GeoMathTest {

    @Test
    fun kmToMeters_convertsAccurately() {
        assertEquals(2000.0, GeoMath.kmToMeters(2.0f), 0.01)
        assertEquals(100.0, GeoMath.kmToMeters(0.1f), 0.01)
    }

    @Test
    fun distanceMeters_chennaiCentralToAirport_isPlausible() {
        val d = GeoMath.distanceMeters(13.0827, 80.2750, 12.9941, 80.1709)
        assertTrue("Expected ~14-16 km, got $d", d in 13_500.0..16_500.0)
    }

    @Test
    fun isSampleReliable_rejectsLowAccuracyAndImplausibleJump() {
        val prev = LocationSample(13.0827, 80.2750, 15f, 10f, 0f, 1_000_000L)
        val poorAccuracy = LocationSample(13.0830, 80.2752, 300f, 10f, 0f, 1_005_000L)
        assertFalse(GeoMath.isSampleReliable(prev, poorAccuracy))

        val teleportJump = LocationSample(14.5000, 80.2750, 15f, 10f, 0f, 1_005_000L)
        assertFalse(GeoMath.isSampleReliable(prev, teleportJump))

        val normalMove = LocationSample(13.0835, 80.2755, 12f, 12f, 0f, 1_010_000L)
        assertTrue(GeoMath.isSampleReliable(prev, normalMove))
    }

    @Test
    fun distanceToPolylineMeters_computesPerpendicularDistance() {
        val route = listOf(
            LatLngPoint(13.0000, 80.0000),
            LatLngPoint(13.0000, 80.0500)
        )
        // ~1.11 km north of the east-west segment
        val (dist, idx) = GeoMath.distanceToPolylineMeters(LatLngPoint(13.0100, 80.0250), route)
        assertEquals(0, idx)
        assertTrue("Expected ~1111m, got $dist", dist in 1050.0..1180.0)
    }

    @Test
    fun computeProgressPercent_clampsToZeroAndHundred() {
        assertEquals(0, GeoMath.computeProgressPercent(10_000.0, 12_000.0))
        assertEquals(50, GeoMath.computeProgressPercent(10_000.0, 5_000.0))
        assertEquals(100, GeoMath.computeProgressPercent(10_000.0, 0.0))
    }
}
