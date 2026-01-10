package com.example.vulpinenotes.data
import androidx.room.Entity
import androidx.room.PrimaryKey
@Entity(tableName = "books")
data class BookEntity @JvmOverloads constructor(
    @PrimaryKey val id: String = "",
    val title: String = "Без названия",
    val desc: String = "Нет описания",
    val coverPath: String? = null,
    val chaptersCount: Int = 0,
    val updatedAt: Long = System.currentTimeMillis(),
    val createdAt: Long = System.currentTimeMillis(),
    val cloudSynced: Boolean = false
)