package com.example.vulpinenotes.data

import com.example.vulpinenotes.Chapter
import java.util.UUID

fun Chapter.toEntity(bookId: String, position: Int): ChapterEntity = ChapterEntity(
    chapterId = chapterId.ifBlank { UUID.randomUUID().toString() },
    bookId = bookId,
    position = position,
    title = title,
    description = description,
    content = content,
    date = date,
    wordCount = wordCount,
    isFavorite = isFavorite,
    createdAt = if (createdAt != 0L) createdAt else System.currentTimeMillis(),
    updatedAt = System.currentTimeMillis()
)

fun ChapterEntity.toChapter(): Chapter = Chapter(
    chapterId = chapterId,
    title = title,
    description = description,
    content = content,
    date = date,
    wordCount = wordCount,
    isFavorite = isFavorite,
    position = position,
    createdAt = createdAt,
    updatedAt = updatedAt
)