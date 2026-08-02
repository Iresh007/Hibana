@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package com.opennovel.reader.backup

import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoNumber

/**
 * Tachiyomi/Mihon `.tachibk` backup schema (protobuf, then gzipped).
 *
 * Field numbers are the wire contract shared by Mihon and every Tachiyomi-lineage
 * fork, verified against a real Mihon export. We declare only the fields Hibana
 * uses; the protobuf decoder skips the rest (preferences, tracking, extensions),
 * so real backups round-trip cleanly and our exports import back into Mihon.
 */
@Serializable
data class Backup(
    @ProtoNumber(1) val backupManga: List<BackupManga> = emptyList(),
    @ProtoNumber(2) val backupCategories: List<BackupCategory> = emptyList(),
    @ProtoNumber(101) val backupSources: List<BackupSource> = emptyList(),
)

@Serializable
data class BackupManga(
    @ProtoNumber(1) val source: Long,
    @ProtoNumber(2) val url: String,
    @ProtoNumber(3) val title: String = "",
    @ProtoNumber(4) val artist: String = "",
    @ProtoNumber(5) val author: String = "",
    @ProtoNumber(6) val description: String = "",
    @ProtoNumber(7) val genre: List<String> = emptyList(),
    @ProtoNumber(8) val status: Int = 0,
    @ProtoNumber(9) val thumbnailUrl: String = "",
    @ProtoNumber(13) val dateAdded: Long = 0,
    @ProtoNumber(16) val chapters: List<BackupChapter> = emptyList(),
    /** Category order values this entry belongs to. */
    @ProtoNumber(17) val categories: List<Long> = emptyList(),
    @ProtoNumber(100) val favorite: Boolean = true,
)

@Serializable
data class BackupChapter(
    @ProtoNumber(1) val url: String,
    @ProtoNumber(2) val name: String,
    @ProtoNumber(3) val scanlator: String = "",
    @ProtoNumber(4) val read: Boolean = false,
    @ProtoNumber(5) val bookmark: Boolean = false,
    @ProtoNumber(6) val lastPageRead: Long = 0,
    @ProtoNumber(7) val dateFetch: Long = 0,
    @ProtoNumber(8) val dateUpload: Long = 0,
    @ProtoNumber(9) val chapterNumber: Float = -1f,
    @ProtoNumber(10) val sourceOrder: Long = 0,
)

@Serializable
data class BackupCategory(
    @ProtoNumber(1) val name: String,
    @ProtoNumber(3) val order: Long = 0,
    @ProtoNumber(100) val flags: Long = 0,
)

@Serializable
data class BackupSource(
    @ProtoNumber(1) val name: String = "",
    @ProtoNumber(2) val sourceId: Long,
)
