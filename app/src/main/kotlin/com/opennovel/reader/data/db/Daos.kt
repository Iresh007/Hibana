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

    @Query("UPDATE novels SET contentType = :type WHERE id = :novelId")
    suspend fun setContentType(novelId: Long, type: String)

    /**
     * Entries cached while browsing that were never added to the library.
     *
     * Kept separate from a blanket delete so "clear database" can never remove
     * something the user actually shelved — the chapters and history of library
     * entries hang off them by foreign key and would cascade away with them.
     */
    @Query("SELECT COUNT(*) FROM novels WHERE inLibrary = 0")
    suspend fun countNotInLibrary(): Int

    @Query("DELETE FROM novels WHERE inLibrary = 0")
    suspend fun deleteNotInLibrary(): Int
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
     * The Updates feed: chapters that arrived *after* the entry joined the
     * library, newest first.
     *
     * `dateFetch > n.dateAdded` is what makes this a feed of updates rather than
     * a dump of the library. Adding a 900-chapter series would otherwise push 900
     * rows into Updates as though they were all new, burying anything that
     * genuinely just released.
     *
     * Ordered by dateFetch, not dateUpload, because a large share of sources
     * report no upload date at all; ordering by it puts those at the epoch.
     */
    @Query(
        """
        SELECT c.id AS chapterId, c.novelId AS novelId, c.name AS name, c.url AS url,
               c.read AS read, c.bookmark AS bookmark, c.downloaded AS downloaded,
               c.dateUpload AS dateUpload, c.dateFetch AS dateFetch,
               n.title AS novelTitle, n.coverUrl AS coverUrl, n.contentType AS contentType
        FROM chapters c
        JOIN novels n ON n.id = c.novelId
        WHERE n.inLibrary = 1 AND c.dateFetch > n.dateAdded
        ORDER BY c.dateFetch DESC, c.id DESC
        LIMIT 500
        """,
    )
    fun observeRecentChapters(): Flow<List<ChapterWithNovel>>

    /** How many entries currently have at least one unseen update. */
    @Query(
        """
        SELECT COUNT(DISTINCT c.novelId) FROM chapters c
        JOIN novels n ON n.id = c.novelId
        WHERE n.inLibrary = 1 AND c.dateFetch > n.dateAdded AND c.read = 0
        """,
    )
    fun observeUpdatedEntryCount(): Flow<Int>

    /**
     * Rows inserted by a refresh, stamped with the moment we first saw them.
     * Returns the new row ids; IGNORE yields -1 for chapters we already had, so
     * the caller can tell a genuine update from a no-op re-fetch.
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAllReturningIds(chapters: List<ChapterEntity>): List<Long>

    @Query("UPDATE chapters SET bookmark = :bookmark WHERE id = :id")
    suspend fun setBookmark(id: Long, bookmark: Boolean)

    @Query("UPDATE chapters SET read = :read, lastReadOffset = :offset WHERE id IN (:ids)")
    suspend fun setReadStateFor(ids: List<Long>, read: Boolean, offset: Float)

    @Query("UPDATE chapters SET bookmark = :bookmark WHERE id IN (:ids)")
    suspend fun setBookmarkFor(ids: List<Long>, bookmark: Boolean)

    @Query("SELECT * FROM chapters WHERE id IN (:ids)")
    suspend fun getByIds(ids: List<Long>): List<ChapterEntity>

    /**
     * Exact tallies over the whole library, for Statistics. Counted in SQL
     * because the feed queries are capped and scoped — deriving these from them
     * would silently under-report once a library outgrew the cap.
     */
    @Query(
        """
        SELECT COUNT(*) FROM chapters c JOIN novels n ON n.id = c.novelId
        WHERE n.inLibrary = 1 AND c.bookmark = 1
        """,
    )
    fun observeBookmarkedCount(): Flow<Int>

    @Query(
        """
        SELECT COUNT(*) FROM chapters c JOIN novels n ON n.id = c.novelId
        WHERE n.inLibrary = 1 AND c.downloaded = 1
        """,
    )
    fun observeDownloadedCount(): Flow<Int>

    /**
     * Release timestamps for library entries, newest first, used to estimate
     * each entry's publishing cadence.
     *
     * Falls back to dateFetch when the source publishes no upload date, since
     * for a regularly-checked library the two track each other closely enough to
     * estimate an interval — and a source with no dates at all would otherwise
     * be excluded from the schedule entirely.
     */
    @Query(
        """
        SELECT c.novelId AS novelId,
               (CASE WHEN c.dateUpload > 0 THEN c.dateUpload ELSE c.dateFetch END) AS releasedAt
        FROM chapters c
        JOIN novels n ON n.id = c.novelId
        WHERE n.inLibrary = 1
          AND (c.dateUpload > 0 OR c.dateFetch > 0)
        ORDER BY c.novelId ASC, releasedAt DESC
        """,
    )
    fun observeReleaseTimes(): Flow<List<ChapterRelease>>

    /** Chapters already downloaded, for the download manager. */
    @Query(
        """
        SELECT c.id AS chapterId, c.novelId AS novelId, c.name AS name, c.url AS url,
               c.read AS read, c.bookmark AS bookmark, c.downloaded AS downloaded,
               c.dateUpload AS dateUpload, c.dateFetch AS dateFetch,
               n.title AS novelTitle, n.coverUrl AS coverUrl, n.contentType AS contentType
        FROM chapters c
        JOIN novels n ON n.id = c.novelId
        WHERE c.downloaded = 1
        ORDER BY n.title COLLATE NOCASE ASC, c.sourceOrder ASC
        """,
    )
    fun observeDownloaded(): Flow<List<ChapterWithNovel>>

    @Query("SELECT id FROM chapters WHERE novelId = :novelId AND downloaded = 0 ORDER BY sourceOrder ASC")
    suspend fun undownloadedIds(novelId: Long): List<Long>

    /** Every stored download path, for clearing the on-disk cache. */
    @Query("SELECT downloadPath FROM chapters WHERE downloaded = 1 AND downloadPath IS NOT NULL")
    suspend fun allDownloadPaths(): List<String>

    @Query("UPDATE chapters SET downloaded = 0, downloadPath = NULL WHERE downloaded = 1")
    suspend fun clearAllDownloadFlags(): Int

    /** Unread/downloaded tallies per novel, for library cover badges. */
    @Query(
        """
        SELECT novelId AS novelId,
               SUM(CASE WHEN read = 0 THEN 1 ELSE 0 END) AS unread,
               SUM(CASE WHEN downloaded = 1 THEN 1 ELSE 0 END) AS downloaded,
               COUNT(*) AS total,
               MAX(dateUpload) AS latestUpload
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
               n.title AS title, n.coverUrl AS coverUrl, c.name AS chapterName,
               n.contentType AS contentType
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
