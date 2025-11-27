package com.example.vulpinenotes

import android.os.Bundle
import android.os.Vibrator
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.GridLayoutManager
import com.example.vulpinenotes.data.AppDatabase
import com.example.vulpinenotes.data.BookEntity
import com.example.vulpinenotes.data.toBook
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_cloud)

        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "Облако"

        initViews()
        setupAdapters()
        setupSearch()
        syncAndLoadBooks()
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
            deleteFromCloud(book.id)
        }
        adapterLocal = CloudBookAdapter(filteredLocal) { book ->
            uploadToCloud(book)
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

    private fun syncAndLoadBooks() {
        if (auth.currentUser == null) {
            Toast.makeText(this, "Войдите в аккаунт", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        CoroutineScope(Dispatchers.Main).launch {
            try {
                val cloudSnapshot = db.collection("users")
                    .document(auth.currentUser!!.uid)
                    .collection("books")
                    .get()
                    .await()

                val cloudBooks = mutableMapOf<String, BookEntity>()

                cloudSnapshot.documents.forEach { doc ->
                    val data = doc.data ?: return@forEach
                    Log.d("CLOUD_DEBUG", "Книга из Firestore: id=${doc.id}")
                    Log.d("CLOUD_DEBUG", "  → title = '${data["title"]}'")
                    Log.d("CLOUD_DEBUG", "  → desc = '${data["desc"]}'")
                    Log.d("CLOUD_DEBUG", "  → updatedAt = ${data["updatedAt"]}")

                    val book = BookEntity(
                        id = doc.id,
                        title = data["title"] as? String ?: "Без названия",
                        desc = data["desc"] as? String ?: "Нет описания",
                        chaptersCount = (data["chaptersCount"] as? Long)?.toInt() ?: 0,
                        updatedAt = (data["updatedAt"] as? Long) ?: System.currentTimeMillis()
                    )
                    Log.d("CLOUD_DEBUG", "  → после парсинга title = '${book.title}'")
                    cloudBooks[doc.id] = book
                }

                val localBooks = withContext(Dispatchers.IO) {
                    roomDb.bookDao().getAllBooksSync()
                }

                withContext(Dispatchers.IO) {
                    cloudBooks.forEach { (id, cloudBook) ->
                        val localBook = localBooks.find { it.id == id }
                        if (localBook == null || cloudBook.updatedAt > localBook.updatedAt) {
                            roomDb.bookDao().insertBook(cloudBook.copy(
                                id = id,
                                coverPath = localBook?.coverPath,
                                cloudSynced = true
                            ))
                        } else if (!localBook.cloudSynced) {
                            roomDb.bookDao().updateCloudState(id, true)
                        }
                    }

                    localBooks.forEach { localBook ->
                        if (!cloudBooks.containsKey(localBook.id) && localBook.cloudSynced) {
                            roomDb.bookDao().updateCloudState(localBook.id, false)
                        }
                    }
                }

                val finalBooks = withContext(Dispatchers.IO) {
                    roomDb.bookDao().getAllBooksSync().map { it.toBook(coversDir) }
                }

                allSyncedBooks.clear()
                allLocalOnlyBooks.clear()

                finalBooks.forEach { book ->
                    if (book.cloudSynced) allSyncedBooks.add(book) else allLocalOnlyBooks.add(book)
                }

                filteredSynced.clear()
                filteredLocal.clear()
                filteredSynced.addAll(allSyncedBooks)
                filteredLocal.addAll(allLocalOnlyBooks)

                adapterSynced.notifyDataSetChanged()
                adapterLocal.notifyDataSetChanged()

                tvSyncedHeader.text = "В облаке (${allSyncedBooks.size})"
                tvLocalHeader.text = "Только на устройстве (${allLocalOnlyBooks.size})"

            } catch (e: Exception) {
                Toast.makeText(this@CloudActivity, "Ошибка: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun uploadToCloud(book: Book) {
        val data = hashMapOf(
            "title" to book.title,
            "desc" to book.desc,
            "chaptersCount" to book.chaptersCount,
            "updatedAt" to System.currentTimeMillis()
        )

        db.collection("users").document(auth.currentUser!!.uid)
            .collection("books").document(book.id)
            .set(data)
            .addOnSuccessListener {
                vibrator.vibrate(80)
                Toast.makeText(this, "Загружено", Toast.LENGTH_SHORT).show()
                syncAndLoadBooks()
            }
            .addOnFailureListener {
                Toast.makeText(this, "Ошибка загрузки", Toast.LENGTH_SHORT).show()
            }
    }

    private fun deleteFromCloud(bookId: String) {
        db.collection("users").document(auth.currentUser!!.uid)
            .collection("books").document(bookId)
            .delete()
            .addOnSuccessListener {
                vibrator.vibrate(80)
                Toast.makeText(this, "Удалено из облака", Toast.LENGTH_SHORT).show()
                syncAndLoadBooks()
            }
    }

    private fun uploadAllLocalBooks() {
        if (allLocalOnlyBooks.isEmpty()) {
            Toast.makeText(this, "Нет книг для загрузки", Toast.LENGTH_SHORT).show()
            return
        }
        btnUploadAll.isEnabled = false
        allLocalOnlyBooks.forEach { uploadToCloud(it) }
        btnUploadAll.isEnabled = true
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}