package com.travelguard.app.domain.monitor

import com.travelguard.app.domain.model.LatLngPoint
import com.travelguard.app.domain.model.RouteData
import com.travelguard.app.domain.model.RouteMonitoringState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RouteDeviationMonitorTest {

    private val route = RouteData(
        points = listOf(
            LatLngPoint(13.0000, 80.0000),
            LatLngPoint(13.0000, 80.1000)
        ),
        totalDistanceMeters = 10_800.0,
        estimatedDurationSeconds = 900L,
        retrievedAtMillis = 1_000L,
        originLatitude = 13.0000,
        originLongitude = 80.0000,
        destinationLatitude = 13.0000,
        destinationLongitude = 80.1000
    )

    @Test
    fun evaluate_requiresConsecutiveSamplesBeforeDeviatingAndRecovers() {
        val monitor = RouteDeviationMonitor(requiredConsecutiveSamples = 3)
        monitor.setEnabled(enabled = true, hasRoute = true)
        assertEquals(RouteMonitoringState.MONITORING, monitor.state)

        // ~2.2 km off route (> 1.0 km threshold)
        assertFalse(monitor.evaluate(13.0200, 80.0500, route, 1.0f))
        assertEquals(RouteMonitoringState.DEVIATION_PENDING, monitor.state)

        assertFalse(monitor.evaluate(13.0200, 80.0500, route, 1.0f))
        assertEquals(RouteMonitoringState.DEVIATION_PENDING, monitor.state)

        // 3rd consecutive sample triggers DEVIATED
        assertTrue(monitor.evaluate(13.0200, 80.0500, route, 1.0f))
        assertEquals(RouteMonitoringState.DEVIATED, monitor.state)

        // Returning to route recovers to MONITORING
        assertFalse(monitor.evaluate(13.0005, 80.0500, route, 1.0f))
        assertEquals(RouteMonitoringState.MONITORING, monitor.state)
    }

    @Test
    fun evaluate_keepsWaitingWhileRouteRequestIsPending() {
        val monitor = RouteDeviationMonitor()
        monitor.setEnabled(enabled = true, hasRoute = false)
        assertEquals(RouteMonitoringState.WAITING_FOR_ROUTE, monitor.state)

        assertFalse(monitor.evaluate(13.0200, 80.0500, null, 1.0f))
        assertEquals(RouteMonitoringState.WAITING_FOR_ROUTE, monitor.state)
    }

    @Test
    fun markRouteUnavailable_changesWaitingStateOnlyAfterFetchFailure() {
        val monitor = RouteDeviationMonitor()
        monitor.setEnabled(enabled = true, hasRoute = false)
        monitor.markRouteUnavailable()
        assertEquals(RouteMonitoringState.UNAVAILABLE, monitor.state)
    }
}
