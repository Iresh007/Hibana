package com.opennovel.reader.data.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A novel saved to the user's library. Identified uniquely by (sourceId, url).
 */
@Entity(
    tableName = "novels",
    indices = [Index(value = ["sourceId", "url"], unique = true)],
)
data class NovelEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sourceId: Long,
    val url: String,
    val title: String,
    val author: String? = null,
    val description: String? = null,
    val coverUrl: String? = null,
    val genres: String = "",          // comma-separated
    val status: String = "UNKNOWN",
    val inLibrary: Boolean = false,
    val dateAdded: Long = 0L,
    val lastReadChapterId: Long? = null,
)

/**
 * A chapter belonging to a novel. Tracks read state and download state so the
 * library, reader, and downloader can all share one source of truth.
 */
@Entity(
    tableName = "chapters",
    foreignKeys = [
        ForeignKey(
            entity = NovelEntity::class,
            parentColumns = ["id"],
            childColumns = ["novelId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["novelId", "url"], unique = true), Index("novelId")],
)
data class ChapterEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val novelId: Long,
    val url: String,
    val name: String,
    val number: Float = -1f,
    val dateUpload: Long = 0L,
    val read: Boolean = false,
    /** Scroll offset (0f..1f) for resume. */
    val lastReadOffset: Float = 0f,
    val downloaded: Boolean = false,
    /** Local file path when downloaded, else null. */
    val downloadPath: String? = null,
    val sourceOrder: Int = 0,
)

/**
 * One row per novel recording the most recently read chapter and when. Powers the
 * History tab (recently read) and "continue reading". Kept to a single row per
 * novel (unique [novelId]) so history shows each novel once at its latest point.
 */
@Entity(
    tableName = "history",
    foreignKeys = [
        ForeignKey(
            entity = NovelEntity::class,
            parentColumns = ["id"],
            childColumns = ["novelId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = ChapterEntity::class,
            parentColumns = ["id"],
            childColumns = ["chapterId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["novelId"], unique = true), Index("chapterId")],
)
data class HistoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val novelId: Long,
    val chapterId: Long,
    /** Epoch millis of the last read event. */
    val readAt: Long = 0L,
)

/**
 * A user-defined library shelf. Novels are assigned to categories many-to-many
 * via [NovelCategoryCrossRef]; a novel in no category shows under "Default".
 */
@Entity(
    tableName = "categories",
    indices = [Index(value = ["name"], unique = true)],
)
data class CategoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    /** Display order of the tab, ascending. */
    val order: Int = 0,
)

/** Join table assigning a novel to a category (many-to-many). */
@Entity(
    tableName = "novel_categories",
    primaryKeys = ["novelId", "categoryId"],
    foreignKeys = [
        ForeignKey(
            entity = NovelEntity::class,
            parentColumns = ["id"],
            childColumns = ["novelId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = CategoryEntity::class,
            parentColumns = ["id"],
            childColumns = ["categoryId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("categoryId"), Index("novelId")],
)
data class NovelCategoryCrossRef(
    val novelId: Long,
    val categoryId: Long,
)

/**
 * Per-novel counts behind the library cover badges. Computed in SQL rather than
 * by loading every chapter, since a large library would otherwise pull tens of
 * thousands of rows just to render badges.
 */
data class NovelCounts(
    val novelId: Long,
    val unread: Int,
    val downloaded: Int,
    val total: Int,
) {
    /** "Started" means at least one chapter has been read — Mihon's definition. */
    val started: Boolean get() = total > unread
}

/**
 * A chapter joined with its novel, for the Updates feed and download queue —
 * both need the novel's title/cover alongside chapter fields.
 */
data class ChapterWithNovel(
    val chapterId: Long,
    val novelId: Long,
    val name: String,
    val url: String,
    val read: Boolean,
    val downloaded: Boolean,
    val dateUpload: Long,
    val novelTitle: String,
    val coverUrl: String?,
)

/** Denormalized history row joined with its novel + chapter, for display. */
data class HistoryWithNovel(
    val novelId: Long,
    val chapterId: Long,
    val readAt: Long,
    val title: String,
    val coverUrl: String?,
    val chapterName: String,
)
