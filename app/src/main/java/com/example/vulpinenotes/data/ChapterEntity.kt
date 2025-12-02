package com.example.vulpinenotes.data
import android.os.Parcelable
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import kotlinx.parcelize.Parcelize
@Parcelize
@Entity(
    tableName = "chapters",
    primaryKeys = ["bookId", "position"],
    foreignKeys = [ForeignKey(
        entity = BookEntity::class,
        parentColumns = ["id"],
        childColumns = ["bookId"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index("bookId")]
)
data class ChapterEntity(
    val bookId: String = "",
    val position: Int = 0,
    val title: String = "",
    val description: String = "",
    val date: String = "",
    val wordCount: Int = 0,
    val isFavorite: Boolean = false,
    val updatedAt: Long = System.currentTimeMillis()
) : Parcelable {
    constructor() : this("", 0, "", "", "", 0, false, System.currentTimeMillis())
}