package com.example.vulpinenotes.data
import android.util.Log
import com.example.vulpinenotes.Book
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID
class BookRepository(
    private val bookDao: BookDao,
    private val chapterDao: ChapterDao,
    private val db: FirebaseFirestore,
    private val storageDir: File // для обложек
) {
    val allBooks: Flow<List<Book>> = bookDao.getAllBooks().map { entities ->
        entities.map { it.toBook(storageDir) }
    }
    suspend fun insertBook(
        title: String,
        desc: String,
        coverFile: File?
    ): String {
        val bookId = UUID.randomUUID().toString()
        val coverPath = coverFile?.let { saveCoverLocally(it, bookId) }
        val book = BookEntity(
            id = bookId,
            title = title.ifBlank { "Без названия" },
            desc = desc.ifBlank { "Нет описания" },
            coverPath = coverPath,
            updatedAt = System.currentTimeMillis(),
            cloudSynced = false
        )
        bookDao.insertBook(book)
        FirebaseAuth.getInstance().currentUser?.let { user ->
            syncBookToCloud(book, user.uid)
        }
        return bookId
    }
    private suspend fun syncBookToCloud(book: BookEntity, uid: String) {
        val cloudBook = hashMapOf(
            "id" to book.id,
            "title" to book.title,
            "desc" to book.desc,
            "coverUri" to null,  // ← обложки НЕ в облаке
            "chaptersCount" to book.chaptersCount,
            "updatedAt" to book.updatedAt
        )
        db.collection("users").document(uid)
            .collection("books").document(book.id)
            .set(cloudBook)
            .addOnSuccessListener {
                // помечаем как синхронизировано
                CoroutineScope(Dispatchers.IO).launch {
                    bookDao.insertBook(book.copy(cloudSynced = true))
                }
            }
            .addOnFailureListener {
                Log.e("SYNC", "Failed to sync book ${book.id}", it)
            }
    }
    private fun saveCoverLocally(sourceFile: File, bookId: String): String {
        val coverFile = File(storageDir, "cover_$bookId.jpg")
        sourceFile.copyTo(coverFile, overwrite = true)
        return coverFile.absolutePath
    }
    suspend fun updateBook(book: BookEntity) {
        val updatedBook = book.copy(
            updatedAt = System.currentTimeMillis(),
            createdAt = book.createdAt
        )
        bookDao.updateBook(updatedBook)

        FirebaseAuth.getInstance().currentUser?.uid?.let { uid ->
            syncBookToCloud(updatedBook, uid)
        }
    }


    suspend fun deleteBook(bookId: String) {
        bookDao.deleteById(bookId)
        chapterDao.deleteChaptersForBook(bookId)
        File(storageDir, "cover_$bookId.jpg").takeIf { it.exists() }?.delete()
        // удаление из облака
        FirebaseAuth.getInstance().currentUser?.uid?.let { uid ->
            db.collection("users").document(uid).collection("books").document(bookId).delete()
        }
    }

    suspend fun getBooksByIds(ids: List<String>): List<Book> {
        return ids.mapNotNull { id ->
            bookDao.getBookById(id)?.toBook(storageDir)
        }
    }

    suspend fun getAllBooksList(): List<Book> {
        return try {
            // Получаем уникальный список книг, используя distinct()
            val books = bookDao.getAllBooksSync()
                .distinctBy { it.id } // Убираем дубли по ID
                .map { it.toBook(storageDir) }
            Log.d("BookRepository", "Loaded ${books.size} unique books")
            books
        } catch (e: Exception) {
            Log.e("BookRepository", "Error loading books", e)
            emptyList()
        }
    }
}