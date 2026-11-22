package com.travelguard.app.update

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.travelguard.app.data.local.TravelGuardPreferences
import com.travelguard.app.domain.model.JourneyStatus
import com.travelguard.app.service.LocationTrackingService

/**
 * Android stops app components during package replacement. Restore a journey that
 * was already active; the service reads its destination and alarm configuration
 * from the app's persistent preferences and starts with its resume path.
 */
class PackageReplacedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_MY_PACKAGE_REPLACED) return

        val prefs = TravelGuardPreferences(context)
        if (prefs.getJourneyStatus() != JourneyStatus.ACTIVE || prefs.getDestination() == null) return

        val hasFine = ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val hasCoarse = ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        if (!hasFine && !hasCoarse) return

        ContextCompat.startForegroundService(
            context,
            Intent(context, LocationTrackingService::class.java)
        )
    }
}
