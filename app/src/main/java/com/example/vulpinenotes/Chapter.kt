package com.example.vulpinenotes

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

@Parcelize
data class Chapter(
    val chapterId: String,
    val title: String,
    val description: String,
    val content: String,
    val date: String,
    val wordCount: Int,
    val isFavorite: Boolean,
    val position: Int,
    val createdAt: Long,
    val updatedAt: Long
) : Parcelable