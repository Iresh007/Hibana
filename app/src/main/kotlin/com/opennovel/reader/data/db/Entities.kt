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
    /**
     * Whether this entry reads as a comic or as prose. Stored per entry rather
     * than derived from the source at read time: the source's ecosystem is only
     * a good *default* (a Mihon source can carry a webtoon-format novel, and
     * IReader hosts illustrated works), and deriving it live would silently
     * change how something reads when an extension is updated or replaced.
     * See [ContentType].
     */
    val contentType: String = ContentType.UNKNOWN.name,
)

/**
 * What an entry actually is, which decides the reader it opens in, the settings
 * that apply to it, and which library segment it appears under.
 *
 * [UNKNOWN] means "not yet decided" rather than a third kind of content — it is
 * resolved to a concrete type on first fetch from the owning source's ecosystem,
 * and the user can override it per entry afterwards.
 */
enum class ContentType {
    COMIC,
    NOVEL,
    UNKNOWN,
    ;

    companion object {
        fun from(value: String?): ContentType =
            entries.firstOrNull { it.name == value } ?: UNKNOWN
    }
}

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
    /**
     * When *we* first saw this chapter, as opposed to when the site says it was
     * posted. The Updates feed keys off this: many sources report no upload date
     * at all, which would otherwise collapse every chapter onto the epoch and
     * make the feed useless. Set once, on first insert.
     */
    val dateFetch: Long = 0L,
    val read: Boolean = false,
    /** User-flagged chapter, surfaced as a filter and never auto-deleted. */
    val bookmark: Boolean = false,
    /** Scroll offset (0f..1f) for resume. */
    val lastReadOffset: Float = 0f,
    val downloaded: Boolean = false,
    /** Local file path when downloaded, else null. */
    val downloadPath: String? = null,
    val sourceOrder: Int = 0,
)

/**
 * Per-entry chapter list preferences: the filter set, the ordering, and how a
 * row is labelled.
 *
 * Held in its own table rather than as columns on [NovelEntity] because it is
 * view state, not library data — a backup or a source migration should carry the
 * entry and its chapters, and quietly dragging "only show unread, descending"
 * along with them would be surprising. A missing row means "never customised",
 * which is why every reader of this table falls back to defaults instead of
 * requiring one to be created up front.
 *
 * [FilterState] values are stored by name, not ordinal, so reordering the enum
 * cannot silently re-interpret existing rows as a different filter.
 */
@Entity(
    tableName = "chapter_settings",
    foreignKeys = [
        ForeignKey(
            entity = NovelEntity::class,
            parentColumns = ["id"],
            childColumns = ["novelId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class ChapterSettingsEntity(
    @PrimaryKey val novelId: Long,
    val filterDownloaded: String = "IGNORED",
    val filterUnread: String = "IGNORED",
    val filterBookmarked: String = "IGNORED",
    /** Name of a `ChapterSort` constant; unknown values fall back to the default. */
    val sort: String = "NUMBER",
    val sortDescending: Boolean = false,
    /** True shows the source's full chapter title, false shows just its number. */
    val displayFullTitle: Boolean = true,
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
    /** Newest chapter upload time, for "sort by latest chapter". */
    val latestUpload: Long = 0L,
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
    val bookmark: Boolean,
    val downloaded: Boolean,
    val dateUpload: Long,
    val dateFetch: Long,
    val novelTitle: String,
    val coverUrl: String?,
    /**
     * Carried from the parent novel so the feeds can be narrowed to the active
     * section without a second query per row. See [ContentType].
     */
    val contentType: String = ContentType.UNKNOWN.name,
)

/** One chapter's release time, for estimating an entry's publishing cadence. */
data class ChapterRelease(
    val novelId: Long,
    val releasedAt: Long,
)

/** Denormalized history row joined with its novel + chapter, for display. */
data class HistoryWithNovel(
    val novelId: Long,
    val chapterId: Long,
    val readAt: Long,
    val title: String,
    val coverUrl: String?,
    val chapterName: String,
    /** Carried from the parent novel so History can be scoped to a section. */
    val contentType: String = ContentType.UNKNOWN.name,
)
