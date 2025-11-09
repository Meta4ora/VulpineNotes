// BookAdapter.kt — без изменений
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

class BookAdapter(
    private val books: MutableList<Book>,
    private val context: Context,
    private val onShowInfo: (Book) -> Unit,
    private val onEditBook: (Book, Int) -> Unit,
    // НОВЫЙ обработчик клика по книге
    private val onBookClick: (Book) -> Unit
) : RecyclerView.Adapter<BookAdapter.BookViewHolder>() {

    class BookViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val title: TextView = itemView.findViewById(R.id.book_title)
        val cover: ImageView = itemView.findViewById(R.id.book_cover)
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

        if (book.coverUri != null) {
            holder.cover.setImageURI(Uri.parse(book.coverUri))
        } else {
            holder.cover.setImageResource(R.drawable.book_cover_placeholder)
        }
        holder.cover.visibility = View.VISIBLE

        // Обработка клика по кнопке меню
        holder.menuButton.setOnClickListener {
            showContextMenu(it, book, position)
        }

        // НОВАЯ Обработка клика по всему элементу (кроме меню)
        holder.itemView.setOnClickListener {
            onBookClick(book)
        }
    }

    private fun showContextMenu(view: View, book: Book, position: Int) {
        val popup = PopupMenu(context, view)
        popup.menuInflater.inflate(R.menu.book_context_menu, popup.menu)
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

    fun updateBook(position: Int, book: Book) {
        books[position] = book
        notifyItemChanged(position)
    }
}