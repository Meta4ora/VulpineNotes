package com.example.vulpinenotes.data

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

@Parcelize
data class NotificationSettings(
    val isEnabled: Boolean = false,
    val interval: ReminderInterval = ReminderInterval.EVERY_DAY,
    val selectedBookIds: List<String> = emptyList()
) : Parcelable
