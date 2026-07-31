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
