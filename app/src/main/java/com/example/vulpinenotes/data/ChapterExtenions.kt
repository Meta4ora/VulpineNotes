package com.example.vulpinenotes.data
import com.example.vulpinenotes.Chapter
import java.text.SimpleDateFormat
import java.util.*
fun ChapterEntity.toChapter(): Chapter = Chapter(
    title = this.title,
    description = this.description,
    date = this.date,
    wordCount = this.wordCount,
    isFavorite = this.isFavorite
)
fun Chapter.toEntity(bookId: String, position: Int): ChapterEntity = ChapterEntity(
    bookId = bookId,
    position = position,
    title = this.title,
    description = this.description,
    date = this.date,
    wordCount = this.wordCount,
    isFavorite = this.isFavorite,
    updatedAt = System.currentTimeMillis()
)