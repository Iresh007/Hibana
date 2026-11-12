package com.opennovel.reader.backup

/**
 * Tachiyomi/Mihon `.tachibk` backup schema (protobuf, normally gzipped).
 *
 * Field numbers are copied from Mihon v0.21.3
 * (`app/src/main/java/eu/kanade/tachiyomi/data/backup/models/`) and checked
 * against a real Mihon export. Only the parts Hibana can store are modelled;
 * everything else (preferences 104/105, extension stores 106, update strategy,
 * viewer flags, notes, memo blobs) is skipped on read and omitted on write.
 *
 * Proto defaults are NOT written by Mihon, so absent fields must decode to the
 * Mihon default: notably [BackupManga.favorite] defaults to true.
 */
data class Backup(
    /** 1 */ val backupManga: List<BackupManga> = emptyList(),
    /** 2 */ val backupCategories: List<BackupCategory> = emptyList(),
    /** 101 */ val backupSources: List<BackupSource> = emptyList(),
)

data class BackupManga(
    /** 1 */ val source: Long,
    /** 2 */ val url: String,
    /** 3 */ val title: String = "",
    /** 4 */ val artist: String? = null,
    /** 5 */ val author: String? = null,
    /** 6 */ val description: String? = null,
    /** 7 (repeated) */ val genre: List<String> = emptyList(),
    /** 8 */ val status: Int = 0,
    /** 9 */ val thumbnailUrl: String? = null,
    /** 13, epoch millis */ val dateAdded: Long = 0,
    /** 16 (repeated) */ val chapters: List<BackupChapter> = emptyList(),
    /** 17 (repeated) category ORDER values, not ids */ val categories: List<Long> = emptyList(),
    /** 18 (repeated) */ val tracking: List<BackupTracking> = emptyList(),
    /** 100, default true */ val favorite: Boolean = true,
    /** 101 */ val chapterFlags: Int = 0,
    /** 104 (repeated) */ val history: List<BackupHistory> = emptyList(),
    /** 107, epoch seconds */ val favoriteModifiedAt: Long? = null,
)

data class BackupChapter(
    /** 1 */ val url: String,
    /** 2 */ val name: String,
    /** 3 */ val scanlator: String? = null,
    /** 4 */ val read: Boolean = false,
    /** 5 */ val bookmark: Boolean = false,
    /** 6 */ val lastPageRead: Long = 0,
    /** 7, epoch millis */ val dateFetch: Long = 0,
    /** 8, epoch millis */ val dateUpload: Long = 0,
    /** 9 (fixed32 float) */ val chapterNumber: Float = 0f,
    /** 10 */ val sourceOrder: Long = 0,
)

data class BackupCategory(
    /** 1 */ val name: String,
    /** 2 */ val order: Long = 0,
    /** 3 */ val id: Long = 0,
    /** 100 */ val flags: Long = 0,
)

data class BackupSource(
    /** 1 */ val name: String = "",
    /** 2 */ val sourceId: Long,
)

data class BackupHistory(
    /** 1, chapter url */ val url: String,
    /** 2, epoch millis */ val lastRead: Long,
    /** 3 */ val readDuration: Long = 0,
)

data class BackupTracking(
    /** 1 */ val syncId: Int,
    /** 2 */ val libraryId: Long,
    /** 3 (deprecated) */ val mediaIdInt: Int = 0,
    /** 4 */ val trackingUrl: String = "",
    /** 5 */ val title: String = "",
    /** 6 (float) */ val lastChapterRead: Float = 0f,
    /** 7 */ val totalChapters: Int = 0,
    /** 8 (float) */ val score: Float = 0f,
    /** 9 */ val status: Int = 0,
    /** 10 */ val startedReadingDate: Long = 0,
    /** 11 */ val finishedReadingDate: Long = 0,
    /** 12 */ val private: Boolean = false,
    /** 100 */ val mediaId: Long = 0,
)
