package com.example.vulpinenotes
// Book.kt
import android.os.Parcelable
import kotlinx.parcelize.Parcelize

@Parcelize
data class Book(
    val id: String = "",
    val title: String = "",
    val desc: String = "",
    val coverUri: String? = null,  // ← Должно быть String?
    val chaptersCount: Int = 0,
    val updatedAt: Long = 0
) : Parcelable
