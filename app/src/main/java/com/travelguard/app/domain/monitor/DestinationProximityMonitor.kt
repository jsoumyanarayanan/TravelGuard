package com.travelguard.app.domain.monitor

import com.travelguard.app.domain.geo.GeoMath
import com.travelguard.app.domain.model.Destination
import com.travelguard.app.domain.model.ProximityAlarmState

/**
 * Deterministic state machine for Wake Me Up destination proximity monitoring.
 * Operates purely on coordinates so it functions 100% offline.
 */
class DestinationProximityMonitor(
    initialState: ProximityAlarmState = ProximityAlarmState.ARMED
) {
    var state: ProximityAlarmState = initialState
        private set

    fun setEnabled(enabled: Boolean) {
        state = if (enabled) {
            if (state == ProximityAlarmState.DISABLED) ProximityAlarmState.ARMED else state
        } else {
            ProximityAlarmState.DISABLED
        }
    }

    fun acknowledge() {
        if (state == ProximityAlarmState.TRIGGERED) {
            state = ProximityAlarmState.ACKNOWLEDGED
        }
    }

    fun resetForNewJourney(enabled: Boolean) {
        state = if (enabled) ProximityAlarmState.ARMED else ProximityAlarmState.DISABLED
    }

    /**
     * Evaluates current distance to [destination] against [radiusKm].
     * Returns true ONLY on the transition into TRIGGERED so callers do not fire repeated alarm storms.
     */
    fun evaluate(
        currentLat: Double,
        currentLon: Double,
        destination: Destination,
        radiusKm: Float
    ): Boolean {
        if (state == ProximityAlarmState.DISABLED || !destination.isValid()) {
            return false
        }
        val distanceMeters = GeoMath.distanceMeters(
            currentLat,
            currentLon,
            destination.latitude,
            destination.longitude
        )
        val radiusMeters = GeoMath.kmToMeters(radiusKm.coerceIn(0.1f, 10.0f))
        val rearmMeters = radiusMeters + REARM_HYSTERESIS_METERS

        return when (state) {
            ProximityAlarmState.ARMED -> {
                if (distanceMeters <= radiusMeters) {
                    state = ProximityAlarmState.TRIGGERED
                    true
                } else {
                    false
                }
            }
            ProximityAlarmState.TRIGGERED -> false
            ProximityAlarmState.ACKNOWLEDGED -> {
                if (distanceMeters > rearmMeters) {
                    state = ProximityAlarmState.ARMED
                }
                false
            }
            ProximityAlarmState.DISABLED -> false
        }
    }

    companion object {
        private const val REARM_HYSTERESIS_METERS = 250.0
    }
}
