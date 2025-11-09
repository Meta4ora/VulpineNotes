package com.example.vulpinenotes

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.navigation.NavigationView
import com.google.android.material.switchmaterial.SwitchMaterial
import com.google.android.material.textfield.TextInputEditText
import android.widget.TextView

class MainActivity : AppCompatActivity() {

    // Views
    private lateinit var booksRecyclerView: RecyclerView
    private lateinit var bookAdapter: BookAdapter
    private val books = mutableListOf<Book>()
    private lateinit var addBookButton: ImageButton
    private lateinit var drawerLayout: DrawerLayout
    private lateinit var searchEditText: EditText
    private lateinit var clearButton: ImageView
    private lateinit var menuButton: ImageView
    private lateinit var navView: NavigationView

    // Dialog state
    private var selectedImageUri: Uri? = null
    private var coverPreview: ImageView? = null
    private var btnAddCover: Button? = null

    // Image picker
    private lateinit var pickImageLauncher: ActivityResultLauncher<String>

    // SharedPreferences
    private val PREFS_NAME = "app_prefs"
    private val KEY_THEME = "app_theme"
    private val KEY_LANGUAGE = "app_language"
    companion object {
        private const val REQUEST_SETTINGS = 1001
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        applySavedLanguage()
        applySavedTheme()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Регистрируем launcher ДО onStart()
        pickImageLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
            uri?.let {
                selectedImageUri = it
                coverPreview?.setImageURI(it)
                coverPreview?.visibility = View.VISIBLE
                btnAddCover?.text = getString(R.string.change_cover)
            }
        }

        initViews()
        setupRecyclerView()
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
        booksRecyclerView = findViewById(R.id.books_recycler_view)
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

    private fun showBookInfo(book: Book) {
        val dialogView = layoutInflater.inflate(R.layout.dialog_book_info, null)

        val cover = dialogView.findViewById<ImageView>(R.id.dialogCover)
        val title = dialogView.findViewById<TextView>(R.id.dialogTitle)
        val author = dialogView.findViewById<TextView>(R.id.dialogAuthor)

        title.text = book.title
        author.text = book.desc

        if (book.coverUri != null) {
            cover.setImageURI(Uri.parse(book.coverUri))
            cover.visibility = View.VISIBLE
        } else {
            cover.visibility = View.GONE
        }

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.info)
            .setView(dialogView)
            .setPositiveButton("OK") { d, _ -> d.dismiss() }
            .show()
    }

    private fun setupRecyclerView() {
        bookAdapter = BookAdapter(books, this) { book ->
            showBookInfo(book)
        }
        booksRecyclerView.adapter = bookAdapter
        booksRecyclerView.layoutManager = GridLayoutManager(this, 2)

        // Тестовая книга
        books.add(Book("Зов Ктулху", "Г. Ф. Лавкрафт"))
        bookAdapter.notifyDataSetChanged()
    }

    private fun showCustomDialog() {
        val dialogView = layoutInflater.inflate(R.layout.add_book_dialog, null)

        val switchOption = dialogView.findViewById<SwitchMaterial>(R.id.switchOption)
        val editText1 = dialogView.findViewById<TextInputEditText>(R.id.editText1)
        val editText2 = dialogView.findViewById<TextInputEditText>(R.id.editText2)
        coverPreview = dialogView.findViewById(R.id.coverPreview)
        btnAddCover = dialogView.findViewById(R.id.btnAddCover)

        // Сброс состояния
        selectedImageUri = null
        coverPreview?.visibility = View.GONE
        btnAddCover?.text = getString(R.string.add_cover)

        btnAddCover?.setOnClickListener {
            pickImageLauncher.launch("image/*")
        }

        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.add_book)
            .setView(dialogView)
            .setPositiveButton(R.string.save) { _, _ ->
                val title = editText1.text.toString().trim()
                val author = editText2.text.toString().trim()

                if (title.isBlank() && author.isBlank()) {
                    Toast.makeText(this, R.string.fill_at_least_one_field, Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }

                val newBook = Book(
                    title = title.ifBlank { getString(R.string.no_title) },
                    desc = author.ifBlank { getString(R.string.unknown_author) },
                    coverUri = selectedImageUri?.toString()
                )
                bookAdapter.addBook(newBook)
                Toast.makeText(this, R.string.book_added, Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(R.string.cancel) { d, _ -> d.dismiss() }
            .setNeutralButton(R.string.reset) { _, _ ->
                switchOption.isChecked = false
                editText1.text?.clear()
                editText2.text?.clear()
                coverPreview?.visibility = View.GONE
                btnAddCover?.text = getString(R.string.add_cover)
                selectedImageUri = null
            }
            .create()

        dialog.show()
    }

    private fun showDialogAddBook() {
        addBookButton.setOnClickListener {
            showCustomDialog()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_SETTINGS && resultCode == RESULT_OK) {
            val langChanged = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getBoolean("lang_changed", false)

            if (langChanged) {
                getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .edit()
                    .remove("lang_changed")
                    .apply()
                recreate()
            }
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

    // --- ТЕМА И ЯЗЫК ---
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

    private fun applySavedLanguage() {
        val lang = getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
            .getString("app_language", "ru") ?: "ru"
        val locale = java.util.Locale(lang)
        java.util.Locale.setDefault(locale)
        val config = resources.configuration
        config.setLocale(locale)
        resources.updateConfiguration(config, resources.displayMetrics)
    }
}