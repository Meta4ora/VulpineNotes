package com.example.vulpinenotes
// Book.kt
import android.net.Uri
import android.os.Parcelable
import com.example.vulpinenotes.data.BookEntity
import kotlinx.parcelize.Parcelize
import java.io.File

@Parcelize
data class Book(
    val id: String = "",
    val title: String = "",
    val desc: String = "",
    val coverUri: Uri? = null,
    val chaptersCount: Int = 0,
    val updatedAt: Long = 0,
    val createdAt: Long = System.currentTimeMillis(),
    val cloudSynced: Boolean = false
) : Parcelable
