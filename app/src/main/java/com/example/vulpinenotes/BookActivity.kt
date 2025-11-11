// BookActivity.kt
package com.example.vulpinenotes

import android.os.Build
import android.os.Bundle
import android.widget.ImageButton
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.appcompat.widget.Toolbar
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText

class BookActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_BOOK = "com.example.vulpinenotes.EXTRA_BOOK"
    }

    private lateinit var chapterAdapter: ChapterAdapter
    private val chapters = mutableListOf<Chapter>()
    private lateinit var recyclerView: RecyclerView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_book)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        val toolbar = findViewById<Toolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        val book: Book? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(EXTRA_BOOK, Book::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(EXTRA_BOOK)
        }

        book?.let { b ->
            supportActionBar?.title = b.title

            // Тестовые главы
            chapters.addAll(listOf(
                Chapter("Глава 1: Начало пути", "Первая встреча с героем", "03.11.25", 225, false),
                Chapter("Глава 2: Тёмный лес", "Опасности и тайны", "04.11.25", 512, true),
                Chapter("Глава 3: Встреча с драконом", "Кульминация", "05.11.25", 890, false)
            ))

            recyclerView = findViewById(R.id.chapters_recycler_view)
            chapterAdapter = ChapterAdapter(
                chapters,
                this,
                onInfo = { chapter -> showChapterInfo(chapter) },
                onEdit = { chapter, pos -> showEditChapterDialog(chapter, pos) },
                onDelete = { chapter -> chapterAdapter.removeChapter(chapter) },
                onFavoriteToggle = { chapter -> toggleFavoriteWithAnimation(chapter) }
            )

            recyclerView.adapter = chapterAdapter
            recyclerView.layoutManager = LinearLayoutManager(this)

            setupDragAndDrop()
        } ?: finish()

        findViewById<ImageButton>(R.id.fab_add_chapter).setOnClickListener {
            showAddChapterDialog()
        }
    }

    private fun setupDragAndDrop() {
        val itemTouchHelper = ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(
            ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0
        ) {
            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder
            ): Boolean {
                val from = viewHolder.adapterPosition
                val to = target.adapterPosition

                val movedChapter = chapters.removeAt(from)
                chapters.add(to, movedChapter)

                chapterAdapter.notifyItemMoved(from, to)
                return true
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {}

            override fun onSelectedChanged(viewHolder: RecyclerView.ViewHolder?, actionState: Int) {
                super.onSelectedChanged(viewHolder, actionState)
                viewHolder?.itemView?.apply {
                    elevation = if (actionState == ItemTouchHelper.ACTION_STATE_DRAG) 16f else 4f
                    alpha = if (actionState == ItemTouchHelper.ACTION_STATE_DRAG) 0.9f else 1f
                }
            }

            override fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
                super.clearView(recyclerView, viewHolder)
                sortFavoritesToTop()
            }
        })
        itemTouchHelper.attachToRecyclerView(recyclerView)
    }

    private fun toggleFavoriteWithAnimation(chapter: Chapter) {
        val index = chapters.indexOf(chapter)
        if (index == -1) return

        val updatedChapter = chapter.copy(isFavorite = !chapter.isFavorite)
        chapters[index] = updatedChapter
        chapterAdapter.notifyItemChanged(index)

        if (updatedChapter.isFavorite && index != 0) {
            // Перемещаем наверх с анимацией
            chapters.removeAt(index)
            chapters.add(0, updatedChapter)
            chapterAdapter.notifyItemMoved(index, 0)
            recyclerView.scrollToPosition(0)
        } else if (!updatedChapter.isFavorite) {
            sortFavoritesToTop()
        }
    }


    private fun sortFavoritesToTop() {
        val sorted = chapters.sortedByDescending { it.isFavorite }.toMutableList()
        if (sorted != chapters) {
            chapters.clear()
            chapters.addAll(sorted)
            chapterAdapter.notifyDataSetChanged()
            recyclerView.scrollToPosition(0)
        }
    }

    private fun showChapterInfo(chapter: Chapter) {
        MaterialAlertDialogBuilder(this)
            .setTitle(chapter.title)
            .setMessage("""
                Описание: ${chapter.description.ifBlank { "Нет описания" }}
                Дата: ${chapter.date}
                Слов: ${chapter.wordCount}
                Избранное: ${if (chapter.isFavorite) "Да" else "Нет"}
            """.trimIndent())
            .setPositiveButton("OK", null)
            .show()
    }

    private fun showAddChapterDialog() {
        val dialogView = layoutInflater.inflate(R.layout.add_chapter_dialog, null)
        val editTitle = dialogView.findViewById<TextInputEditText>(R.id.edit_title)
        val editDesc = dialogView.findViewById<TextInputEditText>(R.id.edit_description)

        MaterialAlertDialogBuilder(this)
            .setTitle("Новая глава")
            .setView(dialogView)
            .setPositiveButton("Добавить") { _, _ ->
                val title = editTitle.text.toString().trim()
                if (title.isNotBlank()) {
                    val newChapter = Chapter(
                        title = title,
                        description = editDesc.text.toString().trim(),
                        date = "11.11.25",
                        wordCount = 0
                    )
                    chapters.add(newChapter)
                    chapterAdapter.notifyItemInserted(chapters.size - 1)
                }
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun showEditChapterDialog(chapter: Chapter, position: Int) {
        val dialogView = layoutInflater.inflate(R.layout.add_chapter_dialog, null)
        val editTitle = dialogView.findViewById<TextInputEditText>(R.id.edit_title)
        val editDesc = dialogView.findViewById<TextInputEditText>(R.id.edit_description)

        editTitle.setText(chapter.title)
        editDesc.setText(chapter.description)

        MaterialAlertDialogBuilder(this)
            .setTitle("Редактировать главу")
            .setView(dialogView)
            .setPositiveButton("Сохранить") { _, _ ->
                val title = editTitle.text.toString().trim()
                if (title.isNotBlank()) {
                    chapters[position] = chapter.copy(
                        title = title,
                        description = editDesc.text.toString().trim()
                    )
                    chapterAdapter.notifyItemChanged(position)
                }
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}