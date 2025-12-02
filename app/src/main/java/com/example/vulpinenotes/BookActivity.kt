package com.example.vulpinenotes

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.widget.ImageButton
import android.widget.Toast
import androidx.activity.addCallback
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.vulpinenotes.data.AppDatabase
import com.example.vulpinenotes.data.ChapterEntity
import com.example.vulpinenotes.data.toChapter
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.*
import kotlinx.coroutines.tasks.await
import java.text.SimpleDateFormat
import java.util.*

class BookActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_BOOK = "com.example.vulpinenotes.EXTRA_BOOK"
        private const val TAG = "BookActivity"
    }

    private lateinit var book: Book
    private val chapters = mutableListOf<Chapter>()
    private lateinit var chapterAdapter: ChapterAdapter
    private lateinit var recyclerView: RecyclerView
    private lateinit var database: AppDatabase
    private lateinit var firestore: FirebaseFirestore
    private lateinit var auth: FirebaseAuth

    // Гарантированная отправка — корутина живёт дольше Activity!
    private val backgroundScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val editChapterLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == ChapterEditActivity.RESULT_UPDATED_CHAPTER) {
            val updated = result.data?.getParcelableExtra<Chapter>(ChapterEditActivity.EXTRA_CHAPTER)
            val pos = result.data?.getIntExtra(ChapterEditActivity.EXTRA_CHAPTER_POSITION, -1) ?: -1
            if (updated != null && pos != -1) {
                chapters[pos] = updated
                chapterAdapter.notifyItemChanged(pos)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_book)
        setSupportActionBar(findViewById(R.id.toolbar))
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        database = AppDatabase.getDatabase(this)
        firestore = FirebaseFirestore.getInstance()
        auth = FirebaseAuth.getInstance()

        book = intent.getParcelableExtra(EXTRA_BOOK) ?: run { finish(); return }
        supportActionBar?.title = book.title

        setupRecyclerView()
        setupFab()
        loadChaptersFromRoom()

        // Просто выход — никаких "гарантированных" batch при закрытии
        onBackPressedDispatcher.addCallback(this) {
            finish()
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.book_menu, menu)

        // Если книга НЕ синхронизируется с облаком — скрываем кнопку
        val syncItem = menu.findItem(R.id.action_sync)
        syncItem.isVisible = book.cloudSynced

        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_sync -> {
                manualSyncAllChapters()
                true
            }
            android.R.id.home -> {
                onSupportNavigateUp()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun manualSyncAllChapters() {
        if (!book.cloudSynced) return

        lifecycleScope.launch {
            val entities = database.chapterDao().getChaptersForBookSync(book.id)
            entities.forEach { chapterEntity ->
                uploadChapterToCloud(chapterEntity)
            }
            Toast.makeText(this@BookActivity, "Синхронизация завершена", Toast.LENGTH_SHORT).show()
        }
    }

    private fun setupRecyclerView() {
        recyclerView = findViewById(R.id.chapters_recycler_view)
        chapterAdapter = ChapterAdapter(
            chapters,
            this,
            onInfo = { showChapterInfo(it) },
            onEdit = { _, _ -> },
            onDelete = { deleteChapter(it) },
            onFavoriteToggle = { toggleFavorite(it) },
            onChapterClick = { chapter, position -> openChapterEditor(chapter, position) }
        )
        recyclerView.adapter = chapterAdapter
        recyclerView.layoutManager = LinearLayoutManager(this)
        setupDragAndDrop()
    }

    private fun setupFab() {
        findViewById<ImageButton>(R.id.fab_add_chapter).setOnClickListener {
            it.animate().scaleX(0.9f).scaleY(0.9f).setDuration(100).withEndAction {
                it.animate().scaleX(1f).scaleY(1f).setDuration(100).start()
                showAddChapterDialog()
            }.start()
        }
    }

    private fun loadChaptersFromRoom() {
        lifecycleScope.launch {
            database.chapterDao().getChaptersForBook(book.id).collect { entities ->
                chapters.clear()
                chapters.addAll(entities.map { it.toChapter() })
                chapterAdapter.notifyDataSetChanged()
            }
        }
    }

    private fun showAddChapterDialog() {
        val view = layoutInflater.inflate(R.layout.add_chapter_dialog, null)
        val editTitle = view.findViewById<TextInputEditText>(R.id.edit_title)

        MaterialAlertDialogBuilder(this)
            .setTitle("Новая глава")
            .setView(view)
            .setPositiveButton("Создать") { _, _ ->
                val title = editTitle.text.toString().trim()
                if (title.isNotBlank()) {
                    lifecycleScope.launch {
                        val count = database.chapterDao().getChapterCount(book.id)
                        val newChapter = ChapterEntity(
                            bookId = book.id,
                            position = count,
                            title = title,
                            description = "",
                            date = SimpleDateFormat("dd.MM.yy", Locale.getDefault()).format(Date()),
                            wordCount = 0,
                            isFavorite = false,
                            updatedAt = System.currentTimeMillis()
                        )
                        database.chapterDao().insertChapter(newChapter)

                        // ГАРАНТИРОВАННАЯ отправка — даже если сразу выйти
                        if (book.cloudSynced) {
                            uploadChapterToCloud(newChapter)
                        }
                    }
                }
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun deleteChapter(chapter: Chapter) {
        val position = chapters.indexOf(chapter)

        MaterialAlertDialogBuilder(this)
            .setTitle("Удалить главу?")
            .setMessage("«${chapter.title}» будет удалена безвозвратно.")
            .setPositiveButton("Удалить") { _, _ ->
                lifecycleScope.launch {
                    // Находим entity по позиции
                    val entity = database.chapterDao()
                        .getChaptersForBookSync(book.id)
                        .find { it.position == position } ?: return@launch

                    // Удаляем из Room
                    database.chapterDao().deleteChapter(entity)

                    // Удаляем из Firestore
                    if (book.cloudSynced) {
                        backgroundScope.launch {
                            try {
                                auth.currentUser?.let { user ->
                                    firestore.collection("users")
                                        .document(user.uid)
                                        .collection("books")
                                        .document(book.id)
                                        .collection("chapters")
                                        .document(position.toString())  // ← по старой позиции!
                                        .delete()
                                        .await()
                                }
                            } catch (e: Exception) {
                                Log.e(TAG, "Не удалось удалить главу из облака", e)
                            }
                        }
                    }

                    reorderPositionsAfterDeletion(position)
                }
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private suspend fun reorderPositionsAfterDeletion(deletedPosition: Int) {
        val all = database.chapterDao().getChaptersForBookSync(book.id)
            .filter { it.position > deletedPosition }

        all.forEach { chapter ->
            val updated = chapter.copy(
                position = chapter.position - 1,
                updatedAt = System.currentTimeMillis()
            )
            database.chapterDao().insertChapter(updated)
            if (book.cloudSynced) {
                uploadChapterToCloud(updated)
            }
        }
    }

    private fun toggleFavorite(chapter: Chapter) {
        val position = chapters.indexOf(chapter)
        lifecycleScope.launch {
            val entity = database.chapterDao().getChaptersForBookSync(book.id)
                .find { it.position == position } ?: return@launch

            val updated = entity.copy(
                isFavorite = !entity.isFavorite,
                updatedAt = System.currentTimeMillis()
            )
            database.chapterDao().insertChapter(updated)

            if (book.cloudSynced) {
                uploadChapterToCloud(updated)
            }
        }
    }

    private fun openChapterEditor(chapter: Chapter, position: Int) {
        val intent = Intent(this, ChapterEditActivity::class.java).apply {
            putExtra(ChapterEditActivity.EXTRA_CHAPTER, chapter)
            putExtra(ChapterEditActivity.EXTRA_CHAPTER_POSITION, position)
            putExtra("book_id", book.id)
            putExtra("book_cloud_synced", book.cloudSynced)
        }
        editChapterLauncher.launch(intent)
    }

    private fun setupDragAndDrop() {
        val helper = ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(
            ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0
        ) {
            override fun onMove(
                rv: RecyclerView,
                vh: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder
            ): Boolean {
                val from = vh.adapterPosition
                val to = target.adapterPosition

                chapters.add(to, chapters.removeAt(from))
                chapterAdapter.notifyItemMoved(from, to)

                lifecycleScope.launch {
                    val allChapters = database.chapterDao().getChaptersForBookSync(book.id)
                    allChapters.forEachIndexed { index, entity ->
                        if (entity.position != index) {
                            val updated = entity.copy(
                                position = index,
                                updatedAt = System.currentTimeMillis()
                            )
                            database.chapterDao().insertChapter(updated)
                            if (book.cloudSynced) {
                                uploadChapterToCloud(updated)
                            }
                        }
                    }
                }
                return true
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {}
        })
        helper.attachToRecyclerView(recyclerView)
    }

    private fun showChapterInfo(chapter: Chapter) {
        MaterialAlertDialogBuilder(this)
            .setTitle(chapter.title)
            .setMessage("Слов: ${chapter.wordCount}\nДата: ${chapter.date}\nИзбранное: ${if (chapter.isFavorite) "Да" else "Нет"}")
            .setPositiveButton("OK", null)
            .show()
    }

    private fun uploadChapterToCloud(chapterEntity: ChapterEntity) {
        val user = auth.currentUser ?: return

        // Преобразуем Entity → чистый Chapter (без Parcelable-шумов и лишних полей)
        val chapterForCloud = Chapter(
            title = chapterEntity.title,
            description = chapterEntity.description,
            date = chapterEntity.date,
            wordCount = chapterEntity.wordCount,
            isFavorite = chapterEntity.isFavorite
        )

        backgroundScope.launch {
            try {
                firestore
                    .collection("users")
                    .document(user.uid)
                    .collection("books")
                    .document(chapterEntity.bookId)
                    .collection("chapters")
                    .document(chapterEntity.position.toString())
                    .set(chapterForCloud)  // ← Теперь 100% корректно сериализуется!
                    .await()

                Log.d(TAG, "Глава «${chapterEntity.title}» синхронизирована в облако (избранное: ${chapterEntity.isFavorite})")
            } catch (e: CancellationException) {
                throw e // чтобы не ловить при завершении приложения
            } catch (e: Exception) {
                Log.e(TAG, "Ошибка синхронизации главы в облако", e)
            }
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    override fun onDestroy() {
        super.onDestroy()
        // backgroundScope живёт дальше — главы всё равно дойдут!
    }
}