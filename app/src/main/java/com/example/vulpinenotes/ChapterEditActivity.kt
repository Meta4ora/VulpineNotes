package com.example.vulpinenotes

import android.app.AlertDialog
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.util.TypedValue
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.Toast
import androidx.activity.addCallback
import androidx.activity.enableEdgeToEdge
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatActivity
import androidx.coordinatorlayout.widget.CoordinatorLayout
import androidx.lifecycle.lifecycleScope
import com.example.vulpinenotes.data.AppDatabase
import com.example.vulpinenotes.databinding.ActivityChapterEditBinding
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import io.noties.markwon.Markwon
import io.noties.markwon.html.HtmlPlugin
import io.noties.markwon.ext.tables.TablePlugin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

class ChapterEditActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_CHAPTER = "com.example.vulpinenotes.EXTRA_CHAPTER"
        const val EXTRA_CHAPTER_POSITION = "com.example.vulpinenotes.EXTRA_CHAPTER_POSITION"
        const val RESULT_UPDATED_CHAPTER = 1001
    }

    private lateinit var binding: ActivityChapterEditBinding
    private lateinit var chapter: Chapter
    private var position = -1
    private lateinit var bookId: String
    private var bookCloudSynced = false

    private lateinit var database: AppDatabase
    private lateinit var firestore: FirebaseFirestore
    private lateinit var auth: FirebaseAuth
    private lateinit var markwon: Markwon

    private var isPreviewVisible = false

    // Undo/Redo с хранением текста + курсора
    private data class EditState(val text: String, val cursor: Int)
    private val undoStack = ArrayDeque<EditState>()
    private val redoStack = ArrayDeque<EditState>()
    private var isInternalChange = false // чтобы не писать состояние при Undo/Redo или автоподстановке

    // Для отслеживания предыдущего состояния в TextWatcher
    private var previousText = ""
    private var previousCursor = 0
    private var isTextChangedByUser = true

    @RequiresApi(Build.VERSION_CODES.S)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        binding = ActivityChapterEditBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Toolbar
        setSupportActionBar(binding.toolbar)
        supportActionBar?.apply {
            setDisplayShowTitleEnabled(false)
            setDisplayHomeAsUpEnabled(true)
            setHomeAsUpIndicator(R.drawable.ic_close)
        }

        database = AppDatabase.getDatabase(this)
        firestore = FirebaseFirestore.getInstance()
        auth = FirebaseAuth.getInstance()

        // Инициализация Markwon с поддержкой таблиц и HTML
        markwon = Markwon.builder(this)
            .usePlugin(HtmlPlugin.create())
            .usePlugin(TablePlugin.create(this))
            .build()

        // Получение данных
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

        binding.chapterTitleTextView.text = chapter.title
        binding.contentEditText.setText(chapter.content)

        // Инициализация состояния для undo/redo
        saveStateToUndoStack()

        // Заголовок
        binding.chapterTitleTextView.setOnClickListener { showEditTitleDialog() }
        binding.toolbar.setNavigationOnClickListener { saveAndExit() }

        // Форматирование
        binding.btnBold.setOnClickListener { applyInline("**", "**") }
        binding.btnItalic.setOnClickListener { applyInline("_", "_") }
        binding.btnUnderline.setOnClickListener { applyInline("<u>", "</u>") }

        binding.btnH1.setOnClickListener { applyLinePrefix("# ") }
        binding.btnH2.setOnClickListener { applyLinePrefix("## ") }
        binding.btnH3.setOnClickListener { applyLinePrefix("### ") }

        binding.btnBulletList.setOnClickListener { applyList() }
        binding.btnNumberList.setOnClickListener { applyNumberedList() }

        binding.btnQuote.setOnClickListener { applyLinePrefix("> ") }

        // Новые кнопки
        binding.btnTable.setOnClickListener { showTableDialog() }
        binding.btnDivider.setOnClickListener { insertDivider() }
        binding.btnClear.setOnClickListener { clearFormatting() }

        // Undo/Redo
        binding.btnUndo.setOnClickListener { undo() }
        binding.btnRedo.setOnClickListener { redo() }

        // Preview
        binding.previewButton.setOnClickListener { togglePreview() }

        // TextWatcher для Undo/Redo + автоподстановки списков
        binding.contentEditText.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {
                previousText = binding.contentEditText.text.toString()
                previousCursor = binding.contentEditText.selectionStart
            }

            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                // Обработка автоподстановки списков
                if (count == 1 && before == 0 && s != null) {
                    val insertedChar = s.subSequence(start, start + count).toString()
                    if (insertedChar == "\n") {
                        handleListContinuation()
                    }
                }
            }

            override fun afterTextChanged(s: Editable?) {
                if (isTextChangedByUser && !isInternalChange) {
                    saveStateToUndoStack()
                }
                refreshPreview()
            }
        })

        // Обработка клавиш для улучшенного управления списками
        binding.contentEditText.setOnKeyListener { _, keyCode, event ->
            if (keyCode == android.view.KeyEvent.KEYCODE_ENTER &&
                event.action == android.view.KeyEvent.ACTION_DOWN
            ) {
                handleListContinuation()
                true // Поглощаем событие, так как сами вставляем текст
            } else false
        }

        onBackPressedDispatcher.addCallback(this) { saveAndExit() }

        setupWindowInsets()

        // Добавляем слушатель фокуса для обновления отступов при появлении клавиатуры
        setupFocusListener()
    }

    private fun setupFocusListener() {
        binding.contentEditText.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                // При получении фокуса обновляем отступы
                updateEditTextPadding()
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun setupWindowInsets() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false)
        }

        ViewCompat.setOnApplyWindowInsetsListener(binding.main) { view, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())

            // AppBar под статусбар
            binding.appbar.setPadding(0, systemBars.top, 0, 0)

            // Обновляем отступы для EditText с учетом клавиатуры и панели форматирования
            updateEditTextPadding(ime.bottom > 0, ime.bottom)

            // Обновляем отступы для предпросмотра
            val previewBottomPadding = if (ime.bottom > 0) {
                dpToPx(100) + ime.bottom
            } else {
                dpToPx(32)
            }

            val paddingHorizontal = dpToPx(24)
            val paddingTop = dpToPx(16)
            binding.previewTextView.setPadding(
                paddingHorizontal,
                paddingTop,
                paddingHorizontal,
                previewBottomPadding
            )

            // Меняем margin панели форматирования вместо translationY
            val params = binding.formattingBar.layoutParams as CoordinatorLayout.LayoutParams
            if (ime.bottom > 0) {
                params.bottomMargin = ime.bottom
            } else {
                params.bottomMargin = dpToPx(16)
            }
            binding.formattingBar.layoutParams = params
            binding.formattingBar.visibility = View.VISIBLE

            insets
        }
    }


    private fun updateEditTextPadding(keyboardVisible: Boolean = false, keyboardHeight: Int = 0) {
        val paddingHorizontal = dpToPx(24)
        val paddingTop = dpToPx(16)

        val paddingBottom = if (keyboardVisible) {
            // Высота панели форматирования + дополнительный отступ
            val formattingBarHeight = binding.formattingBar.height
            formattingBarHeight + keyboardHeight + dpToPx(16)
        } else {
            // Просто отступ для панели форматирования
            val formattingBarHeight = binding.formattingBar.height
            formattingBarHeight + dpToPx(32)
        }

        binding.contentEditText.setPadding(
            paddingHorizontal,
            paddingTop,
            paddingHorizontal,
            paddingBottom
        )

        // Автопрокрутка к курсору
        if (keyboardVisible) {
            scrollToCursorDelayed()
        }
    }

    private fun scrollToCursorDelayed() {
        binding.contentEditText.postDelayed({
            val editText = binding.contentEditText
            val selectionStart = editText.selectionStart
            if (selectionStart >= 0) {
                val layout = editText.layout
                if (layout != null) {
                    val line = layout.getLineForOffset(selectionStart)
                    val lineBottom = layout.getLineBottom(line)

                    // Прокручиваем так, чтобы строка с курсором была видимой
                    editText.scrollTo(0, lineBottom - editText.height + editText.paddingBottom)
                }
            }
        }, 100)
    }

    private fun dpToPx(dp: Int): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            dp.toFloat(),
            resources.displayMetrics
        ).toInt()
    }

    private fun dpToPx(dp: Float): Float {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            dp,
            resources.displayMetrics
        )
    }

    private fun saveStateToUndoStack() {
        if (!isInternalChange) {
            val currentText = binding.contentEditText.text.toString()
            val currentCursor = binding.contentEditText.selectionStart

            // Сохраняем предыдущее состояние в стек undo
            undoStack.addLast(EditState(previousText, previousCursor))
            if (undoStack.size > 100) undoStack.removeFirst()
            redoStack.clear() // Очищаем стек redo при новом действии пользователя

            previousText = currentText
            previousCursor = currentCursor
        }
    }

    private fun showEditTitleDialog() {
        val input = EditText(this).apply { setText(chapter.title) }
        AlertDialog.Builder(this)
            .setTitle("Редактировать название главы")
            .setView(input)
            .setPositiveButton("Сохранить") { _, _ ->
                val title = input.text.toString().trim()
                if (title.isNotBlank()) {
                    chapter = chapter.copy(title = title)
                    binding.chapterTitleTextView.text = title
                } else {
                    Toast.makeText(this, "Название не может быть пустым", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun handleListContinuation() {
        val edit = binding.contentEditText
        val text = edit.text ?: return
        val cursor = edit.selectionStart

        // Находим начало текущей строки
        val lineStart = text.lastIndexOf('\n', cursor - 1).let { if (it == -1) 0 else it + 1 }
        val line = text.substring(lineStart, cursor)

        // Получаем предыдущую строку для определения типа списка
        val prevLineStart = text.lastIndexOf('\n', lineStart - 2).let {
            if (it == -1) 0 else it + 1
        }
        val prevLine = text.substring(prevLineStart, lineStart - 1)

        // Проверяем, является ли предыдущая строка элементом списка
        val insertText: String? = when {
            // Маркированный список: "- элемент" или "-"
            prevLine.trimStart().matches(Regex("^-\\s.*")) -> {
                // Если текущая строка пустая или содержит только "-", продолжаем список
                if (line.trim().isEmpty() || line.trim() == "-") "\n- "
                else null
            }
            // Нумерованный список: "1. элемент" или "1."
            prevLine.trimStart().matches(Regex("^\\d+\\.\\s.*")) -> {
                val numberMatch = Regex("^(\\d+)\\.").find(prevLine.trimStart())
                if (numberMatch != null) {
                    val number = numberMatch.groupValues[1].toIntOrNull() ?: 1
                    if (line.trim().isEmpty() || line.trim().matches(Regex("^\\d+\\."))) {
                        "\n${number + 1}. "
                    } else null
                } else null
            }
            // Цитата: "> текст"
            prevLine.trimStart().startsWith("> ") -> {
                if (line.trim().isEmpty()) "\n> "
                else null
            }
            else -> null
        }

        insertText?.let {
            isInternalChange = true
            text.insert(cursor, it)
            edit.setSelection(cursor + it.length)
            isInternalChange = false
        }
    }

    private fun undo() {
        if (undoStack.isNotEmpty()) {
            isInternalChange = true
            // Сохраняем текущее состояние в стек redo
            val currentState = EditState(
                binding.contentEditText.text.toString(),
                binding.contentEditText.selectionStart
            )
            redoStack.addLast(currentState)
            if (redoStack.size > 100) redoStack.removeFirst()

            // Восстанавливаем предыдущее состояние
            val previousState = undoStack.removeLast()
            binding.contentEditText.setText(previousState.text)

            // Устанавливаем курсор на сохраненную позицию
            val cursor = previousState.cursor.coerceIn(0, previousState.text.length)
            binding.contentEditText.setSelection(cursor)

            // Обновляем предыдущие значения
            previousText = previousState.text
            previousCursor = cursor

            isInternalChange = false
        }
    }

    private fun redo() {
        if (redoStack.isNotEmpty()) {
            isInternalChange = true
            // Сохраняем текущее состояние в стек undo
            val currentState = EditState(
                binding.contentEditText.text.toString(),
                binding.contentEditText.selectionStart
            )
            undoStack.addLast(currentState)
            if (undoStack.size > 100) undoStack.removeFirst()

            // Восстанавливаем состояние из redo
            val nextState = redoStack.removeLast()
            binding.contentEditText.setText(nextState.text)

            // Устанавливаем курсор на сохраненную позицию
            val cursor = nextState.cursor.coerceIn(0, nextState.text.length)
            binding.contentEditText.setSelection(cursor)

            // Обновляем предыдущие значения
            previousText = nextState.text
            previousCursor = cursor

            isInternalChange = false
        }
    }

    private fun applyInline(prefix: String, suffix: String) {
        isTextChangedByUser = false
        val edit = binding.contentEditText
        val text = edit.text ?: return
        val start = edit.selectionStart
        val end = edit.selectionEnd

        if (start != end) {
            val selected = text.subSequence(start, end)
            text.replace(start, end, "$prefix$selected$suffix")
            edit.setSelection(start + prefix.length, start + prefix.length + selected.length)
        } else {
            text.insert(start, "$prefix$suffix")
            edit.setSelection(start + prefix.length)
        }
        isTextChangedByUser = true
        saveStateToUndoStack()
        refreshPreview()
    }

    private fun applyLinePrefix(prefix: String) {
        isTextChangedByUser = false
        val edit = binding.contentEditText
        val text = edit.text ?: return
        val cursor = edit.selectionStart
        val lineStart = text.lastIndexOf('\n', cursor - 1).let { if (it == -1) 0 else it + 1 }

        text.insert(lineStart, prefix)
        edit.setSelection(cursor + prefix.length)
        isTextChangedByUser = true
        saveStateToUndoStack()
        refreshPreview()
    }

    private fun applyList() {
        isTextChangedByUser = false
        val edit = binding.contentEditText
        val text = edit.text ?: return
        val cursor = edit.selectionStart
        text.insert(cursor, "- ")
        edit.setSelection(cursor + 2)
        isTextChangedByUser = true
        saveStateToUndoStack()
    }

    private fun applyNumberedList() {
        isTextChangedByUser = false
        val edit = binding.contentEditText
        val text = edit.text ?: return
        val cursor = edit.selectionStart
        text.insert(cursor, "1. ")
        edit.setSelection(cursor + 3)
        isTextChangedByUser = true
        saveStateToUndoStack()
    }

    private fun showTableDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_table, null)
        val etRows = dialogView.findViewById<EditText>(R.id.etRows)
        val etCols = dialogView.findViewById<EditText>(R.id.etCols)

        AlertDialog.Builder(this)
            .setTitle("Вставить таблицу")
            .setView(dialogView)
            .setPositiveButton("Создать") { _, _ ->
                val rows = etRows.text.toString().toIntOrNull() ?: 2
                val cols = etCols.text.toString().toIntOrNull() ?: 2

                // Проверяем корректность значений
                if (rows < 1 || rows > 10 || cols < 1 || cols > 6) {
                    Toast.makeText(
                        this,
                        "Укажите строки: 1-10, столбцы: 1-6",
                        Toast.LENGTH_SHORT
                    ).show()
                    return@setPositiveButton
                }

                insertTable(rows, cols)
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun insertTable(rows: Int, cols: Int) {
        isTextChangedByUser = false

        val edit = binding.contentEditText
        val text = edit.text ?: return
        val cursor = edit.selectionStart

        val tableMarkdown = buildString {

            // ❗ ОБЯЗАТЕЛЬНО: пустая строка перед таблицей
            if (cursor > 0 && text[cursor - 1] != '\n') {
                append("\n")
            }
            append("\n")

            // Заголовки
            append("|")
            repeat(cols) { append(" Header ${it + 1} |") }
            append("\n")

            // Разделитель (КРИТИЧНО для Markwon)
            append("|")
            repeat(cols) { append(" --- |") }
            append("\n")

            // Данные
            repeat(rows) { row ->
                append("|")
                repeat(cols) { col ->
                    append(" ${row + 1}-${col + 1} |")
                }
                append("\n")
            }

            append("\n")
        }

        text.insert(cursor, tableMarkdown)

        // Курсор в первую ячейку данных
        val firstCellOffset = tableMarkdown.indexOf("|", tableMarkdown.indexOf("\n") + 1) + 2
        edit.setSelection((cursor + firstCellOffset).coerceAtMost(text.length))

        isTextChangedByUser = true
        saveStateToUndoStack()
        refreshPreview()
    }

    // ===================== ФУНКЦИЯ ДЛЯ РАЗДЕЛИТЕЛЯ =====================
    private fun insertDivider() {
        isTextChangedByUser = false
        val edit = binding.contentEditText
        val text = edit.text ?: return
        val cursor = edit.selectionStart

        // Проверяем, нужно ли добавить переносы строк
        val divider = buildString {
            if (cursor > 0 && text[cursor - 1] != '\n') {
                append("\n")
            }
            append("\n---\n\n")
            if (cursor < text.length && text[cursor] != '\n') {
                append("\n")
            }
        }

        text.insert(cursor, divider)
        // Ставим курсор после разделителя
        edit.setSelection(cursor + divider.length - 1)

        isTextChangedByUser = true
        saveStateToUndoStack()
        refreshPreview()
    }

    private fun clearFormatting() {
        val edit = binding.contentEditText
        val text = edit.text ?: return
        val start = edit.selectionStart
        val end = edit.selectionEnd

        if (start == end) {
            Toast.makeText(this, "Выделите текст для очистки форматирования", Toast.LENGTH_SHORT).show()
            return
        }

        AlertDialog.Builder(this)
            .setTitle("Очистить форматирование")
            .setMessage("Удалить все форматирование из выделенного текста?")
            .setPositiveButton("Очистить") { _, _ ->
                isTextChangedByUser = false
                val selected = text.subSequence(start, end).toString()

                // Улучшенная очистка форматирования
                val plainText = selected
                    // 1. Удаляем HTML теги (включая самозакрывающиеся)
                    .replace(Regex("<[^>]+>"), "")
                    // 2. Обработка ссылок Markdown [текст](url) -> текст
                    .replace(Regex("\\[([^\\]]+)\\]\\([^)]+\\)")) { matchResult ->
                        matchResult.groupValues[1]
                    }
                    // 3. Обработка изображений Markdown ![alt](url) -> alt
                    .replace(Regex("!\\[([^\\]]*)\\]\\([^)]+\\)")) { matchResult ->
                        matchResult.groupValues[1].ifEmpty { "" }
                    }
                    // 4. Обработка inline кода `код` -> код
                    .replace(Regex("`([^`]+)`")) { matchResult ->
                        matchResult.groupValues[1]
                    }
                    // 5. Обработка блоков кода ```язык\nкод\n``` -> код
                    .replace(Regex("```[a-zA-Z]*\\n([\\s\\S]*?)\\n```")) { matchResult ->
                        matchResult.groupValues[1]
                    }
                    // 6. Удаляем жирное форматирование **текст** -> текст
                    .replace(Regex("\\*\\*([^*]+)\\*\\*")) { matchResult ->
                        matchResult.groupValues[1]
                    }
                    // 7. Удаляем курсивное форматирование *текст* -> текст
                    .replace(Regex("\\*([^*]+)\\*")) { matchResult ->
                        matchResult.groupValues[1]
                    }
                    // 8. Удаляем курсивное форматирование _текст_ -> текст
                    .replace(Regex("_([^_]+)_")) { matchResult ->
                        matchResult.groupValues[1]
                    }
                    // 9. Удаляем зачеркнутое форматирование ~~текст~~ -> текст
                    .replace(Regex("~~([^~]+)~~")) { matchResult ->
                        matchResult.groupValues[1]
                    }
                    // 10. Удаляем маркеры заголовков #, ##, ### и т.д.
                    .replace(Regex("^#{1,6}\\s+", RegexOption.MULTILINE), "")
                    // 11. Удаляем маркеры списков в начале строки
                    .replace(Regex("^[\\s]*[-*+]\\s+", RegexOption.MULTILINE), "")
                    // 12. Удаляем нумерованные списки в начале строки
                    .replace(Regex("^[\\s]*\\d+\\.\\s+", RegexOption.MULTILINE), "")
                    // 13. Удаляем маркеры цитат в начале строки
                    .replace(Regex("^>\\s+", RegexOption.MULTILINE), "")
                    // 14. Обработка таблиц Markdown - преобразуем в простой текст
                    .lines().joinToString("\n") { line ->
                        if (line.matches(Regex("^\\|.*\\|$"))) {
                            // Убираем начальные и конечные |, заменяем оставшиеся на пробелы
                            line.trim('|')
                                .split(Regex("\\|"))
                                .joinToString("  ") { cell -> cell.trim() }
                        } else {
                            line
                        }
                    }
                    // 15. Убираем лишние пробелы и переносы строк
                    .replace(Regex("\\s+\\n"), "\n")
                    .replace(Regex("\\n\\s+"), "\n")
                    .trim()

                text.replace(start, end, plainText)
                edit.setSelection(start, start + plainText.length)

                isTextChangedByUser = true
                saveStateToUndoStack()
                refreshPreview()
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun togglePreview() {
        val inputMethodManager = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager

        if (isPreviewVisible) {
            // Переключаемся в режим редактирования
            binding.previewTextView.visibility = View.GONE
            binding.contentEditText.visibility = View.VISIBLE

            // Обновляем отступы для EditText с учетом текущего состояния клавиатуры
            val imeBottom = ViewCompat.getRootWindowInsets(binding.main)
                ?.getInsets(WindowInsetsCompat.Type.ime())?.bottom ?: 0
            updateEditTextPadding(imeBottom > 0, imeBottom)

            binding.previewButton.text = "Предпросмотр"

            // Показываем клавиатуру
            binding.contentEditText.requestFocus()
            binding.contentEditText.postDelayed({
                inputMethodManager.showSoftInput(binding.contentEditText, InputMethodManager.SHOW_IMPLICIT)
            }, 50)

            isPreviewVisible = false
        } else {
            // Переключаемся в режим предпросмотра

            // Скрываем клавиатуру
            inputMethodManager.hideSoftInputFromWindow(binding.contentEditText.windowToken, 0)

            // Даем время на скрытие клавиатуры, затем показываем предпросмотр
            binding.contentEditText.postDelayed({
                binding.previewTextView.visibility = View.VISIBLE
                binding.contentEditText.visibility = View.GONE

                // Отступы для предпросмотра (клавиатура уже скрыта)
                val paddingHorizontal = dpToPx(24)
                val paddingTop = dpToPx(16)
                val formattingBarHeight = binding.formattingBar.height
                val paddingBottom = dpToPx(100) + formattingBarHeight

                binding.previewTextView.setPadding(
                    paddingHorizontal,
                    paddingTop,
                    paddingHorizontal,
                    paddingBottom
                )

                markwon.setMarkdown(binding.previewTextView, binding.contentEditText.text.toString())
                binding.previewButton.text = "Редактировать"

                isPreviewVisible = true
            }, 100)
        }
    }

    private fun refreshPreview() {
        if (isPreviewVisible) {
            markwon.setMarkdown(
                binding.previewTextView,
                binding.contentEditText.text.toString()
            )
        }
    }

    private fun saveAndExit() {
        val content = binding.contentEditText.text.toString().trim()
        val words = content.split(Regex("\\s+")).count { it.isNotEmpty() }
        val now = System.currentTimeMillis()

        val updated = chapter.copy(
            content = content,
            wordCount = words,
            updatedAt = now
        )

        lifecycleScope.launch {
            val entity = database.chapterDao()
                .getChaptersForBookSync(bookId)
                .firstOrNull { it.chapterId == chapter.chapterId } ?: return@launch

            database.chapterDao().insertChapter(
                entity.copy(
                    title = updated.title,
                    content = updated.content,
                    wordCount = updated.wordCount,
                    updatedAt = updated.updatedAt
                )
            )

            if (bookCloudSynced) uploadChapterToCloud(entity)

            setResult(RESULT_UPDATED_CHAPTER, Intent().apply {
                putExtra(EXTRA_CHAPTER, updated)
                putExtra(EXTRA_CHAPTER_POSITION, position)
            })
            finish()
        }
    }

    private fun uploadChapterToCloud(entity: com.example.vulpinenotes.data.ChapterEntity) {
        val user = auth.currentUser ?: return
        lifecycleScope.launch(Dispatchers.IO) {
            firestore.collection("users")
                .document(user.uid)
                .collection("books")
                .document(bookId)
                .collection("chapters")
                .document(entity.chapterId)
                .set(entity)
                .await()
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        saveAndExit()
        return true
    }
}