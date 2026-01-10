package com.example.vulpinenotes
import android.os.Bundle
import android.os.Vibrator
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.GridLayoutManager
import com.example.vulpinenotes.data.AppDatabase
import com.example.vulpinenotes.data.toBook
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.*
import kotlinx.coroutines.tasks.await
import java.io.File
class CloudActivity : AppCompatActivity() {
    private lateinit var rvSynced: androidx.recyclerview.widget.RecyclerView
    private lateinit var rvLocalOnly: androidx.recyclerview.widget.RecyclerView
    private lateinit var tvSyncedHeader: android.widget.TextView
    private lateinit var tvLocalHeader: android.widget.TextView
    private lateinit var searchEditText: android.widget.EditText
    private lateinit var clearButton: android.widget.ImageView
    private lateinit var btnUploadAll: android.widget.ImageButton
    private val allSyncedBooks = mutableListOf<Book>()
    private val allLocalOnlyBooks = mutableListOf<Book>()
    private val filteredSynced = mutableListOf<Book>()
    private val filteredLocal = mutableListOf<Book>()
    private lateinit var adapterSynced: CloudBookAdapter
    private lateinit var adapterLocal: CloudBookAdapter
    private lateinit var db: FirebaseFirestore
    private lateinit var auth: FirebaseAuth
    private lateinit var roomDb: AppDatabase
    private val coversDir: File by lazy {
        File(filesDir, "covers").apply { mkdirs() }
    }
    private val vibrator: Vibrator by lazy {
        getSystemService(VIBRATOR_SERVICE) as Vibrator
    }
    private val activityScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_cloud)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "Облако"
        initViews()
        setupAdapters()
        setupSearch()
        loadAndRefreshData()
    }
    private fun initViews() {
        rvSynced = findViewById(R.id.rv_synced)
        rvLocalOnly = findViewById(R.id.rv_local_only)
        tvSyncedHeader = findViewById(R.id.tv_synced_header)
        tvLocalHeader = findViewById(R.id.tv_local_header)
        searchEditText = findViewById(R.id.search_edit_text)
        clearButton = findViewById(R.id.clear_button)
        btnUploadAll = findViewById(R.id.btn_upload_all)
        db = FirebaseFirestore.getInstance()
        auth = FirebaseAuth.getInstance()
        roomDb = AppDatabase.getDatabase(this)
        btnUploadAll.setOnClickListener { uploadAllLocalBooks() }
        findViewById<View>(R.id.back_button).setOnClickListener { onBackPressed() }
    }
    private fun setupAdapters() {
        adapterSynced = CloudBookAdapter(filteredSynced) { book ->
            deleteBookFromCloud(book)
        }
        adapterLocal = CloudBookAdapter(filteredLocal) { book ->
            uploadBookToCloud(book)
        }
        rvSynced.layoutManager = GridLayoutManager(this, 2)
        rvSynced.adapter = adapterSynced
        rvLocalOnly.layoutManager = GridLayoutManager(this, 2)
        rvLocalOnly.adapter = adapterLocal
    }
    private fun setupSearch() {
        searchEditText.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {
                clearButton.visibility = if (s.isNullOrEmpty()) View.GONE else View.VISIBLE
                filterBooks(s.toString())
            }
            override fun afterTextChanged(s: Editable?) {}
        })
        clearButton.setOnClickListener { searchEditText.text.clear() }
    }
    private fun filterBooks(query: String) {
        val q = query.lowercase()
        filteredSynced.clear()
        filteredLocal.clear()
        filteredSynced.addAll(allSyncedBooks.filter {
            it.title.lowercase().contains(q) || it.desc.lowercase().contains(q)
        })
        filteredLocal.addAll(allLocalOnlyBooks.filter {
            it.title.lowercase().contains(q) || it.desc.lowercase().contains(q)
        })
        adapterSynced.notifyDataSetChanged()
        adapterLocal.notifyDataSetChanged()
    }
    private fun loadAndRefreshData() {
        if (auth.currentUser == null) {
            Toast.makeText(this, "Войдите в аккаунт", Toast.LENGTH_LONG).show()
            finish()
            return
        }
        activityScope.launch {
            try {
                // 1. получаем список ID книг из облака
                val cloudSnapshot = db.collection("users")
                    .document(auth.currentUser!!.uid)
                    .collection("books")
                    .get()
                    .await()
                val cloudBookIds = cloudSnapshot.documents.map { it.id }.toSet()
                // 2. получаем все локальные книги
                val localBookEntities = withContext(Dispatchers.IO) {
                    roomDb.bookDao().getAllBooksSync()
                }
                allSyncedBooks.clear()
                allLocalOnlyBooks.clear()
                localBookEntities.forEach { entity ->
                    val book = entity.toBook(coversDir)
                    if (cloudBookIds.contains(entity.id)) {
                        // книга есть в облаке - принудительно считаем её синхронизированной
                        val syncedBook = book.copy(cloudSynced = true)
                        allSyncedBooks.add(syncedBook)
                        // защита: если в базе вдруг cloudSynced = false — исправляем
                        if (!entity.cloudSynced) {
                            withContext(Dispatchers.IO) {
                                roomDb.bookDao().updateCloudState(entity.id, true)
                            }
                        }
                    } else {
                        allLocalOnlyBooks.add(book)
                    }
                }
                filterBooks(searchEditText.text.toString())
                tvSyncedHeader.text = "В облаке (${allSyncedBooks.size})"
                tvLocalHeader.text = "Только на устройстве (${allLocalOnlyBooks.size})"
            } catch (e: Exception) {
                Toast.makeText(this@CloudActivity, "Ошибка загрузки: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
            }
        }
    }
    // загрузка одной книги в облако
    private fun uploadBookToCloud(book: Book) {
        val user = auth.currentUser ?: return
        val data = hashMapOf(
            "title" to book.title,
            "desc" to book.desc,
            "chaptersCount" to book.chaptersCount,
            "updatedAt" to System.currentTimeMillis()
        )
        activityScope.launch {
            try {
                db.collection("users")
                    .document(user.uid)
                    .collection("books")
                    .document(book.id)
                    .set(data)
                    .await()
                withContext(Dispatchers.IO) {
                    roomDb.bookDao().updateCloudState(book.id, true)
                }
                vibrator.vibrate(80)
                Toast.makeText(this@CloudActivity, "«${book.title}» загружено в облако", Toast.LENGTH_SHORT).show()
                // перемещаем в другой список
                allLocalOnlyBooks.remove(book)
                allSyncedBooks.add(book.copy(cloudSynced = true))
                filterBooks(searchEditText.text.toString())
                tvLocalHeader.text = "Только на устройстве (${allLocalOnlyBooks.size})"
                tvSyncedHeader.text = "В облаке (${allSyncedBooks.size})"
            } catch (e: Exception) {
                Toast.makeText(this@CloudActivity, "Ошибка загрузки", Toast.LENGTH_SHORT).show()
            }
        }
    }
    // удаление книги из облака (остаётся локально)
    private fun deleteBookFromCloud(book: Book) {
        val user = auth.currentUser ?: return
        MaterialAlertDialogBuilder(this)
            .setTitle("Удалить из облака?")
            .setMessage("«${book.title}» будет удалена из облака, но останется на этом устройстве.")
            .setPositiveButton("Удалить") { _, _ ->
                activityScope.launch {
                    try {
                        db.collection("users")
                            .document(user.uid)
                            .collection("books")
                            .document(book.id)
                            .delete()
                            .await()
                        // снимаем флаг синхронизации локально
                        withContext(Dispatchers.IO) {
                            roomDb.bookDao().updateCloudState(book.id, false)
                        }
                        vibrator.vibrate(80)
                        Toast.makeText(this@CloudActivity, "Удалено из облака", Toast.LENGTH_SHORT).show()
                        // перемещаем обратно в "только локальные"
                        allSyncedBooks.remove(book)
                        allLocalOnlyBooks.add(book.copy(cloudSynced = false))
                        filterBooks(searchEditText.text.toString())
                        tvSyncedHeader.text = "В облаке (${allSyncedBooks.size})"
                        tvLocalHeader.text = "Только на устройстве (${allLocalOnlyBooks.size})"
                    } catch (e: Exception) {
                        Toast.makeText(this@CloudActivity, "Ошибка удаления", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton("Отмена", null)
            .show()
    }
    // загрузить все локальные книги в облако
    private fun uploadAllLocalBooks() {
        if (allLocalOnlyBooks.isEmpty()) {
            Toast.makeText(this, "Нет книг для загрузки", Toast.LENGTH_SHORT).show()
            return
        }
        btnUploadAll.isEnabled = false
        var uploaded = 0
        var failed = 0
        allLocalOnlyBooks.forEach { book ->
            uploadBookToCloud(book)
            uploaded++
            if (uploaded + failed == allLocalOnlyBooks.size) {
                btnUploadAll.isEnabled = true
                Toast.makeText(
                    this,
                    "Загрузка завершена: $uploaded успешно, $failed ошибок",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }
    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
    override fun onDestroy() {
        super.onDestroy()
        activityScope.cancel()
    }
}