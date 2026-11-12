package com.opennovel.reader.backup

import com.opennovel.reader.data.db.CategoryDao
import com.opennovel.reader.data.db.CategoryEntity
import com.opennovel.reader.data.db.ChapterDao
import com.opennovel.reader.data.db.ChapterEntity
import com.opennovel.reader.data.db.HistoryDao
import com.opennovel.reader.data.db.HistoryEntity
import com.opennovel.reader.data.db.NovelCategoryCrossRef
import com.opennovel.reader.data.db.NovelDao
import com.opennovel.reader.data.db.NovelEntity
import com.opennovel.reader.source.SourceManager
import kotlinx.coroutines.flow.first

/** One entry of the history table: the latest chapter read for a novel. */
internal data class HistoryRow(val novelId: Long, val chapterId: Long, val readAt: Long)

/**
 * The slice of persistence the backup code needs. Importers and exporters talk
 * to this instead of the Room DAOs so the format logic can be unit tested with
 * an in-memory fake.
 */
internal interface BackupStore {
    suspend fun libraryNovels(): List<NovelEntity>
    suspend fun categories(): List<CategoryEntity>
    suspend fun assignments(): List<NovelCategoryCrossRef>
    suspend fun chaptersOf(novelId: Long): List<ChapterEntity>
    suspend fun history(): List<HistoryRow>
    fun sourceName(sourceId: Long): String?

    /** Returns the id of the category with [name], creating it with [order] if missing. */
    suspend fun ensureCategory(name: String, order: Int): Long
    suspend fun findNovel(sourceId: Long, url: String): NovelEntity?

    /** Inserts when [novel].id is 0, otherwise updates; returns the row id. */
    suspend fun saveNovel(novel: NovelEntity): Long
    suspend fun insertChapters(chapters: List<ChapterEntity>)
    suspend fun updateChapter(chapter: ChapterEntity)
    suspend fun assignCategory(novelId: Long, categoryId: Long)
    suspend fun setLastRead(novelId: Long, chapterId: Long)

    /** No-op when history storage is not wired. */
    suspend fun saveHistory(novelId: Long, chapterId: Long, readAt: Long)
}

internal class RoomBackupStore(
    private val novelDao: NovelDao,
    private val chapterDao: ChapterDao,
    private val categoryDao: CategoryDao,
    private val historyDao: HistoryDao?,
    private val sourceManager: SourceManager?,
) : BackupStore {

    override suspend fun libraryNovels() = novelDao.getAllInLibrary()
    override suspend fun categories() = categoryDao.getAll()
    override suspend fun assignments() = categoryDao.allAssignments()
    override suspend fun chaptersOf(novelId: Long) = chapterDao.getForNovel(novelId)

    override suspend fun history(): List<HistoryRow> =
        historyDao?.observeHistory()?.first()
            ?.map { HistoryRow(it.novelId, it.chapterId, it.readAt) }
            .orEmpty()

    override fun sourceName(sourceId: Long): String? = sourceManager?.get(sourceId)?.name

    override suspend fun ensureCategory(name: String, order: Int): Long {
        categoryDao.getAll().firstOrNull { it.name == name }?.let { return it.id }
        val id = categoryDao.insert(CategoryEntity(name = name, order = order))
        return if (id != -1L) id else categoryDao.getAll().first { it.name == name }.id
    }

    override suspend fun findNovel(sourceId: Long, url: String) = novelDao.findByUrl(sourceId, url)

    override suspend fun saveNovel(novel: NovelEntity): Long =
        if (novel.id != 0L) {
            novelDao.update(novel); novel.id
        } else {
            novelDao.upsert(novel)
        }

    override suspend fun insertChapters(chapters: List<ChapterEntity>) = chapterDao.insertAll(chapters)
    override suspend fun updateChapter(chapter: ChapterEntity) = chapterDao.update(chapter)
    override suspend fun assignCategory(novelId: Long, categoryId: Long) =
        categoryDao.assign(NovelCategoryCrossRef(novelId, categoryId))

    override suspend fun setLastRead(novelId: Long, chapterId: Long) =
        novelDao.setLastReadChapter(novelId, chapterId)

    override suspend fun saveHistory(novelId: Long, chapterId: Long, readAt: Long) {
        historyDao?.upsert(HistoryEntity(novelId = novelId, chapterId = chapterId, readAt = readAt))
    }
}

/**
 * Merges an incoming chapter onto one already stored for the same url, so a
 * restore never un-reads, un-bookmarks or regresses existing progress.
 * Returns null when nothing would change.
 */
internal fun mergeChapter(existing: ChapterEntity, incoming: ChapterEntity): ChapterEntity? {
    val merged = existing.copy(
        read = existing.read || incoming.read,
        bookmark = existing.bookmark || incoming.bookmark,
        lastReadOffset = maxOf(existing.lastReadOffset, incoming.lastReadOffset)
            .let { if (existing.read || incoming.read) 1f else it },
        dateFetch = if (existing.dateFetch > 0L) existing.dateFetch else incoming.dateFetch,
        dateUpload = if (existing.dateUpload > 0L) existing.dateUpload else incoming.dateUpload,
    )
    return if (merged == existing) null else merged
}
