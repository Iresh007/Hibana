package com.opennovel.reader.backup

import com.opennovel.reader.data.db.CategoryEntity
import com.opennovel.reader.data.db.ChapterEntity
import com.opennovel.reader.data.db.NovelCategoryCrossRef
import com.opennovel.reader.data.db.NovelEntity

/** In-memory [BackupStore] mirroring the Room constraints the importers rely on. */
internal class FakeBackupStore : BackupStore {
    val novels = mutableListOf<NovelEntity>()
    val chapters = mutableListOf<ChapterEntity>()
    val cats = mutableListOf<CategoryEntity>()
    val refs = mutableListOf<NovelCategoryCrossRef>()
    val historyRows = mutableMapOf<Long, HistoryRow>()
    val lastRead = mutableMapOf<Long, Long>()
    val sourceNames = mutableMapOf<Long, String>()
    private var nextId = 1L

    fun novel(url: String) = novels.first { it.url == url }
    fun chaptersFor(novelId: Long) = chapters.filter { it.novelId == novelId }.sortedBy { it.sourceOrder }

    override suspend fun libraryNovels() = novels.filter { it.inLibrary }
    override suspend fun categories() = cats.sortedBy { it.order }
    override suspend fun assignments() = refs.toList()
    override suspend fun chaptersOf(novelId: Long) = chaptersFor(novelId)
    override suspend fun history() = historyRows.values.toList()
    override fun sourceName(sourceId: Long) = sourceNames[sourceId]

    override suspend fun ensureCategory(name: String, order: Int): Long {
        cats.firstOrNull { it.name == name }?.let { return it.id }
        val c = CategoryEntity(id = nextId++, name = name, order = order)
        cats.add(c)
        return c.id
    }

    override suspend fun findNovel(sourceId: Long, url: String) =
        novels.firstOrNull { it.sourceId == sourceId && it.url == url }

    override suspend fun saveNovel(novel: NovelEntity): Long {
        if (novel.id != 0L) {
            novels[novels.indexOfFirst { it.id == novel.id }] = novel
            return novel.id
        }
        val saved = novel.copy(id = nextId++)
        novels.add(saved)
        return saved.id
    }

    override suspend fun insertChapters(chapters: List<ChapterEntity>) {
        chapters.forEach { c ->
            if (this.chapters.none { it.novelId == c.novelId && it.url == c.url }) {
                this.chapters.add(c.copy(id = nextId++))
            }
        }
    }

    override suspend fun updateChapter(chapter: ChapterEntity) {
        chapters[chapters.indexOfFirst { it.id == chapter.id }] = chapter
    }

    override suspend fun assignCategory(novelId: Long, categoryId: Long) {
        val ref = NovelCategoryCrossRef(novelId, categoryId)
        if (ref !in refs) refs.add(ref)
    }

    override suspend fun setLastRead(novelId: Long, chapterId: Long) { lastRead[novelId] = chapterId }

    override suspend fun saveHistory(novelId: Long, chapterId: Long, readAt: Long) {
        historyRows[novelId] = HistoryRow(novelId, chapterId, readAt)
    }
}
