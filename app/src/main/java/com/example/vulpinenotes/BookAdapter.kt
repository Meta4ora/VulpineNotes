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

        // обложка
        if (book.coverUri != null) {
            Glide.with(context)
                .load(book.coverUri)
                .placeholder(R.drawable.book_vector_placeholder)
                .error(R.drawable.book_vector_placeholder)
                .into(holder.cover)
        } else {
            holder.cover.setImageResource(R.drawable.book_vector_placeholder)
        }

        holder.title.text = book.title.ifBlank { "Без названия" }
        holder.chaptersCount.text = context.getString(R.string.chapters_count, book.chaptersCount)

        holder.itemView.setOnClickListener { onBookClick(book) }
        holder.menuButton.setOnClickListener { showPopupMenu(it, book, position) }
    }

    private fun showPopupMenu(view: View, book: Book, position: Int) {
        val popup = PopupMenu(context, view)
        popup.inflate(R.menu.book_context_menu)

        // если хочешь отдельный пункт "Удалить полностью" — раскомментировать надо в xml
        // popup.menu.findItem(R.id.action_delete_permanently)?.isVisible = book.cloudSynced

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
                // если надо будет добавить в меню:
                // R.id.action_delete_permanently -> { deleteBookEverywhere(book, position); true }
                else -> false
            }
        }
        popup.show()
    }

    //  удаление только локально
    private fun showDeleteLocalDialog(book: Book, position: Int) {
        MaterialAlertDialogBuilder(context)
            .setTitle("Удалить с устройства?")
            .setMessage("Книга «${book.title}» будет удалена только с этого телефона.\n\nВ облаке она останется доступна на других устройствах.")
            .setPositiveButton("Удалить") { _, _ ->
                deleteBookLocally(book, position)
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun deleteBookLocally(book: Book, position: Int) {
        (context as? MainActivity)?.lifecycleScope?.launch(Dispatchers.IO) {
            try {
                val dao = AppDatabase.getDatabase(context).bookDao()

                // 1. удаляем из Room
                dao.deleteById(book.id)

                // 2. удаляем обложку с диска
                book.coverUri?.let { uri ->
                    if (uri.scheme == "file") {
                        File(uri.path!!).takeIf { it.exists() }?.delete()
                    }
                }

                withContext(Dispatchers.Main) {
                    // удаляем из списка и обновляем UI
                    books.removeAt(position)
                    notifyItemRemoved(position)
                    notifyItemRangeChanged(position, books.size)

                    Toast.makeText(context, "Книга удалена с устройства", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "Ошибка при удалении", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    // удаление из облака и лоркально одновременно
    // надо добавить в book_context_menu.xml пункт с id action_delete_permanently
    // пока убрал за ненадобностью
    /*
    private fun deleteBookEverywhere(book: Book, position: Int) {
        (context as? MainActivity)?.lifecycleScope?.launch(Dispatchers.IO) {
            val dao = AppDatabase.getDatabase(context).bookDao()
            dao.deleteById(book.id)

            book.coverUri?.path?.let { File(it).delete() }

            FirebaseAuth.getInstance().currentUser?.let { user ->
                FirebaseFirestore.getInstance()
                    .collection("users").document(user.uid)
                    .collection("books").document(book.id)
                    .delete()
            }

            withContext(Dispatchers.Main) {
                books.removeAt(position)
                notifyItemRemoved(position)
                notifyItemRangeChanged(position, books.size)
                Toast.makeText(context, "Книга удалена полностью", Toast.LENGTH_LONG).show()
            }
        }
    }
    */

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