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
import com.example.vulpinenotes.data.BookRepository
import com.example.vulpinenotes.data.ChapterEntity
import com.example.vulpinenotes.data.NotificationManager
import com.example.vulpinenotes.data.NotificationSettings
import com.example.vulpinenotes.data.ReminderInterval
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
import com.itextpdf.kernel.geom.PageSize
import com.itextpdf.kernel.pdf.PdfDocument
import com.itextpdf.kernel.pdf.PdfWriter
import com.itextpdf.layout.Document
import com.itextpdf.layout.element.Paragraph
import com.itextpdf.layout.element.IBlockElement
import com.itextpdf.layout.element.Div
import com.itextpdf.layout.element.AreaBreak
import com.itextpdf.layout.properties.AreaBreakType
import com.itextpdf.layout.properties.TextAlignment


class MainActivity : BaseActivity() {

    // UI элементы
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

    private var authStateListener: FirebaseAuth.AuthStateListener? = null

    // Firebase компоненты
    private lateinit var auth: FirebaseAuth
    private lateinit var db: FirebaseFirestore

    // Локальная база данных
    private lateinit var database: AppDatabase
    private lateinit var coversDir: File

    private val PREFS_NAME = "app_prefs"
    private val KEY_THEME = "app_theme"

    // Переменные для экспорта книг
    private var selectedBookForExport: Book? = null
    private var selectedExportFormat: String? = null
    private val REQUEST_EXPORT = 1003

    companion object {
        private const val REQUEST_SETTINGS = 1001
        private const val REQUEST_ACCOUNT = 1002
        const val EXTRA_BOOK = "com.example.vulpinenotes.EXTRA_BOOK"

        private const val NOTIFICATION_PERMISSION_REQUEST_CODE = 1001
        private const val ALARM_PERMISSION_REQUEST_CODE = 1002
    }

    // Основной метод создания активности. Инициализирует все компоненты, настраивает UI,
    // подписывается на обновления данных и восстанавливает уведомления
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

        // Автоматическая синхронизация с облаком при наличии пользователя и настройки
        if (auth.currentUser != null) {
            val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val autoSync = prefs.getBoolean("auto_sync", true)
            if (autoSync) {
                syncAllFromCloud(auth.currentUser!!)
            }
        }
        authStateListener = FirebaseAuth.AuthStateListener { firebaseAuth ->
            val currentUser = firebaseAuth.currentUser
            updateNavHeader()           // ← всегда обновляем шапку
        }
        auth.addAuthStateListener(authStateListener!!)

        restoreNotifications()
    }

    // Инициализация всех View элементов активности
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

    // Копирование изображения из URI во временный файл для предварительного просмотра
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

    // Получение имени файла из URI для сохранения с оригинальным именем
    private fun getFileName(uri: Uri): String? {
        return if (uri.scheme == "content") {
            contentResolver.query(uri, null, null, null, null)?.use {
                if (it.moveToFirst()) {
                    it.getString(it.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME))
                } else null
            }
        } else uri.lastPathSegment
    }

    // Наблюдение за изменениями в локальной базе данных книг с использованием Flow
    // Автоматически обновляет список при любых изменениях в БД
    private fun observeLocalBooks() {
        lifecycleScope.launch {
            database.bookDao().getAllBooks().collect { bookEntities ->
                allBooks.clear()

                bookEntities.forEach { entity ->
                    // Корректировка счетчика глав (синхронизация с реальным количеством)
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

    // Настройка поиска с динамической фильтрацией списка книг
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

    // Фильтрация книг по поисковому запросу (название и описание)
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

    // Восстановление запланированных уведомлений из SharedPreferences
    private fun restoreNotifications() {
        val prefs = getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
        val json = prefs.getString("notification_settings", null) ?: return

        try {
            val obj = org.json.JSONObject(json)
            val settings = NotificationSettings(
                isEnabled = obj.getBoolean("isEnabled"),
                interval = ReminderInterval.valueOf(obj.optString("interval", "EVERY_DAY")),
                selectedBookIds = jsonArrayToList(obj.getJSONArray("selectedBookIds"))
            )

            if (settings.isEnabled && settings.selectedBookIds.isNotEmpty()) {
                val database = AppDatabase.getDatabase(this)
                val firestore = FirebaseFirestore.getInstance()
                val storageDir = filesDir.resolve("covers").apply { mkdirs() }

                val repository = BookRepository(
                    database.bookDao(),
                    database.chapterDao(),
                    firestore,
                    storageDir
                )

                val notificationManager = NotificationManager(this, repository)
                notificationManager.schedule(settings)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    // Преобразование JSONArray в List<String> для восстановления настроек уведомлений
    private fun jsonArrayToList(jsonArray: org.json.JSONArray): List<String> {
        val list = mutableListOf<String>()
        for (i in 0 until jsonArray.length()) {
            list.add(jsonArray.getString(i))
        }
        return list
    }

    // Показ кнопки добавления книги с анимацией при необходимости
    private fun showAddBookButton() {
        addBookButton.visibility = View.VISIBLE
        addBookButton.setOnClickListener { showAddDialog() }
    }

    // Отображение диалога для создания новой книги
    // Позволяет задать название, описание, обложку и выбрать синхронизацию с облаком
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
                    Toast.makeText(
                        this@MainActivity,
                        "Введите название или описание",
                        Toast.LENGTH_SHORT
                    ).show()
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

    // Отображение диалога редактирования существующей книги
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
                    Toast.makeText(
                        this@MainActivity,
                        "Заполните хотя бы одно поле",
                        Toast.LENGTH_SHORT
                    ).show()
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

    // Добавление книги в локальную базу данных с возможной синхронизацией с облаком
    private fun addBookLocally(
        title: String,
        desc: String,
        coverFile: File?,
        uploadToCloud: Boolean
    ) {
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
                        Toast.makeText(
                            this@MainActivity,
                            "Книга добавлена и синхронизирована",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(
                            this@MainActivity,
                            "Книга добавлена локально (нет сети)",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                    Log.e("SYNC", "Не удалось синхронизировать", e)
                }
            } else {
                val message =
                    if (auth.currentUser == null) "Книга добавлена локально" else "Книга добавлена"
                Toast.makeText(this@MainActivity, message, Toast.LENGTH_SHORT).show()
            }
        }
    }

    // Обновление существующей книги в локальной базе данных
    // При наличии синхронизации обновляет также облачную версию
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

    // Загрузка всех глав книги в облако (Firestore)
    // Используется при синхронизации существующих книг
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

    // Скачивание всех глав книги из облака в локальную базу
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

    // Полная синхронизация всех книг с облаком
    // Загружает книги из Firestore и объединяет с локальными данными
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
                    val cloudBook =
                        doc.toObject(BookEntity::class.java)?.copy(id = doc.id) ?: continue
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
                        Toast.makeText(
                            this@MainActivity,
                            "Синхронизировано: $processed книг",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "Ошибка синхронизации", Toast.LENGTH_LONG)
                        .show()
                }
            }
        }
    }

    // Настройка RecyclerView для отображения списка книг в виде сетки
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

    // Запуск активности с детальным просмотром книги (список глав)
    private fun startBookActivity(book: Book) {
        startActivity(Intent(this, BookActivity::class.java).putExtra(EXTRA_BOOK, book))
    }

    // Отображение детальной информации о книге в диалоге
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
        v.findViewById<TextView>(R.id.dialogAuthor).text =
            book.desc.ifBlank { getString(R.string.unknown_desc) }
        v.findViewById<TextView>(R.id.dialogChaptersCount)?.text =
            "Количество глав: ${book.chaptersCount}"

        val dateFormat = java.text.DateFormat.getDateTimeInstance()
        v.findViewById<TextView>(R.id.dialogCreatedAt)?.text =
            "Создано: ${dateFormat.format(book.createdAt)}"
        v.findViewById<TextView>(R.id.dialogUpdatedAt)?.text =
            "Обновлено: ${dateFormat.format(book.updatedAt)}"
        v.findViewById<TextView>(R.id.dialogCloudSynced)?.text =
            "Синхронизировано: ${if (book.cloudSynced) "Да" else "Нет"}"

        MaterialAlertDialogBuilder(this@MainActivity, R.style.CustomAlertDialogTheme)
            .setTitle(R.string.info)
            .setView(v)
            .setPositiveButton("OK") { d, _ -> d.dismiss() }
            .setNeutralButton("Экспорт") { _, _ -> showExportDialog(book) }
            .show()
    }

    // Экспорт книги в различные форматы (PDF, Markdown)
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

    // Генерация контента для экспорта в выбранном формате
    // Обрабатывает пустые книги и главы, создавая соответствующие сообщения
    private suspend fun generateExportContent(book: Book, format: String): ByteArray =
        withContext(Dispatchers.IO) {
            try {
                val chapters = database.chapterDao().getChaptersForExport(book.id)

                // проверка на пустую книгу
                if (chapters.isNullOrEmpty()) {
                    // Возвращаем специальный PDF/Markdown с сообщением о пустой книге
                    return@withContext when (format.lowercase()) {
                        "md", "markdown" -> createEmptyBookMarkdown(book)
                        "pdf" -> createEmptyBookPdf(book)
                        else -> byteArrayOf()
                    }
                }

                // проверка на главы с пустым содержимым
                val hasContent = chapters.any { chapter ->
                    chapter.content.isNotBlank() ||
                            chapter.title.isNotBlank()
                }

                if (!hasContent) {
                    // Все главы пустые
                    return@withContext when (format.lowercase()) {
                        "md", "markdown" -> createEmptyChaptersMarkdown(book)
                        "pdf" -> createEmptyChaptersPdf(book)
                        else -> byteArrayOf()
                    }
                }

                val sortedChapters = chapters.sortedBy { it.position }
                val dateFormat = java.text.DateFormat.getDateTimeInstance()

                // Markdown контент
                val markdownContent = buildString {
                    appendLine("# ${book.title}\n")
                    appendLine("**Описание:** ${book.desc.ifBlank { "Нет описания" }}\n")
                    appendLine("**Создано:** ${dateFormat.format(Date(book.createdAt))}\n")
                    appendLine("---\n")
                    for ((index, chapter) in sortedChapters.withIndex()) {
                        if (chapter.content.isBlank() && chapter.title.isBlank()) continue

                        if (index > 0) {
                            appendLine("# ##")
                        }

                        val chapterTitle = chapter.title.ifBlank { "Без названия" }
                        val chapterContent = chapter.content.ifBlank { "*Пустая глава*" }

                        appendLine("## $chapterTitle\n")
                        appendLine(chapterContent)
                        appendLine("\n")
                    }
                }

                when (format.lowercase()) {
                    "md", "markdown" -> markdownContent.toByteArray(Charsets.UTF_8)

                    "pdf" -> {
                        val htmlContent = markdownToHtml(markdownContent)
                        ByteArrayOutputStream().use { outputStream ->
                            PdfWriter(outputStream).use { writer ->
                                PdfDocument(writer).use { pdfDoc ->
                                    pdfDoc.defaultPageSize = PageSize.A4
                                    val converterProperties =
                                        com.itextpdf.html2pdf.ConverterProperties().apply {
                                            setBaseUri("")
                                            setCreateAcroForm(false)
                                        }
                                    HtmlConverter.convertToPdf(
                                        htmlContent,
                                        pdfDoc,
                                        converterProperties
                                    )
                                    addFooterToAllPages(pdfDoc, book.title)
                                }
                            }
                            outputStream.toByteArray()
                        }
                    }

                    else -> byteArrayOf()
                }
            } catch (e: Exception) {
                Log.e("EXPORT", "Ошибка генерации контента: ${e.message}", e)
                if (format.lowercase() == "pdf") {
                    return@withContext createErrorPdf(e.message ?: "Неизвестная ошибка")
                }
                throw e
            }
        }

    // Создание Markdown контента для пустой книги
    private fun createEmptyBookMarkdown(book: Book): ByteArray {
        val content = """
        # ${book.title}
        
        ## Книга пуста
        
        В этой книге пока нет ни одной главы с содержимым.
        
        Добавьте главы в книгу, чтобы их можно было экспортировать.
        
        ---
        
        *Создано в Vulpine Notes*
    """.trimIndent()
        return content.toByteArray(Charsets.UTF_8)
    }

    // Создание Markdown контента для книги с пустыми главами
    private fun createEmptyChaptersMarkdown(book: Book): ByteArray {
        val content = """
        # ${book.title}
        
        ## Главы пустые
        
        В этой книге есть главы, но все они не содержат текста.
        
        Отредактируйте главы и добавьте в них содержимое, чтобы их можно было экспортировать.
        
        ---
        
        *Создано в Vulpine Notes*
    """.trimIndent()
        return content.toByteArray(Charsets.UTF_8)
    }

    // Создание PDF для пустой книги
    private fun createEmptyBookPdf(book: Book): ByteArray {
        return ByteArrayOutputStream().use { outputStream ->
            PdfWriter(outputStream).use { writer ->
                PdfDocument(writer).use { pdfDoc ->
                    Document(pdfDoc).use { document ->
                        pdfDoc.defaultPageSize = PageSize.A4

                        document.add(
                            Paragraph(book.title)
                                .setFontSize(20f)
                                .setBold()
                                .setTextAlignment(TextAlignment.CENTER)
                        )

                        document.add(Paragraph("\n\n"))

                        document.add(
                            Paragraph("Книга пуста")
                                .setFontSize(16f)
                                .setTextAlignment(TextAlignment.CENTER)
                        )

                        document.add(Paragraph("\n"))

                        document.add(
                            Paragraph(
                                "В этой книге пока нет ни одной главы с содержимым.\n\n" +
                                        "Добавьте главы в книгу, чтобы их можно было экспортировать."
                            )
                                .setFontSize(12f)
                                .setTextAlignment(TextAlignment.LEFT)
                        )

                        document.add(Paragraph("\n\n---\n\n"))

                        document.add(
                            Paragraph("Сделано в Vulpine Notes")
                                .setFontSize(10f)
                                .setTextAlignment(TextAlignment.CENTER)
                        )
                    }
                }
            }
            outputStream.toByteArray()
        }
    }

    // Создание PDF для книги с пустыми главами
    private fun createEmptyChaptersPdf(book: Book): ByteArray {
        return ByteArrayOutputStream().use { outputStream ->
            PdfWriter(outputStream).use { writer ->
                PdfDocument(writer).use { pdfDoc ->
                    Document(pdfDoc).use { document ->
                        pdfDoc.defaultPageSize = PageSize.A4

                        document.add(
                            Paragraph(book.title)
                                .setFontSize(20f)
                                .setBold()
                                .setTextAlignment(TextAlignment.CENTER)
                        )

                        document.add(Paragraph("\n\n"))

                        document.add(
                            Paragraph("Главы пустые")
                                .setFontSize(16f)
                                .setTextAlignment(TextAlignment.CENTER)
                        )

                        document.add(Paragraph("\n"))

                        document.add(
                            Paragraph(
                                "В этой книге есть главы, но все они не содержат текста.\n\n" +
                                        "Отредактируйте главы и добавьте в них содержимое, " +
                                        "чтобы их можно было экспортировать."
                            )
                                .setFontSize(12f)
                                .setTextAlignment(TextAlignment.LEFT)
                        )

                        document.add(Paragraph("\n\n---\n\n"))

                        document.add(
                            Paragraph("Сделано в Vulpine Notes")
                                .setFontSize(10f)
                                .setTextAlignment(TextAlignment.CENTER)
                        )
                    }
                }
            }
            outputStream.toByteArray()
        }
    }

    private fun addFooterToAllPages(pdfDoc: PdfDocument, bookTitle: String) {
        try {
            val totalPages = pdfDoc.numberOfPages
            for (i in 1..totalPages) {
                val page = pdfDoc.getPage(i)
                val pageSize = page.pageSize

                // создаем новую Document для добавления footer
                Document(pdfDoc, pageSize as PageSize?, false).use { doc ->
                    val footerText = "Страница $i из $totalPages | Сделано в Vulpine Notes"
                    val footer = Paragraph(footerText)
                        .setFontSize(9f)
                        .setTextAlignment(TextAlignment.CENTER)
                        .setFixedPosition(
                            pageSize.left + 20,
                            pageSize.bottom + 10,
                            pageSize.width - 40
                        )

                    doc.add(footer)
                    doc.close()
                }
            }
        } catch (e: Exception) {
            Log.e("PDF", "Ошибка добавления footer: ${e.message}", e)
        }
    }

    private fun createErrorPdf(errorMessage: String): ByteArray {
        return ByteArrayOutputStream().use { outputStream ->
            PdfWriter(outputStream).use { writer ->
                PdfDocument(writer).use { pdfDoc ->
                    Document(pdfDoc).use { document ->
                        pdfDoc.defaultPageSize = PageSize.A4

                        document.add(
                            Paragraph("Ошибка при экспорте книги")
                                .setFontSize(16f)
                                .setBold()
                                .setTextAlignment(TextAlignment.CENTER)
                        )

                        document.add(Paragraph("\n"))

                        document.add(
                            Paragraph("Произошла ошибка при создании PDF файла:")
                                .setFontSize(12f)
                        )

                        document.add(
                            Paragraph(errorMessage)
                                .setFontSize(10f)
                                .setItalic()
                                .setBackgroundColor(com.itextpdf.kernel.colors.ColorConstants.LIGHT_GRAY)
                        )

                        document.add(Paragraph("\n\n"))

                        document.add(
                            Paragraph("Сделано в Vulpine Notes")
                                .setFontSize(10f)
                                .setTextAlignment(TextAlignment.CENTER)
                        )
                    }
                }
            }
            outputStream.toByteArray()
        }
    }

    private fun markdownToHtml(markdown: String): String {
        val sb = StringBuilder()
        val lines = markdown.lines()
        var inTable = false
        var inCodeBlock = false
        var inQuote = false
        var inUl = false
        var inOl = false
        var emptyLineCount = 0
        var chapterCount = 0
        var isTitlePagePassed = false
        var previousLineWasH1 = false

        // Для обработки таблиц
        var tableRows = mutableListOf<List<String>>()
        var isHeaderRow = false

        sb.append("<!DOCTYPE html>")
        sb.append("<html><head><meta charset=\"UTF-8\">")
        sb.append("<style>")
        // Основные стили
        sb.append("body { font-family: Arial, sans-serif; margin: 40px; line-height: 1.6; }")
        sb.append("h1 { border-bottom: 2px solid #333; padding-bottom: 10px; margin-top: 20px; }")
        sb.append("h2 { margin-top: 30px; padding-top: 20px; border-bottom: 1px solid #ddd; }")
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

        // Убрали page-break-before из заголовков
        sb.append("h1, h2, h3 { page-break-before: avoid; page-break-after: avoid; }")

        // Специальный класс для разрыва между главой и контентом
        sb.append(".chapter-break { page-break-before: always; }")

        // Стиль для заглавной страницы
        sb.append(".title-page { text-align: center; margin-top: 100px; }")
        sb.append(".title-page h1 { border: none; font-size: 24pt; }")
        sb.append(".title-page p { font-size: 14pt; color: #666; }")
        sb.append("</style></head><body>")

        // Функция для применения inline разметки
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
            if (inUl) {
                sb.append("</ul>")
                inUl = false
            }
            if (inOl) {
                sb.append("</ol>")
                inOl = false
            }
            if (inTable) {
                // Закрываем таблицу
                if (tableRows.isNotEmpty()) {
                    sb.append("<table>")
                    for ((index, row) in tableRows.withIndex()) {
                        if (index == 0 && isHeaderRow) {
                            sb.append("<tr>")
                            for (cell in row) {
                                sb.append("<th>${applyInlineMarkdown(cell)}</th>")
                            }
                            sb.append("</tr>")
                        } else {
                            sb.append("<tr>")
                            for (cell in row) {
                                sb.append("<td>${applyInlineMarkdown(cell)}</td>")
                            }
                            sb.append("</tr>")
                        }
                    }
                    sb.append("</table>")
                }
                tableRows.clear()
                inTable = false
                isHeaderRow = false
            }
            if (inQuote) {
                sb.append("</blockquote>")
                inQuote = false
            }
            emptyLineCount = 0
        }

        // Обработка строк
        for (i in lines.indices) {
            val line = lines[i]
            val trimmedLine = line.trim()
            val nextLine = if (i + 1 < lines.size) lines[i + 1].trim() else ""

            when {
                // спец разделитель для разрыва страниц
                trimmedLine == "# ##" -> {
                    closeActiveBlocks()
                    // Добавляем div с классом для разрыва страницы
                    sb.append("<div class=\"chapter-break\"></div>")
                    emptyLineCount = 0
                    continue
                }

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

                // Разделители (hr) - обычные
                trimmedLine.matches(Regex("^---+$")) -> {
                    closeActiveBlocks()
                    sb.append("<hr/>")
                    emptyLineCount = 0
                }

                // Заголовки
                trimmedLine.startsWith("### ") -> {
                    closeActiveBlocks()
                    sb.append("<h3>${applyInlineMarkdown(trimmedLine.substring(4).trim())}</h3>")
                    emptyLineCount = 0
                    previousLineWasH1 = false
                }

                trimmedLine.startsWith("## ") -> {
                    closeActiveBlocks()

                    // Если это первая глава после заглавной страницы, добавляем разрыв
                    if (isTitlePagePassed && chapterCount == 0) {
                        sb.append("<div class=\"chapter-break\"></div>")
                    }

                    sb.append("<h2>${applyInlineMarkdown(trimmedLine.substring(3).trim())}</h2>")
                    emptyLineCount = 0
                    previousLineWasH1 = false
                    chapterCount++
                }

                trimmedLine.startsWith("# ") -> {
                    closeActiveBlocks()

                    if (!isTitlePagePassed) {
                        // Это заглавная страница книги
                        sb.append("<div class=\"title-page\">")
                        sb.append(
                            "<h1>${
                                applyInlineMarkdown(
                                    trimmedLine.substring(2).trim()
                                )
                            }</h1>"
                        )
                        isTitlePagePassed = true
                        previousLineWasH1 = true
                    } else {
                        // Это другой H1 заголовок (возможно, в содержании)
                        sb.append(
                            "<h1>${
                                applyInlineMarkdown(
                                    trimmedLine.substring(2).trim()
                                )
                            }</h1>"
                        )
                        previousLineWasH1 = true
                    }
                    emptyLineCount = 0
                }

                // Закрываем div заглавной страницы при следующем не-пустом элементе после H1
                previousLineWasH1 && trimmedLine.isNotEmpty() && !trimmedLine.startsWith("#") -> {
                    if (isTitlePagePassed && chapterCount == 0) {
                        // Завершаем заглавную страницу
                        sb.append("</div>")
                        // Добавляем разрыв перед первой главой
                        sb.append("<div class=\"chapter-break\"></div>")
                    }
                    previousLineWasH1 = false
                    // Не используем return@when - продолжаем обработку этой же строки
                }

                // Таблицы (должно быть после проверки заголовков)
                line.contains("|") && !inCodeBlock && !trimmedLine.startsWith(">") -> {
                    // Проверяем, есть ли символы | в начале и конце или нет
                    val cleanLine = line.trim()
                    if (cleanLine.startsWith("|") || cleanLine.endsWith("|")) {
                        val cells = cleanLine.trim('|').split("|").map { it.trim() }

                        if (!inTable) {
                            // Если это начало таблицы, закрываем предыдущие блоки
                            closeActiveBlocks()
                            inTable = true
                            tableRows.clear()
                        }

                        // Проверяем, является ли это разделительной строкой
                        val isSeparator = cells.all { cell -> cell.matches(Regex("^:?---+:?$")) }

                        if (isSeparator) {
                            isHeaderRow = true
                        } else {
                            tableRows.add(cells)
                        }

                        emptyLineCount = 0
                        continue
                    }
                }

                // Цитаты
                trimmedLine.startsWith("> ") -> {
                    val quoteContent = applyInlineMarkdown(trimmedLine.substring(2).trim())
                    if (!inQuote) {
                        closeActiveBlocks()
                        sb.append("<blockquote>")
                        inQuote = true
                    } else {
                        sb.append("<br/>")
                    }
                    sb.append("<p>$quoteContent</p>")
                    emptyLineCount = 0

                    // Закрываем цитату, если следующая строка не цитата
                    if (nextLine.isNotEmpty() && !nextLine.startsWith("> ")) {
                        sb.append("</blockquote>")
                        inQuote = false
                    }
                }

                // Маркированные списки
                trimmedLine.matches(Regex("^[-*+]\\s+.*")) -> {
                    val listItem = applyInlineMarkdown(trimmedLine.substring(2).trim())
                    if (!inUl) {
                        closeActiveBlocks()
                        sb.append("<ul>")
                        inUl = true
                    }
                    sb.append("<li>$listItem</li>")
                    emptyLineCount = 0

                    // Закрываем список, если следующая строка не элемент списка
                    if (nextLine.isNotEmpty() && !nextLine.matches(Regex("^[-*+]\\s+.*"))
                        && !nextLine.matches(Regex("^\\d+\\.\\s+.*"))
                        && !nextLine.matches(Regex("^\\s+[-*+\\d]\\s+.*"))
                    ) {
                        sb.append("</ul>")
                        inUl = false
                    }
                }

                // Нумерованные списки
                trimmedLine.matches(Regex("^\\d+\\.\\s+.*")) -> {
                    // Извлекаем содержимое (без номера)
                    val listItemContent = trimmedLine.substring(trimmedLine.indexOf('.') + 1).trim()
                    val listItem = applyInlineMarkdown(listItemContent)

                    if (!inOl) {
                        closeActiveBlocks()
                        sb.append("<ol>")
                        inOl = true
                    }
                    sb.append("<li>$listItem</li>")
                    emptyLineCount = 0

                    // Закрываем список, если следующая строка не элемент списка
                    if (nextLine.isNotEmpty() && !nextLine.matches(Regex("^\\d+\\.\\s+.*"))
                        && !nextLine.matches(Regex("^[-*+]\\s+.*"))
                        && !nextLine.matches(Regex("^\\s+[-*+\\d]\\s+.*"))
                    ) {
                        sb.append("</ol>")
                        inOl = false
                    }
                }

                // Пустые строки
                trimmedLine.isEmpty() -> {
                    emptyLineCount++

                    // Если мы в таблице и встречаем пустую строку, закрываем таблицу
                    if (inTable) {
                        closeActiveBlocks()
                    }

                    // Если два пустых строки подряд и мы в списке, закрываем его
                    if (emptyLineCount >= 2) {
                        if (inUl) {
                            sb.append("</ul>")
                            inUl = false
                        }
                        if (inOl) {
                            sb.append("</ol>")
                            inOl = false
                        }
                        if (inQuote) {
                            sb.append("</blockquote>")
                            inQuote = false
                        }
                    } else if (i > 0 && lines[i - 1].trim().isNotEmpty() && !inCodeBlock) {
                        sb.append("<br/>")
                    }
                }

                // Обычный текст (абзацы)
                else -> {
                    // Если предыдущая строка была пустой и мы не в активных блоках,
                    // начинаем новый абзац
                    if (emptyLineCount > 0 && !inUl && !inOl && !inQuote && !inTable && !inCodeBlock) {
                        sb.append("<p>${applyInlineMarkdown(line)}</p>")
                    } else if (!inUl && !inOl && !inQuote && !inTable && !inCodeBlock) {
                        // Проверяем, продолжается ли предыдущий абзац
                        val isContinuation = i > 0 &&
                                lines[i - 1].trim().isNotEmpty() &&
                                !lines[i - 1].trim().startsWith("#") &&
                                !lines[i - 1].trim().startsWith(">") &&
                                !lines[i - 1].trim().matches(Regex("^[-*+]\\s+.*")) &&
                                !lines[i - 1].trim().matches(Regex("^\\d+\\.\\s+.*")) &&
                                !lines[i - 1].trim().matches(Regex("^---+$"))

                        if (isContinuation) {
                            sb.append("<br/>${applyInlineMarkdown(line)}")
                        } else {
                            sb.append("<p>${applyInlineMarkdown(line)}</p>")
                        }
                    } else if (inTable || inCodeBlock || inQuote) {
                        // Внутри специальных блоков просто добавляем текст
                        sb.append(applyInlineMarkdown(line))
                        if (inCodeBlock) sb.append("\n")
                    }
                    emptyLineCount = 0
                    previousLineWasH1 = false
                }
            }
        }

        // Закрываем все незакрытые блоки в конце
        closeActiveBlocks()
        if (inCodeBlock) sb.append("</code></pre>")
        if (inQuote) sb.append("</blockquote>")
        if (inUl) sb.append("</ul>")
        if (inOl) sb.append("</ol>")

        // Закрываем div заглавной страницы, если он еще открыт
        if (previousLineWasH1) {
            sb.append("</div>")
        }

        sb.append("</body></html>")
        return sb.toString()
    }

    // Обработка результатов запущенных активностей (аккаунт, настройки, экспорт)
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        // Обработка результата экспорта книги в выбранный формат
        if (requestCode == REQUEST_EXPORT && resultCode == RESULT_OK) {
            data?.data?.let { uri ->
                lifecycleScope.launch {
                    try {
                        val book = selectedBookForExport ?: return@launch
                        val format = selectedExportFormat ?: return@launch

                        // Генерация контента для экспорта в фоновом режиме
                        val content = generateExportContent(book, format)

                        // Запись сгенерированного контента в выбранное пользователем место
                        contentResolver.openOutputStream(uri, "w")?.use { output ->
                            output.write(content)
                            output.flush()
                        }

                        Toast.makeText(
                            this@MainActivity,
                            "Книга экспортирована в ${book.title}.${format}",
                            Toast.LENGTH_LONG
                        ).show()

                    } catch (e: Exception) {
                        Log.e("EXPORT", "Ошибка записи файла", e)
                        Toast.makeText(
                            this@MainActivity,
                            "Не удалось сохранить файл: ${e.message}",
                            Toast.LENGTH_LONG
                        ).show()
                    } finally {
                        // Очистка временных данных после завершения экспорта
                        selectedBookForExport = null
                        selectedExportFormat = null
                    }
                }
            } ?: run {
                Toast.makeText(this, "Не выбрано место сохранения", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // Показ меню фильтрации/сортировки книг в виде PopupMenu
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

    // Обновление текста на чипе фильтра в соответствии с выбранным типом сортировки
    private fun updateFilterChip(text: String) {
        findViewById<com.google.android.material.chip.Chip>(R.id.filter_chip).text = text
    }

    // Сортировка списка книг в соответствии с выбранным критерием
    private fun sortBooks() {
        when (currentSortType) {
            SortType.TITLE -> books.sortBy { it.title.lowercase(Locale.getDefault()) }
            SortType.UPDATED_AT -> books.sortByDescending { it.updatedAt }
            SortType.CREATED_AT -> books.sortByDescending { it.createdAt }
        }
        bookAdapter.notifyDataSetChanged()
    }

    // Настройка боковой навигационной панели (Drawer)
    private fun setupDrawer() {
        // Открытие Drawer при нажатии на кнопку меню
        menuButton.setOnClickListener { drawerLayout.openDrawer(GravityCompat.START) }

        // Обработка выбора пунктов навигационного меню
        navView.setNavigationItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_account -> startActivityForResult(
                    Intent(
                        this,
                        AccountActivity::class.java
                    ), REQUEST_ACCOUNT
                )

                R.id.nav_sync -> {
                    auth.currentUser?.let { user ->
                        syncAllFromCloud(user)
                        Toast.makeText(this, "Синхронизация запущена...", Toast.LENGTH_SHORT).show()
                    } ?: Toast.makeText(this, "Войдите в аккаунт", Toast.LENGTH_LONG).show()
                }

                R.id.nav_cloud -> startActivity(Intent(this, CloudActivity::class.java))
                R.id.nav_settings -> startActivityForResult(
                    Intent(
                        this,
                        SettingsActivity::class.java
                    ), REQUEST_SETTINGS
                )

                R.id.nav_support -> startActivityForResult(
                    Intent(
                        this,
                        SupportActivity::class.java
                    ), REQUEST_SETTINGS
                )
            }
            drawerLayout.closeDrawer(GravityCompat.START)
            true
        }
    }

    // Настройка обработки нажатия кнопки "Назад" на устройстве
    private fun setupBackPress() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                // Закрытие Drawer если он открыт, иначе закрытие активности
                if (drawerLayout.isDrawerOpen(GravityCompat.START)) {
                    drawerLayout.closeDrawer(GravityCompat.START)
                } else {
                    finish()
                }
            }
        })
    }

    // Обновление заголовка навигационной панели с информацией о пользователе
    private fun updateNavHeader() {
        val headerView = navView.getHeaderView(0)
        val avatar = headerView.findViewById<ShapeableImageView>(R.id.nav_header_avatar)
        val name = headerView.findViewById<TextView>(R.id.nav_header_name)
        val email = headerView.findViewById<TextView>(R.id.nav_header_email)
        val user = auth.currentUser

        if (user != null) {
            // Отображение данных авторизованного пользователя
            name.text = user.displayName ?: "Пользователь"
            email.text = user.email ?: ""
            user.photoUrl?.let {
                Glide.with(this).load(it).placeholder(R.drawable.ic_fox_logo).into(avatar)
            }
                ?: avatar.setImageResource(R.drawable.ic_fox_logo)
        } else {
            // Отображение информации для неавторизованного пользователя
            name.text = "Локальный режим"
            email.text = "Войдите для синхронизации"
            avatar.setImageResource(R.drawable.ic_fox_logo)
        }

        // Обработка клика по заголовку для перехода в аккаунт
        headerView.setOnClickListener {
            drawerLayout.closeDrawer(GravityCompat.START)
            startActivityForResult(Intent(this, AccountActivity::class.java), REQUEST_ACCOUNT)
        }
    }

    // Применение сохраненной темы приложения из SharedPreferences
    private fun applySavedTheme() {
        val mode = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getInt(KEY_THEME, AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
        AppCompatDelegate.setDefaultNightMode(mode)
    }
}