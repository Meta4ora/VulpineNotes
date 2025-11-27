package com.example.vulpinenotes.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

@Entity(
    tableName = "chapters",
    primaryKeys = ["bookId", "position"],
    foreignKeys = [ForeignKey(
        entity = BookEntity::class,
        parentColumns = ["id"],
        childColumns = ["bookId"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index("bookId")]
)
data class ChapterEntity(
    val bookId: String,
    val position: Int,  // для сохранения порядка

    val title: String,
    val description: String,
    val date: String,
    val wordCount: Int,
    val isFavorite: Boolean = false
)