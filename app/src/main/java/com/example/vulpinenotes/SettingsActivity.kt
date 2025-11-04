package com.example.vulpinenotes

import android.content.Context
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import com.google.android.material.button.MaterialButtonToggleGroup
import androidx.appcompat.widget.Toolbar
class SettingsActivity : AppCompatActivity() {

    private val PREFS_NAME = "theme_prefs"
    private val KEY_THEME = "app_theme"

    override fun onCreate(savedInstanceState: Bundle?) {
        applySavedTheme()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        setupToolbar()
        setupThemeSwitcher()
    }

    private fun setupToolbar() {
        val toolbar = findViewById<Toolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
    }

    private fun setupThemeSwitcher() {
        val themeToggleGroup = findViewById<MaterialButtonToggleGroup>(R.id.theme_toggle_group)

        // Установить текущую тему
        when (getSavedTheme()) {
            AppCompatDelegate.MODE_NIGHT_NO -> themeToggleGroup.check(R.id.btn_light)
            AppCompatDelegate.MODE_NIGHT_YES -> themeToggleGroup.check(R.id.btn_dark)
            else -> themeToggleGroup.check(R.id.btn_system)
        }

        themeToggleGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) {
                val mode = when (checkedId) {
                    R.id.btn_light -> AppCompatDelegate.MODE_NIGHT_NO
                    R.id.btn_dark -> AppCompatDelegate.MODE_NIGHT_YES
                    else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
                }
                saveTheme(mode)
                AppCompatDelegate.setDefaultNightMode(mode)
            }
        }
    }

    private fun saveTheme(mode: Int) {
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_THEME, mode)
            .apply()
    }

    private fun getSavedTheme(): Int {
        return getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getInt(KEY_THEME, AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
    }

    private fun applySavedTheme() {
        AppCompatDelegate.setDefaultNightMode(getSavedTheme())
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}