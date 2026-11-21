package com.travelguard.app.domain.monitor

import com.travelguard.app.domain.model.Destination
import com.travelguard.app.domain.model.ProximityAlarmState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DestinationProximityMonitorTest {

    private val dest = Destination("Central Station", "Chennai", 13.0827, 80.2750)

    @Test
    fun evaluate_triggersOnceInsideRadiusAndDoesNotStorm() {
        val monitor = DestinationProximityMonitor(ProximityAlarmState.ARMED)

        // Far away (~15 km)
        assertFalse(monitor.evaluate(12.9941, 80.1709, dest, 2.0f))
        assertEquals(ProximityAlarmState.ARMED, monitor.state)

        // Inside 2.0 km radius (~0.5 km away)
        assertTrue(monitor.evaluate(13.0860, 80.2750, dest, 2.0f))
        assertEquals(ProximityAlarmState.TRIGGERED, monitor.state)

        // Subsequent sample still inside must NOT re-trigger
        assertFalse(monitor.evaluate(13.0850, 80.2750, dest, 2.0f))
        assertEquals(ProximityAlarmState.TRIGGERED, monitor.state)
    }

    @Test
    fun acknowledge_stopsRepeatAndRearmsOnlyAfterExitingHysteresis() {
        val monitor = DestinationProximityMonitor(ProximityAlarmState.ARMED)
        assertTrue(monitor.evaluate(13.0840, 80.2750, dest, 2.0f))
        monitor.acknowledge()
        assertEquals(ProximityAlarmState.ACKNOWLEDGED, monitor.state)

        // Still inside radius -> stays ACKNOWLEDGED
        assertFalse(monitor.evaluate(13.0830, 80.2750, dest, 2.0f))
        assertEquals(ProximityAlarmState.ACKNOWLEDGED, monitor.state)

        // Exits well beyond 2.0 km + 250 m hysteresis (~15 km away) -> re-arms
        assertFalse(monitor.evaluate(12.9941, 80.1709, dest, 2.0f))
        assertEquals(ProximityAlarmState.ARMED, monitor.state)
    }

    @Test
    fun disabledMonitor_neverTriggers() {
        val monitor = DestinationProximityMonitor(ProximityAlarmState.DISABLED)
        assertFalse(monitor.evaluate(13.0827, 80.2750, dest, 2.0f))
        assertEquals(ProximityAlarmState.DISABLED, monitor.state)
    }
}
