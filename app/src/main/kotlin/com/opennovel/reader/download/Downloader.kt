package com.opennovel.reader.download

import android.content.Context
import com.opennovel.reader.data.db.ChapterDao
import com.opennovel.reader.data.db.NovelDao
import com.opennovel.reader.source.SourceManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Downloads chapter text to app storage for offline reading. Persists the body
 * as a UTF-8 file and flips the chapter's downloaded flag. A production build
 * would run this inside a foreground WorkManager job with a queue + retries;
 * this implementation is queue-ready (see [enqueue]) and progress-observable.
 */
class Downloader(
    private val context: Context,
    private val novelDao: NovelDao,
    private val chapterDao: ChapterDao,
    private val sourceManager: SourceManager,
) {
    private val _progress = MutableStateFlow<Map<Long, DownloadState>>(emptyMap())
    val progress: StateFlow<Map<Long, DownloadState>> = _progress.asStateFlow()

    private val root: File by lazy { File(context.filesDir, "downloads").apply { mkdirs() } }

    suspend fun download(chapterId: Long): Result<File> = withContext(Dispatchers.IO) {
        val chapter = chapterDao.getById(chapterId) ?: return@withContext Result.failure(
            IllegalStateException("Chapter $chapterId not found"),
        )
        val novel = novelDao.getById(chapter.novelId) ?: return@withContext Result.failure(
            IllegalStateException("Novel not found"),
        )
        val source = sourceManager.get(novel.sourceId) ?: return@withContext Result.failure(
            IllegalStateException("Source ${novel.sourceId} not installed"),
        )
        setState(chapterId, DownloadState.RUNNING)
        runCatching {
            val text = source.getChapterText(chapter.url)
            val dir = File(root, novel.id.toString()).apply { mkdirs() }
            val file = File(dir, "$chapterId.txt")
            file.writeText(text.plain, Charsets.UTF_8)
            chapterDao.setDownloadState(chapterId, downloaded = true, path = file.absolutePath)
            setState(chapterId, DownloadState.DONE)
            file
        }.onFailure { setState(chapterId, DownloadState.FAILED) }
    }

    suspend fun enqueue(chapterIds: List<Long>) {
        // Simple sequential drain; swap for WorkManager for background durability.
        chapterIds.forEach { download(it) }
    }

    fun readLocal(path: String): String? = runCatching { File(path).readText(Charsets.UTF_8) }.getOrNull()

    suspend fun delete(chapterId: Long) = withContext(Dispatchers.IO) {
        val chapter = chapterDao.getById(chapterId) ?: return@withContext
        chapter.downloadPath?.let { File(it).delete() }
        chapterDao.setDownloadState(chapterId, downloaded = false, path = null)
        setState(chapterId, DownloadState.NONE)
    }

    private fun setState(id: Long, state: DownloadState) {
        _progress.value = _progress.value.toMutableMap().apply { put(id, state) }
    }
}

enum class DownloadState { NONE, RUNNING, DONE, FAILED }
