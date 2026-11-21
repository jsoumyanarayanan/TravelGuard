package com.travelguard.app.alarm

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.core.app.NotificationCompat
import com.travelguard.app.R
import com.travelguard.app.data.local.TravelGuardPreferences
import com.travelguard.app.service.LocationTrackingService
import com.travelguard.app.ui.MainActivity

class AlarmController(private val context: Context) {

    private val notificationManager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    private val handler = Handler(Looper.getMainLooper())
    private var activeRingtone: Ringtone? = null
    private var isAlarmPlaying = false

    private val repeatRunnable = object : Runnable {
        override fun run() {
            if (!isAlarmPlaying) return
            val settings = TravelGuardPreferences(context).getSettings()
            if (settings.alarmSoundEnabled) {
                playRingtoneOnce()
            }
            if (settings.vibrationEnabled) {
                vibrateBurst()
            }
            if (settings.repeatAlarmUntilAck) {
                handler.postDelayed(this, 6000L)
            }
        }
    }

    init {
        ensureChannels()
    }

    fun ensureChannels() {
        val monitoringChannel = NotificationChannel(
            CHANNEL_MONITORING,
            "Journey Monitoring",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Shows active TravelGuard GPS monitoring status."
        }

        val wakeChannel = NotificationChannel(
            CHANNEL_WAKE_ALARM,
            "Wake Me Up Alarm",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Urgent alert when approaching your destination."
            enableVibration(true)
        }

        val deviationChannel = NotificationChannel(
            CHANNEL_DEVIATION,
            "Route Deviation Alert",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Alerts when the device moves away from the planned route."
            enableVibration(true)
        }

        notificationManager.createNotificationChannel(monitoringChannel)
        notificationManager.createNotificationChannel(wakeChannel)
        notificationManager.createNotificationChannel(deviationChannel)
    }

    fun buildMonitoringNotification(
        destinationName: String,
        wakeEnabled: Boolean,
        routeEnabled: Boolean
    ): Notification {
        val openIntent = PendingIntent.getActivity(
            context,
            100,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = PendingIntent.getService(
            context,
            101,
            Intent(context, LocationTrackingService::class.java).apply {
                action = LocationTrackingService.ACTION_STOP_JOURNEY
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val wakeLabel = if (wakeEnabled) "Wake ON" else "Wake OFF"
        val routeLabel = if (routeEnabled) "Route Guard ON" else "Route Guard OFF"

        return NotificationCompat.Builder(context, CHANNEL_MONITORING)
            .setSmallIcon(R.drawable.ic_notification_guard)
            .setContentTitle("TravelGuard active • $destinationName")
            .setContentText("$wakeLabel • $routeLabel")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openIntent)
            .addAction(0, "End Journey", stopIntent)
            .build()
    }

    fun triggerWakeAlarm(destinationName: String, distanceMeters: Double) {
        val openIntent = PendingIntent.getActivity(
            context,
            200,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val ackIntent = PendingIntent.getService(
            context,
            201,
            Intent(context, LocationTrackingService::class.java).apply {
                action = LocationTrackingService.ACTION_ACKNOWLEDGE_ALARM
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val kmText = String.format("%.2f km", distanceMeters / 1000.0)
        val notification = NotificationCompat.Builder(context, CHANNEL_WAKE_ALARM)
            .setSmallIcon(R.drawable.ic_notification_guard)
            .setContentTitle("Approaching $destinationName")
            .setContentText("You are within $kmText of your destination. Tap I'm Awake to stop.")
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setOngoing(true)
            .setAutoCancel(false)
            .setContentIntent(openIntent)
            .addAction(0, "I'm Awake", ackIntent)
            .build()

        notificationManager.notify(WAKE_NOTIFICATION_ID, notification)

        if (!isAlarmPlaying) {
            isAlarmPlaying = true
            handler.post(repeatRunnable)
        }
    }

    fun stopWakeAlarm() {
        isAlarmPlaying = false
        handler.removeCallbacks(repeatRunnable)
        runCatching { activeRingtone?.stop() }
        activeRingtone = null
        notificationManager.cancel(WAKE_NOTIFICATION_ID)
    }

    fun notifyRouteDeviation(deviationMeters: Double) {
        val openIntent = PendingIntent.getActivity(
            context,
            300,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val kmText = String.format("%.2f km", deviationMeters / 1000.0)
        val notification = NotificationCompat.Builder(context, CHANNEL_DEVIATION)
            .setSmallIcon(R.drawable.ic_notification_guard)
            .setContentTitle("Route deviation detected")
            .setContentText("You appear to be $kmText away from the planned route.")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(openIntent)
            .build()

        notificationManager.notify(DEVIATION_NOTIFICATION_ID, notification)

        if (TravelGuardPreferences(context).getSettings().vibrationEnabled) {
            vibrateBurst()
        }
    }

    private fun playRingtoneOnce() {
        runCatching {
            val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            if (activeRingtone == null) {
                activeRingtone = RingtoneManager.getRingtone(context, uri)?.apply {
                    audioAttributes = AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                }
            }
            if (activeRingtone?.isPlaying == false) {
                activeRingtone?.play()
            }
        }
    }

    private fun vibrateBurst() {
        runCatching {
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vm = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
                vm.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            }
            vibrator.vibrate(
                VibrationEffect.createWaveform(longArrayOf(0, 500, 250, 500), -1)
            )
        }
    }

    companion object {
        const val CHANNEL_MONITORING = "tg_journey_monitoring"
        const val CHANNEL_WAKE_ALARM = "tg_wake_alarm"
        const val CHANNEL_DEVIATION = "tg_route_deviation"
        const val MONITORING_NOTIFICATION_ID = 1001
        const val WAKE_NOTIFICATION_ID = 1002
        const val DEVIATION_NOTIFICATION_ID = 1003
    }
}
