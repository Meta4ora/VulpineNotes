// BookAdapter.kt
package com.example.vulpinenotes

import android.content.Context
import android.net.Uri
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.widget.PopupMenu
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder

class BookAdapter(
    private val books: MutableList<Book>,
    private val context: Context,
    private val onShowInfo: (Book) -> Unit // Колбэк
) : RecyclerView.Adapter<BookAdapter.BookViewHolder>() {

    class BookViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val title: TextView = itemView.findViewById(R.id.book_title)
        val menuButton: ImageView = itemView.findViewById(R.id.book_menu_button)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): BookViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_book_grid, parent, false)
        return BookViewHolder(view)
    }

    override fun onBindViewHolder(holder: BookViewHolder, position: Int) {
        val book = books[position]
        holder.title.text = book.title

        // ДОБАВЬ ЭТО: отображение обложки
        val coverImage = holder.itemView.findViewById<ImageView>(R.id.book_cover)
        if (book.coverUri != null) {
            coverImage.setImageURI(Uri.parse(book.coverUri))
            coverImage.visibility = View.VISIBLE
        } else {
            coverImage.setImageResource(R.drawable.book_cover_placeholder)
            coverImage.visibility = View.VISIBLE
        }

        holder.menuButton.setOnClickListener {
            showContextMenu(it, book)
        }
    }

    private fun showContextMenu(view: View, book: Book) {
        val popup = PopupMenu(context, view)
        popup.menuInflater.inflate(R.menu.book_context_menu, popup.menu)
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_info -> {
                    onShowInfo(book) // Вызываем колбэк
                    true
                }
                else -> false
            }
        }
        popup.show()
    }

    override fun getItemCount() = books.size

    fun addBook(book: Book) {
        books.add(book)
        notifyItemInserted(books.size - 1)
    }
}