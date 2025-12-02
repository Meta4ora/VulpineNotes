package com.example.vulpinenotes

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide

class CloudBookAdapter(
    private val books: MutableList<Book>,
    private val onCloudClick: (Book) -> Unit
) : RecyclerView.Adapter<CloudBookAdapter.VH>() {

    class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val coverImage: ImageView = itemView.findViewById(R.id.iv_cover)
        val titleText: TextView = itemView.findViewById(R.id.tv_title_below)
        val cloudButton: ImageView = itemView.findViewById(R.id.iv_cloud_action)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_book_cloud, parent, false)
        return VH(view)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val book = books[position]

        // Название — теперь только снизу
        holder.titleText.text = book.title.ifBlank { "Без названия" }

        // Обложка
        if (book.coverUri != null) {
            Glide.with(holder.itemView.context)
                .load(book.coverUri)
                .placeholder(R.drawable.book_cover_placeholder)
                .into(holder.coverImage)
        } else {
            holder.coverImage.setImageResource(R.drawable.book_cover_placeholder)
        }

        holder.cloudButton.setImageResource(
            if (book.cloudSynced) R.drawable.ic_cloud_done      // в облаке галочка
            else R.drawable.ic_cloud_upload                     // локально загрузить
        )

        holder.cloudButton.setOnClickListener { onCloudClick(book) }
    }

    override fun getItemCount() = books.size

    fun submitList(newBooks: List<Book>) {
        books.clear()
        books.addAll(newBooks)
        notifyDataSetChanged()
    }
}