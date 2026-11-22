package com.travelguard.app.domain.monitor

import com.travelguard.app.domain.geo.GeoMath
import com.travelguard.app.domain.model.LatLngPoint
import com.travelguard.app.domain.model.RouteData
import com.travelguard.app.domain.model.RouteMonitoringState

/**
 * Independent Route Guard state machine with sustained-sample confirmation
 * to avoid false positives from GPS drift, bridges, or parallel roads.
 */
class RouteDeviationMonitor(
    private val requiredConsecutiveSamples: Int = 3
) {
    var state: RouteMonitoringState = RouteMonitoringState.DISABLED
        private set

    var lastDeviationMeters: Double? = null
        private set

    private var outsideCount: Int = 0

    fun setEnabled(enabled: Boolean, hasRoute: Boolean) {
        if (!enabled) {
            state = RouteMonitoringState.DISABLED
            outsideCount = 0
            lastDeviationMeters = null
        } else {
            state = if (hasRoute) RouteMonitoringState.MONITORING else RouteMonitoringState.WAITING_FOR_ROUTE
            outsideCount = 0
        }
    }

    fun markRouteUnavailable() {
        if (state != RouteMonitoringState.DISABLED) {
            state = RouteMonitoringState.UNAVAILABLE
            outsideCount = 0
            lastDeviationMeters = null
        }
    }

    fun markRouteLoaded() {
        if (state != RouteMonitoringState.DISABLED) {
            state = RouteMonitoringState.MONITORING
            outsideCount = 0
        }
    }

    /**
     * Returns true ONLY when transitioning into DEVIATED.
     */
    fun evaluate(
        currentLat: Double,
        currentLon: Double,
        route: RouteData?,
        thresholdKm: Float
    ): Boolean {
        if (state == RouteMonitoringState.DISABLED) return false
        if (route == null || route.points.size < 2) {
            lastDeviationMeters = null
            // A route request may still be in flight. Keep the UI in WAITING_FOR_ROUTE
            // until the caller explicitly reports a network/routing failure.
            if (state != RouteMonitoringState.WAITING_FOR_ROUTE) {
                state = RouteMonitoringState.UNAVAILABLE
            }
            return false
        }

        val (distanceMeters, _) = GeoMath.distanceToPolylineMeters(
            LatLngPoint(currentLat, currentLon),
            route.points
        )
        lastDeviationMeters = distanceMeters
        val thresholdMeters = GeoMath.kmToMeters(thresholdKm.coerceIn(0.2f, 5.0f))

        if (distanceMeters > thresholdMeters) {
            outsideCount++
            if (outsideCount >= requiredConsecutiveSamples) {
                val newlyDeviated = state != RouteMonitoringState.DEVIATED
                state = RouteMonitoringState.DEVIATED
                return newlyDeviated
            } else {
                state = RouteMonitoringState.DEVIATION_PENDING
                return false
            }
        } else {
            outsideCount = 0
            state = RouteMonitoringState.MONITORING
            return false
        }
    }
}
