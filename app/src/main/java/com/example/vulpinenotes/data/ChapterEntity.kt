package com.example.vulpinenotes.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "chapters",
    indices = [
        Index("bookId"),
        Index("position")
    ]
)
data class ChapterEntity(
    @PrimaryKey val chapterId: String,
    val bookId: String,
    val position: Int,
    val title: String,
    val description: String,
    val content: String,
    val date: String,
    val wordCount: Int,
    val isFavorite: Boolean,
    val createdAt: Long,
    val updatedAt: Long
)