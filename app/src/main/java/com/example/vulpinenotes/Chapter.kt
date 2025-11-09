package com.example.vulpinenotes

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

@Parcelize
data class Chapter(
    val title: String,
    val date: String,
    val wordCount: Int
) : Parcelable