package com.travelguard.app.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.travelguard.app.TravelGuardApp
import com.travelguard.app.data.local.TravelGuardPreferences
import com.travelguard.app.databinding.ActivitySettingsBinding
import com.travelguard.app.domain.model.ThemePreference

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private lateinit var prefs: TravelGuardPreferences

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = TravelGuardPreferences(this)

        binding.toolbarSettings.setNavigationOnClickListener { finish() }

        val current = prefs.getSettings()
        when (current.theme) {
            ThemePreference.SYSTEM -> binding.rbThemeSystem.isChecked = true
            ThemePreference.LIGHT -> binding.rbThemeLight.isChecked = true
            ThemePreference.DARK -> binding.rbThemeDark.isChecked = true
        }
        binding.switchAlarmSound.isChecked = current.alarmSoundEnabled
        binding.switchVibration.isChecked = current.vibrationEnabled
        binding.switchRepeatAlarm.isChecked = current.repeatAlarmUntilAck

        binding.rgTheme.setOnCheckedChangeListener { _, checkedId ->
            val theme = when (checkedId) {
                binding.rbThemeLight.id -> ThemePreference.LIGHT
                binding.rbThemeDark.id -> ThemePreference.DARK
                else -> ThemePreference.SYSTEM
            }
            val updated = prefs.getSettings().copy(theme = theme)
            prefs.saveSettings(updated)
            TravelGuardApp.applyTheme(theme)
        }

        binding.switchAlarmSound.setOnCheckedChangeListener { _, isChecked ->
            prefs.saveSettings(prefs.getSettings().copy(alarmSoundEnabled = isChecked))
        }
        binding.switchVibration.setOnCheckedChangeListener { _, isChecked ->
            prefs.saveSettings(prefs.getSettings().copy(vibrationEnabled = isChecked))
        }
        binding.switchRepeatAlarm.setOnCheckedChangeListener { _, isChecked ->
            prefs.saveSettings(prefs.getSettings().copy(repeatAlarmUntilAck = isChecked))
        }

        binding.btnOpenAppSettings.setOnClickListener {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.fromParts("package", packageName, null)
            }
            startActivity(intent)
        }

        updatePermissionSummary()
    }

    override fun onResume() {
        super.onResume()
        updatePermissionSummary()
    }

    private fun updatePermissionSummary() {
        val fine = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val bg = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
        val notif = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }

        binding.tvPermissionSummary.text = buildString {
            appendLine("• Precise location: ${if (fine) "Granted" else "Not granted"}")
            appendLine("• Background location: ${if (bg) "Granted" else "While-in-use only (foreground service still runs during active journey)"}")
            appendLine("• Notifications: ${if (notif) "Granted" else "Denied"}")
            append("Tip: For long overnight journeys, set TravelGuard battery usage to Unrestricted in Android App Settings.")
        }
    }
}
