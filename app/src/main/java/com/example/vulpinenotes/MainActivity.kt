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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
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
        // при запуске проверяем авторизацию
        if (auth.currentUser != null) {
            showAddBookButton()
            updateNavHeader()
            val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val autoSync = prefs.getBoolean("auto_sync", true)

            if (auth.currentUser != null) {
                showAddBookButton()
                updateNavHeader()
                if (autoSync) {
                    syncAllFromCloud(auth.currentUser!!)
                }
            }
        } else {
            showAuthRequired()
            updateNavHeader()
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
                allBooks.clear()
                allBooks.addAll(bookEntities.map { it.toBook(coversDir) })

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
            filterBooks("") // сброс фильтра
        }
    }

    private fun filterBooks(query: String) {
        val lowerQuery = query.lowercase(Locale.getDefault())
        books.clear()
        if (lowerQuery.isEmpty()) {
            books.addAll(allBooks)
        } else {
            books.addAll(
                allBooks.filter { book ->
                    book.title.lowercase(Locale.getDefault()).contains(lowerQuery) ||
                            book.desc.lowercase(Locale.getDefault()).contains(lowerQuery)
                }
            )
        }
        sortBooks()
        bookAdapter.notifyDataSetChanged()
    }
    private fun showAuthRequired() {
        MaterialAlertDialogBuilder(this)
            .setTitle("Требуется вход")
            .setMessage("Войдите через Google, чтобы синхронизировать книги в облако.")
            .setPositiveButton("Войти") { _, _ ->
                startActivityForResult(Intent(this, AccountActivity::class.java), REQUEST_ACCOUNT)
            }
            .setNegativeButton("Позже") { d, _ -> d.dismiss() }
            .setCancelable(false)
            .show()
        addBookButton.visibility = View.GONE
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
            Glide.with(this)
                .load(book.coverUri)
                .placeholder(R.drawable.book_vector_placeholder)
                .error(R.drawable.book_vector_placeholder)
                .skipMemoryCache(true) // игнорируем память
                .diskCacheStrategy(com.bumptech.glide.load.engine.DiskCacheStrategy.NONE) // игнорируем кэш на диске
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

            // синхронизация с облаком без полей coverPath и cloudSynced
            if (uploadToCloud && auth.currentUser != null) {
                try {
                    withContext(Dispatchers.IO) {
                        db.collection("users")
                            .document(auth.currentUser!!.uid)
                            .collection("books")
                            .document(bookId)
                            .set(hashMapOf(
                                "title" to title,
                                "desc" to desc,
                                "chaptersCount" to 0,
                                "createdAt" to bookEntity.createdAt,
                                "updatedAt" to bookEntity.updatedAt
                            )).await()
                    }
                    database.bookDao().updateCloudState(bookId, true)
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@MainActivity, "Книга добавлена и синхронизирована", Toast.LENGTH_SHORT).show()
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@MainActivity, "Книга добавлена локально (нет сети)", Toast.LENGTH_LONG).show()
                    }
                    Log.e("SYNC", "Не удалось синхронизировать при создании", e)
                }
            } else {
                Toast.makeText(this@MainActivity, "Книга добавлена локально", Toast.LENGTH_SHORT).show()
            }
        }
    }
    private fun updateBookLocally(bookId: String, newTitle: String, newDesc: String, newCoverFile: File?) {
        lifecycleScope.launch {
            val currentBook = database.bookDao().getBookById(bookId) ?: return@launch

            val finalCoverPath = newCoverFile?.let {
                val destFile = File(coversDir, "cover_$bookId.jpg") // всегда одно имя на книгу
                if (destFile.exists()) destFile.delete()           // удаляем старый файл
                it.copyTo(destFile, overwrite = true)
                destFile.absolutePath
            } ?: currentBook.coverPath


            val updatedBook = currentBook.copy(
                title = newTitle,
                desc = newDesc,
                coverPath = finalCoverPath,
                updatedAt = System.currentTimeMillis()  // обновляем только updatedAt
            )

            database.bookDao().insertBook(updatedBook) // заменяем существующую запись

            // если синхронизировано с облаком
            if (currentBook.cloudSynced && auth.currentUser != null) {
                try {
                    withContext(Dispatchers.IO) {
                        db.collection("users")
                            .document(auth.currentUser!!.uid)
                            .collection("books")
                            .document(bookId)
                            .update(mapOf(
                                "title" to newTitle,
                                "desc" to newDesc,
                                "updatedAt" to updatedBook.updatedAt
                            )).await()
                    }
                } catch (e: Exception) {
                    Log.e("SYNC", "Не удалось обновить книгу в облаке", e)
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
                    .document(chapter.position.toString())
                batch.set(ref, chapter)
            }
            batch.commit().await()
            Log.d("SYNC", "Залито ${localChapters.size} глав для книги $bookId")
        } catch (e: Exception) {
            Log.e("SYNC", "Ошибка заливки глав книги $bookId", e)
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
                val chapter = doc.toObject(Chapter::class.java) ?: return@mapNotNull null
                // преобразуем в Entity с правильным bookId!
                ChapterEntity(
                    bookId = bookId,
                    position = doc.id.toInt(),
                    title = chapter.title,
                    description = chapter.description,
                    date = chapter.date,
                    wordCount = chapter.wordCount,
                    isFavorite = chapter.isFavorite,
                    updatedAt = System.currentTimeMillis()
                )
            }

            if (cloudChapters.isNotEmpty()) {
                withContext(Dispatchers.IO) {
                    database.chapterDao().insertChapters(cloudChapters)
                }
                Log.d("SYNC", "Скачано ${cloudChapters.size} глав для книги $bookId")
            }
        } catch (e: Exception) {
            Log.e("SYNC", "Ошибка скачивания глав книги $bookId", e)
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
                        // просто обновляем флаг — книга точно есть в облаке
                        database.bookDao().updateCloudState(cloudBook.id, true)
                        // и сохраняем остальные поля (если их нет локально)
                        if (localBook == null) {
                            database.bookDao().insertBook(
                                cloudBook.copy(
                                    coverPath = null,
                                    cloudSynced = true
                                )
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
                    // скачиваем главы из облака
                    downloadAllChaptersForBook(cloudBook.id, user)
                    // заливаем локальные главы (если они новее или отсутствуют в облаке)
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
                    Toast.makeText(this@MainActivity, "Ошибка синхронизации: ${e.message}", Toast.LENGTH_LONG).show()
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

        // обложка
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
        } else cover.visibility = View.GONE

        // Название и описание
        v.findViewById<TextView>(R.id.dialogTitle).text = book.title.ifBlank { "Без названия" }
        v.findViewById<TextView>(R.id.dialogAuthor).text = book.desc.ifBlank { getString(R.string.unknown_desc) }

        // Количество глав
        v.findViewById<TextView>(R.id.dialogChaptersCount)?.text =
            "Количество глав: ${book.chaptersCount}"

        // Дата создания и обновления
        val dateFormat = java.text.DateFormat.getDateTimeInstance()
        v.findViewById<TextView>(R.id.dialogCreatedAt)?.text =
            "Создано: ${dateFormat.format(book.createdAt)}"
        v.findViewById<TextView>(R.id.dialogUpdatedAt)?.text =
            "Обновлено: ${dateFormat.format(book.updatedAt)}"

        // Статус синхронизации
        v.findViewById<TextView>(R.id.dialogCloudSynced)?.text =
            "Синхронизировано: ${if (book.cloudSynced) "Да" else "Нет"}"

        // Показываем диалог
        MaterialAlertDialogBuilder(this, R.style.CustomAlertDialogTheme)
            .setTitle(R.string.info)
            .setView(v)
            .setPositiveButton("OK") { d, _ -> d.dismiss() }
            .show()
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
        val chip = findViewById<com.google.android.material.chip.Chip>(R.id.filter_chip)
        chip.text = text
    }

    private fun sortBooks() {
        when (currentSortType) {
            SortType.TITLE -> {
                books.sortBy { it.title.lowercase(Locale.getDefault()) }
            }
            SortType.UPDATED_AT -> {
                books.sortByDescending { it.updatedAt }
            }
            SortType.CREATED_AT -> {
                books.sortByDescending { it.createdAt }
            }
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
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_ACCOUNT && resultCode == RESULT_OK) {
            if (auth.currentUser != null) {
                syncAllFromCloud(auth.currentUser!!)
                showAddBookButton()
            } else {
                addBookButton.visibility = View.GONE
                Toast.makeText(this, "Вы вышли из аккаунта", Toast.LENGTH_SHORT).show()
            }

            updateNavHeader()
        }

        if (requestCode == REQUEST_SETTINGS && resultCode == RESULT_OK) {
            recreate()

            val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val autoSync = prefs.getBoolean("auto_sync", true)

            if (auth.currentUser != null && autoSync) {
                syncAllFromCloud(auth.currentUser!!)
            }
        }

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
            headerView.setOnClickListener {
                drawerLayout.closeDrawer(GravityCompat.START)
                startActivity(Intent(this, AccountActivity::class.java))
            }
        } else {
            name.text = getString(R.string.name_def)
            email.text = getString(R.string.name_tip)
            avatar.setImageResource(R.drawable.ic_fox_logo)
            headerView.setOnClickListener {
                drawerLayout.closeDrawer(GravityCompat.START)
                startActivityForResult(Intent(this, AccountActivity::class.java), REQUEST_ACCOUNT)
            }
        }
    }
    private fun applySavedTheme() {
        val mode = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getInt(KEY_THEME, AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
        AppCompatDelegate.setDefaultNightMode(mode)
    }
}