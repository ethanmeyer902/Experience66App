package com.example.experience66hello

import android.os.Bundle
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate

class SettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        val prefs = getSharedPreferences(AppSettings.PREFS_NAME, MODE_PRIVATE)
        val darkModeEnabled = prefs.getBoolean(AppSettings.KEY_DARK_MODE, false)
        AppCompatDelegate.setDefaultNightMode(
            if (darkModeEnabled) AppCompatDelegate.MODE_NIGHT_YES
            else AppCompatDelegate.MODE_NIGHT_NO
        )

        super.onCreate(savedInstanceState)

        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "Settings"

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 48, 48, 48)
        }

        val darkTitle = TextView(this).apply {
            text = "Dark Mode"
            textSize = 18f
        }
        val darkSwitch = Switch(this).apply {
            isChecked = darkModeEnabled
            setOnCheckedChangeListener { _, checked ->
                prefs.edit().putBoolean(AppSettings.KEY_DARK_MODE, checked).apply()
                AppCompatDelegate.setDefaultNightMode(
                    if (checked) AppCompatDelegate.MODE_NIGHT_YES
                    else AppCompatDelegate.MODE_NIGHT_NO
                )
            }
        }

        val notifTitle = TextView(this).apply {
            text = "Geofence Notifications"
            textSize = 18f
            setPadding(0, 48, 0, 0)
        }
        val notifSwitch = Switch(this).apply {
            isChecked = prefs.getBoolean(AppSettings.KEY_NOTIFICATIONS_ENABLED, true)
            setOnCheckedChangeListener { _, checked ->
                prefs.edit().putBoolean(AppSettings.KEY_NOTIFICATIONS_ENABLED, checked).apply()
            }
        }

        root.addView(darkTitle)
        root.addView(darkSwitch)
        root.addView(notifTitle)
        root.addView(notifSwitch)

        setContentView(root)
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}
