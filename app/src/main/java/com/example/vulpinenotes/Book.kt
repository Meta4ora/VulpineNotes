package com.example.vulpinenotes
// Book.kt
import android.os.Parcelable
import kotlinx.parcelize.Parcelize

@Parcelize
data class Book(
    val title: String,
    val desc: String,
    val coverUri: String? = null,
    val chaptersCount: Int = 0
) : Parcelable
