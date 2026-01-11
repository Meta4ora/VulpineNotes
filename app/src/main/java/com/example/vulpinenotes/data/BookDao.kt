package com.example.vulpinenotes.data
import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow
@Dao
interface BookDao {
    @Query("SELECT * FROM books ORDER BY updatedAt DESC")
    fun getAllBooks(): Flow<List<BookEntity>>
    @Query("SELECT * FROM books WHERE id = :id LIMIT 1")
    suspend fun getBookById(id: String): BookEntity?
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertBook(book: BookEntity)
    @Delete
    suspend fun deleteBook(book: BookEntity)
    @Update
    suspend fun updateBook(book: BookEntity)
    @Query("DELETE FROM books WHERE id = :bookId")
    suspend fun deleteById(bookId: String)
    @Query("SELECT DISTINCT * FROM books")
    fun getAllBooksSync(): List<BookEntity>
    @Query("UPDATE books SET cloudSynced = :state WHERE id = :id")
    suspend fun updateCloudState(id: String, state: Boolean)

    @Query("SELECT COUNT(*) FROM chapters WHERE bookId = :bookId")
    suspend fun getChapterCountForBook(bookId: String): Int

    @Query("SELECT COUNT(*) FROM chapters WHERE bookId = :bookId")
    suspend fun getRealChapterCount(bookId: String): Int

    // Метод для обновления счётчика
    @Query("UPDATE books SET chaptersCount = :count, updatedAt = :now WHERE id = :bookId")
    suspend fun updateChaptersCount(bookId: String, count: Int, now: Long = System.currentTimeMillis())

}