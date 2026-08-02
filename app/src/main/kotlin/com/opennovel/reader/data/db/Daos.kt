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
