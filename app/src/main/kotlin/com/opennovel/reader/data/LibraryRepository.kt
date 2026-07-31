package com.opennovel.reader.data

import com.opennovel.reader.data.db.ChapterDao
import com.opennovel.reader.data.db.ChapterEntity
import com.opennovel.reader.data.db.NovelDao
import com.opennovel.reader.data.db.NovelEntity
import com.opennovel.reader.source.SourceManager
import com.opennovel.reader.source.model.SChapter
import com.opennovel.reader.source.model.SNovel
import kotlinx.coroutines.flow.Flow

/**
 * Single source of truth over library + chapters. Coordinates the local Room
 * store with remote sources: fetching details/chapters, adding to library,
 * and persisting read/download state.
 */
class LibraryRepository(
    private val novelDao: NovelDao,
    private val chapterDao: ChapterDao,
    private val sourceManager: SourceManager,
) {
    fun observeLibrary(): Flow<List<NovelEntity>> = novelDao.observeLibrary()

    fun observeNovel(id: Long): Flow<NovelEntity?> = novelDao.observeNovel(id)

    fun observeChapters(novelId: Long): Flow<List<ChapterEntity>> =
        chapterDao.observeChapters(novelId)

    suspend fun getChapter(id: Long): ChapterEntity? = chapterDao.getById(id)

    suspend fun getNovel(id: Long): NovelEntity? = novelDao.getById(id)

    /** The chapter to open when the user taps a library item: resume point or first. */
    suspend fun resumeChapterId(novelId: Long): Long? {
        val novel = novelDao.getById(novelId)
        return novel?.lastReadChapterId ?: chapterDao.firstChapterId(novelId)
    }

    /** Resolves the owning source and fetches a chapter body from the network. */
    suspend fun fetchChapterText(chapter: ChapterEntity): com.opennovel.reader.source.model.ChapterText? {
        val novel = novelDao.getById(chapter.novelId) ?: return null
        val source = sourceManager.get(novel.sourceId) ?: return null
        return runCatching { source.getChapterText(chapter.url) }.getOrNull()
    }

    /** Ensures a source result exists locally; returns the local novel id. */
    suspend fun cacheNovel(sourceId: Long, novel: SNovel): Long {
        val existing = novelDao.findByUrl(sourceId, novel.url)
        val entity = (existing ?: NovelEntity(sourceId = sourceId, url = novel.url, title = novel.title)).copy(
            title = novel.title,
            author = novel.author ?: existing?.author,
            description = novel.description ?: existing?.description,
            coverUrl = novel.coverUrl ?: existing?.coverUrl,
            genres = novel.genres.joinToString(","),
            status = novel.status.name,
        )
        return if (existing != null) {
            novelDao.update(entity); entity.id
        } else {
            novelDao.upsert(entity)
        }
    }

    suspend fun addToLibrary(novelId: Long, inLibrary: Boolean) {
        novelDao.setInLibrary(novelId, inLibrary, System.currentTimeMillis())
    }

    /** Fetches chapters from the source and persists any new ones. */
    suspend fun refreshChapters(novelId: Long) {
        val novel = novelDao.getById(novelId) ?: return
        val source = sourceManager.get(novel.sourceId) ?: return
        val remote = source.getChapterList(novel.url)
        val entities = remote.mapIndexed { index, ch: SChapter ->
            ChapterEntity(
                novelId = novelId,
                url = ch.url,
                name = ch.name,
                number = ch.number,
                dateUpload = ch.dateUpload,
                sourceOrder = index,
            )
        }
        chapterDao.insertAll(entities)
    }

    suspend fun markRead(chapterId: Long, read: Boolean, offset: Float = 0f) {
        chapterDao.setReadState(chapterId, read, offset)
    }

    suspend fun saveProgress(novelId: Long, chapterId: Long, offset: Float) {
        chapterDao.setReadState(chapterId, read = offset >= 0.98f, offset = offset)
        novelDao.setLastReadChapter(novelId, chapterId)
    }
}
