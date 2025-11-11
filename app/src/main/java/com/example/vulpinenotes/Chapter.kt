package com.example.vulpinenotes

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

@Parcelize
data class Chapter(
    val title: String,
    val description: String = "",
    val date: String,
    val wordCount: Int,
    var isFavorite: Boolean = false
) : Parcelable