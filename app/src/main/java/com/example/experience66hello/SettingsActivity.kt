package com.example.experience66hello

import android.content.SharedPreferences
import android.os.Bundle
import android.widget.ImageButton
import android.widget.RadioButton
import android.widget.RadioGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.appcompat.widget.SwitchCompat

class SettingsActivity : AppCompatActivity() {

    private lateinit var prefs: SharedPreferences
    private lateinit var switchDarkMode: SwitchCompat
    private lateinit var switchNotifications: SwitchCompat
    private lateinit var buttonBack: ImageButton
    private lateinit var radioGroupNavigationMode: RadioGroup
    private lateinit var radioInAppNavigation: RadioButton
    private lateinit var radioGoogleMapsNavigation: RadioButton

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

        if (!prefs.contains(AppSettings.KEY_NAVIGATION_MODE)) {
            prefs.edit()
                .putString(
                    AppSettings.KEY_NAVIGATION_MODE,
                    AppSettings.VALUE_NAVIGATION_IN_APP
                )
                .apply()
        }

        buttonBack = findViewById(R.id.buttonBack)
        switchDarkMode = findViewById(R.id.switchDarkMode)
        switchNotifications = findViewById(R.id.switchNotifications)
        radioGroupNavigationMode = findViewById(R.id.radioGroupNavigationMode)
        radioInAppNavigation = findViewById(R.id.radioInAppNavigation)
        radioGoogleMapsNavigation = findViewById(R.id.radioGoogleMapsNavigation)

        buttonBack.setOnClickListener {
            finish()
        }

        switchDarkMode.isChecked = prefs.getBoolean(AppSettings.KEY_DARK_MODE, false)
        switchNotifications.isChecked = prefs.getBoolean(AppSettings.KEY_NOTIFICATIONS_ENABLED, true)

        val navigationMode = prefs.getString(
            AppSettings.KEY_NAVIGATION_MODE,
            AppSettings.VALUE_NAVIGATION_IN_APP
        )

        if (navigationMode == AppSettings.VALUE_NAVIGATION_GOOGLE_MAPS) {
            radioGoogleMapsNavigation.isChecked = true
        } else {
            radioInAppNavigation.isChecked = true
        }

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

        radioGroupNavigationMode.setOnCheckedChangeListener { _, checkedId ->
            val value = when (checkedId) {
                R.id.radioGoogleMapsNavigation -> AppSettings.VALUE_NAVIGATION_GOOGLE_MAPS
                else -> AppSettings.VALUE_NAVIGATION_IN_APP
            }

            prefs.edit().putString(AppSettings.KEY_NAVIGATION_MODE, value).apply()
        }
    }
}