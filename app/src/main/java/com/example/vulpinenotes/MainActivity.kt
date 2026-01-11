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
import androidx.core.content.FileProvider
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.example.vulpinenotes.data.AppDatabase
import com.example.vulpinenotes.data.BookEntity
import com.example.vulpinenotes.data.ChapterEntity
import com.example.vulpinenotes.data.SortType
import com.example.vulpinenotes.data.toBook
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.imageview.ShapeableImageView
import com.google.android.material.navigation.NavigationView
import com.google.android.material.switchmaterial.SwitchMaterial
import com.google.android.material.textfield.TextInputEditText
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.firestore.FirebaseFirestore
import com.itextpdf.html2pdf.HtmlConverter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.*

class MainActivity : BaseActivity() {

    // UI
    private lateinit var booksRecyclerView: RecyclerView
    private lateinit var bookAdapter: BookAdapter
    private var currentSortType = SortType.TITLE
    private val books = mutableListOf<Book>()
    private val allBooks = mutableListOf<Book>()
    private lateinit var addBookButton: ImageButton
    private lateinit var drawerLayout: DrawerLayout
    private lateinit var searchEditText: EditText
    private lateinit var clearButton: ImageView
    private lateinit var menuButton: ImageView
    private lateinit var navView: NavigationView
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

    // Export
    private var selectedBookForExport: Book? = null
    private var selectedExportFormat: String? = null
    private val REQUEST_EXPORT = 1003

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

        showAddBookButton()
        updateNavHeader()

        if (auth.currentUser != null) {
            val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val autoSync = prefs.getBoolean("auto_sync", true)
            if (autoSync) {
                syncAllFromCloud(auth.currentUser!!)
            }
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
        findViewById<com.google.android.material.chip.Chip>(R.id.filter_chip)
            .setOnClickListener { showFilterMenu(it) }
    }

    private fun copyUriToCache(uri: Uri): File? {
        return try {
            val name = getFileName(uri) ?: "temp_cover_${System.currentTimeMillis()}.jpg"
            val file = File(cacheDir, name)
            contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(file).use { output ->
                    input.copyTo(output)
                }
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
                allBooks.clear()

                bookEntities.forEach { entity ->
                    val realCount = database.bookDao().getRealChapterCount(entity.id)
                    if (realCount != entity.chaptersCount) {
                        database.bookDao().updateChaptersCount(entity.id, realCount)
                    }

                    val book = entity.toBook(coversDir).copy(chaptersCount = realCount)
                    allBooks.add(book)
                }

                books.clear()
                books.addAll(allBooks)
                bookAdapter.notifyDataSetChanged()
                sortBooks()
                updateFilterChip(getString(R.string.by_name))
            }
        }
    }

    private fun setupSearch() {
        searchEditText.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                clearButton.visibility = if (s.isNullOrBlank()) View.GONE else View.VISIBLE
                filterBooks(s.toString())
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        clearButton.setOnClickListener {
            searchEditText.text.clear()
            searchEditText.clearFocus()
            filterBooks("")
        }
    }

    private fun filterBooks(query: String) {
        val lowerQuery = query.lowercase(Locale.getDefault())
        books.clear()
        if (lowerQuery.isEmpty()) {
            books.addAll(allBooks)
        } else {
            books.addAll(
                allBooks.filter {
                    it.title.lowercase(Locale.getDefault()).contains(lowerQuery) ||
                            it.desc.lowercase(Locale.getDefault()).contains(lowerQuery)
                }
            )
        }
        sortBooks()
        bookAdapter.notifyDataSetChanged()
    }

    private fun showAddBookButton() {
        addBookButton.visibility = View.VISIBLE
        addBookButton.setOnClickListener { showAddDialog() }
    }

    private fun showAddDialog() {
        val view = layoutInflater.inflate(R.layout.add_book_dialog, null)
        val editTitle = view.findViewById<TextInputEditText>(R.id.editText1)
        val editDesc = view.findViewById<TextInputEditText>(R.id.editText2)
        val switchUpload = view.findViewById<SwitchMaterial>(R.id.switchOptionCloud)
        currentCoverPreview = view.findViewById(R.id.coverPreview)
        currentBtnAddCover = view.findViewById<Button>(R.id.btnAddCover)
        selectedImageFile = null
        currentCoverPreview?.visibility = View.GONE
        currentBtnAddCover?.setText("Добавить обложку")

        if (auth.currentUser != null) {
            switchUpload?.visibility = View.VISIBLE
            switchUpload?.isChecked = true
        } else {
            switchUpload?.visibility = View.GONE
        }

        currentBtnAddCover?.setOnClickListener {
            pickImageLauncher.launch("image/*")
        }

        MaterialAlertDialogBuilder(this@MainActivity)
            .setTitle("Новая книга")
            .setView(view)
            .setPositiveButton("Создать") { _, _ ->
                val title = editTitle.text.toString().trim()
                val desc = editDesc.text.toString().trim()
                if (title.isBlank() && desc.isBlank()) {
                    Toast.makeText(this@MainActivity, "Введите название или описание", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }

                val uploadToCloud = auth.currentUser != null && switchUpload?.isChecked == true

                addBookLocally(
                    title = title.ifBlank { "Без названия" },
                    desc = desc.ifBlank { "Нет описания" },
                    coverFile = selectedImageFile,
                    uploadToCloud = uploadToCloud
                )
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun showEditDialog(book: Book) {
        val view = layoutInflater.inflate(R.layout.edit_book_dialog, null)
        val editTitle = view.findViewById<TextInputEditText>(R.id.editText1)
        val editDesc = view.findViewById<TextInputEditText>(R.id.editText2)
        currentCoverPreview = view.findViewById(R.id.coverPreview)
        currentBtnAddCover = view.findViewById<Button>(R.id.btnAddCover)
        editTitle.setText(book.title)
        editDesc.setText(book.desc)
        selectedImageFile = null

        if (book.coverUri != null) {
            Glide.with(this)
                .load(book.coverUri)
                .placeholder(R.drawable.book_vector_placeholder)
                .error(R.drawable.book_vector_placeholder)
                .skipMemoryCache(true)
                .diskCacheStrategy(com.bumptech.glide.load.engine.DiskCacheStrategy.NONE)
                .into(currentCoverPreview!!)
            currentCoverPreview?.visibility = View.VISIBLE
            currentBtnAddCover?.setText("Изменить обложку")
        } else {
            currentCoverPreview?.visibility = View.GONE
            currentBtnAddCover?.setText("Добавить обложку")
        }

        currentBtnAddCover?.setOnClickListener {
            pickImageLauncher.launch("image/*")
        }

        MaterialAlertDialogBuilder(this@MainActivity)
            .setTitle("Редактировать книгу")
            .setView(view)
            .setPositiveButton("Сохранить") { _, _ ->
                val newTitle = editTitle.text.toString().trim()
                val newDesc = editDesc.text.toString().trim()
                if (newTitle.isBlank() && newDesc.isBlank()) {
                    Toast.makeText(this@MainActivity, "Заполните хотя бы одно поле", Toast.LENGTH_SHORT).show()
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
                createdAt = System.currentTimeMillis(),
                updatedAt = System.currentTimeMillis(),
                cloudSynced = false
            )
            database.bookDao().insertBook(bookEntity)

            if (uploadToCloud && auth.currentUser != null) {
                try {
                    withContext(Dispatchers.IO) {
                        db.collection("users")
                            .document(auth.currentUser!!.uid)
                            .collection("books")
                            .document(bookId)
                            .set(
                                hashMapOf(
                                    "title" to title,
                                    "desc" to desc,
                                    "chaptersCount" to 0,
                                    "createdAt" to bookEntity.createdAt,
                                    "updatedAt" to bookEntity.updatedAt
                                )
                            ).await()
                    }
                    database.bookDao().updateCloudState(bookId, true)
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@MainActivity, "Книга добавлена и синхронизирована", Toast.LENGTH_SHORT).show()
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@MainActivity, "Книга добавлена локально (нет сети)", Toast.LENGTH_LONG).show()
                    }
                    Log.e("SYNC", "Не удалось синхронизировать", e)
                }
            } else {
                val message = if (auth.currentUser == null) "Книга добавлена локально" else "Книга добавлена"
                Toast.makeText(this@MainActivity, message, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun updateBookLocally(bookId: String, newTitle: String, newDesc: String, newCoverFile: File?) {
        lifecycleScope.launch {
            val currentBook = database.bookDao().getBookById(bookId) ?: return@launch

            val finalCoverPath = newCoverFile?.let {
                val destFile = File(coversDir, "cover_$bookId.jpg")
                if (destFile.exists()) destFile.delete()
                it.copyTo(destFile, overwrite = true)
                destFile.absolutePath
            } ?: currentBook.coverPath

            val updatedBook = currentBook.copy(
                title = newTitle,
                desc = newDesc,
                coverPath = finalCoverPath,
                updatedAt = System.currentTimeMillis()
            )

            database.bookDao().insertBook(updatedBook)

            if (currentBook.cloudSynced && auth.currentUser != null) {
                try {
                    withContext(Dispatchers.IO) {
                        db.collection("users")
                            .document(auth.currentUser!!.uid)
                            .collection("books")
                            .document(bookId)
                            .update(
                                mapOf<String, Any>(                     // ← ЯВНО указываем тип
                                    "title" to newTitle,
                                    "desc" to newDesc,
                                    "updatedAt" to updatedBook.updatedAt
                                )
                            ).await()
                    }
                } catch (e: Exception) {
                    Log.e("SYNC", "Не удалось обновить в облаке", e)
                }
            }

            Toast.makeText(this@MainActivity, "Книга обновлена", Toast.LENGTH_SHORT).show()
        }
    }

    private suspend fun uploadAllChaptersForBook(bookId: String, user: FirebaseUser) {
        val isSynced = withContext(Dispatchers.IO) {
            database.bookDao().getBookById(bookId)?.cloudSynced == true
        }
        if (!isSynced) return
        try {
            val localChapters = withContext(Dispatchers.IO) {
                database.chapterDao().getChaptersForBookSync(bookId)
            }
            if (localChapters.isEmpty()) return
            val batch = db.batch()
            localChapters.forEach { chapter ->
                val ref = db.collection("users")
                    .document(user.uid)
                    .collection("books")
                    .document(bookId)
                    .collection("chapters")
                    .document(chapter.chapterId)

                val cloudData = mapOf(
                    "chapterId" to chapter.chapterId,
                    "title" to chapter.title,
                    "description" to chapter.description,
                    "date" to chapter.date,
                    "wordCount" to chapter.wordCount,
                    "isFavorite" to chapter.isFavorite,
                    "position" to chapter.position,
                    "createdAt" to chapter.createdAt,
                    "updatedAt" to chapter.updatedAt
                )
                batch.set(ref, cloudData)
            }
            batch.commit().await()
            Log.d("SYNC", "Залито ${localChapters.size} глав")
        } catch (e: Exception) {
            Log.e("SYNC", "Ошибка заливки глав", e)
        }
    }

    private suspend fun downloadAllChaptersForBook(bookId: String, user: FirebaseUser) {
        try {
            val snapshot = db.collection("users")
                .document(user.uid)
                .collection("books")
                .document(bookId)
                .collection("chapters")
                .get()
                .await()

            val cloudChapters = snapshot.documents.mapNotNull { doc ->
                doc.toObject(ChapterEntity::class.java)?.copy(bookId = bookId)
            }

            if (cloudChapters.isNotEmpty()) {
                withContext(Dispatchers.IO) {
                    database.chapterDao().insertChapters(cloudChapters)
                }
                Log.d("SYNC", "Скачано ${cloudChapters.size} глав")
            }
        } catch (e: Exception) {
            Log.e("SYNC", "Ошибка скачивания глав", e)
        }
    }

    private fun syncAllFromCloud(user: FirebaseUser) {
        lifecycleScope.launch {
            try {
                val snapshot = db.collection("users")
                    .document(user.uid)
                    .collection("books")
                    .get()
                    .await()
                var processed = 0
                for (doc in snapshot.documents) {
                    val cloudBook = doc.toObject(BookEntity::class.java)?.copy(id = doc.id) ?: continue
                    withContext(Dispatchers.IO) {
                        val localBook = database.bookDao().getBookById(cloudBook.id)
                        database.bookDao().updateCloudState(cloudBook.id, true)
                        if (localBook == null) {
                            database.bookDao().insertBook(
                                cloudBook.copy(coverPath = null, cloudSynced = true)
                            )
                        } else {
                            database.bookDao().insertBook(
                                localBook.copy(
                                    title = cloudBook.title,
                                    desc = cloudBook.desc,
                                    updatedAt = cloudBook.updatedAt,
                                    cloudSynced = true
                                )
                            )
                        }
                    }
                    downloadAllChaptersForBook(cloudBook.id, user)
                    uploadAllChaptersForBook(cloudBook.id, user)
                    processed++
                }
                withContext(Dispatchers.Main) {
                    if (processed > 0) {
                        Toast.makeText(this@MainActivity, "Синхронизировано: $processed книг", Toast.LENGTH_LONG).show()
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "Ошибка синхронизации", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun setupRecyclerView() {
        bookAdapter = BookAdapter(
            books,
            this,
            onShowInfo = { showBookInfo(it) },
            onEditBook = { book, _ -> showEditDialog(book) },
            onBookClick = { startBookActivity(it) },
            onExportBook = { book -> showExportDialog(book) }
        )
        booksRecyclerView.adapter = bookAdapter
        booksRecyclerView.layoutManager = GridLayoutManager(this, 2)
    }

    private fun startBookActivity(book: Book) {
        startActivity(Intent(this, BookActivity::class.java).putExtra(EXTRA_BOOK, book))
    }

    private fun showBookInfo(book: Book) {
        val v = layoutInflater.inflate(R.layout.dialog_book_info, null)

        val cover = v.findViewById<ImageView>(R.id.dialogCover)
        if (book.coverUri != null) {
            Glide.with(this)
                .load(book.coverUri)
                .placeholder(R.drawable.book_vector_placeholder)
                .error(R.drawable.book_vector_placeholder)
                .diskCacheStrategy(com.bumptech.glide.load.engine.DiskCacheStrategy.NONE)
                .skipMemoryCache(true)
                .into(cover)
            cover.visibility = View.VISIBLE
        } else {
            cover.visibility = View.GONE
        }

        v.findViewById<TextView>(R.id.dialogTitle).text = book.title.ifBlank { "Без названия" }
        v.findViewById<TextView>(R.id.dialogAuthor).text = book.desc.ifBlank { getString(R.string.unknown_desc) }
        v.findViewById<TextView>(R.id.dialogChaptersCount)?.text = "Количество глав: ${book.chaptersCount}"

        val dateFormat = java.text.DateFormat.getDateTimeInstance()
        v.findViewById<TextView>(R.id.dialogCreatedAt)?.text = "Создано: ${dateFormat.format(book.createdAt)}"
        v.findViewById<TextView>(R.id.dialogUpdatedAt)?.text = "Обновлено: ${dateFormat.format(book.updatedAt)}"
        v.findViewById<TextView>(R.id.dialogCloudSynced)?.text =
            "Синхронизировано: ${if (book.cloudSynced) "Да" else "Нет"}"

        MaterialAlertDialogBuilder(this@MainActivity, R.style.CustomAlertDialogTheme)
            .setTitle(R.string.info)
            .setView(v)
            .setPositiveButton("OK") { d, _ -> d.dismiss() }
            .setNeutralButton("Экспорт") { _, _ -> showExportDialog(book) }
            .show()
    }

    // Вспомогательная функция для экранирования HTML
    private fun escapeHtml(text: String): String {
        return text.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&#39;")
    }

    // Вызывается после выбора книги для экспорта
    private fun showExportDialog(book: Book) {
        val formats = arrayOf("PDF", "Markdown")
        MaterialAlertDialogBuilder(this@MainActivity)
            .setTitle("Экспорт книги")
            .setItems(formats) { _, which ->
                val type = if (which == 0) "pdf" else "md"
                val mime = if (type == "pdf") "application/pdf" else "text/markdown"
                val ext = type

                val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    this.type = mime
                    putExtra(Intent.EXTRA_TITLE, "${book.title}.$ext")
                }

                selectedBookForExport = book
                selectedExportFormat = type
                startActivityForResult(intent, REQUEST_EXPORT)
            }
            .show()
    }

    // Генерация контента для экспорта (Markdown или PDF)
    private suspend fun generateExportContent(book: Book, format: String): ByteArray =
        withContext(Dispatchers.IO) {
            try {
                val chapters = database.chapterDao().getChaptersForExport(book.id)
                if (chapters.isNullOrEmpty()) throw IllegalStateException("В книге нет глав для экспорта")

                val sortedChapters = chapters.sortedBy { it.position }
                val dateFormat = java.text.DateFormat.getDateTimeInstance()

                // Markdown контент - БЕЗ page-break-before для заголовков
                val markdownContent = buildString {
                    appendLine("# ${book.title}\n")
                    appendLine("**Описание:** ${book.desc.ifBlank { "Нет описания" }}\n")
                    appendLine("**Создано:** ${dateFormat.format(Date(book.createdAt))}\n")
                    appendLine("---\n")
                    for (chapter in sortedChapters) {
                        appendLine("## ${chapter.title}\n")
                        appendLine(chapter.content)
                        appendLine("\n")
                        // Только разделитель между главами, без принудительного разрыва страницы
                        if (sortedChapters.last() != chapter) {
                            appendLine("---\n")
                        }
                    }
                }

                when (format.lowercase()) {
                    "md", "markdown" -> markdownContent.toByteArray(Charsets.UTF_8)

                    "pdf" -> {
                        // Конвертируем Markdown в HTML с правильным форматированием
                        val html = markdownToHtml(markdownContent)
                        ByteArrayOutputStream().use {
                            HtmlConverter.convertToPdf(html, it)
                            it.toByteArray()
                        }
                    }

                    else -> byteArrayOf()
                }
            } catch (e: Exception) {
                Log.e("EXPORT", "Ошибка генерации контента: ${e.message}", e)
                throw e
            }
        }

    // Улучшенная функция преобразования Markdown в HTML
    private fun markdownToHtml(markdown: String): String {
        val sb = StringBuilder()
        val lines = markdown.lines()
        var inTable = false
        var inCodeBlock = false
        var inQuote = false
        var inUl = false  // Маркированный список
        var inOl = false  // Нумерованный список
        var emptyLineCount = 0 // Счетчик пустых строк подряд
        var chapterCount = 0 // Счетчик глав

        sb.append("<!DOCTYPE html>")
        sb.append("<html><head><meta charset=\"UTF-8\">")
        sb.append("<style>")
        // Основные стили
        sb.append("body { font-family: Arial, sans-serif; margin: 40px; line-height: 1.6; }")
        sb.append("h1 { border-bottom: 2px solid #333; padding-bottom: 10px; margin-top: 20px; }")
        sb.append("h2 { margin-top: 30px; padding-top: 20px; }")
        sb.append("h3 { margin-top: 25px; }")
        sb.append("p { margin: 8px 0; }")
        sb.append("em { font-style: italic; }")
        sb.append("strong { font-weight: bold; }")
        sb.append("u { text-decoration: underline; }")
        sb.append("del { text-decoration: line-through; }")
        sb.append("pre { background: #f5f5f5; padding: 12px; border-radius: 4px; margin: 10px 0; white-space: pre-wrap; }")
        sb.append("code { font-family: 'Courier New', monospace; background: #f0f0f0; padding: 2px 4px; border-radius: 3px; }")
        sb.append("blockquote { border-left: 3px solid #ccc; padding-left: 15px; margin: 10px 0; color: #666; }")
        sb.append("table { border-collapse: collapse; margin: 15px 0; width: 100%; }")
        sb.append("td, th { border: 1px solid #ddd; padding: 8px 12px; text-align: left; }")
        sb.append("th { background-color: #f2f2f2; font-weight: bold; }")
        sb.append("hr { border: none; border-top: 1px solid #ddd; margin: 20px 0; }")
        sb.append("ul, ol { margin: 10px 0; padding-left: 25px; }")
        sb.append("li { margin: 5px 0; }")
        sb.append("img { max-width: 100%; height: auto; }")
        // Стиль для разрыва страниц между главами
        sb.append(".chapter-break { page-break-before: always; }")
        sb.append("</style></head><body>")

        // Функция для обработки inline форматирования
        fun applyInlineMarkdown(text: String): String {
            var t = text

            // Обрабатываем HTML-тег подчеркивания <u>текст</u>
            t = Regex("<u>(.*?)</u>", RegexOption.DOT_MATCHES_ALL).replace(t) {
                "<u>${it.groupValues[1]}</u>"
            }

            // Временная замена <u> тегов на плейсхолдеры
            val uTags = mutableListOf<Pair<String, String>>()
            var tagIndex = 0

            t = Regex("<u>.*?</u>").replace(t) { match ->
                val placeholder = "%%%U_TAG_${tagIndex}%%%"
                uTags.add(placeholder to match.value)
                tagIndex++
                placeholder
            }

            // Экранируем HTML
            t = t.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;")

            // Восстанавливаем <u> теги
            for ((placeholder, original) in uTags) {
                t = t.replace(placeholder, original)
            }

            // Жирный **текст**
            t = Regex("\\*\\*(.*?)\\*\\*").replace(t) { "<strong>${it.groupValues[1]}</strong>" }
            // Курсив *текст* или _текст_
            t = Regex("\\*(.*?)\\*").replace(t) { "<em>${it.groupValues[1]}</em>" }
            t = Regex("_(.*?)_").replace(t) { "<em>${it.groupValues[1]}</em>" }
            // Зачёркнутый ~~текст~~
            t = Regex("~~(.*?)~~").replace(t) { "<del>${it.groupValues[1]}</del>" }
            // Inline код `код`
            t = Regex("`([^`]+)`").replace(t) { "<code>${it.groupValues[1]}</code>" }

            // Ссылки [текст](url)
            t = Regex("\\[([^\\]]+)\\]\\(([^\\)]+)\\)").replace(t) {
                "<a href=\"${it.groupValues[2]}\">${it.groupValues[1]}</a>"
            }

            // Изображения ![alt](url)
            t = Regex("!\\[([^\\]]*)\\]\\(([^\\)]+)\\)").replace(t) {
                val alt = it.groupValues[1].ifEmpty { "Image" }
                "<img src=\"${it.groupValues[2]}\" alt=\"$alt\" />"
            }

            return t
        }

        // Функция для закрытия всех активных блоков
        fun closeActiveBlocks() {
            if (inUl) { sb.append("</ul>"); inUl = false }
            if (inOl) { sb.append("</ol>"); inOl = false }
            if (inTable) { sb.append("</table>"); inTable = false }
            if (inQuote) { sb.append("</blockquote>"); inQuote = false }
            emptyLineCount = 0
        }

        // Функция для добавления разрыва главы
        fun addChapterBreak() {
            if (chapterCount > 0) {
                sb.append("<div class=\"chapter-break\"></div>")
            }
            chapterCount++
        }

        // Обработка строк
        for (i in lines.indices) {
            val line = lines[i]
            val trimmedLine = line.trim()
            val prevLine = if (i > 0) lines[i-1].trim() else ""

            when {
                // Блоки кода ```
                trimmedLine.startsWith("```") -> {
                    inCodeBlock = !inCodeBlock
                    if (inCodeBlock) {
                        closeActiveBlocks()
                        sb.append("<pre><code>")
                    } else {
                        sb.append("</code></pre>")
                    }
                    emptyLineCount = 0
                }
                inCodeBlock -> {
                    sb.append(applyInlineMarkdown(line)).append("\n")
                    emptyLineCount = 0
                }

                // СПЕЦИАЛЬНЫЕ РАЗДЕЛИТЕЛИ - ПЕРВЫМИ!
                trimmedLine == "# ##" || trimmedLine == "## ##" || trimmedLine == "# #" -> {
                    closeActiveBlocks()
                    // Добавляем разрыв страницы
                    addChapterBreak()
                    // Не добавляем HTML для самого разделителя
                    emptyLineCount = 0
                    continue // Важно: пропускаем остальную обработку
                }

                // Разделители (hr) - обычные
                trimmedLine.matches(Regex("^---+$")) && trimmedLine != "# ##" && trimmedLine != "## ##" -> {
                    closeActiveBlocks()
                    sb.append("<hr/>")
                    emptyLineCount = 0
                }

                // Заголовки - проверяем только если это НЕ специальный разделитель
                trimmedLine.startsWith("### ") -> {
                    closeActiveBlocks()
                    sb.append("<h3>${applyInlineMarkdown(trimmedLine.substring(4).trim())}</h3>")
                    emptyLineCount = 0
                }
                trimmedLine.startsWith("## ") -> {
                    // Проверяем, не является ли это специальным разделителем
                    if (trimmedLine == "## ##" || trimmedLine == "# ##") {
                        // Уже обработано выше
                        emptyLineCount = 0
                    } else {
                        closeActiveBlocks()

                        // Определяем, является ли это заголовком главы
                        val isChapterTitle = trimmedLine.contains("глава", ignoreCase = true) ||
                                trimmedLine.contains("chapter", ignoreCase = true) ||
                                trimmedLine.length < 50

                        if (isChapterTitle) {
                            addChapterBreak()
                        }

                        // Заголовок без встроенного page-break
                        sb.append("<h2>${applyInlineMarkdown(trimmedLine.substring(3).trim())}</h2>")
                        emptyLineCount = 0
                    }
                }
                trimmedLine.startsWith("# ") -> {
                    // Проверяем, не является ли это специальным разделителем
                    if (trimmedLine == "# ##" || trimmedLine == "## ##") {
                        // Уже обработано выше
                        emptyLineCount = 0
                    } else {
                        closeActiveBlocks()

                        // Для h1 проверяем контекст
                        val isMainTitle = i == 0 || prevLine.isEmpty()
                        if (isMainTitle && chapterCount == 0) {
                            chapterCount++ // Увеличиваем счетчик, но не добавляем разрыв
                        } else if (isMainTitle) {
                            addChapterBreak()
                        }

                        sb.append("<h1>${applyInlineMarkdown(trimmedLine.substring(2).trim())}</h1>")
                        emptyLineCount = 0
                    }
                }

                // Таблицы
                line.contains("|") && !inCodeBlock -> {
                    val trimmedLine = line.trim()
                    if (trimmedLine.startsWith("|") && trimmedLine.endsWith("|")) {
                        val cells = trimmedLine.trim('|').split("|").map { applyInlineMarkdown(it.trim()) }

                        if (!inTable) {
                            closeActiveBlocks()
                            sb.append("<table>")
                            inTable = true
                        }

                        val isSeparator = cells.all { cell -> cell.matches(Regex("^:?---+:?$")) }

                        if (isSeparator) {
                            // Разделитель таблицы
                        } else {
                            sb.append("<tr>")
                            for (cell in cells) {
                                val isHeader = i > 0 && lines[i-1].trim().matches(Regex("^\\|(:?---+:?\\|?)+$"))
                                if (isHeader) {
                                    sb.append("<th>$cell</th>")
                                } else {
                                    sb.append("<td>$cell</td>")
                                }
                            }
                            sb.append("</tr>")
                        }
                        emptyLineCount = 0
                    }
                }

                // Завершение таблицы
                trimmedLine.isEmpty() && inTable -> {
                    sb.append("</table>")
                    inTable = false
                    emptyLineCount++
                }

                // Цитаты
                trimmedLine.startsWith("> ") -> {
                    if (inUl) { sb.append("</ul>"); inUl = false }
                    if (inOl) { sb.append("</ol>"); inOl = false }

                    val quoteContent = applyInlineMarkdown(trimmedLine.substring(2).trim())
                    if (!inQuote) {
                        sb.append("<blockquote>")
                        inQuote = true
                    }
                    sb.append("<p>$quoteContent</p>")
                    emptyLineCount = 0

                    if (i + 1 < lines.size && !lines[i + 1].trim().startsWith("> ")) {
                        sb.append("</blockquote>")
                        inQuote = false
                    }
                }

                // Маркированные списки
                trimmedLine.matches(Regex("^[-*+]\\s+.*")) -> {
                    val listItem = applyInlineMarkdown(trimmedLine.substring(2).trim())
                    if (!inUl) {
                        if (inOl) { sb.append("</ol>"); inOl = false }
                        sb.append("<ul>")
                        inUl = true
                    }
                    sb.append("<li>$listItem</li>")
                    emptyLineCount = 0
                }

                // Нумерованные списки - УПРОЩЕННАЯ ЛОГИКА
                trimmedLine.matches(Regex("^\\d+\\.\\s+.*")) -> {
                    // Извлекаем содержимое (без номера)
                    val listItemContent = trimmedLine.substring(trimmedLine.indexOf('.') + 1).trim()
                    val listItem = applyInlineMarkdown(listItemContent)

                    if (!inOl) {
                        if (inUl) { sb.append("</ul>"); inUl = false }
                        sb.append("<ol>")
                        inOl = true
                    }

                    sb.append("<li>$listItem</li>")
                    emptyLineCount = 0
                }

                // Обычные абзацы
                trimmedLine.isNotEmpty() -> {
                    if (!inTable && !inCodeBlock && !trimmedLine.startsWith(">")) {
                        if (emptyLineCount == 1 && (inUl || inOl)) {
                            closeActiveBlocks()
                        } else if (emptyLineCount >= 2) {
                            closeActiveBlocks()
                        }

                        val isContinuation = i > 0 &&
                                lines[i-1].trim().isNotEmpty() &&
                                !lines[i-1].trim().matches(Regex("^[-*+]\\s+.*")) &&
                                !lines[i-1].trim().matches(Regex("^\\d+\\.\\s+.*")) &&
                                !lines[i-1].trim().startsWith("#") &&
                                !lines[i-1].trim().startsWith(">") &&
                                !lines[i-1].trim().matches(Regex("^---+$"))

                        if (isContinuation) {
                            sb.append("<br/>${applyInlineMarkdown(line)}")
                        } else {
                            sb.append("<p>${applyInlineMarkdown(line)}</p>")
                        }
                    } else if (!inTable && !inCodeBlock && !inQuote) {
                        sb.append(applyInlineMarkdown(line))
                    }
                    emptyLineCount = 0
                }

                // Пустые строки
                else -> {
                    emptyLineCount++

                    if (!inCodeBlock && emptyLineCount >= 2 && (inUl || inOl)) {
                        closeActiveBlocks()
                    }

                    if (i > 0 && lines[i-1].trim().isNotEmpty() && !inCodeBlock && !inUl && !inOl) {
                        sb.append("<br/>")
                    }
                }
            }
        }

        // Закрываем незакрытые теги в конце
        closeActiveBlocks()
        if (inCodeBlock) sb.append("</code></pre>")

        sb.append("</body></html>")
        return sb.toString()
    }

    // Обработка результата экспорта
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode == REQUEST_EXPORT && resultCode == RESULT_OK) {
            val uri = data?.data ?: return
            val book = selectedBookForExport ?: return
            val format = selectedExportFormat ?: return

            lifecycleScope.launch {
                try {
                    val content = generateExportContent(book, format)
                    contentResolver.openOutputStream(uri)?.use { it.write(content) }
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@MainActivity, "Экспорт завершён", Toast.LENGTH_SHORT).show()
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@MainActivity, "Ошибка экспорта: ${e.message}", Toast.LENGTH_LONG).show()
                    }
                }
            }

            selectedBookForExport = null
            selectedExportFormat = null
        }
    }

    private fun showFilterMenu(anchor: View) {
        val popup = PopupMenu(this, anchor)
        popup.inflate(R.menu.filter_menu)

        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.sort_title -> {
                    currentSortType = SortType.TITLE
                    updateFilterChip(getString(R.string.by_name))
                }
                R.id.sort_updated -> {
                    currentSortType = SortType.UPDATED_AT
                    updateFilterChip(getString(R.string.by_edit_date))
                }
                R.id.sort_created -> {
                    currentSortType = SortType.CREATED_AT
                    updateFilterChip(getString(R.string.by_create_date))
                }
            }
            sortBooks()
            true
        }
        popup.show()
    }

    private fun updateFilterChip(text: String) {
        findViewById<com.google.android.material.chip.Chip>(R.id.filter_chip).text = text
    }

    private fun sortBooks() {
        when (currentSortType) {
            SortType.TITLE -> books.sortBy { it.title.lowercase(Locale.getDefault()) }
            SortType.UPDATED_AT -> books.sortByDescending { it.updatedAt }
            SortType.CREATED_AT -> books.sortByDescending { it.createdAt }
        }
        bookAdapter.notifyDataSetChanged()
    }

    private fun setupDrawer() {
        menuButton.setOnClickListener { drawerLayout.openDrawer(GravityCompat.START) }
        navView.setNavigationItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_account -> startActivityForResult(Intent(this, AccountActivity::class.java), REQUEST_ACCOUNT)
                R.id.nav_sync -> {
                    auth.currentUser?.let { user ->
                        syncAllFromCloud(user)
                        Toast.makeText(this, "Синхронизация запущена...", Toast.LENGTH_SHORT).show()
                    } ?: Toast.makeText(this, "Войдите в аккаунт", Toast.LENGTH_LONG).show()
                }
                R.id.nav_cloud -> startActivity(Intent(this, CloudActivity::class.java))
                R.id.nav_settings -> startActivityForResult(Intent(this, SettingsActivity::class.java), REQUEST_SETTINGS)
                R.id.nav_support -> startActivityForResult(Intent(this, SupportActivity::class.java), REQUEST_SETTINGS)
            }
            drawerLayout.closeDrawer(GravityCompat.START)
            true
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

    private fun updateNavHeader() {
        val headerView = navView.getHeaderView(0)
        val avatar = headerView.findViewById<ShapeableImageView>(R.id.nav_header_avatar)
        val name = headerView.findViewById<TextView>(R.id.nav_header_name)
        val email = headerView.findViewById<TextView>(R.id.nav_header_email)
        val user = auth.currentUser

        if (user != null) {
            name.text = user.displayName ?: "Пользователь"
            email.text = user.email ?: ""
            user.photoUrl?.let { Glide.with(this).load(it).placeholder(R.drawable.ic_fox_logo).into(avatar) }
                ?: avatar.setImageResource(R.drawable.ic_fox_logo)
        } else {
            name.text = "Локальный режим"
            email.text = "Войдите для синхронизации"
            avatar.setImageResource(R.drawable.ic_fox_logo)
        }

        headerView.setOnClickListener {
            drawerLayout.closeDrawer(GravityCompat.START)
            startActivityForResult(Intent(this, AccountActivity::class.java), REQUEST_ACCOUNT)
        }
    }

    private fun applySavedTheme() {
        val mode = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getInt(KEY_THEME, AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
        AppCompatDelegate.setDefaultNightMode(mode)
    }
}