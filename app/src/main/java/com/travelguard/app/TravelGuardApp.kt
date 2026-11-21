package com.travelguard.app

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate
import com.travelguard.app.data.local.TravelGuardPreferences
import com.travelguard.app.domain.model.ThemePreference
import org.osmdroid.config.Configuration

class TravelGuardApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Configuration.getInstance().userAgentValue = "${packageName}/${BuildConfig.VERSION_NAME}"
        val prefs = TravelGuardPreferences(this)
        applyTheme(prefs.getSettings().theme)
    }

    companion object {
        fun applyTheme(theme: ThemePreference) {
            val mode = when (theme) {
                ThemePreference.SYSTEM -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
                ThemePreference.LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
                ThemePreference.DARK -> AppCompatDelegate.MODE_NIGHT_YES
            }
            AppCompatDelegate.setDefaultNightMode(mode)
        }
    }
}
