// BookAdapter.kt — без изменений
package com.example.vulpinenotes

import android.content.Context
import android.net.Uri
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.widget.PopupMenu
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder

// BookAdapter.kt
class BookAdapter(
    private val books: MutableList<Book>,
    private val context: Context,
    private val onShowInfo: (Book) -> Unit,
    private val onEditBook: (Book, Int) -> Unit,
    private val onBookClick: (Book) -> Unit
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

        // Обложка
        if (book.coverUri != null) {
            holder.cover.setImageURI(Uri.parse(book.coverUri))
        } else {
            holder.cover.setImageResource(R.drawable.book_cover_placeholder)
        }

        // Название
        holder.title.text = book.title

        // Количество глав
        holder.chaptersCount.text = context.getString(R.string.chapters_count, book.chaptersCount)

        // Клик по карточке
        holder.itemView.setOnClickListener {
            onBookClick(book)
        }

        // Кнопка меню (три точки)
        holder.menuButton.setOnClickListener { view ->
            showPopupMenu(view, book, position)
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
                R.id.action_upload -> {
                    showUploadConfirmationDialog(book)
                    true
                }
                R.id.action_delete -> {
                    showDeleteConfirmationDialog(book, position)
                    true
                }
                else -> false
            }
        }
        popup.show()
    }

    private fun showUploadConfirmationDialog(book: Book) {
        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.upload_to_cloud)
            .setMessage(context.getString(R.string.confirm_upload, book.title))
            .setPositiveButton("Да") { _, _ ->
                // TODO: Реальная отправка в облако (Firebase, Google Drive и т.д.)
                Toast.makeText(context, R.string.upload_started, Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Нет") { _, _ ->
                Toast.makeText(context, R.string.upload_cancelled, Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    private fun showDeleteConfirmationDialog(book: Book, position: Int) {
        MaterialAlertDialogBuilder(context)
            .setTitle("Удалить книгу?")
            .setMessage("Книга \"${book.title}\" будет удалена без возможности восстановления.")
            .setPositiveButton("Удалить") { _, _ ->
                books.removeAt(position)
                notifyItemRemoved(position)
                notifyItemRangeChanged(position, books.size)
                Toast.makeText(context, "Книга удалена", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Отмена", null)
            .show()
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