package com.example.vulpinenotes.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

// Определение Room Database с сущностями BookEntity и ChapterEntity
// version = 6 - текущая версия базы данных
// exportSchema = true - включение экспорта схемы для анализа структуры БД
@Database(
    entities = [BookEntity::class, ChapterEntity::class],
    version = 6,
    exportSchema = true
)
abstract class AppDatabase : RoomDatabase() {

    // DAO (Data Access Object) интерфейсы для работы с таблицами
    abstract fun bookDao(): BookDao
    abstract fun chapterDao(): ChapterDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        // Миграция с версии 1 на 2: добавление поля updatedAt в таблицу chapters
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    ALTER TABLE chapters 
                    ADD COLUMN updatedAt INTEGER NOT NULL DEFAULT ${System.currentTimeMillis()}
                """.trimIndent())
            }
        }

        // Миграция с версии 2 на 3: полная реструктуризация таблицы books
        // Добавление полей createdAt и cloudSynced, создание новой таблицы и копирование данных
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
            CREATE TABLE books_new (
                id TEXT NOT NULL PRIMARY KEY,
                title TEXT NOT NULL,
                desc TEXT NOT NULL,
                coverPath TEXT,
                chaptersCount INTEGER NOT NULL,
                updatedAt INTEGER NOT NULL,
                createdAt INTEGER NOT NULL,
                cloudSynced INTEGER NOT NULL
            )
        """)

                db.execSQL("""
            INSERT INTO books_new (id, title, desc, coverPath, chaptersCount, updatedAt, createdAt, cloudSynced)
            SELECT id, title, desc, coverPath, chaptersCount,
                   ${System.currentTimeMillis()}, ${System.currentTimeMillis()}, 0
            FROM books
        """)

                db.execSQL("DROP TABLE books")
                db.execSQL("ALTER TABLE books_new RENAME TO books")
            }
        }

        // Миграция с версии 3 на 4: полный рефакторинг таблицы chapters
        // Генерация новых ID, добавление полей createdAt/updatedAt, создание индексов
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
            CREATE TABLE chapters_new (
                chapterId TEXT NOT NULL PRIMARY KEY,
                bookId TEXT NOT NULL,
                position INTEGER NOT NULL,
                title TEXT NOT NULL,
                description TEXT NOT NULL,
                date TEXT NOT NULL DEFAULT 'undefined',
                wordCount INTEGER NOT NULL DEFAULT 0,
                isFavorite INTEGER NOT NULL DEFAULT 0,
                createdAt INTEGER NOT NULL DEFAULT 0,
                updatedAt INTEGER NOT NULL DEFAULT 0
            )
        """.trimIndent())

                // Перенос данных с генерацией новых ID и временных меток
                db.execSQL("""
            INSERT INTO chapters_new (
                chapterId, bookId, position,
                title, description, date,
                wordCount, isFavorite,
                createdAt, updatedAt
            )
            SELECT
                lower(hex(randomblob(16))), -- генерация нового UUID
                bookId,
                position,
                title,
                description,
                date,
                IFNULL(wordCount, 0),
                IFNULL(isFavorite, 0),
                strftime('%s','now')*1000,   -- текущее время в миллисекундах
                strftime('%s','now')*1000
            FROM chapters
        """.trimIndent())

                db.execSQL("DROP TABLE chapters")
                db.execSQL("ALTER TABLE chapters_new RENAME TO chapters")

                // Создание индексов для оптимизации запросов
                db.execSQL("CREATE INDEX index_chapters_position ON chapters(position)")
                db.execSQL("CREATE INDEX index_chapters_bookId ON chapters(bookId)")
            }
        }

        // Миграция с версии 4 на 5: добавление поля content для хранения текста главы
        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
            ALTER TABLE chapters
            ADD COLUMN content TEXT NOT NULL DEFAULT ''
        """.trimIndent())
            }
        }

        // Миграция с версии 5 на 6 - проверка и создание индексов
        // Используется IF NOT EXISTS для безопасности (До этого приложение падало)
        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE INDEX IF NOT EXISTS index_chapters_position 
                    ON chapters(position)
                """.trimIndent())

                db.execSQL("""
                    CREATE INDEX IF NOT EXISTS index_chapters_bookId 
                    ON chapters(bookId)
                """.trimIndent())
            }
        }

        // Общий метод для получения экземпляра базы данных
        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "vulpine_notes_db"
                )
                    .addMigrations(
                        MIGRATION_1_2,
                        MIGRATION_2_3,
                        MIGRATION_3_4,
                        MIGRATION_4_5,
                        MIGRATION_5_6
                    )
                    .fallbackToDestructiveMigration() // Разрушительное восстановление при ошибках миграции
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}