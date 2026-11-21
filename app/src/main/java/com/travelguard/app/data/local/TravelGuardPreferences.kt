package com.travelguard.app.data.local

import android.content.Context
import android.content.SharedPreferences
import com.travelguard.app.domain.model.AlertEvent
import com.travelguard.app.domain.model.AppSettings
import com.travelguard.app.domain.model.Destination
import com.travelguard.app.domain.model.JourneyStatus
import com.travelguard.app.domain.model.LatLngPoint
import com.travelguard.app.domain.model.ProximityAlarmState
import com.travelguard.app.domain.model.RouteData
import com.travelguard.app.domain.model.ThemePreference
import org.json.JSONArray
import org.json.JSONObject

class TravelGuardPreferences(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun saveSettings(settings: AppSettings) {
        prefs.edit()
            .putString(KEY_THEME, settings.theme.name)
            .putBoolean(KEY_VIBRATION, settings.vibrationEnabled)
            .putBoolean(KEY_REPEAT_ALARM, settings.repeatAlarmUntilAck)
            .putBoolean(KEY_ALARM_SOUND, settings.alarmSoundEnabled)
            .apply()
    }

    fun getSettings(): AppSettings {
        val themeName = prefs.getString(KEY_THEME, ThemePreference.SYSTEM.name)
        val theme = runCatching { ThemePreference.valueOf(themeName ?: ThemePreference.SYSTEM.name) }
            .getOrDefault(ThemePreference.SYSTEM)
        return AppSettings(
            theme = theme,
            vibrationEnabled = prefs.getBoolean(KEY_VIBRATION, true),
            repeatAlarmUntilAck = prefs.getBoolean(KEY_REPEAT_ALARM, true),
            alarmSoundEnabled = prefs.getBoolean(KEY_ALARM_SOUND, true)
        )
    }

    fun saveDestination(destination: Destination?) {
        if (destination == null) {
            prefs.edit()
                .remove(KEY_DEST_NAME)
                .remove(KEY_DEST_ADDR)
                .remove(KEY_DEST_LAT)
                .remove(KEY_DEST_LON)
                .apply()
        } else {
            prefs.edit()
                .putString(KEY_DEST_NAME, destination.name)
                .putString(KEY_DEST_ADDR, destination.address)
                .putString(KEY_DEST_LAT, destination.latitude.toString())
                .putString(KEY_DEST_LON, destination.longitude.toString())
                .apply()
        }
    }

    fun getDestination(): Destination? {
        val name = prefs.getString(KEY_DEST_NAME, null) ?: return null
        val addr = prefs.getString(KEY_DEST_ADDR, "") ?: ""
        val lat = prefs.getString(KEY_DEST_LAT, null)?.toDoubleOrNull() ?: return null
        val lon = prefs.getString(KEY_DEST_LON, null)?.toDoubleOrNull() ?: return null
        return Destination(name, addr, lat, lon)
    }

    fun saveAlarmConfig(
        wakeMeUpEnabled: Boolean,
        wakeRadiusKm: Float,
        routeGuardEnabled: Boolean,
        deviationThresholdKm: Float
    ) {
        prefs.edit()
            .putBoolean(KEY_WAKE_ENABLED, wakeMeUpEnabled)
            .putFloat(KEY_WAKE_RADIUS_KM, wakeRadiusKm.coerceIn(0.1f, 10.0f))
            .putBoolean(KEY_ROUTE_GUARD_ENABLED, routeGuardEnabled)
            .putFloat(KEY_DEVIATION_THRESHOLD_KM, deviationThresholdKm.coerceIn(0.2f, 5.0f))
            .apply()
    }

    fun isWakeMeUpEnabled(): Boolean = prefs.getBoolean(KEY_WAKE_ENABLED, true)
    fun getWakeRadiusKm(): Float = prefs.getFloat(KEY_WAKE_RADIUS_KM, 2.0f).coerceIn(0.1f, 10.0f)
    fun isRouteGuardEnabled(): Boolean = prefs.getBoolean(KEY_ROUTE_GUARD_ENABLED, false)
    fun getDeviationThresholdKm(): Float = prefs.getFloat(KEY_DEVIATION_THRESHOLD_KM, 1.0f).coerceIn(0.2f, 5.0f)

    fun saveJourneyRuntimeState(
        status: JourneyStatus,
        proximityState: ProximityAlarmState
    ) {
        prefs.edit()
            .putString(KEY_JOURNEY_STATUS, status.name)
            .putString(KEY_PROXIMITY_STATE, proximityState.name)
            .apply()
    }

    fun getJourneyStatus(): JourneyStatus {
        val raw = prefs.getString(KEY_JOURNEY_STATUS, JourneyStatus.IDLE.name)
        return runCatching { JourneyStatus.valueOf(raw ?: JourneyStatus.IDLE.name) }
            .getOrDefault(JourneyStatus.IDLE)
    }

    fun getProximityState(): ProximityAlarmState {
        val raw = prefs.getString(KEY_PROXIMITY_STATE, ProximityAlarmState.ARMED.name)
        return runCatching { ProximityAlarmState.valueOf(raw ?: ProximityAlarmState.ARMED.name) }
            .getOrDefault(ProximityAlarmState.ARMED)
    }

    fun saveCachedRoute(route: RouteData?) {
        if (route == null) {
            prefs.edit().remove(KEY_CACHED_ROUTE).apply()
            return
        }
        val pts = JSONArray()
        for (p in route.points) {
            pts.put(JSONArray().put(p.latitude).put(p.longitude))
        }
        val obj = JSONObject().apply {
            put("points", pts)
            put("distance", route.totalDistanceMeters)
            put("duration", route.estimatedDurationSeconds)
            put("retrievedAt", route.retrievedAtMillis)
            put("origLat", route.originLatitude)
            put("origLon", route.originLongitude)
            put("destLat", route.destinationLatitude)
            put("destLon", route.destinationLongitude)
        }
        prefs.edit().putString(KEY_CACHED_ROUTE, obj.toString()).apply()
    }

    fun getCachedRoute(): RouteData? {
        val raw = prefs.getString(KEY_CACHED_ROUTE, null) ?: return null
        return runCatching {
            val obj = JSONObject(raw)
            val arr = obj.getJSONArray("points")
            val pts = ArrayList<LatLngPoint>(arr.length())
            for (i in 0 until arr.length()) {
                val pair = arr.getJSONArray(i)
                pts.add(LatLngPoint(pair.getDouble(0), pair.getDouble(1)))
            }
            RouteData(
                points = pts,
                totalDistanceMeters = obj.getDouble("distance"),
                estimatedDurationSeconds = obj.getLong("duration"),
                retrievedAtMillis = obj.getLong("retrievedAt"),
                originLatitude = obj.getDouble("origLat"),
                originLongitude = obj.getDouble("origLon"),
                destinationLatitude = obj.getDouble("destLat"),
                destinationLongitude = obj.getDouble("destLon")
            )
        }.getOrNull()
    }

    fun saveRecentAlerts(alerts: List<AlertEvent>) {
        val arr = JSONArray()
        alerts.take(10).forEach { alert ->
            arr.put(
                JSONObject().apply {
                    put("id", alert.id)
                    put("type", alert.type)
                    put("message", alert.message)
                    put("ts", alert.timestampMillis)
                }
            )
        }
        prefs.edit().putString(KEY_RECENT_ALERTS, arr.toString()).apply()
    }

    fun getRecentAlerts(): List<AlertEvent> {
        val raw = prefs.getString(KEY_RECENT_ALERTS, null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            List(arr.length()) { i ->
                val o = arr.getJSONObject(i)
                AlertEvent(
                    id = o.getString("id"),
                    type = o.getString("type"),
                    message = o.getString("message"),
                    timestampMillis = o.getLong("ts")
                )
            }
        }.getOrDefault(emptyList())
    }

    companion object {
        private const val PREFS_NAME = "travelguard_prefs"
        private const val KEY_THEME = "theme"
        private const val KEY_VIBRATION = "vibration_enabled"
        private const val KEY_REPEAT_ALARM = "repeat_alarm"
        private const val KEY_ALARM_SOUND = "alarm_sound_enabled"
        private const val KEY_DEST_NAME = "dest_name"
        private const val KEY_DEST_ADDR = "dest_addr"
        private const val KEY_DEST_LAT = "dest_lat"
        private const val KEY_DEST_LON = "dest_lon"
        private const val KEY_WAKE_ENABLED = "wake_enabled"
        private const val KEY_WAKE_RADIUS_KM = "wake_radius_km"
        private const val KEY_ROUTE_GUARD_ENABLED = "route_guard_enabled"
        private const val KEY_DEVIATION_THRESHOLD_KM = "deviation_threshold_km"
        private const val KEY_JOURNEY_STATUS = "journey_status"
        private const val KEY_PROXIMITY_STATE = "proximity_state"
        private const val KEY_CACHED_ROUTE = "cached_route"
        private const val KEY_RECENT_ALERTS = "recent_alerts"
    }
}
