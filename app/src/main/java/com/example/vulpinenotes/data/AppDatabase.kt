package com.example.vulpinenotes.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [BookEntity::class, ChapterEntity::class],
    version = 6,
    exportSchema = true
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun bookDao(): BookDao
    abstract fun chapterDao(): ChapterDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    ALTER TABLE chapters 
                    ADD COLUMN updatedAt INTEGER NOT NULL DEFAULT ${System.currentTimeMillis()}
                """.trimIndent())
            }
        }

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

        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // создаём новую таблицу
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

                // переносим данные, создавая createdAt/updatedAt заново
                db.execSQL("""
            INSERT INTO chapters_new (
                chapterId, bookId, position,
                title, description, date,
                wordCount, isFavorite,
                createdAt, updatedAt
            )
            SELECT
                lower(hex(randomblob(16))), -- новый chapterId
                bookId,
                position,
                title,
                description,
                date,
                IFNULL(wordCount, 0),
                IFNULL(isFavorite, 0),
                strftime('%s','now')*1000,   -- создаём createdAt
                strftime('%s','now')*1000    -- создаём updatedAt
            FROM chapters
        """.trimIndent())

                // удаляем старую таблицу
                db.execSQL("DROP TABLE chapters")
                db.execSQL("ALTER TABLE chapters_new RENAME TO chapters")

                // создаём индексы
                db.execSQL("CREATE INDEX index_chapters_position ON chapters(position)")
                db.execSQL("CREATE INDEX index_chapters_bookId ON chapters(bookId)")
            }
        }

        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
            ALTER TABLE chapters
            ADD COLUMN content TEXT NOT NULL DEFAULT ''
        """.trimIndent())
            }
        }

        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // проверяем и создаем недостающие индексы
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
                    .fallbackToDestructiveMigration()
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}