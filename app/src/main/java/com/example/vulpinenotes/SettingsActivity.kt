package com.example.vulpinenotes

import android.content.Context
import android.os.Bundle
import android.widget.AutoCompleteTextView
import android.widget.Button
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatDelegate
import androidx.appcompat.widget.Toolbar
import com.example.vulpinenotes.data.AppDatabase
import com.example.vulpinenotes.data.BookRepository
import com.example.vulpinenotes.data.NotificationManager
import com.example.vulpinenotes.data.NotificationSettings
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.switchmaterial.SwitchMaterial
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File

class SettingsActivity : BaseActivity() {

    private val PREFS_NAME = "app_prefs"
    private val KEY_LANGUAGE = "app_language"
    private val KEY_THEME = "app_theme"
    private val KEY_AUTO_SYNC = "auto_sync"
    private val KEY_NOTIFICATION_SETTINGS = "notification_settings"

    private lateinit var autoSyncSwitch: SwitchMaterial
    private lateinit var notificationsSwitch: SwitchMaterial
    private lateinit var selectBooksBtn: Button
    private lateinit var selectedBooksText: TextView
    private lateinit var selectIntervalBtn: Button

    private lateinit var notificationManager: NotificationManager
    private lateinit var bookRepository: BookRepository
    private val allBooks = mutableListOf<Book>()
    private var isLoadingBooks = false // Флаг загрузки

    private lateinit var languageSpinner: AutoCompleteTextView
    private lateinit var languages: List<Language>

    private var isApplyingThemeFromState = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applySavedTheme()
        setContentView(R.layout.activity_settings)

        // инициализируем зависимости
        val database = AppDatabase.getDatabase(this)
        val firestore = FirebaseFirestore.getInstance()
        val storageDir = File(filesDir, "covers").apply { mkdirs() }

        bookRepository = BookRepository(
            database.bookDao(),
            database.chapterDao(),
            firestore,
            storageDir
        )

        notificationManager = NotificationManager(this, bookRepository)

        bindViews()
        setupToolbar()
        setupThemeSwitcher()
        setupLanguageSpinner()
        setupAutoSyncSwitch()
        setupNotificationsSection()
        setupBackPressHandler()

        // загружаем все книги в фоновом потоке
        loadAllBooks()
    }

    private fun bindViews() {
        notificationsSwitch = findViewById(R.id.switch_notifications)
        selectBooksBtn = findViewById(R.id.btn_select_books)
        selectedBooksText = findViewById(R.id.tv_selected_books)
        selectIntervalBtn = findViewById(R.id.btn_select_interval)
    }

    private fun setupNotificationsSection() {
        val settings = loadNotificationSettings()

        notificationsSwitch.isChecked = settings.isEnabled

        // обновляем текст интервала
        updateIntervalText(settings.interval)

        notificationsSwitch.setOnCheckedChangeListener { _, enabled ->
            val updated = settings.copy(isEnabled = enabled)
            saveNotificationSettings(updated)

            if (enabled && updated.selectedBookIds.isNotEmpty()) {
                notificationManager.schedule(updated)
            } else {
                notificationManager.cancel()
            }
        }

        selectBooksBtn.setOnClickListener {
            if (allBooks.isEmpty() && !isLoadingBooks) {
                loadAllBooks {
                    showBooksSelectionDialog()
                }
            } else if (!isLoadingBooks) {
                showBooksSelectionDialog()
            }
        }

        selectIntervalBtn.setOnClickListener {
            showIntervalSelectionDialog()
        }
    }

    private fun loadAllBooks(onLoaded: (() -> Unit)? = null) {
        if (isLoadingBooks) return // не загружаем, если уже идет загрузка

        isLoadingBooks = true

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val books = bookRepository.getAllBooksList()

                // возвращаемся в главный поток для обновления UI
                CoroutineScope(Dispatchers.Main).launch {
                    allBooks.clear() // Очищаем список перед добавлением новых
                    allBooks.addAll(books)

                    // обновляем текст выбранных книг
                    val settings = loadNotificationSettings()
                    updateSelectedBooksText(settings.selectedBookIds)

                    onLoaded?.invoke()
                    isLoadingBooks = false
                }
            } catch (e: Exception) {
                e.printStackTrace()
                CoroutineScope(Dispatchers.Main).launch {
                    isLoadingBooks = false
                }
            }
        }
    }

    private fun showIntervalSelectionDialog() {
        val intervals = com.example.vulpinenotes.data.ReminderInterval.values()
        val intervalNames = intervals.map { it.title }.toTypedArray()

        val currentSettings = loadNotificationSettings()
        val currentIndex = intervals.indexOfFirst { it.name == currentSettings.interval.name }

        MaterialAlertDialogBuilder(this)
            .setTitle("Выберите интервал")
            .setSingleChoiceItems(intervalNames, currentIndex.coerceAtLeast(0)) { dialog, which ->
                val selectedInterval = intervals[which]
                val updated = currentSettings.copy(interval = selectedInterval)
                saveNotificationSettings(updated)
                updateIntervalText(selectedInterval)

                if (updated.isEnabled && updated.selectedBookIds.isNotEmpty()) {
                    notificationManager.schedule(updated)
                }
                dialog.dismiss()
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun updateIntervalText(interval: com.example.vulpinenotes.data.ReminderInterval) {
        selectIntervalBtn.text = interval.title
    }

    private fun showBooksSelectionDialog() {
        val settings = loadNotificationSettings()
        val selectedIds = settings.selectedBookIds.toMutableSet()

        if (allBooks.isEmpty()) {
            MaterialAlertDialogBuilder(this)
                .setTitle("Нет книг")
                .setMessage("Сначала создайте книги в главном меню")
                .setPositiveButton("OK", null)
                .show()
            return
        }

        val bookTitles = allBooks.map { it.title }.toTypedArray()
        val checkedItems = allBooks.map { selectedIds.contains(it.id) }.toBooleanArray()

        MaterialAlertDialogBuilder(this)
            .setTitle("Выберите книги для напоминаний")
            .setMultiChoiceItems(bookTitles, checkedItems) { _, which, isChecked ->
                val bookId = allBooks[which].id
                if (isChecked) {
                    selectedIds.add(bookId)
                } else {
                    selectedIds.remove(bookId)
                }
            }
            .setPositiveButton("Сохранить") { dialog, _ ->
                updateSelectedBooks(selectedIds.toList())
                dialog.dismiss()
            }
            .setNegativeButton("Отмена") { dialog, _ ->
                dialog.dismiss()
            }
            .setNeutralButton("Выбрать все") { dialog, _ ->
                selectedIds.clear()
                selectedIds.addAll(allBooks.map { it.id })
                updateSelectedBooks(selectedIds.toList())
                dialog.dismiss()
            }
            .show()
    }

    private fun updateSelectedBooks(selectedIds: List<String>) {
        val currentSettings = loadNotificationSettings()
        val updatedSettings = currentSettings.copy(selectedBookIds = selectedIds)
        saveNotificationSettings(updatedSettings)
        updateSelectedBooksText(selectedIds)

        if (updatedSettings.isEnabled && updatedSettings.selectedBookIds.isNotEmpty()) {
            notificationManager.schedule(updatedSettings)
        } else if (updatedSettings.selectedBookIds.isEmpty()) {
            notificationManager.cancel()
        }
    }

    private fun updateSelectedBooksText(selectedIds: List<String>) {
        val selectedBooks = allBooks.filter { selectedIds.contains(it.id) }

        val text = when {
            selectedBooks.isEmpty() -> "Не выбрано"
            selectedBooks.size <= 3 -> {
                selectedBooks.joinToString(", ") { it.title }
            }
            else -> {
                "${selectedBooks.take(3).joinToString(", ")} и ещё ${selectedBooks.size - 3}"
            }
        }

        selectedBooksText.text = text
    }

    private fun loadNotificationSettings(): NotificationSettings {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val json = prefs.getString(KEY_NOTIFICATION_SETTINGS, null) ?: return NotificationSettings()

        return try {
            val obj = org.json.JSONObject(json)
            NotificationSettings(
                isEnabled = obj.getBoolean("isEnabled"),
                interval = com.example.vulpinenotes.data.ReminderInterval.valueOf(
                    obj.optString("interval", "EVERY_DAY")
                ),
                selectedBookIds = jsonArrayToList(obj.getJSONArray("selectedBookIds"))
            )
        } catch (e: Exception) {
            NotificationSettings()
        }
    }

    private fun saveNotificationSettings(settings: NotificationSettings) {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val json = org.json.JSONObject().apply {
            put("isEnabled", settings.isEnabled)
            put("interval", settings.interval.name)
            put("selectedBookIds", org.json.JSONArray(settings.selectedBookIds))
        }
        prefs.edit().putString(KEY_NOTIFICATION_SETTINGS, json.toString()).apply()
    }

    private fun jsonArrayToList(jsonArray: org.json.JSONArray): List<String> {
        val list = mutableListOf<String>()
        for (i in 0 until jsonArray.length()) {
            list.add(jsonArray.getString(i))
        }
        return list
    }

    private fun setupBackPressHandler() {
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

    private fun setupAutoSyncSwitch() {
        autoSyncSwitch = findViewById(R.id.switch_auto_sync)
        val isAutoSync = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_AUTO_SYNC, true)
        autoSyncSwitch.isChecked = isAutoSync

        autoSyncSwitch.setOnCheckedChangeListener { _, isChecked ->
            getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_AUTO_SYNC, isChecked)
                .apply()
        }
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