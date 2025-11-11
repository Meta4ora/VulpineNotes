// ChapterAdapter.kt
package com.example.vulpinenotes

import android.content.Context
import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.widget.PopupMenu
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView

class ChapterAdapter(
    private val chapters: MutableList<Chapter>,
    private val context: Context,
    private val onInfo: (Chapter) -> Unit,
    private val onEdit: (Chapter, Int) -> Unit,
    private val onDelete: (Chapter) -> Unit,
    private val onFavoriteToggle: (Chapter) -> Unit,
    private val onChapterClick: (Chapter, Int) -> Unit  // ← Новый
) : RecyclerView.Adapter<ChapterAdapter.ChapterViewHolder>() {

    inner class ChapterViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val title: TextView = itemView.findViewById(R.id.chapter_title)
        val date: TextView = itemView.findViewById(R.id.chapter_date)
        val wordCount: TextView = itemView.findViewById(R.id.word_count)
        val menuButton: ImageView = itemView.findViewById(R.id.menu_button)
        val bookmarkIcon: ImageView = itemView.findViewById(R.id.bookmark_icon)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ChapterViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_chapter, parent, false)
        return ChapterViewHolder(view)
    }

    override fun onBindViewHolder(holder: ChapterViewHolder, position: Int) {
        val chapter = chapters[position]

        holder.title.text = chapter.title
        holder.date.text = chapter.date
        holder.wordCount.text = "${chapter.wordCount} слов"

        // Закладка
        val tintColor = if (chapter.isFavorite) {
            ContextCompat.getColor(context, R.color.gold)
        } else {
            ContextCompat.getColor(context, R.color.outline)
        }
        holder.bookmarkIcon.imageTintList = ColorStateList.valueOf(tintColor)
        holder.bookmarkIcon.setOnClickListener { onFavoriteToggle(chapter) }

        // Меню
        holder.menuButton.setOnClickListener { view ->
            showPopupMenu(view, chapter)
        }

        // Клик по всей карточке - редактор
        holder.itemView.setOnClickListener {
            onChapterClick(chapter, position)
        }
    }

    private fun showPopupMenu(view: View, chapter: Chapter) {
        val popup = PopupMenu(context, view)
        popup.inflate(R.menu.chapter_context_menu)
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_chapter_info -> { onInfo(chapter); true }
                R.id.action_chapter_edit -> {
                    val pos = chapters.indexOf(chapter)
                    if (pos != -1) onEdit(chapter, pos)
                    true
                }
                R.id.action_chapter_delete -> { onDelete(chapter); true }
                else -> false
            }
        }
        popup.show()
    }

    override fun getItemCount() = chapters.size

    fun removeChapter(chapter: Chapter) {
        val index = chapters.indexOf(chapter)
        if (index != -1) {
            chapters.removeAt(index)
            notifyItemRemoved(index)
            notifyItemRangeChanged(index, chapters.size)
        }
    }
}