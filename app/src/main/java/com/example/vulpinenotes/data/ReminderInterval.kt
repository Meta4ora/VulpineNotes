package com.example.vulpinenotes.data

enum class ReminderInterval(val title: String, val millis: Long) {
    EVERY_MINUTE("Каждую минуту (debug)", 60_000L),
    EVERY_DAY("Каждый день", 24 * 60 * 60 * 1000L),
    EVERY_3_DAYS("Каждые 3 дня", 3 * 24 * 60 * 60 * 1000L),
    EVERY_WEEK("Каждую неделю", 7 * 24 * 60 * 60 * 1000L)
}
