package com.example.vulpinenotes

import android.content.Context
import android.os.Bundle
import android.widget.AutoCompleteTextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatDelegate
import androidx.appcompat.widget.Toolbar
import com.google.android.material.button.MaterialButtonToggleGroup

class SettingsActivity : BaseActivity() {

    private val PREFS_NAME = "app_prefs"
    private val KEY_LANGUAGE = "app_language"
    private val KEY_THEME = "app_theme"

    private lateinit var languageSpinner: AutoCompleteTextView
    private lateinit var languages: List<Language>

    private var isApplyingThemeFromState = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applySavedTheme()
        setContentView(R.layout.activity_settings)

        setupToolbar()
        setupThemeSwitcher()
        setupLanguageSpinner()
        setupBackPressHandler()
    }

    private fun setupBackPressHandler() {
        // это обрабатывает свайп назад и системную кнопку назад
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                if (prefs.getBoolean("lang_changed", false)) {
                    setResult(RESULT_OK)
                }
                finish()
            }
        })
    }

    private fun setupThemeSwitcher() {
        val themeToggleGroup = findViewById<MaterialButtonToggleGroup>(R.id.theme_toggle_group)
        themeToggleGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked || isApplyingThemeFromState) return@addOnButtonCheckedListener

            val mode = when (checkedId) {
                R.id.btn_light -> AppCompatDelegate.MODE_NIGHT_NO
                R.id.btn_dark -> AppCompatDelegate.MODE_NIGHT_YES
                else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            }

            if (AppCompatDelegate.getDefaultNightMode() == mode && getSavedTheme() == mode) return@addOnButtonCheckedListener

            saveTheme(mode)
            AppCompatDelegate.setDefaultNightMode(mode)
            setResult(RESULT_OK)
        }

        val savedMode = getSavedTheme()
        val checkedId = when (savedMode) {
            AppCompatDelegate.MODE_NIGHT_NO -> R.id.btn_light
            AppCompatDelegate.MODE_NIGHT_YES -> R.id.btn_dark
            else -> R.id.btn_system
        }

        isApplyingThemeFromState = true
        themeToggleGroup.check(checkedId)
        isApplyingThemeFromState = false
    }

    private fun setupLanguageSpinner() {
        languageSpinner = findViewById(R.id.language_spinner)
        languages = listOf(
            Language("ru", "Русский", R.drawable.ic_ru_flag),
            Language("en", "English", R.drawable.ic_us_flag)
        )

        val adapter = LanguageAdapter(this, R.layout.item_language, languages)
        languageSpinner.setAdapter(adapter)

        val savedLang = getSavedLanguage()
        val current = languages.find { it.code == savedLang } ?: languages[0]
        languageSpinner.setText(current.name, false)

        languageSpinner.setOnItemClickListener { _, _, position, _ ->
            val selected = languages[position]
            if (getSavedLanguage() != selected.code) {
                saveLanguage(selected.code)
                getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .edit()
                    .putBoolean("lang_changed", true)
                    .apply()
                setResult(RESULT_OK)
                recreate()
            }
        }
    }

    private fun saveLanguage(code: String) {
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_LANGUAGE, code)
            .apply()
    }

    private fun getSavedLanguage(): String {
        return getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_LANGUAGE, "ru") ?: "ru"
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
        val mode = getSavedTheme()
        if (AppCompatDelegate.getDefaultNightMode() != mode) {
            AppCompatDelegate.setDefaultNightMode(mode)
        }
    }

    private fun setupToolbar() {
        val toolbar = findViewById<Toolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = getString(R.string.settings_title)

        toolbar.setNavigationOnClickListener {
            val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            if (prefs.getBoolean("lang_changed", false)) {
                setResult(RESULT_OK)
            }
            finish()
        }
    }
}
