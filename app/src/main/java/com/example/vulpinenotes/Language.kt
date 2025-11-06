package com.example.vulpinenotes

data class Language(
    val code: String,
    val name: String,
    val flagResId: Int
) {
    override fun toString(): String {
        return name
    }
}
