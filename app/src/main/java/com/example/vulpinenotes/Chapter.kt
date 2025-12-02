package com.example.vulpinenotes

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

@Parcelize
data class Chapter(
    val title: String = "",
    val description: String = "",
    val date: String = "",
    val wordCount: Int = 0,
    var isFavorite: Boolean = false
) : Parcelable {
    // ← ЭТОТ КОНСТРУКТОР РЕШИТ ВСЁ!
    constructor() : this("", "", "", 0, false)
}