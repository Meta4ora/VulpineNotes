package com.example.vulpinenotes

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
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
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.navigation.NavigationView
import com.google.android.material.textfield.TextInputEditText
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.firestore.*
import com.google.firebase.storage.FirebaseStorage
import com.google.firebase.storage.StorageException
import com.google.firebase.storage.StorageReference
import java.io.*
import java.util.*

class MainActivity : BaseActivity() {

    // ──────────────────────────────────────────────────────────────
    // UI + адаптер
    // ──────────────────────────────────────────────────────────────
    private lateinit var booksRecyclerView: RecyclerView
    private lateinit var bookAdapter: BookAdapter
    private val books = mutableListOf<Book>()
    private lateinit var addBookButton: ImageButton
    private lateinit var drawerLayout: DrawerLayout
    private lateinit var searchEditText: EditText
    private lateinit var clearButton: ImageView
    private lateinit var menuButton: ImageView
    private lateinit var navView: NavigationView

    // ──────────────────────────────────────────────────────────────
    // Диалог добавления/редактирования
    // ──────────────────────────────────────────────────────────────
    private var currentCoverPreview: ImageView? = null
    private var currentBtnAddCover: Button? = null
    private var selectedImageFile: File? = null
    private var isNewCoverSelected = false

    private lateinit var pickImageLauncher: ActivityResultLauncher<String>

    // ──────────────────────────────────────────────────────────────
    // Firebase
    // ──────────────────────────────────────────────────────────────
    private lateinit var auth: FirebaseAuth
    private lateinit var db: FirebaseFirestore
    private var userListener: ListenerRegistration? = null

    private val PREFS_NAME = "app_prefs"
    private val KEY_THEME = "app_theme"

    companion object {
        private const val REQUEST_SETTINGS = 1001
        private const val REQUEST_ACCOUNT = 1002
        const val EXTRA_BOOK = "com.example.vulpinenotes.EXTRA_BOOK"
        private const val MAX_RETRY = 3
        private const val MAX_IMAGE_SIZE = 1_500_000L   // 1.5 МБ
    }

    // ──────────────────────────────────────────────────────────────
    // onCreate
    // ──────────────────────────────────────────────────────────────
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applySavedTheme()
        setContentView(R.layout.activity_main)

        auth = FirebaseAuth.getInstance()
        db = FirebaseFirestore.getInstance()

        // ---------- Пикер изображения ----------
        pickImageLauncher =
            registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
                uri?.let { copyUriToCache(it) }?.let { file ->
                    selectedImageFile = file
                    isNewCoverSelected = true
                    currentCoverPreview?.apply {
                        setImageURI(Uri.fromFile(file))
                        visibility = View.VISIBLE
                    }
                    currentBtnAddCover?.setText(R.string.change_cover)
                } ?: Toast.makeText(this, "Ошибка обработки изображения", Toast.LENGTH_SHORT).show()
            }

        initViews()
        setupRecyclerView()
        setupDrawer()
        setupSearch()
        setupBackPress()
        checkAuthAndLoadData()
    }

    // ──────────────────────────────────────────────────────────────
    // UI init
    // ──────────────────────────────────────────────────────────────
    private fun initViews() {
        drawerLayout = findViewById(R.id.drawer_layout)
        menuButton = findViewById(R.id.menu_button)
        searchEditText = findViewById(R.id.search_edit_text)
        clearButton = findViewById(R.id.clear_button)
        navView = findViewById(R.id.nav_view)
        addBookButton = findViewById(R.id.add_button)
        booksRecyclerView = findViewById(R.id.books_recycler_view)
    }

    // ──────────────────────────────────────────────────────────────
    // Копирование Uri → File (в кэш)
    // ──────────────────────────────────────────────────────────────
    private fun copyUriToCache(uri: Uri): File? {
        return try {
            val name = getFileName(uri) ?: "cover_${System.currentTimeMillis()}.jpg"
            val cacheFile = File(cacheDir, name)
            contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(cacheFile).use { output -> input.copyTo(output) }
            } ?: return null
            cacheFile
        } catch (e: Exception) {
            Log.e("COPY", "Failed to copy image", e)
            null
        }
    }

    private fun getFileName(uri: Uri): String? {
        return if (uri.scheme == "content") {
            contentResolver.query(uri, null, null, null, null)?.use {
                if (it.moveToFirst()) it.getString(it.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME))
                else null
            }
        } else null
    }

    // ──────────────────────────────────────────────────────────────
    // Авторизация + загрузка книг
    // ──────────────────────────────────────────────────────────────
    private fun checkAuthAndLoadData() {
        if (auth.currentUser != null) {
            loadUserBooks(auth.currentUser!!)
            showAddBookButton()
        } else {
            showAuthRequired()
        }
    }

    private fun loadUserBooks(user: FirebaseUser) {
        userListener?.remove()
        books.clear()
        bookAdapter.notifyDataSetChanged()

        userListener = db.collection("users")
            .document(user.uid)
            .collection("books")
            .orderBy("updatedAt", Query.Direction.DESCENDING)
            .addSnapshotListener { snap, e ->
                if (e != null) {
                    Toast.makeText(this, "Ошибка: ${e.message}", Toast.LENGTH_SHORT).show()
                    return@addSnapshotListener
                }
                snap ?: return@addSnapshotListener
                books.clear()
                snap.documents.forEach { doc ->
                    doc.toObject(Book::class.java)?.copy(id = doc.id)?.let { books.add(it) }
                }
                bookAdapter.notifyDataSetChanged()
            }
    }

    private fun showAuthRequired() {
        MaterialAlertDialogBuilder(this)
            .setTitle("Требуется вход")
            .setMessage("Войдите через Google, чтобы сохранять книги в облако.")
            .setPositiveButton("Войти") { _, _ ->
                startActivityForResult(Intent(this, AccountActivity::class.java), REQUEST_ACCOUNT)
            }
            .setNegativeButton("Отмена") { d, _ -> d.dismiss() }
            .setCancelable(false)
            .show()
        addBookButton.visibility = View.GONE
        booksRecyclerView.visibility = View.GONE
    }

    private fun showAddBookButton() {
        addBookButton.visibility = View.VISIBLE
        booksRecyclerView.visibility = View.VISIBLE
        addBookButton.setOnClickListener { showAddDialog() }
    }

    // ──────────────────────────────────────────────────────────────
    // Диалог добавления книги
    // ──────────────────────────────────────────────────────────────
    private fun showAddDialog() {
        if (auth.currentUser == null) {
            Toast.makeText(this, "Войдите в аккаунт", Toast.LENGTH_SHORT).show()
            return
        }

        val view = layoutInflater.inflate(R.layout.add_book_dialog, null)
        val editTitle = view.findViewById<TextInputEditText>(R.id.editText1)
        val editDesc = view.findViewById<TextInputEditText>(R.id.editText2)
        currentCoverPreview = view.findViewById(R.id.coverPreview)
        currentBtnAddCover = view.findViewById(R.id.btnAddCover)

        selectedImageFile = null
        isNewCoverSelected = false
        currentCoverPreview?.visibility = View.GONE
        currentBtnAddCover?.setText(R.string.add_cover)

        currentBtnAddCover?.setOnClickListener {
            isNewCoverSelected = true
            pickImageLauncher.launch("image/*")
        }

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.add_book)
            .setView(view)
            .setPositiveButton(R.string.save) { _, _ ->
                val title = editTitle.text.toString().trim()
                val desc = editDesc.text.toString().trim()
                if (title.isBlank() && desc.isBlank()) {
                    Toast.makeText(this, R.string.fill_at_least_one_field, Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                addBookToFirestore(
                    title = title.ifBlank { getString(R.string.no_title) },
                    desc = desc.ifBlank { getString(R.string.unknown_desc) },
                    coverFile = selectedImageFile
                )
            }
            .setNegativeButton(R.string.cancel) { d, _ -> d.dismiss() }
            .show()
    }

    // ──────────────────────────────────────────────────────────────
    // Загрузка обложки
    // ──────────────────────────────────────────────────────────────
    private fun uploadCoverAndGetUrl(
        coverFile: File?,
        onComplete: (String?) -> Unit,
        attempt: Int = 0
    ) {
        if (coverFile == null || !coverFile.exists() || coverFile.length() == 0L) {
            onComplete(null)
            return
        }

        val user = auth.currentUser ?: run {
            onComplete(null)
            return
        }

        val fileToUpload = if (coverFile.length() > MAX_IMAGE_SIZE) {
            compressImageFile(coverFile) ?: coverFile
        } else coverFile

        val storage = FirebaseStorage.getInstance()
        val bucketName = storage.app.options.storageBucket // правильное получение имени бакета
        Log.d("UPLOAD", "Bucket: $bucketName")

        val fileName = "cover_${UUID.randomUUID()}.jpg"
        val ref = storage.reference.child("covers/${user.uid}/$fileName")
        Log.d("UPLOAD", "Uploading ${fileToUpload.length()} bytes → ${ref.path}")

        try {
            val stream = FileInputStream(fileToUpload)
            val task = ref.putStream(stream)

            task.addOnSuccessListener {
                Log.d("UPLOAD", "Upload OK, fetching URL")
                Thread.sleep(800)

                ref.downloadUrl.addOnSuccessListener { uri ->
                    Log.d("UPLOAD", "URL: $uri")
                    fileToUpload.delete()
                    if (fileToUpload != coverFile) coverFile.delete()
                    onComplete(uri.toString())
                }.addOnFailureListener { e ->
                    handleUrlError(e, ref, fileToUpload, coverFile, onComplete, attempt)
                }
            }.addOnFailureListener { e ->
                handleUploadError(e, fileToUpload, coverFile, onComplete, attempt, ref)
            }
        } catch (e: Exception) {
            Log.e("UPLOAD", "IO error", e)
            onComplete(null)
        }
    }

    // ──────────────────────────────────────────────────────────────
    // Обработка ошибок
    // ──────────────────────────────────────────────────────────────
    private fun handleUploadError(
        e: Exception,
        fileToUpload: File,
        originalFile: File,
        onComplete: (String?) -> Unit,
        attempt: Int,
        ref: StorageReference
    ) {
        Log.e("UPLOAD", "Upload failed (attempt $attempt)", e)

        if (e is StorageException && e.httpResultCode == 404 && attempt < MAX_RETRY) {
            Log.d("UPLOAD", "Retry ${attempt + 1}/$MAX_RETRY")
            uploadCoverAndGetUrl(originalFile, onComplete, attempt + 1)
            return
        }

        val msg = when ((e as? StorageException)?.httpResultCode) {
            404 -> "Бакет не найден. Проверь google-services.json"
            401 -> "Не авторизован"
            else -> e.message ?: "Неизвестная ошибка"
        }
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
        fileToUpload.delete()
        if (fileToUpload != originalFile) originalFile.delete()
        onComplete(null)
    }

    private fun handleUrlError(
        e: Exception,
        ref: StorageReference,
        fileToUpload: File,
        originalFile: File,
        onComplete: (String?) -> Unit,
        attempt: Int
    ) {
        Log.e("UPLOAD", "URL fetch failed (attempt $attempt)", e)
        if (e is StorageException && e.httpResultCode == 404 && attempt < MAX_RETRY) {
            Log.d("UPLOAD", "Retry URL ${attempt + 1}/$MAX_RETRY")
            uploadCoverAndGetUrl(originalFile, onComplete, attempt + 1)
            return
        }
        Toast.makeText(this, "Не удалось получить URL", Toast.LENGTH_LONG).show()
        fileToUpload.delete()
        if (fileToUpload != originalFile) originalFile.delete()
        onComplete(null)
    }

    // ──────────────────────────────────────────────────────────────
    // Компрессия
    // ──────────────────────────────────────────────────────────────
    private fun compressImageFile(src: File): File? {
        return try {
            val bmp = BitmapFactory.decodeFile(src.path)
            val out = ByteArrayOutputStream()
            bmp.compress(Bitmap.CompressFormat.JPEG, 75, out)
            val compressed = File(cacheDir, "compressed_${System.currentTimeMillis()}.jpg")
            compressed.writeBytes(out.toByteArray())
            compressed
        } catch (e: Exception) {
            Log.e("COMPRESS", "Failed", e)
            null
        }
    }

    // ──────────────────────────────────────────────────────────────
    // Сохранение книги
    // ──────────────────────────────────────────────────────────────
    private fun saveBookToFirestore(
        user: FirebaseUser,
        title: String,
        desc: String,
        coverUrl: String?,
        bookId: String? = null,
        chaptersCount: Int = 0
    ) {
        val ref = if (bookId != null) {
            db.collection("users").document(user.uid).collection("books").document(bookId)
        } else {
            db.collection("users").document(user.uid).collection("books").document()
        }

        val book = Book(
            id = ref.id,
            title = title,
            desc = desc,
            coverUri = coverUrl,
            chaptersCount = chaptersCount,
            updatedAt = System.currentTimeMillis()
        )

        ref.set(book)
            .addOnSuccessListener {
                Toast.makeText(this, if (bookId == null) "Книга добавлена" else "Книга обновлена", Toast.LENGTH_SHORT).show()
            }
            .addOnFailureListener { e ->
                Toast.makeText(this, "Ошибка сохранения: ${e.message}", Toast.LENGTH_SHORT).show()
            }
    }

    // ──────────────────────────────────────────────────────────────
    // Добавление книги
    // ──────────────────────────────────────────────────────────────
    private fun addBookToFirestore(title: String, desc: String, coverFile: File?) {
        val user = auth.currentUser ?: return
        if (coverFile == null) {
            saveBookToFirestore(user, title, desc, null)
            return
        }
        uploadCoverAndGetUrl(coverFile, { url ->
            saveBookToFirestore(user, title, desc, url)
        })
    }

    // ──────────────────────────────────────────────────────────────
    // Редактирование книги
    // ──────────────────────────────────────────────────────────────
    private fun showEditDialog(book: Book, position: Int) {
        val view = layoutInflater.inflate(R.layout.edit_book_dialog, null)
        val editTitle = view.findViewById<TextInputEditText>(R.id.editText1)
        val editDesc = view.findViewById<TextInputEditText>(R.id.editText2)
        currentCoverPreview = view.findViewById(R.id.coverPreview)
        currentBtnAddCover = view.findViewById(R.id.btnAddCover)

        editTitle.setText(book.title)
        editDesc.setText(book.desc)

        selectedImageFile = null
        isNewCoverSelected = false

        if (!book.coverUri.isNullOrBlank()) {
            Glide.with(this).load(book.coverUri)
                .placeholder(R.drawable.book_cover_placeholder)
                .into(currentCoverPreview!!)
            currentCoverPreview?.visibility = View.VISIBLE
        } else currentCoverPreview?.visibility = View.GONE

        currentBtnAddCover?.setText(R.string.change_cover)
        currentBtnAddCover?.setOnClickListener {
            isNewCoverSelected = true
            pickImageLauncher.launch("image/*")
        }

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.edit)
            .setView(view)
            .setPositiveButton(R.string.save) { _, _ ->
                val title = editTitle.text.toString().trim()
                val desc = editDesc.text.toString().trim()
                if (title.isBlank() && desc.isBlank()) {
                    Toast.makeText(this, R.string.fill_at_least_one_field, Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                val finalTitle = title.ifBlank { getString(R.string.no_title) }
                val finalDesc = desc.ifBlank { getString(R.string.unknown_desc) }

                if (!isNewCoverSelected) {
                    saveBookToFirestore(
                        user = auth.currentUser!!,
                        title = finalTitle,
                        desc = finalDesc,
                        coverUrl = book.coverUri,
                        bookId = book.id,
                        chaptersCount = book.chaptersCount
                    )
                } else {
                    uploadCoverAndGetUrl(selectedImageFile, { url ->
                        saveBookToFirestore(
                            user = auth.currentUser!!,
                            title = finalTitle,
                            desc = finalDesc,
                            coverUrl = url,
                            bookId = book.id,
                            chaptersCount = book.chaptersCount
                        )
                    })
                }
            }
            .setNegativeButton(R.string.cancel) { d, _ -> d.dismiss() }
            .show()
    }

    // ──────────────────────────────────────────────────────────────
    // UI методы
    // ──────────────────────────────────────────────────────────────
    private fun setupRecyclerView() {
        bookAdapter = BookAdapter(
            books,
            this,
            onShowInfo = { showBookInfo(it) },
            onEditBook = { book, pos -> showEditDialog(book, pos) },
            onBookClick = { startBookActivity(it) }
        )
        booksRecyclerView.adapter = bookAdapter
        booksRecyclerView.layoutManager = GridLayoutManager(this, 2)
    }

    private fun startBookActivity(book: Book) {
        startActivity(Intent(this, BookActivity::class.java).putExtra(EXTRA_BOOK, book))
    }

    private fun setupDrawer() {
        menuButton.setOnClickListener { drawerLayout.openDrawer(GravityCompat.START) }
        navView.setNavigationItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_account -> startActivityForResult(Intent(this, AccountActivity::class.java), REQUEST_ACCOUNT)
                R.id.nav_cloud -> startActivity(Intent(this, CloudActivity::class.java))
                R.id.nav_support -> startActivity(Intent(this, SupportActivity::class.java))
                R.id.nav_settings -> startActivityForResult(Intent(this, SettingsActivity::class.java), REQUEST_SETTINGS)
            }
            drawerLayout.closeDrawer(GravityCompat.START)
            true
        }
    }

    private fun showBookInfo(book: Book) {
        val v = layoutInflater.inflate(R.layout.dialog_book_info, null)
        v.findViewById<TextView>(R.id.dialogTitle).text = book.title
        v.findViewById<TextView>(R.id.dialogAuthor).text = book.desc.ifBlank { getString(R.string.unknown_desc) }
        val cover = v.findViewById<ImageView>(R.id.dialogCover)
        if (!book.coverUri.isNullOrBlank()) {
            Glide.with(this).load(book.coverUri).placeholder(R.drawable.book_cover_placeholder).into(cover)
            cover.visibility = View.VISIBLE
        } else cover.visibility = View.GONE

        MaterialAlertDialogBuilder(this, R.style.CustomAlertDialogTheme)
            .setTitle(R.string.info)
            .setView(v)
            .setPositiveButton("OK") { d, _ -> d.dismiss() }
            .show()
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
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (drawerLayout.isDrawerOpen(GravityCompat.START)) drawerLayout.closeDrawer(GravityCompat.START)
                else finish()
            }
        })
    }

    // ──────────────────────────────────────────────────────────────
    // Жизненный цикл
    // ──────────────────────────────────────────────────────────────
    override fun onStart() {
        super.onStart()
        auth.currentUser?.let {
            if (userListener == null) {
                loadUserBooks(it)
                showAddBookButton()
            }
        }
    }

    override fun onStop() {
        super.onStop()
        userListener?.remove()
        userListener = null
    }

    override fun onDestroy() {
        super.onDestroy()
        cacheDir.listFiles()?.forEach { f ->
            if (f.name.startsWith("cover_") || f.name.endsWith(".jpg")) f.delete()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_SETTINGS && resultCode == RESULT_OK) {
            val changed = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getBoolean("lang_changed", false)
            if (changed) {
                getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().remove("lang_changed").apply()
                recreate()
            }
        }
        if (requestCode == REQUEST_ACCOUNT && resultCode == RESULT_OK) checkAuthAndLoadData()
    }

    private fun applySavedTheme() {
        val mode = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getInt(KEY_THEME, AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
        if (AppCompatDelegate.getDefaultNightMode() != mode) AppCompatDelegate.setDefaultNightMode(mode)
    }
}