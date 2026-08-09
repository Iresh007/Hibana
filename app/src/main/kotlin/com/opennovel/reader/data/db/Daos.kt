package com.opennovel.reader.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

// (History DAO defined at end of file.)

@Dao
interface NovelDao {

    @Query("SELECT * FROM novels WHERE inLibrary = 1 ORDER BY title COLLATE NOCASE ASC")
    fun observeLibrary(): Flow<List<NovelEntity>>

    @Query("SELECT * FROM novels WHERE inLibrary = 1")
    suspend fun getAllInLibrary(): List<NovelEntity>

    @Query("SELECT * FROM novels WHERE id = :id")
    fun observeNovel(id: Long): Flow<NovelEntity?>

    @Query("SELECT * FROM novels WHERE sourceId = :sourceId AND url = :url LIMIT 1")
    suspend fun findByUrl(sourceId: Long, url: String): NovelEntity?

    @Query("SELECT * FROM novels WHERE id = :id")
    suspend fun getById(id: Long): NovelEntity?

    @Upsert
    suspend fun upsert(novel: NovelEntity): Long

    @Update
    suspend fun update(novel: NovelEntity)

    @Query("UPDATE novels SET inLibrary = :inLibrary, dateAdded = :date WHERE id = :id")
    suspend fun setInLibrary(id: Long, inLibrary: Boolean, date: Long)

    @Query("UPDATE novels SET lastReadChapterId = :chapterId WHERE id = :novelId")
    suspend fun setLastReadChapter(novelId: Long, chapterId: Long)
}

@Dao
interface ChapterDao {

    @Query("SELECT * FROM chapters WHERE novelId = :novelId ORDER BY sourceOrder ASC")
    fun observeChapters(novelId: Long): Flow<List<ChapterEntity>>

    @Query("SELECT * FROM chapters WHERE id = :id")
    suspend fun getById(id: Long): ChapterEntity?

    @Query("SELECT * FROM chapters WHERE novelId = :novelId ORDER BY sourceOrder ASC")
    suspend fun getForNovel(novelId: Long): List<ChapterEntity>

    @Query("SELECT id FROM chapters WHERE novelId = :novelId ORDER BY sourceOrder ASC LIMIT 1")
    suspend fun firstChapterId(novelId: Long): Long?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(chapters: List<ChapterEntity>)

    @Update
    suspend fun update(chapter: ChapterEntity)

    @Query("UPDATE chapters SET read = :read, lastReadOffset = :offset WHERE id = :id")
    suspend fun setReadState(id: Long, read: Boolean, offset: Float)

    @Query("UPDATE chapters SET downloaded = :downloaded, downloadPath = :path WHERE id = :id")
    suspend fun setDownloadState(id: Long, downloaded: Boolean, path: String?)

    /**
     * Recent chapters across the whole library, newest first — the Updates feed.
     * Restricted to library novels so browsing doesn't pollute it.
     */
    @Query(
        """
        SELECT c.id AS chapterId, c.novelId AS novelId, c.name AS name, c.url AS url,
               c.read AS read, c.downloaded AS downloaded, c.dateUpload AS dateUpload,
               n.title AS novelTitle, n.coverUrl AS coverUrl
        FROM chapters c
        JOIN novels n ON n.id = c.novelId
        WHERE n.inLibrary = 1
        ORDER BY c.dateUpload DESC, c.id DESC
        LIMIT 300
        """,
    )
    fun observeRecentChapters(): Flow<List<ChapterWithNovel>>

    /** Chapters already downloaded, for the download manager. */
    @Query(
        """
        SELECT c.id AS chapterId, c.novelId AS novelId, c.name AS name, c.url AS url,
               c.read AS read, c.downloaded AS downloaded, c.dateUpload AS dateUpload,
               n.title AS novelTitle, n.coverUrl AS coverUrl
        FROM chapters c
        JOIN novels n ON n.id = c.novelId
        WHERE c.downloaded = 1
        ORDER BY n.title COLLATE NOCASE ASC, c.sourceOrder ASC
        """,
    )
    fun observeDownloaded(): Flow<List<ChapterWithNovel>>

    @Query("SELECT id FROM chapters WHERE novelId = :novelId AND downloaded = 0 ORDER BY sourceOrder ASC")
    suspend fun undownloadedIds(novelId: Long): List<Long>

    /** Unread/downloaded tallies per novel, for library cover badges. */
    @Query(
        """
        SELECT novelId AS novelId,
               SUM(CASE WHEN read = 0 THEN 1 ELSE 0 END) AS unread,
               SUM(CASE WHEN downloaded = 1 THEN 1 ELSE 0 END) AS downloaded,
               COUNT(*) AS total
        FROM chapters
        GROUP BY novelId
        """,
    )
    fun observeNovelCounts(): Flow<List<NovelCounts>>
}

@Dao
interface CategoryDao {

    @Query("SELECT * FROM categories ORDER BY `order` ASC, name COLLATE NOCASE ASC")
    fun observeCategories(): Flow<List<CategoryEntity>>

    @Query("SELECT COUNT(*) FROM categories")
    suspend fun count(): Int

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(category: CategoryEntity): Long

    @Update
    suspend fun update(category: CategoryEntity)

    @Query("UPDATE categories SET name = :name WHERE id = :id")
    suspend fun rename(id: Long, name: String)

    @Query("DELETE FROM categories WHERE id = :id")
    suspend fun delete(id: Long)

    // --- assignment ---

    @Query("SELECT categoryId FROM novel_categories WHERE novelId = :novelId")
    suspend fun categoryIdsForNovel(novelId: Long): List<Long>

    @Query("SELECT categoryId FROM novel_categories WHERE novelId = :novelId")
    fun observeCategoryIdsForNovel(novelId: Long): Flow<List<Long>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun assign(ref: NovelCategoryCrossRef)

    @Query("DELETE FROM novel_categories WHERE novelId = :novelId AND categoryId = :categoryId")
    suspend fun unassign(novelId: Long, categoryId: Long)

    @Query("DELETE FROM novel_categories WHERE novelId = :novelId")
    suspend fun clearAssignments(novelId: Long)

    @Query("SELECT * FROM categories ORDER BY `order` ASC")
    suspend fun getAll(): List<CategoryEntity>

    @Query("SELECT * FROM novel_categories")
    suspend fun allAssignments(): List<NovelCategoryCrossRef>

    /** All (novelId, categoryId) pairs, so the library can be grouped in one pass. */
    @Query("SELECT * FROM novel_categories")
    fun observeAllAssignments(): Flow<List<NovelCategoryCrossRef>>
}

@Dao
interface HistoryDao {

    @Query(
        """
        SELECT h.novelId AS novelId, h.chapterId AS chapterId, h.readAt AS readAt,
               n.title AS title, n.coverUrl AS coverUrl, c.name AS chapterName
        FROM history h
        JOIN novels n ON n.id = h.novelId
        JOIN chapters c ON c.id = h.chapterId
        ORDER BY h.readAt DESC
        """,
    )
    fun observeHistory(): Flow<List<HistoryWithNovel>>

    /** Replaces any existing row for the same novel (unique novelId index). */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entry: HistoryEntity)

    @Query("DELETE FROM history WHERE novelId = :novelId")
    suspend fun deleteForNovel(novelId: Long)

    @Query("DELETE FROM history")
    suspend fun clear()
}
