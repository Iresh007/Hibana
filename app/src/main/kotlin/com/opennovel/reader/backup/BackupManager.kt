@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package com.opennovel.reader.backup

import com.opennovel.reader.data.db.CategoryDao
import com.opennovel.reader.data.db.CategoryEntity
import com.opennovel.reader.data.db.ChapterDao
import com.opennovel.reader.data.db.ChapterEntity
import com.opennovel.reader.data.db.NovelCategoryCrossRef
import com.opennovel.reader.data.db.NovelDao
import com.opennovel.reader.data.db.NovelEntity
import com.opennovel.reader.source.SourceManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.protobuf.ProtoBuf
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/** Summary shown to the user after a restore. */
data class ImportResult(val novels: Int, val chapters: Int, val categories: Int)

/**
 * Reads and writes Tachiyomi/Mihon `.tachibk` backups (protobuf + gzip). Hibana's
 * own export uses the identical format, so a Hibana backup restores into Mihon and
 * a Mihon backup restores into Hibana. Novels are matched by (source, url) and
 * merged, not duplicated.
 */
class BackupManager(
    private val novelDao: NovelDao,
    private val chapterDao: ChapterDao,
    private val categoryDao: CategoryDao,
    private val sourceManager: SourceManager,
) {

    suspend fun export(out: OutputStream): Unit = withContext(Dispatchers.IO) {
        val novels = novelDao.getAllInLibrary()
        val categories = categoryDao.getAll()
        val assignments = categoryDao.allAssignments()
        val catOrderById = categories.associate { it.id to it.order.toLong() }

        val backup = Backup(
            backupManga = novels.map { novel ->
                BackupManga(
                    source = novel.sourceId,
                    url = novel.url,
                    title = novel.title,
                    author = novel.author.orEmpty(),
                    description = novel.description.orEmpty(),
                    genre = novel.genres.split(",").map { it.trim() }.filter { it.isNotEmpty() },
                    status = statusToInt(novel.status),
                    thumbnailUrl = novel.coverUrl.orEmpty(),
                    dateAdded = novel.dateAdded,
                    chapters = chapterDao.getForNovel(novel.id).map { it.toBackup() },
                    categories = assignments.filter { it.novelId == novel.id }
                        .mapNotNull { catOrderById[it.categoryId] },
                )
            },
            backupCategories = categories.map {
                BackupCategory(name = it.name, order = it.order.toLong())
            },
            backupSources = novels.map { it.sourceId }.distinct().map {
                BackupSource(name = sourceManager.get(it)?.name.orEmpty(), sourceId = it)
            },
        )

        val bytes = ProtoBuf.encodeToByteArray(Backup.serializer(), backup)
        GZIPOutputStream(out).use { it.write(bytes) }
    }

    suspend fun import(input: InputStream): ImportResult = withContext(Dispatchers.IO) {
        val bytes = GZIPInputStream(input).use { it.readBytes() }
        val backup = ProtoBuf.decodeFromByteArray(Backup.serializer(), bytes)

        // Map backup category "order" values onto local category ids, creating any missing.
        val existingByName = categoryDao.getAll().associateBy { it.name }.toMutableMap()
        val orderToLocalId = mutableMapOf<Long, Long>()
        backup.backupCategories.forEach { bc ->
            val local = existingByName[bc.name]
            val id = if (local != null) {
                local.id
            } else {
                val newId = categoryDao.insert(CategoryEntity(name = bc.name, order = bc.order.toInt()))
                if (newId != -1L) newId else categoryDao.getAll().first { it.name == bc.name }.id
            }
            orderToLocalId[bc.order] = id
        }

        var novelCount = 0
        var chapterCount = 0
        backup.backupManga.forEach { bm ->
            val existing = novelDao.findByUrl(bm.source, bm.url)
            val entity = (existing ?: NovelEntity(sourceId = bm.source, url = bm.url, title = bm.title)).copy(
                title = bm.title,
                author = bm.author.ifBlank { null },
                description = bm.description.ifBlank { null },
                coverUrl = bm.thumbnailUrl.ifBlank { null },
                genres = bm.genre.joinToString(","),
                status = intToStatus(bm.status),
                inLibrary = true,
                dateAdded = if (bm.dateAdded > 0) bm.dateAdded else System.currentTimeMillis(),
            )
            val novelId = if (existing != null) {
                novelDao.update(entity); entity.id
            } else {
                novelDao.upsert(entity)
            }
            novelCount++

            val chapters = bm.chapters.map { bc ->
                ChapterEntity(
                    novelId = novelId,
                    url = bc.url,
                    name = bc.name,
                    number = bc.chapterNumber,
                    dateUpload = bc.dateUpload,
                    read = bc.read,
                    lastReadOffset = if (bc.read) 1f else 0f,
                    sourceOrder = bc.sourceOrder.toInt(),
                )
            }
            chapterDao.insertAll(chapters)
            chapterCount += chapters.size

            bm.categories.forEach { order ->
                orderToLocalId[order]?.let { categoryDao.assign(NovelCategoryCrossRef(novelId, it)) }
            }
        }

        ImportResult(novelCount, chapterCount, backup.backupCategories.size)
    }

    private fun ChapterEntity.toBackup() = BackupChapter(
        url = url,
        name = name,
        read = read,
        lastPageRead = 0,
        dateUpload = dateUpload,
        chapterNumber = number,
        sourceOrder = sourceOrder.toLong(),
    )

    // Tachiyomi status ints: 0 unknown, 1 ongoing, 2 completed, 5 cancelled, 6 on-hiatus.
    private fun statusToInt(status: String): Int = when (status) {
        "ONGOING" -> 1
        "COMPLETED" -> 2
        "CANCELLED" -> 5
        "HIATUS" -> 6
        else -> 0
    }

    private fun intToStatus(value: Int): String = when (value) {
        1 -> "ONGOING"
        2, 4 -> "COMPLETED"
        5 -> "CANCELLED"
        6 -> "HIATUS"
        else -> "UNKNOWN"
    }
}
