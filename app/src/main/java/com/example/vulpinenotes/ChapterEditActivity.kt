package com.example.vulpinenotes
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.addCallback
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.vulpinenotes.data.AppDatabase
import com.example.vulpinenotes.data.ChapterEntity
import com.example.vulpinenotes.databinding.ActivityChapterEditBinding
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
class ChapterEditActivity : AppCompatActivity() {
    companion object {
        const val EXTRA_CHAPTER = "com.example.vulpinenotes.EXTRA_CHAPTER"
        const val EXTRA_CHAPTER_POSITION = "com.example.vulpinenotes.EXTRA_CHAPTER_POSITION"
        const val RESULT_UPDATED_CHAPTER = 1001
    }
    private lateinit var binding: ActivityChapterEditBinding
    private lateinit var chapter: Chapter
    private var position: Int = -1
    private lateinit var bookId: String
    private var bookCloudSynced: Boolean = false  // ← правильное имя переменной
    private lateinit var database: AppDatabase
    private lateinit var firestore: FirebaseFirestore
    private lateinit var auth: FirebaseAuth
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityChapterEditBinding.inflate(layoutInflater)
        setContentView(binding.root)
        database = AppDatabase.getDatabase(this)
        firestore = FirebaseFirestore.getInstance()
        auth = FirebaseAuth.getInstance()
// Получаем главу
        chapter = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(EXTRA_CHAPTER, Chapter::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(EXTRA_CHAPTER)
        } ?: run { finish(); return }
        position = intent.getIntExtra(EXTRA_CHAPTER_POSITION, -1)
        if (position == -1) { finish(); return }
        bookId = intent.getStringExtra("book_id") ?: ""
        bookCloudSynced = intent.getBooleanExtra("book_cloud_synced", false)
        supportActionBar?.title = chapter.title
        binding.contentEditText.setText(chapter.description)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.setHomeAsUpIndicator(R.drawable.ic_close)
// Сохранение при нажатии "назад" или крестика
        onBackPressedDispatcher.addCallback(this) { saveAndExit() }
    }
    override fun onSupportNavigateUp(): Boolean {
        saveAndExit()
        return true
    }
    private fun saveAndExit() {
        val text = binding.contentEditText.text.toString()
        val words = text.trim().split(Regex("\\s+")).count { it.isNotEmpty() }
        val updatedChapter = chapter.copy(
            description = text,
            wordCount = words
        )
        lifecycleScope.launch {
            val entity = ChapterEntity(
                bookId = bookId,
                position = position,
                title = chapter.title,
                description = text,
                date = chapter.date,
                wordCount = words,
                isFavorite = chapter.isFavorite,
                updatedAt = System.currentTimeMillis()
            )
// 1. Сохраняем локально (всегда)
            database.chapterDao().insertChapter(entity)
// 2. Отправляем в облако, если книга синхронизирована
            if (bookCloudSynced && auth.currentUser != null) {
                try {
                    withContext(Dispatchers.IO) {
                        firestore.collection("users")
                            .document(auth.currentUser!!.uid)
                            .collection("books")
                            .document(bookId)
                            .collection("chapters")
                            .document(position.toString())
                            .set(entity)
                            .await()
                    }
                } catch (e: Exception) {
// Если нет интернета — не падаем, просто не синхронизировалось сейчас
                    e.printStackTrace()
                    runOnUiThread {
                        Toast.makeText(this@ChapterEditActivity, "Не удалось сохранить в облако (проверьте интернет)", Toast.LENGTH_LONG).show()
                    }
                }
            }
// Возвращаем обновлённую главу в BookActivity
            setResult(RESULT_UPDATED_CHAPTER, Intent().apply {
                putExtra(EXTRA_CHAPTER, updatedChapter)
                putExtra(EXTRA_CHAPTER_POSITION, position)
            })
            finish()
        }
    }
}