package com.example.vulpinenotes

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.View
import android.widget.*
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.example.vulpinenotes.data.AppDatabase
import com.example.vulpinenotes.data.BookEntity
import com.example.vulpinenotes.data.toBook
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.navigation.NavigationView
import com.google.android.material.switchmaterial.SwitchMaterial
import com.google.android.material.textfield.TextInputEditText
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.*

class MainActivity : BaseActivity() {

    // UI
    private lateinit var booksRecyclerView: RecyclerView
    private lateinit var bookAdapter: BookAdapter
    private val books = mutableListOf<Book>()
    private lateinit var addBookButton: ImageButton
    private lateinit var drawerLayout: DrawerLayout
    private lateinit var searchEditText: EditText
    private lateinit var clearButton: ImageView
    private lateinit var menuButton: ImageView
    private lateinit var navView: NavigationView

    // Диалог
    private var currentCoverPreview: ImageView? = null
    private var currentBtnAddCover: Button? = null
    private var selectedImageFile: File? = null

    private lateinit var pickImageLauncher: ActivityResultLauncher<String>

    // Firebase
    private lateinit var auth: FirebaseAuth
    private lateinit var db: FirebaseFirestore

    // Room
    private lateinit var database: AppDatabase
    private lateinit var coversDir: File

    private val PREFS_NAME = "app_prefs"
    private val KEY_THEME = "app_theme"

    companion object {
        private const val REQUEST_SETTINGS = 1001
        private const val REQUEST_ACCOUNT = 1002
        const val EXTRA_BOOK = "com.example.vulpinenotes.EXTRA_BOOK"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applySavedTheme()
        setContentView(R.layout.activity_main)

        auth = FirebaseAuth.getInstance()
        db = FirebaseFirestore.getInstance()

        database = AppDatabase.getDatabase(this)
        coversDir = File(filesDir, "covers").apply { mkdirs() }

        pickImageLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            uri?.let { copyUriToCache(it) }?.let { file ->
                selectedImageFile = file
                currentCoverPreview?.apply {
                    setImageURI(Uri.fromFile(file))
                    visibility = View.VISIBLE
                }
                currentBtnAddCover?.setText("Изменить обложку")
            }
        }

        initViews()
        setupRecyclerView()
        setupDrawer()
        setupSearch()
        setupBackPress()

        observeLocalBooks()

        if (auth.currentUser != null) {
            syncLocalWithCloud(auth.currentUser!!)
            showAddBookButton()
        } else {
            showAuthRequired()
        }
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

    private fun copyUriToCache(uri: Uri): File? {
        return try {
            val name = getFileName(uri) ?: "temp_cover_${System.currentTimeMillis()}.jpg"
            val file = File(cacheDir, name)
            contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(file).use { output -> input.copyTo(output) }
            }
            file
        } catch (e: Exception) {
            Log.e("COPY", "Failed to copy image", e)
            null
        }
    }

    private fun getFileName(uri: Uri): String? {
        return if (uri.scheme == "content") {
            contentResolver.query(uri, null, null, null, null)?.use {
                if (it.moveToFirst()) {
                    it.getString(it.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME))
                } else null
            }
        } else uri.lastPathSegment
    }

    private fun observeLocalBooks() {
        lifecycleScope.launch {
            database.bookDao().getAllBooks().collect { bookEntities ->
                books.clear()
                books.addAll(bookEntities.map { it.toBook(coversDir) })
                bookAdapter.notifyDataSetChanged()
            }
        }
    }

    private fun showAuthRequired() {
        MaterialAlertDialogBuilder(this)
            .setTitle("Требуется вход")
            .setMessage("Войдите через Google, чтобы синхронизировать книги в облако.")
            .setPositiveButton("Войти") { _, _ ->
                startActivityForResult(Intent(this, AccountActivity::class.java), REQUEST_ACCOUNT)
            }
            .setNegativeButton("Отмена") { d, _ -> d.dismiss() }
            .setCancelable(false)
            .show()
        addBookButton.visibility = View.GONE
    }

    private fun showAddBookButton() {
        addBookButton.visibility = View.VISIBLE
        addBookButton.setOnClickListener { showAddDialog() }
    }

    // ДИАЛОГ СОЗДАНИЯ КНИГИ
    private fun showAddDialog() {
        val view = layoutInflater.inflate(R.layout.add_book_dialog, null)
        val editTitle = view.findViewById<TextInputEditText>(R.id.editText1)
        val editDesc = view.findViewById<TextInputEditText>(R.id.editText2)
        val switchUpload = view.findViewById<SwitchMaterial>(R.id.switchOptionCloud)
        currentCoverPreview = view.findViewById(R.id.coverPreview)
        currentBtnAddCover = view.findViewById(R.id.btnAddCover)

        selectedImageFile = null
        currentCoverPreview?.visibility = View.GONE
        currentBtnAddCover?.setText("Добавить обложку")
        switchUpload?.isChecked = true

        currentBtnAddCover?.setOnClickListener {
            pickImageLauncher.launch("image/*")
        }

        MaterialAlertDialogBuilder(this)
            .setTitle("Новая книга")
            .setView(view)
            .setPositiveButton("Создать") { _, _ ->
                val title = editTitle.text.toString().trim()
                val desc = editDesc.text.toString().trim()
                if (title.isBlank() && desc.isBlank()) {
                    Toast.makeText(this, "Введите название или описание", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }

                addBookLocally(
                    title = title.ifBlank { "Без названия" },
                    desc = desc.ifBlank { "Нет описания" },
                    coverFile = selectedImageFile,
                    uploadToCloud = switchUpload?.isChecked == true
                )
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    // ДИАЛОГ РЕДАКТИРОВАНИЯ — УБИРАЕМ СВИТЧ!
    private fun showEditDialog(book: Book) {
        val view = layoutInflater.inflate(R.layout.edit_book_dialog, null)
        val editTitle = view.findViewById<TextInputEditText>(R.id.editText1)
        val editDesc = view.findViewById<TextInputEditText>(R.id.editText2)
        currentCoverPreview = view.findViewById(R.id.coverPreview)
        currentBtnAddCover = view.findViewById(R.id.btnAddCover)

        editTitle.setText(book.title)
        editDesc.setText(book.desc)

        selectedImageFile = null

        if (book.coverUri != null) {
            Glide.with(this).load(book.coverUri).into(currentCoverPreview!!)
            currentCoverPreview?.visibility = View.VISIBLE
            currentBtnAddCover?.setText("Изменить обложку")
        } else {
            currentCoverPreview?.visibility = View.GONE
            currentBtnAddCover?.setText("Добавить обложку")
        }

        currentBtnAddCover?.setOnClickListener {
            pickImageLauncher.launch("image/*")
        }

        MaterialAlertDialogBuilder(this)
            .setTitle("Редактировать книгу")
            .setView(view)
            .setPositiveButton("Сохранить") { _, _ ->
                val newTitle = editTitle.text.toString().trim()
                val newDesc = editDesc.text.toString().trim()

                if (newTitle.isBlank() && newDesc.isBlank()) {
                    Toast.makeText(this, "Заполните хотя бы одно поле", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }

                updateBookLocally(
                    bookId = book.id,
                    newTitle = newTitle.ifBlank { "Без названия" },
                    newDesc = newDesc.ifBlank { "Нет описания" },
                    newCoverFile = selectedImageFile
                )
            }
            .setNegativeButton("Отмена", null)
            .show()
    }
    // ДОБАВЛЕНИЕ КНИГИ
    private fun addBookLocally(title: String, desc: String, coverFile: File?, uploadToCloud: Boolean) {
        lifecycleScope.launch {
            val bookId = UUID.randomUUID().toString()
            val coverPath = coverFile?.let {
                val dest = File(coversDir, "cover_$bookId.jpg")
                it.copyTo(dest, overwrite = true)
                dest.absolutePath
            }

            val bookEntity = BookEntity(
                id = bookId,
                title = title,
                desc = desc,
                coverPath = coverPath,
                updatedAt = System.currentTimeMillis(),
                cloudSynced = uploadToCloud
            )

            database.bookDao().insertBook(bookEntity)

            if (uploadToCloud && auth.currentUser != null) {
                syncBookToCloud(bookEntity, auth.currentUser!!)
            }

            Toast.makeText(this@MainActivity, "Книга добавлена", Toast.LENGTH_SHORT).show()
        }
    }

    // ОБНОВЛЕНИЕ КНИГИ (с контролем облака!)
    private fun updateBookLocally(
        bookId: String,
        newTitle: String,
        newDesc: String,
        newCoverFile: File?
    ) {
        lifecycleScope.launch {
            val currentBook = database.bookDao().getBookById(bookId) ?: return@launch

            val finalCoverPath = newCoverFile?.let {
                val destFile = File(coversDir, "cover_$bookId.jpg")
                it.copyTo(destFile, overwrite = true)
                destFile.absolutePath
            } ?: currentBook.coverPath

            val updatedBook = currentBook.copy(
                title = newTitle,
                desc = newDesc,
                coverPath = finalCoverPath,
                updatedAt = System.currentTimeMillis()
                // cloudSynced НЕ ТРОГАЕМ — остаётся как при создании!
            )

            database.bookDao().insertBook(updatedBook)

            // Если книга была в облаке — обновляем её там
            if (currentBook.cloudSynced && auth.currentUser != null) {
                syncBookToCloud(updatedBook, auth.currentUser!!)
            }

            Toast.makeText(this@MainActivity, "Книга обновлена", Toast.LENGTH_SHORT).show()
        }
    }

    private fun syncBookToCloud(book: BookEntity, user: FirebaseUser) {
        val data = hashMapOf(
            "title" to book.title,
            "desc" to book.desc,
            "chaptersCount" to book.chaptersCount,
            "updatedAt" to book.updatedAt
        )

        db.collection("users")
            .document(user.uid)
            .collection("books")
            .document(book.id)
            .set(data)
            .addOnSuccessListener {
                lifecycleScope.launch(Dispatchers.IO) {
                    database.bookDao().updateCloudState(book.id, true)
                }
            }
            .addOnFailureListener {
                Log.e("SYNC", "Ошибка синхронизации: ${book.title}", it)
            }
    }


    private fun syncLocalWithCloud(user: FirebaseUser) {
        db.collection("users")
            .document(user.uid)
            .collection("books")
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    Log.e("SYNC", "Ошибка слушателя Firestore", error)
                    return@addSnapshotListener
                }

                snapshot?.documents?.forEach { doc ->
                    val cloudBook = doc.toObject(BookEntity::class.java)?.copy(id = doc.id)
                    cloudBook?.let { book ->
                        lifecycleScope.launch {
                            // Проверяем, есть ли локально
                            val localBook = withContext(Dispatchers.IO) {
                                database.bookDao().getBookById(book.id)
                            }

                            // Если локальной нет ИЛИ облачная новее — обновляем
                            if (localBook == null || (book.updatedAt ?: 0) > (localBook.updatedAt)) {
                                database.bookDao().insertBook(
                                    book.copy(
                                        coverPath = localBook?.coverPath,  // сохраняем локальную обложку
                                        cloudSynced = true
                                    )
                                )
                            }
                        }
                    }
                }
            }
    }

    // Остальные функции без изменений...
    private fun setupRecyclerView() {
        bookAdapter = BookAdapter(
            books,
            this,
            onShowInfo = { showBookInfo(it) },
            onEditBook = { book, _ -> showEditDialog(book) },
            onBookClick = { startBookActivity(it) }
        )
        booksRecyclerView.adapter = bookAdapter
        booksRecyclerView.layoutManager = GridLayoutManager(this, 2)
    }

    private fun startBookActivity(book: Book) {
        startActivity(Intent(this, BookActivity::class.java).putExtra(EXTRA_BOOK, book))
    }

    private fun showBookInfo(book: Book) {
        val v = layoutInflater.inflate(R.layout.dialog_book_info, null)
        v.findViewById<TextView>(R.id.dialogTitle).text = book.title
        v.findViewById<TextView>(R.id.dialogAuthor).text = book.desc.ifBlank { getString(R.string.unknown_desc) }
        val cover = v.findViewById<ImageView>(R.id.dialogCover)
        if (book.coverUri != null) {
            Glide.with(this).load(book.coverUri).placeholder(R.drawable.book_cover_placeholder).into(cover)
            cover.visibility = View.VISIBLE
        } else cover.visibility = View.GONE

        MaterialAlertDialogBuilder(this, R.style.CustomAlertDialogTheme)
            .setTitle(R.string.info)
            .setView(v)
            .setPositiveButton("OK") { d, _ -> d.dismiss() }
            .show()
    }

    private fun setupDrawer() {
        menuButton.setOnClickListener { drawerLayout.openDrawer(GravityCompat.START) }
        navView.setNavigationItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_account -> startActivityForResult(Intent(this, AccountActivity::class.java), REQUEST_ACCOUNT)
                R.id.nav_settings -> startActivityForResult(Intent(this, SettingsActivity::class.java), REQUEST_SETTINGS)
                R.id.nav_cloud -> startActivity(Intent(this, CloudActivity::class.java))
            }
            drawerLayout.closeDrawer(GravityCompat.START)
            true
        }
    }

    private fun setupSearch() {
        searchEditText.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                clearButton.visibility = if (s.isNullOrBlank()) View.GONE else View.VISIBLE
            }
            override fun afterTextChanged(s: Editable?) {}
        })
        clearButton.setOnClickListener {
            searchEditText.text.clear()
            searchEditText.clearFocus()
        }
    }

    private fun setupBackPress() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (drawerLayout.isDrawerOpen(GravityCompat.START)) {
                    drawerLayout.closeDrawer(GravityCompat.START)
                } else {
                    finish()
                }
            }
        })
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_ACCOUNT && resultCode == RESULT_OK) {
            auth.currentUser?.let {
                syncLocalWithCloud(it)
                showAddBookButton()
            }
        }
        if (requestCode == REQUEST_SETTINGS && resultCode == RESULT_OK) {
            recreate()
        }
    }

    private fun applySavedTheme() {
        val mode = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getInt(KEY_THEME, AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
        AppCompatDelegate.setDefaultNightMode(mode)
    }
}