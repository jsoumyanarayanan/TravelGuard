package com.travelguard.app.data

import android.content.Context
import com.travelguard.app.data.local.TravelGuardPreferences
import com.travelguard.app.domain.model.AlertEvent
import com.travelguard.app.domain.model.Destination
import com.travelguard.app.domain.model.JourneyStatus
import com.travelguard.app.domain.model.JourneyUiState
import com.travelguard.app.domain.model.ProximityAlarmState
import com.travelguard.app.domain.model.RouteData
import com.travelguard.app.domain.model.RouteMonitoringState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Single authoritative in-process state store shared between LocationTrackingService and UI.
 */
object JourneyStateStore {

    private val _uiState = MutableStateFlow(JourneyUiState())
    val uiState: StateFlow<JourneyUiState> = _uiState.asStateFlow()

    @Volatile
    private var initialized = false

    fun initFromPrefs(context: Context) {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            val prefs = TravelGuardPreferences(context)
            val wakeEnabled = prefs.isWakeMeUpEnabled()
            val routeEnabled = prefs.isRouteGuardEnabled()
            val cachedRoute = prefs.getCachedRoute()
            val savedStatus = prefs.getJourneyStatus()
            val savedProximity = if (wakeEnabled) prefs.getProximityState() else ProximityAlarmState.DISABLED

            _uiState.value = JourneyUiState(
                journeyStatus = savedStatus,
                destination = prefs.getDestination(),
                wakeMeUpEnabled = wakeEnabled,
                wakeRadiusKm = prefs.getWakeRadiusKm(),
                proximityAlarmState = savedProximity,
                routeGuardEnabled = routeEnabled,
                deviationThresholdKm = prefs.getDeviationThresholdKm(),
                routeMonitoringState = when {
                    !routeEnabled -> RouteMonitoringState.DISABLED
                    cachedRoute != null -> RouteMonitoringState.MONITORING
                    else -> RouteMonitoringState.WAITING_FOR_ROUTE
                },
                routeData = cachedRoute,
                recentAlerts = prefs.getRecentAlerts()
            )
            initialized = true
        }
    }

    fun update(transform: (JourneyUiState) -> JourneyUiState) {
        _uiState.update(transform)
    }

    fun setDestination(context: Context, destination: Destination?) {
        TravelGuardPreferences(context).saveDestination(destination)
        if (destination == null) {
            TravelGuardPreferences(context).saveCachedRoute(null)
        }
        _uiState.update {
            it.copy(
                destination = destination,
                routeData = if (destination == null) null else it.routeData,
                remainingDistanceMeters = if (destination == null) null else it.remainingDistanceMeters,
                estimatedRemainingSeconds = if (destination == null) null else it.estimatedRemainingSeconds,
                estimatedArrivalMillis = if (destination == null) null else it.estimatedArrivalMillis,
                progressPercent = if (destination == null) null else it.progressPercent
            )
        }
    }

    fun setRouteData(context: Context, route: RouteData?) {
        TravelGuardPreferences(context).saveCachedRoute(route)
        _uiState.update {
            it.copy(
                routeData = route,
                isRouteStale = false,
                routeMonitoringState = when {
                    !it.routeGuardEnabled -> RouteMonitoringState.DISABLED
                    route != null -> RouteMonitoringState.MONITORING
                    else -> RouteMonitoringState.UNAVAILABLE
                }
            )
        }
    }

    fun addAlert(context: Context, type: String, message: String) {
        val event = AlertEvent(
            id = "${System.currentTimeMillis()}-$type",
            type = type,
            message = message,
            timestampMillis = System.currentTimeMillis()
        )
        _uiState.update { state ->
            val updated = (listOf(event) + state.recentAlerts).take(10)
            TravelGuardPreferences(context).saveRecentAlerts(updated)
            state.copy(recentAlerts = updated)
        }
    }
}
