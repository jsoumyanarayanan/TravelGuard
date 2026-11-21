package com.travelguard.app.domain.model

enum class JourneyStatus {
    IDLE,
    PREPARING,
    ACTIVE,
    COMPLETED,
    ERROR
}

enum class ProximityAlarmState {
    DISABLED,
    ARMED,
    TRIGGERED,
    ACKNOWLEDGED
}

enum class RouteMonitoringState {
    DISABLED,
    WAITING_FOR_ROUTE,
    MONITORING,
    DEVIATION_PENDING,
    DEVIATED,
    UNAVAILABLE
}

enum class ThemePreference {
    SYSTEM,
    LIGHT,
    DARK
}

data class LatLngPoint(
    val latitude: Double,
    val longitude: Double
)

data class Destination(
    val name: String,
    val address: String,
    val latitude: Double,
    val longitude: Double
) {
    fun isValid(): Boolean =
        latitude in -90.0..90.0 &&
            longitude in -180.0..180.0 &&
            !(latitude == 0.0 && longitude == 0.0)
}

data class LocationSample(
    val latitude: Double,
    val longitude: Double,
    val accuracyMeters: Float,
    val speedMps: Float,
    val bearingDegrees: Float,
    val timestampMillis: Long
)

data class RouteData(
    val points: List<LatLngPoint>,
    val totalDistanceMeters: Double,
    val estimatedDurationSeconds: Long,
    val retrievedAtMillis: Long,
    val originLatitude: Double,
    val originLongitude: Double,
    val destinationLatitude: Double,
    val destinationLongitude: Double
)

data class AlertEvent(
    val id: String,
    val type: String,
    val message: String,
    val timestampMillis: Long
)

data class AppSettings(
    val theme: ThemePreference = ThemePreference.SYSTEM,
    val vibrationEnabled: Boolean = true,
    val repeatAlarmUntilAck: Boolean = true,
    val alarmSoundEnabled: Boolean = true
)

data class JourneyUiState(
    val journeyStatus: JourneyStatus = JourneyStatus.IDLE,
    val destination: Destination? = null,
    val currentLocation: LocationSample? = null,
    val currentLocality: String? = null,
    val isLocationStale: Boolean = false,
    val wakeMeUpEnabled: Boolean = true,
    val wakeRadiusKm: Float = 2.0f,
    val proximityAlarmState: ProximityAlarmState = ProximityAlarmState.ARMED,
    val routeGuardEnabled: Boolean = false,
    val deviationThresholdKm: Float = 1.0f,
    val routeMonitoringState: RouteMonitoringState = RouteMonitoringState.DISABLED,
    val routeDeviationMeters: Double? = null,
    val routeData: RouteData? = null,
    val isRouteStale: Boolean = false,
    val remainingDistanceMeters: Double? = null,
    val isRemainingDistanceDirect: Boolean = false,
    val estimatedRemainingSeconds: Long? = null,
    val estimatedArrivalMillis: Long? = null,
    val progressPercent: Int? = null,
    val warningMessage: String? = null,
    val recentAlerts: List<AlertEvent> = emptyList()
)
