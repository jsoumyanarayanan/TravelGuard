package com.travelguard.app.assist

import android.app.Activity
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.TimePickerDialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.travelguard.app.R
import com.travelguard.app.ui.MainActivity
import java.util.Calendar
import java.util.Locale
import kotlin.math.*

/** Optional overnight travel nudge based on filtered cumulative GPS distance. */
object OvernightTravelAssist {
    private const val PREFS = "overnight_travel_assist"
    private const val CHANNEL = "overnight_assist"
    private const val PROMPT_ID = 4101
    private const val DISTANCE_LIMIT_M = 40_000.0

    fun resetForNewJourney(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putFloat("distance_m", 0f).putBoolean("has_fix", false)
            .putLong("last_sample_ms", 0L).putString("prompted_date", "").apply()
    }

    fun recordLocation(context: Context, lat: Double, lon: Double, accuracy: Float, timestamp: Long, journeyActive: Boolean) {
        if (!journeyActive || !lat.isFinite() || !lon.isFinite() || accuracy > 50f) return
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val hasFix = p.getBoolean("has_fix", false)
        val previousLat = p.getString("last_lat", null)?.toDoubleOrNull()
        val previousLon = p.getString("last_lon", null)?.toDoubleOrNull()
        val previousTime = p.getLong("last_sample_ms", 0L)
        var distance = p.getFloat("distance_m", 0f).toDouble()
        if (hasFix && previousLat != null && previousLon != null && timestamp > previousTime &&
            timestamp - previousTime <= 5 * 60_000L) {
            val segment = distanceMeters(previousLat, previousLon, lat, lon)
            // Reject GPS jumps and implausible travel speeds; don't count stationary drift.
            val speed = segment / ((timestamp - previousTime) / 1000.0)
            if (segment in 8.0..3_000.0 && speed in 0.7..60.0) distance += segment
        }
        p.edit().putFloat("distance_m", distance.toFloat()).putBoolean("has_fix", true)
            .putString("last_lat", lat.toString()).putString("last_lon", lon.toString())
            .putLong("last_sample_ms", timestamp).apply()

        val now = Calendar.getInstance()
        val dateKey = String.format(Locale.US, "%04d-%02d-%02d", now.get(Calendar.YEAR), now.get(Calendar.MONTH)+1, now.get(Calendar.DAY_OF_MONTH))
        if (now.get(Calendar.HOUR_OF_DAY) >= 23 && distance >= DISTANCE_LIMIT_M &&
            p.getString("prompted_date", "") != dateKey) {
            p.edit().putString("prompted_date", dateKey).apply()
            showPrompt(context)
        }
    }

    private fun showPrompt(context: Context) {
        ensureChannel(context)
        val open = PendingIntent.getActivity(context, 4102,
            Intent(context, MorningReminderActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val later = PendingIntent.getBroadcast(context, 4103,
            Intent(context, OvernightPromptDismissReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Long journey tonight?")
            .setContentText("Let us help you start tomorrow right. Would you like a morning wake-up reminder?")
            .setStyle(NotificationCompat.BigTextStyle().bigText("You've covered more than 40 km tonight. A little thought from TravelGuard: would you like us to remind you to wake up tomorrow morning?"))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(open)
            .addAction(0, "Set reminder", open)
            .addAction(0, "Maybe later", later)
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(PROMPT_ID, notification) }
    }

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= 26) {
            val manager = context.getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(NotificationChannel(CHANNEL, "Overnight travel reminders", NotificationManager.IMPORTANCE_DEFAULT))
        }
    }

    private fun distanceMeters(aLat: Double, aLon: Double, bLat: Double, bLon: Double): Double {
        val earth = 6_371_000.0
        val dLat = Math.toRadians(bLat-aLat)
        val dLon = Math.toRadians(bLon-aLon)
        val x = sin(dLat/2).pow(2) + cos(Math.toRadians(aLat))*cos(Math.toRadians(bLat))*sin(dLon/2).pow(2)
        return 2 * earth * asin(sqrt(x.coerceIn(0.0, 1.0)))
    }
}

class MorningReminderActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val now = Calendar.getInstance()
        val picker = TimePickerDialog(this, { _, hour, minute ->
            val trigger = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, hour); set(Calendar.MINUTE, minute); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
                if (timeInMillis <= System.currentTimeMillis()) add(Calendar.DAY_OF_YEAR, 1)
            }
            val alarm = getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val pending = PendingIntent.getBroadcast(this, 4110,
                Intent(this, MorningReminderReceiver::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            alarm.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger.timeInMillis, pending)
            getSharedPreferences("overnight_travel_assist", MODE_PRIVATE).edit()
                .putLong("morning_alarm_at", trigger.timeInMillis).apply()
            android.widget.Toast.makeText(this, "Morning reminder set.", android.widget.Toast.LENGTH_LONG).show()
            finish()
        }, 7, 0, false)
        picker.setTitle("What time should we wake you?")
        picker.setOnCancelListener { finish() }
        picker.show()
    }
}

class MorningReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val channel = "overnight_assist"
        if (Build.VERSION.SDK_INT >= 26) {
            val manager = context.getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(NotificationChannel(channel, "Overnight travel reminders", NotificationManager.IMPORTANCE_HIGH))
        }
        val open = PendingIntent.getActivity(context, 4111, Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Good morning!")
            .setContentText("Your TravelGuard wake-up reminder is here. Have a safe day.")
            .setAutoCancel(true).setContentIntent(open).setPriority(NotificationCompat.PRIORITY_HIGH).build()
        runCatching { NotificationManagerCompat.from(context).notify(4112, notification) }
    }
}

class OvernightPromptDismissReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        NotificationManagerCompat.from(context).cancel(4101)
    }
}
