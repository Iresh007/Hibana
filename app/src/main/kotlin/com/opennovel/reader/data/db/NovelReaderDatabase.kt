package com.opennovel.reader.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        NovelEntity::class,
        ChapterEntity::class,
        HistoryEntity::class,
        CategoryEntity::class,
        NovelCategoryCrossRef::class,
    ],
    version = 4,
    exportSchema = false,
)
abstract class NovelReaderDatabase : RoomDatabase() {
    abstract fun novelDao(): NovelDao
    abstract fun chapterDao(): ChapterDao
    abstract fun historyDao(): HistoryDao
    abstract fun categoryDao(): CategoryDao

    companion object {
        @Volatile
        private var instance: NovelReaderDatabase? = null

        /**
         * v3 → v4 adds the columns the Updates feed, chapter bookmarks, and the
         * novel/comic split need.
         *
         * Written as a real migration rather than a destructive fallback: by this
         * point users have libraries worth thousands of entries, and silently
         * wiping one on upgrade is not a recoverable mistake. Every column is
         * added with a NOT NULL default so existing rows stay valid.
         *
         * `dateFetch` backfills from `dateUpload` rather than 0 so the first
         * Updates render after upgrading is ordered sensibly instead of collapsing
         * every pre-existing chapter onto the epoch.
         */
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE chapters ADD COLUMN dateFetch INTEGER NOT NULL DEFAULT 0")
                db.execSQL("UPDATE chapters SET dateFetch = dateUpload")
                db.execSQL("ALTER TABLE chapters ADD COLUMN bookmark INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE novels ADD COLUMN contentType TEXT NOT NULL DEFAULT 'UNKNOWN'")
            }
        }

        fun get(context: Context): NovelReaderDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    NovelReaderDatabase::class.java,
                    "novelreader.db",
                )
                    .addMigrations(MIGRATION_3_4)
                    .build()
                    .also { instance = it }
            }
    }
}
