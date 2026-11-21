package com.travelguard.app.service

import android.Manifest
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.IBinder
import android.os.Looper
import androidx.core.content.ContextCompat
import com.travelguard.app.alarm.AlarmController
import com.travelguard.app.data.JourneyStateStore
import com.travelguard.app.data.local.TravelGuardPreferences
import com.travelguard.app.data.remote.NominatimGeocodingClient
import com.travelguard.app.data.remote.OsrmRoutingClient
import com.travelguard.app.domain.geo.GeoMath
import com.travelguard.app.domain.model.JourneyStatus
import com.travelguard.app.domain.model.LatLngPoint
import com.travelguard.app.domain.model.LocationSample
import com.travelguard.app.domain.model.ProximityAlarmState
import com.travelguard.app.domain.model.RouteMonitoringState
import com.travelguard.app.domain.monitor.DestinationProximityMonitor
import com.travelguard.app.domain.monitor.RouteDeviationMonitor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class LocationTrackingService : Service(), LocationListener {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var locationManager: LocationManager
    private lateinit var alarmController: AlarmController
    private lateinit var prefs: TravelGuardPreferences
    private val geocodingClient = NominatimGeocodingClient()
    private val routingClient = OsrmRoutingClient()

    private val proximityMonitor = DestinationProximityMonitor()
    private val deviationMonitor = RouteDeviationMonitor()

    private var lastReliableSample: LocationSample? = null
    private var lastLocalityLookupTime = 0L
    private var isHighPrecisionInterval = false

    override fun onCreate() {
        super.onCreate()
        JourneyStateStore.initFromPrefs(this)
        locationManager = getSystemService(LOCATION_SERVICE) as LocationManager
        alarmController = AlarmController(this)
        prefs = TravelGuardPreferences(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_JOURNEY -> startJourney()
            ACTION_STOP_JOURNEY -> stopJourney()
            ACTION_ACKNOWLEDGE_ALARM -> acknowledgeAlarm()
            ACTION_REFRESH_CONFIG -> syncConfigFromStore()
        }
        return START_STICKY
    }

    private fun startJourney() {
        val state = JourneyStateStore.uiState.value
        val dest = state.destination
        if (dest == null || !dest.isValid()) {
            JourneyStateStore.update {
                it.copy(
                    journeyStatus = JourneyStatus.ERROR,
                    warningMessage = "Select a valid destination before starting the journey."
                )
            }
            stopSelf()
            return
        }

        proximityMonitor.resetForNewJourney(state.wakeMeUpEnabled)
        deviationMonitor.setEnabled(state.routeGuardEnabled, state.routeData != null)
        prefs.saveJourneyRuntimeState(JourneyStatus.ACTIVE, proximityMonitor.state)

        JourneyStateStore.update {
            it.copy(
                journeyStatus = JourneyStatus.ACTIVE,
                proximityAlarmState = proximityMonitor.state,
                routeMonitoringState = deviationMonitor.state,
                warningMessage = null
            )
        }

        startForeground(
            AlarmController.MONITORING_NOTIFICATION_ID,
            alarmController.buildMonitoringNotification(
                destinationName = dest.name,
                wakeEnabled = state.wakeMeUpEnabled,
                routeEnabled = state.routeGuardEnabled
            )
        )

        requestLocationUpdates(highPrecision = false)
        seedLastKnownAndFetchRoute()
    }

    private fun seedLastKnownAndFetchRoute() {
        val hasFine = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val hasCoarse = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!hasFine && !hasCoarse) return

        val lastGps = runCatching { locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER) }.getOrNull()
        val lastNet = runCatching { locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER) }.getOrNull()
        val best = listOfNotNull(lastGps, lastNet).maxByOrNull { it.time }

        if (best != null) {
            onLocationChanged(best)
            val state = JourneyStateStore.uiState.value
            val dest = state.destination ?: return
            if (state.routeData == null) {
                serviceScope.launch {
                    when (val result = routingClient.fetchRoute(best.latitude, best.longitude, dest.latitude, dest.longitude)) {
                        is OsrmRoutingClient.RouteResult.Success -> {
                            JourneyStateStore.setRouteData(this@LocationTrackingService, result.route)
                            deviationMonitor.markRouteLoaded()
                        }
                        is OsrmRoutingClient.RouteResult.Unavailable -> {
                            deviationMonitor.markRouteUnavailable()
                            JourneyStateStore.update {
                                it.copy(
                                    routeMonitoringState = deviationMonitor.state,
                                    warningMessage = result.reason
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    private fun syncConfigFromStore() {
        val state = JourneyStateStore.uiState.value
        proximityMonitor.setEnabled(state.wakeMeUpEnabled)
        if (!state.wakeMeUpEnabled) {
            alarmController.stopWakeAlarm()
        }
        deviationMonitor.setEnabled(state.routeGuardEnabled, state.routeData != null)
        prefs.saveJourneyRuntimeState(state.journeyStatus, proximityMonitor.state)

        JourneyStateStore.update {
            it.copy(
                proximityAlarmState = proximityMonitor.state,
                routeMonitoringState = deviationMonitor.state
            )
        }

        val destName = state.destination?.name ?: "Destination"
        if (state.journeyStatus == JourneyStatus.ACTIVE) {
            val manager = getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager
            manager.notify(
                AlarmController.MONITORING_NOTIFICATION_ID,
                alarmController.buildMonitoringNotification(
                    destinationName = destName,
                    wakeEnabled = state.wakeMeUpEnabled,
                    routeEnabled = state.routeGuardEnabled
                )
            )
        }
    }

    private fun acknowledgeAlarm() {
        alarmController.stopWakeAlarm()
        proximityMonitor.acknowledge()
        prefs.saveJourneyRuntimeState(JourneyStateStore.uiState.value.journeyStatus, proximityMonitor.state)
        JourneyStateStore.update {
            it.copy(proximityAlarmState = proximityMonitor.state)
        }
        JourneyStateStore.addAlert(this, "Wake Me Up", "Alarm acknowledged (I'm Awake).")
    }

    private fun stopJourney() {
        alarmController.stopWakeAlarm()
        runCatching { locationManager.removeUpdates(this) }
        prefs.saveJourneyRuntimeState(JourneyStatus.COMPLETED, ProximityAlarmState.DISABLED)
        JourneyStateStore.update {
            it.copy(
                journeyStatus = JourneyStatus.COMPLETED,
                proximityAlarmState = if (it.wakeMeUpEnabled) ProximityAlarmState.ARMED else ProximityAlarmState.DISABLED,
                routeMonitoringState = if (it.routeGuardEnabled) RouteMonitoringState.WAITING_FOR_ROUTE else RouteMonitoringState.DISABLED
            )
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun requestLocationUpdates(highPrecision: Boolean) {
        val hasFine = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val hasCoarse = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!hasFine && !hasCoarse) {
            JourneyStateStore.update {
                it.copy(warningMessage = "Location permission is required for journey monitoring.")
            }
            return
        }

        runCatching { locationManager.removeUpdates(this) }
        val minTimeMs = if (highPrecision) 3000L else 10_000L
        val minDistM = if (highPrecision) 10f else 25f
        isHighPrecisionInterval = highPrecision

        if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
            locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, minTimeMs, minDistM, this, Looper.getMainLooper())
        }
        if (locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
            locationManager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, minTimeMs * 2, minDistM * 2, this, Looper.getMainLooper())
        }
    }

    override fun onLocationChanged(location: Location) {
        val sample = LocationSample(
            latitude = location.latitude,
            longitude = location.longitude,
            accuracyMeters = location.accuracy,
            speedMps = location.speed,
            bearingDegrees = location.bearing,
            timestampMillis = location.time.takeIf { it > 0 } ?: System.currentTimeMillis()
        )

        if (!GeoMath.isSampleReliable(lastReliableSample, sample)) {
            return
        }
        lastReliableSample = sample

        val state = JourneyStateStore.uiState.value
        val dest = state.destination ?: return
        val directDistance = GeoMath.distanceMeters(sample.latitude, sample.longitude, dest.latitude, dest.longitude)

        // Adaptive GPS sampling: tighten when within 2x wake radius
        val shouldUseHighPrecision = directDistance <= GeoMath.kmToMeters(state.wakeRadiusKm) * 2.0
        if (shouldUseHighPrecision != isHighPrecisionInterval && state.journeyStatus == JourneyStatus.ACTIVE) {
            requestLocationUpdates(shouldUseHighPrecision)
        }

        // Evaluate Wake Me Up (100% offline)
        val newlyTriggeredWake = proximityMonitor.evaluate(
            sample.latitude,
            sample.longitude,
            dest,
            state.wakeRadiusKm
        )
        if (newlyTriggeredWake) {
            alarmController.triggerWakeAlarm(dest.name, directDistance)
            JourneyStateStore.addAlert(
                this,
                "Wake Me Up",
                "Entered ${String.format("%.1f", state.wakeRadiusKm)} km radius of ${dest.name}."
            )
        }

        // Evaluate Route Guard
        val newlyDeviated = deviationMonitor.evaluate(
            sample.latitude,
            sample.longitude,
            state.routeData,
            state.deviationThresholdKm
        )
        if (newlyDeviated) {
            val devMeters = deviationMonitor.lastDeviationMeters ?: 0.0
            alarmController.notifyRouteDeviation(devMeters)
            JourneyStateStore.addAlert(
                this,
                "Route Guard",
                "You appear to be ${String.format("%.2f", devMeters / 1000.0)} km away from the planned route."
            )
        }

        // Compute honest metrics
        val route = state.routeData
        val remainingMeters: Double
        val isDirect: Boolean
        val remainingSeconds: Long?
        val progressPercent: Int?

        if (route != null && route.points.size >= 2) {
            remainingMeters = GeoMath.remainingRouteDistanceMeters(
                LatLngPoint(sample.latitude, sample.longitude),
                route.points
            )
            isDirect = false
            val speed = if (sample.speedMps > 2.0f) sample.speedMps.toDouble() else {
                if (route.estimatedDurationSeconds > 0) route.totalDistanceMeters / route.estimatedDurationSeconds else 11.0
            }
            remainingSeconds = (remainingMeters / speed).toLong().coerceAtLeast(0L)
            progressPercent = GeoMath.computeProgressPercent(route.totalDistanceMeters, remainingMeters)
        } else {
            remainingMeters = directDistance
            isDirect = true
            remainingSeconds = if (sample.speedMps > 2.0f) (directDistance / sample.speedMps).toLong() else null
            progressPercent = null
        }

        val arrivalMillis = remainingSeconds?.let { System.currentTimeMillis() + it * 1000L }

        prefs.saveJourneyRuntimeState(state.journeyStatus, proximityMonitor.state)

        JourneyStateStore.update {
            it.copy(
                currentLocation = sample,
                isLocationStale = false,
                proximityAlarmState = proximityMonitor.state,
                routeMonitoringState = deviationMonitor.state,
                routeDeviationMeters = deviationMonitor.lastDeviationMeters,
                remainingDistanceMeters = remainingMeters,
                isRemainingDistanceDirect = isDirect,
                estimatedRemainingSeconds = remainingSeconds,
                estimatedArrivalMillis = arrivalMillis,
                progressPercent = progressPercent
            )
        }

        // Reverse-geocode locality sparingly (at most once every 90 seconds)
        val now = System.currentTimeMillis()
        if (now - lastLocalityLookupTime > 90_000L) {
            lastLocalityLookupTime = now
            serviceScope.launch {
                val locality = geocodingClient.reverseGeocode(sample.latitude, sample.longitude)
                if (locality != null) {
                    JourneyStateStore.update { it.copy(currentLocality = locality) }
                }
            }
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}

    override fun onProviderDisabled(provider: String) {
        if (provider == LocationManager.GPS_PROVIDER) {
            JourneyStateStore.update {
                it.copy(warningMessage = "GPS is disabled. Enable location services for accurate monitoring.")
            }
        }
    }

    override fun onDestroy() {
        serviceScope.cancel()
        runCatching { locationManager.removeUpdates(this) }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_START_JOURNEY = "com.travelguard.action.START_JOURNEY"
        const val ACTION_STOP_JOURNEY = "com.travelguard.action.STOP_JOURNEY"
        const val ACTION_ACKNOWLEDGE_ALARM = "com.travelguard.action.ACK_ALARM"
        const val ACTION_REFRESH_CONFIG = "com.travelguard.action.REFRESH_CONFIG"
    }
}
