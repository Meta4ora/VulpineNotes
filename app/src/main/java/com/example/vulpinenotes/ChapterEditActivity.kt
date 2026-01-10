package com.example.vulpinenotes

import android.app.AlertDialog
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.Toast
import androidx.activity.addCallback
import androidx.activity.enableEdgeToEdge
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.vulpinenotes.data.AppDatabase
import com.example.vulpinenotes.data.toEntity
import com.example.vulpinenotes.databinding.ActivityChapterEditBinding
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import io.noties.markwon.Markwon
import io.noties.markwon.html.HtmlPlugin
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
    private var bookCloudSynced: Boolean = false

    private lateinit var database: AppDatabase
    private lateinit var firestore: FirebaseFirestore
    private lateinit var auth: FirebaseAuth
    private lateinit var markwon: Markwon

    private var isPreviewVisible = false

    @RequiresApi(Build.VERSION_CODES.S)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        binding = ActivityChapterEditBinding.inflate(layoutInflater)
        setContentView(binding.root)

        database = AppDatabase.getDatabase(this)
        firestore = FirebaseFirestore.getInstance()
        auth = FirebaseAuth.getInstance()

        // Markwon с поддержкой HTML (<u> для подчёркивания)
        markwon = Markwon.builder(this)
            .usePlugin(HtmlPlugin.create())
            .build()

        // Получаем главу
        chapter = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(EXTRA_CHAPTER, Chapter::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(EXTRA_CHAPTER)
        } ?: run { finish(); return }

        position = intent.getIntExtra(EXTRA_CHAPTER_POSITION, -1)
        bookId = intent.getStringExtra("book_id") ?: ""
        bookCloudSynced = intent.getBooleanExtra("book_cloud_synced", false)
        if (position == -1) { finish(); return }

        supportActionBar?.apply {
            title = chapter.title
            setDisplayHomeAsUpEnabled(true)
            setHomeAsUpIndicator(R.drawable.ic_close)
            setHomeButtonEnabled(true)
        }

        binding.toolbar.setNavigationOnClickListener { saveAndExit() }
        binding.toolbar.setOnClickListener { showEditTitleDialog() }

        binding.contentEditText.setText(chapter.content)

        // Кнопки форматирования
        binding.btnBold.setOnClickListener { applyFormat("**", "**") }
        binding.btnItalic.setOnClickListener { applyFormat("_", "_") }
        binding.btnUnderline.setOnClickListener { applyFormat("<u>", "</u>") }

        // Кнопка предпросмотра
        binding.previewButton.setOnClickListener { togglePreview() }

        // Сохранение при нажатии "назад"
        onBackPressedDispatcher.addCallback(this) { saveAndExit() }

        // Обработка оконных inset (IME и статус-бар)
        binding.main.setOnApplyWindowInsetsListener { _, insets ->
            val imeHeight = insets.getInsets(android.view.WindowInsets.Type.ime()).bottom
            val statusBarHeight = insets.getInsets(android.view.WindowInsets.Type.statusBars()).top

            // Двигаем нижнюю панель
            binding.formattingBar.translationY = -imeHeight.toFloat()

            // Отступ для AppBar с учётом статус-бара
            binding.appbar.setPadding(
                binding.appbar.paddingLeft,
                statusBarHeight,
                binding.appbar.paddingRight,
                binding.appbar.paddingBottom
            )
            insets
        }
    }

    private fun showEditTitleDialog() {
        val input = EditText(this)
        input.setText(chapter.title)
        AlertDialog.Builder(this)
            .setTitle("Редактировать заголовок")
            .setView(input)
            .setPositiveButton("Сохранить") { _, _ ->
                val newTitle = input.text.toString().trim()
                if (newTitle.isNotBlank()) {
                    chapter = chapter.copy(title = newTitle)
                    supportActionBar?.title = newTitle
                } else {
                    Toast.makeText(this, "Название не может быть пустым", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    /** Применяем Markdown/HTML формат к выделенному тексту */
    private fun applyFormat(prefix: String, suffix: String) {
        val editText = binding.contentEditText
        val start = editText.selectionStart.coerceAtLeast(0)
        val end = editText.selectionEnd.coerceAtLeast(0)
        val text = editText.text ?: return

        if (start != end) {
            val selected = text.subSequence(start, end)
            val replacement = "$prefix$selected$suffix"
            text.replace(start, end, replacement)
            editText.setSelection(start + prefix.length, start + prefix.length + selected.length)
        } else {
            text.insert(start, "$prefix$suffix")
            editText.setSelection(start + prefix.length)
        }

        if (isPreviewVisible) togglePreview()
    }

    private fun togglePreview() {
        isPreviewVisible = !isPreviewVisible
        if (isPreviewVisible) {
            binding.previewTextView.visibility = View.VISIBLE
            binding.contentEditText.visibility = View.GONE
            markwon.setMarkdown(binding.previewTextView, binding.contentEditText.text.toString())
            binding.previewButton.text = "Редактировать"
        } else {
            binding.previewTextView.visibility = View.GONE
            binding.contentEditText.visibility = View.VISIBLE
            binding.previewButton.text = "Предпросмотр"
        }
    }

    private fun uploadChapterToCloud(chapterEntity: com.example.vulpinenotes.data.ChapterEntity) {
        val user = auth.currentUser ?: return

        val chapterForCloud = mapOf(
            "chapterId" to chapterEntity.chapterId,
            "title" to chapterEntity.title,
            "description" to chapterEntity.description,
            "date" to chapterEntity.date,
            "wordCount" to chapterEntity.wordCount,
            "isFavorite" to chapterEntity.isFavorite,
            "position" to chapterEntity.position,
            "createdAt" to chapterEntity.createdAt,
            "updatedAt" to chapterEntity.updatedAt
        )

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                firestore
                    .collection("users")
                    .document(user.uid)
                    .collection("books")
                    .document(bookId)
                    .collection("chapters")
                    .document(chapterEntity.chapterId)
                    .set(chapterForCloud)
                    .await()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun saveAndExit() {
        val content = binding.contentEditText.text.toString().trim()
        val words = content.split(Regex("\\s+")).count { it.isNotEmpty() }
        val now = System.currentTimeMillis()

        val updatedChapter = chapter.copy(
            content = binding.contentEditText.text.toString().trim(),
            wordCount = words,
            updatedAt = now
        )

        lifecycleScope.launch {
            val entity = database.chapterDao()
                .getChaptersForBookSync(bookId)
                .firstOrNull { it.chapterId == chapter.chapterId } ?: return@launch

            val entityUpdated = entity.copy(
                content = updatedChapter.content,
                wordCount = updatedChapter.wordCount,
                updatedAt = updatedChapter.updatedAt
            )

            database.chapterDao().insertChapter(entityUpdated)

            if (bookCloudSynced) {
                uploadChapterToCloud(entityUpdated)
            }

            val resultIntent = Intent().apply {
                putExtra(EXTRA_CHAPTER, updatedChapter)
                putExtra(EXTRA_CHAPTER_POSITION, position)
            }
            setResult(RESULT_UPDATED_CHAPTER, resultIntent)
            finish()
        }
    }


    override fun onSupportNavigateUp(): Boolean {
        saveAndExit()
        return true
    }
}
