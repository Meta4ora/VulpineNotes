package com.example.vulpinenotes.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [BookEntity::class, ChapterEntity::class],
    version = 3,
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



        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "vulpine_notes_db"
                )
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                    .build()

                INSTANCE = instance
                instance
            }
        }
    }
}