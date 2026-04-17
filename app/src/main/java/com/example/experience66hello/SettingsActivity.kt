package com.example.experience66hello

import android.content.SharedPreferences
import android.os.Bundle
import android.widget.ImageButton
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.appcompat.widget.SwitchCompat

class SettingsActivity : AppCompatActivity() {

    private lateinit var prefs: SharedPreferences
    private lateinit var switchDarkMode: SwitchCompat
    private lateinit var switchNotifications: SwitchCompat
    private lateinit var buttonBack: ImageButton

    override fun onCreate(savedInstanceState: Bundle?) {
        val prefsTemp = getSharedPreferences(AppSettings.PREFS_NAME, MODE_PRIVATE)
        val darkModeEnabled = prefsTemp.getBoolean(AppSettings.KEY_DARK_MODE, false)

        AppCompatDelegate.setDefaultNightMode(
            if (darkModeEnabled) AppCompatDelegate.MODE_NIGHT_YES
            else AppCompatDelegate.MODE_NIGHT_NO
        )

        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        prefs = getSharedPreferences(AppSettings.PREFS_NAME, MODE_PRIVATE)

        if (!prefs.contains(AppSettings.KEY_NOTIFICATIONS_ENABLED)) {
            prefs.edit().putBoolean(AppSettings.KEY_NOTIFICATIONS_ENABLED, true).apply()
        }

        buttonBack = findViewById(R.id.buttonBack)
        switchDarkMode = findViewById(R.id.switchDarkMode)
        switchNotifications = findViewById(R.id.switchNotifications)

        buttonBack.setOnClickListener {
            finish()
        }

        switchDarkMode.isChecked = prefs.getBoolean(AppSettings.KEY_DARK_MODE, false)
        switchNotifications.isChecked = prefs.getBoolean(AppSettings.KEY_NOTIFICATIONS_ENABLED, true)

        switchDarkMode.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean(AppSettings.KEY_DARK_MODE, isChecked).apply()

            AppCompatDelegate.setDefaultNightMode(
                if (isChecked) AppCompatDelegate.MODE_NIGHT_YES
                else AppCompatDelegate.MODE_NIGHT_NO
            )

            recreate()
        }

        switchNotifications.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean(AppSettings.KEY_NOTIFICATIONS_ENABLED, isChecked).apply()
        }
    }
}
