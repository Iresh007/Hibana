package com.opennovel.reader.data.db

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Upsert
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

/**
 * Reads and writes one entry's chapter filter/sort/display choice.
 *
 * Declared here rather than in Daos.kt only to keep this feature's storage in
 * one place alongside the migration that creates its table.
 */
@Dao
interface ChapterSettingsDao {

    @Query("SELECT * FROM chapter_settings WHERE novelId = :novelId")
    fun observe(novelId: Long): Flow<ChapterSettingsEntity?>

    @Upsert
    suspend fun upsert(settings: ChapterSettingsEntity)
}

@Database(
    entities = [
        NovelEntity::class,
        ChapterEntity::class,
        HistoryEntity::class,
        CategoryEntity::class,
        NovelCategoryCrossRef::class,
        ChapterSettingsEntity::class,
    ],
    version = 5,
    exportSchema = false,
)
abstract class NovelReaderDatabase : RoomDatabase() {
    abstract fun novelDao(): NovelDao
    abstract fun chapterDao(): ChapterDao
    abstract fun historyDao(): HistoryDao
    abstract fun categoryDao(): CategoryDao
    abstract fun chapterSettingsDao(): ChapterSettingsDao

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

        /**
         * v4 → v5 adds the per-entry chapter filter/sort/display table.
         *
         * Purely additive — no existing table is touched — so nothing can be lost
         * here. Rows are created lazily on the first change, so an upgraded
         * library simply keeps the default view until the user picks otherwise.
         */
        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS chapter_settings (
                        novelId INTEGER NOT NULL,
                        filterDownloaded TEXT NOT NULL DEFAULT 'IGNORED',
                        filterUnread TEXT NOT NULL DEFAULT 'IGNORED',
                        filterBookmarked TEXT NOT NULL DEFAULT 'IGNORED',
                        sort TEXT NOT NULL DEFAULT 'NUMBER',
                        sortDescending INTEGER NOT NULL DEFAULT 0,
                        displayFullTitle INTEGER NOT NULL DEFAULT 1,
                        PRIMARY KEY(novelId),
                        FOREIGN KEY(novelId) REFERENCES novels(id) ON DELETE CASCADE
                    )
                    """.trimIndent(),
                )
            }
        }

        fun get(context: Context): NovelReaderDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    NovelReaderDatabase::class.java,
                    "novelreader.db",
                )
                    .addMigrations(MIGRATION_3_4, MIGRATION_4_5)
                    .build()
                    .also { instance = it }
            }
    }
}
