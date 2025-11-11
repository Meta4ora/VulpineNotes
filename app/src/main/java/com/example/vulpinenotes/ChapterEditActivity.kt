// ChapterEditActivity.kt
package com.example.vulpinenotes

import android.os.Build
import android.os.Bundle
import androidx.activity.enableEdgeToEdge
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.example.vulpinenotes.databinding.ActivityChapterEditBinding

class ChapterEditActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_CHAPTER = "com.example.vulpinenotes.EXTRA_CHAPTER"
        const val EXTRA_CHAPTER_POSITION = "com.example.vulpinenotes.EXTRA_CHAPTER_POSITION"
        const val RESULT_UPDATED_CHAPTER = 1001
    }

    private lateinit var binding: ActivityChapterEditBinding
    private lateinit var chapter: Chapter
    private var chapterPosition: Int = -1

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityChapterEditBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ViewCompat.setOnApplyWindowInsetsListener(binding.main) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.setHomeAsUpIndicator(R.drawable.ic_close)

        // Получаем данные
        chapter = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(EXTRA_CHAPTER, Chapter::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(EXTRA_CHAPTER)
        } ?: run { finish(); return }

        chapterPosition = intent.getIntExtra(EXTRA_CHAPTER_POSITION, -1)
        if (chapterPosition == -1) finish()

        supportActionBar?.title = chapter.title
        binding.contentEditText.setText(chapter.description)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                saveAndExit()
            }
        })
    }

    override fun onSupportNavigateUp(): Boolean {
        saveAndExit()
        return true
    }

    private fun saveAndExit() {
        val text = binding.contentEditText.text.toString()
        val wordCount = text.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }.size

        val updatedChapter = chapter.copy(
            description = text,
            wordCount = wordCount
        )

        setResult(RESULT_UPDATED_CHAPTER, intent.apply {
            putExtra(EXTRA_CHAPTER, updatedChapter)
            putExtra(EXTRA_CHAPTER_POSITION, chapterPosition)
        })
        finish()
    }
}