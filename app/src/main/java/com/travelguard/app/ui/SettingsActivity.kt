package com.travelguard.app.ui

import android.app.DownloadManager
import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.travelguard.app.BuildConfig
import com.travelguard.app.TravelGuardApp
import com.travelguard.app.data.local.TravelGuardPreferences
import com.travelguard.app.databinding.ActivitySettingsBinding
import com.travelguard.app.domain.model.ThemePreference
import com.travelguard.app.data.JourneyStateStore
import com.travelguard.app.domain.model.JourneyStatus
import androidx.lifecycle.lifecycleScope
import com.travelguard.app.update.GithubUpdateManager
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private lateinit var prefs: TravelGuardPreferences
    private var availableUpdate: GithubUpdateManager.UpdateInfo? = null
    private var activeDownloadId: Long = -1L
    private var downloadProgressJob: Job? = null
    private var forceUpdateFlow = false
    private var awaitingInstallPermission = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = TravelGuardPreferences(this)
        forceUpdateFlow = intent.getBooleanExtra(EXTRA_FORCE_UPDATE, false)
        binding.tvInstalledVersion.text = "Installed version v${BuildConfig.VERSION_NAME}"
        binding.progressUpdateDownload.max = 100
        activeDownloadId = getSharedPreferences("travelguard_updates", MODE_PRIVATE).getLong("download_id", -1L)

        binding.btnCheckForUpdates.setOnClickListener { checkForUpdates() }
        binding.btnDownloadUpdate.setOnClickListener { downloadUpdate() }
        binding.btnInstallUpdate.setOnClickListener { installUpdate() }

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
        checkForUpdates()
        resumeDownloadIfAny()
    }

    override fun onResume() {
        super.onResume()
        updatePermissionSummary()
        if (awaitingInstallPermission) {
            awaitingInstallPermission = false
            installUpdate()
        }
    }


    private fun checkForUpdates() {
        binding.btnCheckForUpdates.isEnabled = false
        binding.btnCheckForUpdates.text = "Checking..."
        binding.tvUpdateStatus.text = "Checking the latest published GitHub Release..."
        lifecycleScope.launch {
            when (val result = GithubUpdateManager.checkForUpdate(BuildConfig.VERSION_NAME)) {
                is GithubUpdateManager.CheckResult.Available -> {
                    availableUpdate = result.update
                    binding.tvUpdateStatus.text = buildString {
                        append("Version v${result.update.versionName} is available.")
                        if (result.update.releaseNotes.isNotBlank()) {
                            append("\n\n")
                            append(result.update.releaseNotes)
                        }
                    }
                    binding.btnDownloadUpdate.visibility = if (activeDownloadId >= 0L) android.view.View.GONE else android.view.View.VISIBLE
                    binding.btnDownloadUpdate.isEnabled = activeDownloadId < 0L
                    if (forceUpdateFlow && activeDownloadId < 0L) downloadUpdate()
                }
                is GithubUpdateManager.CheckResult.UpToDate -> {
                    availableUpdate = null
                    binding.tvUpdateStatus.text = "You're using the latest version (v${result.installedVersion})."
                    binding.btnDownloadUpdate.visibility = android.view.View.GONE
                }
                is GithubUpdateManager.CheckResult.Failed -> {
                    binding.tvUpdateStatus.text = result.reason
                    binding.btnDownloadUpdate.visibility = android.view.View.GONE
                }
            }
            binding.btnCheckForUpdates.isEnabled = true
            binding.btnCheckForUpdates.text = "Check for updates"
        }
    }

    private fun downloadUpdate() {
        val update = availableUpdate ?: return
        try {
            activeDownloadId = GithubUpdateManager.enqueueDownload(this, update)
            getSharedPreferences("travelguard_updates", MODE_PRIVATE).edit()
                .putLong("download_id", activeDownloadId)
                .apply()
            binding.btnDownloadUpdate.isEnabled = false
            binding.btnDownloadUpdate.visibility = android.view.View.GONE
            binding.btnInstallUpdate.visibility = android.view.View.GONE
            binding.progressUpdateDownload.visibility = android.view.View.VISIBLE
            binding.progressUpdateDownload.progress = 0
            binding.tvUpdateStatus.text = "Download started. You can continue using TravelGuard."
            monitorDownload()
        } catch (error: Exception) {
            binding.tvUpdateStatus.text = error.localizedMessage ?: "Unable to start the update download."
            binding.btnDownloadUpdate.isEnabled = true
            binding.btnDownloadUpdate.visibility = android.view.View.VISIBLE
        }
    }

    private fun resumeDownloadIfAny() {
        if (activeDownloadId < 0L) return
        val progress = GithubUpdateManager.getDownloadProgress(this, activeDownloadId)
        if (progress == null) {
            clearSavedDownload()
            if (availableUpdate != null) {
                binding.btnDownloadUpdate.visibility = android.view.View.VISIBLE
                binding.btnDownloadUpdate.isEnabled = true
            }
            return
        }
        binding.progressUpdateDownload.visibility = android.view.View.VISIBLE
        binding.progressUpdateDownload.progress = progress.percent
        when (progress.status) {
            DownloadManager.STATUS_SUCCESSFUL -> {
                binding.progressUpdateDownload.progress = 100
                binding.tvUpdateStatus.text = "Update downloaded. Preparing Android installer..."
                binding.btnInstallUpdate.visibility = android.view.View.VISIBLE
                if (forceUpdateFlow) installUpdate()
            }
            DownloadManager.STATUS_FAILED -> {
                binding.tvUpdateStatus.text = progress.reason ?: "Download failed. Please try again."
                clearSavedDownload()
                binding.btnDownloadUpdate.visibility = android.view.View.VISIBLE
                binding.btnDownloadUpdate.isEnabled = true
            }
            else -> monitorDownload()
        }
    }

    private fun monitorDownload() {
        downloadProgressJob?.cancel()
        downloadProgressJob = lifecycleScope.launch {
            while (true) {
                delay(500)
                val progress = GithubUpdateManager.getDownloadProgress(this@SettingsActivity, activeDownloadId)
                if (progress == null) {
                    binding.tvUpdateStatus.text = "Download status unavailable. Please check for updates again."
                    clearSavedDownload()
                    break
                }
                binding.progressUpdateDownload.progress = progress.percent
                when (progress.status) {
                    DownloadManager.STATUS_PENDING -> binding.tvUpdateStatus.text = "Update queued..."
                    DownloadManager.STATUS_RUNNING -> binding.tvUpdateStatus.text = "Downloading update... ${progress.percent}%"
                    DownloadManager.STATUS_PAUSED -> binding.tvUpdateStatus.text = "Download paused. Waiting for network..."
                    DownloadManager.STATUS_SUCCESSFUL -> {
                        binding.progressUpdateDownload.progress = 100
                        binding.tvUpdateStatus.text = "Update downloaded. Preparing Android installer..."
                        binding.btnInstallUpdate.visibility = android.view.View.VISIBLE
                        if (forceUpdateFlow) installUpdate()
                        break
                    }
                    DownloadManager.STATUS_FAILED -> {
                        binding.tvUpdateStatus.text = progress.reason ?: "Download failed. Please try again."
                        clearSavedDownload()
                        binding.btnDownloadUpdate.visibility = android.view.View.VISIBLE
                        binding.btnDownloadUpdate.isEnabled = true
                        break
                    }
                }
            }
        }
    }

    private fun clearSavedDownload() {
        getSharedPreferences("travelguard_updates", MODE_PRIVATE).edit()
            .remove("download_id")
            .apply()
        activeDownloadId = -1L
    }

    private fun installUpdate() {
        if (activeDownloadId < 0L) return
        when (val result = GithubUpdateManager.installDownloaded(this, activeDownloadId)) {
            GithubUpdateManager.InstallResult.Started -> {
                awaitingInstallPermission = false
                binding.tvUpdateStatus.text = "Android package installer opened. Confirm the update there."
            }
            GithubUpdateManager.InstallResult.PermissionRequired -> {
                awaitingInstallPermission = true
                binding.tvUpdateStatus.text = "Allow TravelGuard to install unknown apps in Android Settings. The installer will open when you return."
            }
            is GithubUpdateManager.InstallResult.Failed -> {
                binding.tvUpdateStatus.text = result.reason
            }
        }
    }

    companion object {
        const val EXTRA_FORCE_UPDATE = "com.travelguard.app.extra.FORCE_UPDATE"
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
