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
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.bumptech.glide.Glide

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

        // Используем Glide — безопасно загружает локальные и удалённые URI
        if (!book.coverUri.isNullOrBlank()) {
            try {
                Glide.with(context)
                    .load(book.coverUri)
                    .placeholder(R.drawable.book_cover_placeholder)
                    .into(holder.cover)
            } catch (e: Exception) {
                holder.cover.setImageResource(R.drawable.book_cover_placeholder)
            }
        } else {
            holder.cover.setImageResource(R.drawable.book_cover_placeholder)
        }

        holder.title.text = book.title
        holder.chaptersCount.text = context.getString(R.string.chapters_count, book.chaptersCount)

        holder.itemView.setOnClickListener { onBookClick(book) }
        holder.menuButton.setOnClickListener { view -> showPopupMenu(view, book, position) }
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
                deleteBookFromFirestore(book, position)
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun deleteBookFromFirestore(book: Book, position: Int) {
        val user = FirebaseAuth.getInstance().currentUser ?: return
        val db = FirebaseFirestore.getInstance()

        db.collection("users").document(user.uid).collection("books").document(book.id)
            .delete()
            .addOnSuccessListener {
                // Удаление обработает listener в MainActivity; можно показать тост
                Toast.makeText(context, "Книга удалена", Toast.LENGTH_SHORT).show()
            }
            .addOnFailureListener { e ->
                Toast.makeText(context, "Ошибка: ${e.message}", Toast.LENGTH_SHORT).show()
            }
    }

    override fun getItemCount() = books.size

    // Эти методы используются редко, но оставлены
    fun addBook(book: Book) {
        books.add(book)
        notifyItemInserted(books.size - 1)
    }

    fun updateBook(position: Int, book: Book) {
        books[position] = book
        notifyItemChanged(position)
    }
}
