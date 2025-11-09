package com.example.vulpinenotes

import android.os.Build
import android.os.Bundle
import android.view.MenuItem
import android.widget.ImageButton
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.appcompat.widget.Toolbar
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView


class BookActivity : AppCompatActivity() {

    companion object {
        // ИСПРАВЛЕНО: используем тот же ключ, что и в MainActivity
        const val EXTRA_BOOK = "com.example.vulpinenotes.EXTRA_BOOK"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_book)

        // Поддержка edge-to-edge
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        // Установка Toolbar
        val toolbar = findViewById<Toolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        // Получение книги
        val book: Book? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(EXTRA_BOOK, Book::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(EXTRA_BOOK)
        }

        book?.let {
            supportActionBar?.title = it.title

            // --- ОТЛАДКА: 3 главы ---
            val debugChapters = listOf(
                Chapter("Глава 1: Начало пути", "03.11.25", 225),
                Chapter("Глава 2: Тёмный лес", "04.11.25", 512),
                Chapter("Глава 3: Встреча с драконом", "05.11.25", 890)
            )

            val recyclerView = findViewById<RecyclerView>(R.id.chapters_recycler_view)
            recyclerView.adapter = ChapterAdapter(debugChapters)
            recyclerView.layoutManager = LinearLayoutManager(this)
        } ?: run {
            finish()
            return
        }

        // FAB
        findViewById<ImageButton>(R.id.fab_add_chapter)
            .setOnClickListener {
                // TODO: Добавить главу
            }
    }

    // Обработка нажатия "Назад"
    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    // Опционально: меню
    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            android.R.id.home -> {
                finish()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }
}