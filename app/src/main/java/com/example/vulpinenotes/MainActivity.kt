package com.example.vulpinenotes

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.*
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.navigation.NavigationView
import com.google.android.material.textfield.TextInputEditText

class MainActivity : BaseActivity() {

    private lateinit var booksRecyclerView: RecyclerView
    private lateinit var bookAdapter: BookAdapter
    private val books = mutableListOf<Book>()
    private lateinit var addBookButton: ImageButton
    private lateinit var drawerLayout: DrawerLayout
    private lateinit var searchEditText: EditText
    private lateinit var clearButton: ImageView
    private lateinit var menuButton: ImageView
    private lateinit var navView: NavigationView

    private var currentCoverPreview: ImageView? = null
    private var currentBtnAddCover: Button? = null
    private var selectedImageUri: Uri? = null

    private lateinit var pickImageLauncher: ActivityResultLauncher<String>

    private val PREFS_NAME = "app_prefs"
    private val KEY_THEME = "app_theme"

    companion object {
        private const val REQUEST_SETTINGS = 1001
        const val EXTRA_BOOK = "com.example.vulpinenotes.EXTRA_BOOK"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        applySavedTheme()
        setContentView(R.layout.activity_main)

        pickImageLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
            uri?.let {
                selectedImageUri = it
                currentCoverPreview?.setImageURI(it)
                currentCoverPreview?.visibility = View.VISIBLE
                currentBtnAddCover?.text = getString(R.string.change_cover)
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

    private fun startBookActivity(book: Book) {
        val intent = Intent(this, BookActivity::class.java).apply {
            putExtra(EXTRA_BOOK, book)
        }
        startActivity(intent)
    }

    private fun setupDrawer() {
        menuButton.setOnClickListener {
            drawerLayout.openDrawer(GravityCompat.START)
        }

        navView.setNavigationItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_account -> {
                    startActivity(Intent(this, AccountActivity::class.java))
                    drawerLayout.closeDrawer(GravityCompat.START)
                    true
                }
                R.id.nav_cloud -> {
                    startActivity(Intent(this, CloudActivity::class.java))
                    drawerLayout.closeDrawer(GravityCompat.START)
                    true
                }
                R.id.nav_support -> {
                    startActivity(Intent(this, SupportActivity::class.java))
                    drawerLayout.closeDrawer(GravityCompat.START)
                    true
                }
                R.id.nav_settings -> {
                    startActivityForResult(Intent(this, SettingsActivity::class.java), REQUEST_SETTINGS)
                    drawerLayout.closeDrawer(GravityCompat.START)
                    true
                }
                else -> false
            }
        }
    }

    private fun setupRecyclerView() {
        bookAdapter = BookAdapter(
            books,
            this,
            onShowInfo = { book -> showBookInfo(book) },
            onEditBook = { book, position -> showEditDialog(book, position) },
            onBookClick = { book -> startBookActivity(book) }
        )
        booksRecyclerView.adapter = bookAdapter
        booksRecyclerView.layoutManager = GridLayoutManager(this, 2)

        books.add(Book("Зов Ктулху", "Г. Ф. Лавкрафт", chaptersCount = 12))
        books.add(Book("Дюна", "Фрэнк Герберт", chaptersCount = 48))
        bookAdapter.notifyDataSetChanged()
    }

    private fun showBookInfo(book: Book) {
        val dialogView = layoutInflater.inflate(R.layout.dialog_book_info, null)
        val cover = dialogView.findViewById<ImageView>(R.id.dialogCover)
        val title = dialogView.findViewById<TextView>(R.id.dialogTitle)
        val author = dialogView.findViewById<TextView>(R.id.dialogAuthor)

        title.text = book.title
        author.text = book.desc.ifBlank { getString(R.string.unknown_desc) }

        if (book.coverUri != null) {
            cover.setImageURI(Uri.parse(book.coverUri))
            cover.visibility = View.VISIBLE
        } else {
            cover.visibility = View.GONE
        }

        MaterialAlertDialogBuilder(this, R.style.CustomAlertDialogTheme)
            .setTitle(R.string.info)
            .setView(dialogView)
            .setPositiveButton("OK") { d, _ -> d.dismiss() }
            .show()
    }

    private fun showAddDialog() {
        val dialogView = layoutInflater.inflate(R.layout.add_book_dialog, null)

        val editTitle = dialogView.findViewById<TextInputEditText>(R.id.editText1)
        val editDesc = dialogView.findViewById<TextInputEditText>(R.id.editText2)
        currentCoverPreview = dialogView.findViewById(R.id.coverPreview)
        currentBtnAddCover = dialogView.findViewById(R.id.btnAddCover)

        selectedImageUri = null
        currentCoverPreview?.visibility = View.GONE
        currentBtnAddCover?.text = getString(R.string.add_cover)

        currentBtnAddCover?.setOnClickListener {
            pickImageLauncher.launch("image/*")
        }

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.add_book)
            .setView(dialogView)
            .setPositiveButton(R.string.save) { _, _ ->
                val title = editTitle.text.toString().trim()
                val desc = editDesc.text.toString().trim()

                if (title.isBlank() && desc.isBlank()) {
                    Toast.makeText(this, R.string.fill_at_least_one_field, Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }

                val newBook = Book(
                    title = title.ifBlank { getString(R.string.no_title) },
                    desc = desc.ifBlank { getString(R.string.unknown_desc) },
                    coverUri = selectedImageUri?.toString(),
                    chaptersCount = 0  // Новая книга — 0 глав
                )
                bookAdapter.addBook(newBook)
                Toast.makeText(this, R.string.book_added, Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(R.string.cancel) { d, _ -> d.dismiss() }
            .setNeutralButton(R.string.reset) { _, _ ->
                editTitle.text?.clear()
                editDesc.text?.clear()
                currentCoverPreview?.visibility = View.GONE
                currentBtnAddCover?.text = getString(R.string.add_cover)
                selectedImageUri = null
            }
            .show()
    }

    private fun showEditDialog(book: Book, position: Int) {
        val dialogView = layoutInflater.inflate(R.layout.edit_book_dialog, null)

        val editTitle = dialogView.findViewById<TextInputEditText>(R.id.editText1)
        val editDesc = dialogView.findViewById<TextInputEditText>(R.id.editText2)
        currentCoverPreview = dialogView.findViewById(R.id.coverPreview)
        currentBtnAddCover = dialogView.findViewById(R.id.btnAddCover)

        editTitle.setText(book.title)
        editDesc.setText(book.desc)
        selectedImageUri = book.coverUri?.let { Uri.parse(it) }

        if (selectedImageUri != null) {
            currentCoverPreview?.setImageURI(selectedImageUri)
            currentCoverPreview?.visibility = View.VISIBLE
            currentBtnAddCover?.text = getString(R.string.change_cover)
        } else {
            currentCoverPreview?.visibility = View.GONE
            currentBtnAddCover?.text = getString(R.string.change_cover)
        }

        currentBtnAddCover?.setOnClickListener {
            pickImageLauncher.launch("image/*")
        }

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.edit)
            .setView(dialogView)
            .setPositiveButton(R.string.save) { _, _ ->
                val title = editTitle.text.toString().trim()
                val desc = editDesc.text.toString().trim()

                if (title.isBlank() && desc.isBlank()) {
                    Toast.makeText(this, R.string.fill_at_least_one_field, Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }

                // В showEditDialog()
                val updatedBook = Book(
                    title = title.ifBlank { getString(R.string.no_title) },
                    desc = desc.ifBlank { getString(R.string.unknown_desc) },
                    coverUri = selectedImageUri?.toString(),
                    chaptersCount = book.chaptersCount  // Сохраняем текущее количество
                )
                bookAdapter.updateBook(position, updatedBook)
                Toast.makeText(this, R.string.book_updated, Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(R.string.cancel) { d, _ -> d.dismiss() }
            .setNeutralButton(R.string.reset) { _, _ ->
                editTitle.setText(book.title)
                editDesc.setText(book.desc)
                selectedImageUri = book.coverUri?.let { Uri.parse(it) }
                if (selectedImageUri != null) {
                    currentCoverPreview?.setImageURI(selectedImageUri)
                    currentCoverPreview?.visibility = View.VISIBLE
                } else {
                    currentCoverPreview?.visibility = View.GONE
                }
            }
            .show()
    }

    private fun showDialogAddBook() {
        addBookButton.setOnClickListener { showAddDialog() }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_SETTINGS && resultCode == RESULT_OK) {
            val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            if (prefs.getBoolean("lang_changed", false)) {
                prefs.edit().remove("lang_changed").apply()
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

    private fun applySavedTheme() {
        val mode = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getInt(KEY_THEME, AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
        if (AppCompatDelegate.getDefaultNightMode() != mode) {
            AppCompatDelegate.setDefaultNightMode(mode)
        }
    }
}