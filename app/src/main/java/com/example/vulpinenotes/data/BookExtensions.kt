package com.example.vulpinenotes.data

import android.net.Uri
import com.example.vulpinenotes.Book
import java.io.File

fun BookEntity.toBook(coversDir: File? = null): Book {
    val coverUri = coverPath?.let { path ->
        val file = File(path)
        if (file.exists()) Uri.fromFile(file) else null
    }
    return Book(
        id = id,
        title = title,
        desc = desc,
        coverUri = coverUri,
        chaptersCount = chaptersCount,
        updatedAt = updatedAt,
        cloudSynced = cloudSynced
    )
}