package com.example.vulpinenotes

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.widget.PopupMenu
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.example.vulpinenotes.data.AppDatabase
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.*
import java.io.File

class BookAdapter(
    private val books: MutableList<Book>,
    private val context: Context,
    private val onShowInfo: (Book) -> Unit,
    private val onEditBook: (Book, Int) -> Unit,
    private val onBookClick: (Book) -> Unit,
    private val onExportBook: (Book) -> Unit  // Добавлен callback для экспорта
) : RecyclerView.Adapter<BookAdapter.BookViewHolder>() {

    inner class BookViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val cover: ImageView = itemView.findViewById(R.id.book_cover)
        val title: TextView = itemView.findViewById(R.id.book_title)
        val chaptersCount: TextView = itemView.findViewById(R.id.chapters_count)
        val menuButton: ImageView = itemView.findViewById(R.id.book_menu_button)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): BookViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_book_grid, parent, false)
        return BookViewHolder(view)
    }

    override fun onBindViewHolder(holder: BookViewHolder, position: Int) {
        val book = books[position]

        // обложка
        if (book.coverUri != null) {
            Glide.with(context)
                .load(book.coverUri)
                .placeholder(R.drawable.book_vector_placeholder)
                .error(R.drawable.book_vector_placeholder)
                .diskCacheStrategy(com.bumptech.glide.load.engine.DiskCacheStrategy.NONE)
                .skipMemoryCache(true)
                .into(holder.cover)
        } else {
            holder.cover.setImageResource(R.drawable.book_vector_placeholder)
        }

        holder.title.text = book.title.ifBlank { "Без названия" }
        holder.chaptersCount.text = "${book.chaptersCount} ${book.chaptersCount.chapterWordForm()}"

        holder.itemView.setOnClickListener { onBookClick(book) }
        holder.menuButton.setOnClickListener { showPopupMenu(it, book, position) }
    }

    fun Int.chapterWordForm(): String {
        val count = this % 100
        val lastDigit = count % 10

        return when {
            count in 11..14 -> "глав"
            lastDigit == 1 -> "глава"
            lastDigit in 2..4 -> "главы"
            else -> "глав"
        }
    }

    private fun showPopupMenu(view: View, book: Book, position: Int) {
        val popup = PopupMenu(context, view)
        popup.inflate(R.menu.book_context_menu)

        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_info -> {
                    onShowInfo(book)
                    true
                }
                R.id.action_edit -> {
                    onEditBook(book, position)
                    true
                }
                R.id.action_delete -> {
                    showDeleteLocalDialog(book, position)
                    true
                }
                R.id.action_export -> {
                    onExportBook(book)  // Вызываем callback для экспорта
                    true
                }
                else -> false
            }
        }
        popup.show()
    }

    private fun showDeleteLocalDialog(book: Book, position: Int) {
        MaterialAlertDialogBuilder(context)
            .setTitle("Удалить с устройства?")
            .setMessage("Книга «${book.title}» будет удалена только с этого телефона.\n\nВ облаке она останется доступна на других устройствах.")
            .setPositiveButton("Удалить") { _, _ ->
                deleteBookLocally(book)
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun deleteBookLocally(book: Book) {
        (context as? MainActivity)?.lifecycleScope?.launch(Dispatchers.IO) {
            try {
                val dao = AppDatabase.getDatabase(context).bookDao()
                dao.deleteById(book.id)

                book.coverUri?.let { uri ->
                    if (uri.scheme == "file") {
                        File(uri.path!!).takeIf { it.exists() }?.delete()
                    }
                }

                withContext(Dispatchers.Main) {
                    val index = books.indexOfFirst { it.id == book.id }
                    if (index != -1) {
                        books.removeAt(index)
                        notifyItemRemoved(index)
                    }
                    Toast.makeText(context, "Книга удалена с устройства", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "Ошибка при удалении", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    override fun getItemCount() = books.size

    fun addBook(book: Book) {
        books.add(book)
        notifyItemInserted(books.size - 1)
    }

    fun updateBook(position: Int, book: Book) {
        books[position] = book
        notifyItemChanged(position)
    }
}