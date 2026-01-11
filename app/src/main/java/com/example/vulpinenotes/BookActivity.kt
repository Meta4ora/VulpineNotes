package com.example.vulpinenotes

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.activity.addCallback
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
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
import com.example.vulpinenotes.data.toEntity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
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

    private val backgroundScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val editChapterLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == ChapterEditActivity.RESULT_UPDATED_CHAPTER) {
            val updated = result.data?.getParcelableExtra<Chapter>(ChapterEditActivity.EXTRA_CHAPTER)
            val pos = result.data?.getIntExtra(ChapterEditActivity.EXTRA_CHAPTER_POSITION, -1) ?: -1
            if (updated != null && pos != -1) {
                chapters[pos] = updated
                chapterAdapter.notifyItemChanged(pos)

                if (book.cloudSynced) {
                    lifecycleScope.launch {
                        val entity = database.chapterDao()
                            .getChaptersForBookSync(book.id)
                            .firstOrNull { it.chapterId == updated.chapterId } ?: return@launch

                        uploadChapterToCloud(entity)
                    }
                }
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

        onBackPressedDispatcher.addCallback(this) {
            finish()
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.book_menu, menu)
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
            onEdit = { chapter, _ -> showEditChapterDialog(chapter) },
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
            database.chapterDao()
                .getChaptersForBook(book.id)
                .collect { entities ->
                    chapters.clear()
                    chapters.addAll(entities.map { it.toChapter() }
                        .sortedBy { it.position }
                    )
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
                        val now = System.currentTimeMillis()

                        val newChapter = Chapter(
                            chapterId = UUID.randomUUID().toString(),
                            title = title,
                            description = "",
                            content = "",
                            date = SimpleDateFormat("dd.MM.yy", Locale.getDefault()).format(Date()),
                            wordCount = 0,
                            isFavorite = false,
                            position = count,
                            createdAt = now,
                            updatedAt = now
                        )

                        val entity = newChapter.toEntity(
                            bookId = book.id,
                            position = count
                        )
                        database.bookDao().updateChaptersCount(book.id, count + 1)
                        database.chapterDao().insertChapter(entity)

                        if (book.cloudSynced) {
                            uploadChapterToCloud(entity)
                        }
                    }
                }
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun deleteChapter(chapter: Chapter) {
        MaterialAlertDialogBuilder(this)
            .setTitle("Удалить главу?")
            .setMessage("«${chapter.title}» будет безвозвратно удалена.\n\nЭто действие нельзя отменить.")
            .setPositiveButton("Удалить") { _, _ ->
                lifecycleScope.launch {
                    try {
                        val entity = database.chapterDao()
                            .getChaptersForBookSync(book.id)
                            .firstOrNull { it.chapterId == chapter.chapterId }
                            ?: return@launch

                        database.chapterDao().deleteChapter(entity)

                        val newCount = database.chapterDao().getChapterCount(book.id)

                        database.bookDao().updateChaptersCount(book.id, newCount)

                        if (book.cloudSynced && auth.currentUser != null) {
                            backgroundScope.launch {
                                try {
                                    firestore.collection("users")
                                        .document(auth.currentUser!!.uid)
                                        .collection("books")
                                        .document(book.id)
                                        .collection("chapters")
                                        .document(chapter.chapterId)
                                        .delete()
                                        .await()
                                    Log.d(TAG, "Глава удалена из облака")
                                } catch (e: Exception) {
                                    Log.e(TAG, "Ошибка удаления главы из облака", e)
                                    // Можно показать тост об ошибке, но не блокировать UI
                                }
                            }
                        }

                        reorderPositionsAfterDeletion(entity.position)

                        val idx = chapters.indexOfFirst { it.chapterId == chapter.chapterId }
                        if (idx != -1) {
                            chapters.removeAt(idx)
                            chapterAdapter.notifyItemRemoved(idx)
                            chapterAdapter.notifyItemRangeChanged(idx, chapters.size)
                        }

                        // 8. Уведомление
                        Toast.makeText(this@BookActivity, "Глава «${chapter.title}» удалена", Toast.LENGTH_SHORT).show()

                    } catch (e: Exception) {
                        Log.e(TAG, "Ошибка при удалении главы", e)
                        Toast.makeText(this@BookActivity, "Ошибка удаления главы", Toast.LENGTH_LONG).show()
                    }
                }
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private suspend fun reorderPositionsAfterDeletion(deletedPosition: Int) {
        val affected = database.chapterDao()
            .getChaptersForBookSync(book.id)
            .filter { it.position > deletedPosition }

        affected.forEach {
            val updated = it.copy(
                position = it.position - 1,
                updatedAt = System.currentTimeMillis()
            )
            database.chapterDao().insertChapter(updated)

            if (book.cloudSynced) {
                uploadChapterToCloud(updated)
            }
        }
    }

    private fun toggleFavorite(chapter: Chapter) {
        lifecycleScope.launch {
            val entity = database.chapterDao().getChaptersForBookSync(book.id)
                .firstOrNull { it.chapterId == chapter.chapterId } ?: return@launch

            val updated = entity.copy(
                isFavorite = !entity.isFavorite,
                updatedAt = System.currentTimeMillis()
            )
            database.chapterDao().insertChapter(updated)

            if (book.cloudSynced) {
                uploadChapterToCloud(updated)
            }

            val idx = chapters.indexOfFirst { it.chapterId == chapter.chapterId }
            if (idx != -1) {
                chapters[idx] = updated.toChapter()
                chapterAdapter.notifyItemChanged(idx)
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

    private fun showEditChapterDialog(chapter: Chapter) {
        val view = layoutInflater.inflate(R.layout.add_chapter_dialog, null)
        val editTitle = view.findViewById<TextInputEditText>(R.id.edit_title)
        val editDescription = view.findViewById<TextInputEditText>(R.id.edit_description)

        editTitle.setText(chapter.title)
        editDescription?.setText(chapter.description)  // ← description

        MaterialAlertDialogBuilder(this)
            .setTitle("Редактировать главу")
            .setView(view)
            .setPositiveButton("Сохранить") { _, _ ->
                val newTitle = editTitle.text.toString().trim()
                val newDescription = editDescription?.text.toString().trim() ?: ""

                if (newTitle.isNotBlank()) {
                    lifecycleScope.launch {
                        val entity = database.chapterDao()
                            .getChaptersForBookSync(book.id)
                            .firstOrNull { it.chapterId == chapter.chapterId } ?: return@launch

                        val updated = entity.copy(
                            title = newTitle,
                            description = newDescription,
                            updatedAt = System.currentTimeMillis()
                        )
                        database.chapterDao().insertChapter(updated)

                        if (book.cloudSynced) {
                            uploadChapterToCloud(updated)
                        }

                        val idx = chapters.indexOfFirst { it.chapterId == chapter.chapterId }
                        if (idx != -1) {
                            chapters[idx] = updated.toChapter()
                            chapterAdapter.notifyItemChanged(idx)
                        }
                    }
                } else {
                    Toast.makeText(this, "Название не может быть пустым", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Отмена", null)
            .show()
    }


    private fun setupDragAndDrop() {
        val helper = ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(
            ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0
        ) {

            private var fromPosition = -1
            private var toPosition = -1

            override fun onMove(
                rv: RecyclerView,
                vh: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder
            ): Boolean {
                val from = vh.adapterPosition
                val to = target.adapterPosition

                if (fromPosition == -1) fromPosition = from
                toPosition = to

                Collections.swap(chapters, from, to)
                chapterAdapter.notifyItemMoved(from, to)

                return true
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {}

            override fun clearView(rv: RecyclerView, vh: RecyclerView.ViewHolder) {
                super.clearView(rv, vh)

                if (fromPosition != -1 && toPosition != -1 && fromPosition != toPosition) {
                    val chaptersCopy = chapters.toList()
                    lifecycleScope.launch {
                        chaptersCopy.forEachIndexed { index, chapter ->
                            val entity = database.chapterDao()
                                .getChaptersForBookSync(book.id)
                                .firstOrNull { it.chapterId == chapter.chapterId } ?: return@forEachIndexed

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
                }

                fromPosition = -1
                toPosition = -1
            }
        })

        helper.attachToRecyclerView(recyclerView)
    }

    private fun showChapterInfo(chapter: Chapter) {
        val df = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault())
        val created = if (chapter.createdAt != 0L) df.format(Date(chapter.createdAt)) else "—"
        val updated = if (chapter.updatedAt != 0L) df.format(Date(chapter.updatedAt)) else "—"

        val view = layoutInflater.inflate(R.layout.dialog_chapter_info, null)
        view.findViewById<TextView>(R.id.infoTitle).text = chapter.title
        view.findViewById<TextView>(R.id.infoDescription).text = chapter.description.ifBlank { "Нет описания" }
        view.findViewById<TextView>(R.id.infoWordCount).text = "Слов: ${chapter.wordCount}"
        view.findViewById<TextView>(R.id.infoDate).text = "Дата: ${chapter.date}"
        view.findViewById<TextView>(R.id.infoCreated).text = "Создано: $created"
        view.findViewById<TextView>(R.id.infoUpdated).text = "Изменено: $updated"
        view.findViewById<TextView>(R.id.infoFavorite).text = "Избранное: ${if (chapter.isFavorite) "Да" else "Нет"}"

        MaterialAlertDialogBuilder(this)
            .setTitle("Информация о главе")
            .setView(view)
            .setPositiveButton("Закрыть", null)
            .setNeutralButton("Редактировать") { _, _ ->
                openChapterEditor(chapter, chapters.indexOf(chapter))
            }
            .show()
    }

    private fun uploadChapterToCloud(chapterEntity: ChapterEntity) {
        val user = auth.currentUser ?: return

        val chapterForCloud = mapOf(
            "chapterId" to chapterEntity.chapterId,
            "title" to chapterEntity.title,
            "description" to chapterEntity.description,
            "date" to chapterEntity.date,
            "wordCount" to chapterEntity.wordCount,
            "isFavorite" to chapterEntity.isFavorite,
            "position" to chapterEntity.position,
            "createdAt" to chapterEntity.createdAt,
            "updatedAt" to chapterEntity.updatedAt
        )

        backgroundScope.launch {
            try {
                firestore
                    .collection("users")
                    .document(user.uid)
                    .collection("books")
                    .document(chapterEntity.bookId)
                    .collection("chapters")
                    .document(chapterEntity.chapterId)
                    .set(chapterForCloud)
                    .await()

                Log.d(TAG, "Глава «${chapterEntity.title}» синхронизирована в облако")
            } catch (e: CancellationException) {
                throw e
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
        // backgroundScope живёт дальше — главы всё равно будут синхронизированы
    }
}
