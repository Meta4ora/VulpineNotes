package com.example.vulpinenotes

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.navigation.NavigationView
import com.google.android.material.switchmaterial.SwitchMaterial
import com.google.android.material.textfield.TextInputEditText

class MainActivity : AppCompatActivity() {

    private lateinit var addBookButton: ImageButton
    private lateinit var drawerLayout: DrawerLayout
    private lateinit var searchEditText: EditText
    private lateinit var clearButton: ImageView
    private lateinit var menuButton: ImageView
    private lateinit var navView: NavigationView

    private val PREFS_NAME = "app_prefs"
    private val KEY_THEME = "app_theme"
    companion object {
        private const val REQUEST_SETTINGS = 1001
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        applySavedTheme()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        initViews()
        setupDrawer()
        setupSearch()
        setupBackPress()
        showDialogAddBook()
    }

    private fun initViews() {
        drawerLayout = findViewById(R.id.drawer_layout)
        menuButton = findViewById(R.id.menu_button)
        searchEditText = findViewById(R.id.search_edit_text)
        clearButton = findViewById(R.id.clear_button)
        navView = findViewById(R.id.nav_view)
        addBookButton = findViewById(R.id.add_button)
    }

    private fun setupDrawer() {
        menuButton.setOnClickListener {
            drawerLayout.openDrawer(GravityCompat.START)
        }

        navView.setNavigationItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_settings -> {
                    startActivityForResult(Intent(this, SettingsActivity::class.java), REQUEST_SETTINGS)
                    drawerLayout.closeDrawer(GravityCompat.START)
                    return@setNavigationItemSelectedListener true
                }
            }
            drawerLayout.closeDrawer(GravityCompat.START)
            true
        }
    }

    private fun showCustomDialog() {
        val dialogView = layoutInflater.inflate(R.layout.add_book_dialog, null)

        val switchOption = dialogView.findViewById<SwitchMaterial>(R.id.switchOption)
        val editText1 = dialogView.findViewById<TextInputEditText>(R.id.editText1)
        val editText2 = dialogView.findViewById<TextInputEditText>(R.id.editText2)

        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle("Настройки")
            .setView(dialogView)
            .setPositiveButton("Сохранить") { dialogInterface, which ->
                // Действия при нажатии "Сохранить"
                val isSwitchChecked = switchOption.isChecked
                val text1 = editText1.text.toString()
                val text2 = editText2.text.toString()

                // Обработка данных
                handleDialogResults(isSwitchChecked, text1, text2)
            }
            .setNegativeButton("Отмена") { dialogInterface, which ->
                // Действия при нажатии "Отмена"
                dialogInterface.dismiss()
            }
            .setNeutralButton("Сброс") { dialogInterface, which ->
                // Действия при нажатии "Сброс"
                switchOption.isChecked = false
                editText1.setText("")
                editText2.setText("")
            }
            .create()

        dialog.show()
    }

    private fun handleDialogResults(
        isSwitchChecked: Boolean,
        text1: String,
        text2: String
    ) {
        // Обработка результатов
        val message = "Switch: $isSwitchChecked\nПоле 1: $text1\nПоле 2: $text2"
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()

        // Здесь можно сохранить данные или выполнить другие действия
    }

    private fun showDialogAddBook() {
        addBookButton.setOnClickListener {
            showCustomDialog()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_SETTINGS && resultCode == RESULT_OK) {
            if (data?.getBooleanExtra("lang_changed", false) == true) {
                recreate() // язык меняли — пересоздадимся
            }
            // Тему трогать не нужно: уже применена глобально
        }
    }

    private fun setupSearch() {
        searchEditText.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                clearButton.visibility = if (s.isNullOrEmpty()) View.GONE else View.VISIBLE
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        clearButton.setOnClickListener {
            searchEditText.text.clear()
            searchEditText.clearFocus()
        }
    }

    private fun setupBackPress() {
        val callback = object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (drawerLayout.isDrawerOpen(GravityCompat.START)) {
                    drawerLayout.closeDrawer(GravityCompat.START)
                } else {
                    finish()
                }
            }
        }
        onBackPressedDispatcher.addCallback(this, callback)
    }

    // --- ФУНКЦИИ ДЛЯ ТЕМЫ (оставлены, но используются только при запуске) ---
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

}