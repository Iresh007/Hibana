package com.opennovel.reader.download

import android.content.Context
import com.opennovel.reader.data.db.ChapterDao
import com.opennovel.reader.data.db.NovelDao
import com.opennovel.reader.source.SourceManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/** Where a queued chapter is in its life cycle. */
enum class QueueState { QUEUED, DOWNLOADING, DONE, FAILED }

/** One row of the download queue, carrying the labels the queue UI needs. */
data class QueuedDownload(
    val chapterId: Long,
    val novelId: Long,
    val novelTitle: String,
    val chapterName: String,
    val state: QueueState = QueueState.QUEUED,
)

/**
 * Downloads chapter text to app storage for offline reading, driven by a single
 * observable queue.
 *
 * The queue is drained by one worker at a time rather than fanning out: sources
 * are third-party sites and hammering them with parallel requests is the fastest
 * way to get rate-limited or banned. Completed items leave the queue shortly
 * after finishing, so the queue always reads as "what is still pending".
 */
class Downloader(
    private val context: Context,
    private val novelDao: NovelDao,
    private val chapterDao: ChapterDao,
    private val sourceManager: SourceManager,
) {
    private val _progress = MutableStateFlow<Map<Long, DownloadState>>(emptyMap())
    val progress: StateFlow<Map<Long, DownloadState>> = _progress.asStateFlow()

    private val _queue = MutableStateFlow<List<QueuedDownload>>(emptyList())
    val queue: StateFlow<List<QueuedDownload>> = _queue.asStateFlow()

    // Survives the ViewModels that trigger downloads, so navigating away from
    // the Downloads screen doesn't abort an in-flight chapter.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val workerLock = Mutex()
    private var worker: Job? = null

    /**
     * User-chosen download folder, if any. Public so the Downloads screen can
     * offer the folder picker without a second source of truth.
     */
    val storage = DownloadStorage(context)

    private val root: File by lazy { storage.defaultRoot().apply { mkdirs() } }

    /** Downloads one chapter immediately, bypassing the queue. Yields its location. */
    suspend fun download(chapterId: Long): Result<String> = fetch(chapterId)

    /** Adds chapters to the queue (skipping duplicates) and starts the drain. */
    suspend fun enqueue(chapterIds: List<Long>) {
        if (chapterIds.isEmpty()) return
        val known = _queue.value.map { it.chapterId }.toSet()
        val fresh = chapterIds.filter { it !in known }.mapNotNull { id ->
            val chapter = withContext(Dispatchers.IO) { chapterDao.getById(id) } ?: return@mapNotNull null
            if (chapter.downloaded) return@mapNotNull null
            val novel = withContext(Dispatchers.IO) { novelDao.getById(chapter.novelId) }
            QueuedDownload(
                chapterId = chapter.id,
                novelId = chapter.novelId,
                novelTitle = novel?.title ?: "Unknown",
                chapterName = chapter.name,
            )
        }
        if (fresh.isEmpty()) return
        _queue.value = _queue.value + fresh
        startWorker()
    }

    suspend fun enqueue(chapterId: Long) = enqueue(listOf(chapterId))

    /**
     * Drops a queued item. Cancelling the item currently downloading tears down
     * the worker and restarts it, which is the only way to interrupt a network
     * read already in flight.
     */
    fun cancel(chapterId: Long) {
        val wasRunning = _queue.value.any { it.chapterId == chapterId && it.state == QueueState.DOWNLOADING }
        _queue.value = _queue.value.filterNot { it.chapterId == chapterId }
        setState(chapterId, DownloadState.NONE)
        if (wasRunning) {
            worker?.cancel()
            worker = null
            scope.launch { startWorker() }
        }
    }

    fun cancelAll() {
        _queue.value = emptyList()
        worker?.cancel()
        worker = null
    }

    private suspend fun startWorker() = workerLock.withLock {
        if (worker?.isActive == true) return@withLock
        worker = scope.launch {
            while (true) {
                val next = _queue.value.firstOrNull { it.state == QueueState.QUEUED } ?: break
                updateQueue(next.chapterId) { it.copy(state = QueueState.DOWNLOADING) }
                val ok = fetch(next.chapterId).isSuccess
                if (ok) {
                    updateQueue(next.chapterId) { it.copy(state = QueueState.DONE) }
                    // Brief pause so a completed row is visible before it goes.
                    delay(600)
                    _queue.value = _queue.value.filterNot { it.chapterId == next.chapterId }
                } else {
                    updateQueue(next.chapterId) { it.copy(state = QueueState.FAILED) }
                }
            }
        }
    }

    /** Requeues a failed item so the worker picks it up again. */
    fun retry(chapterId: Long) {
        updateQueue(chapterId) { it.copy(state = QueueState.QUEUED) }
        scope.launch { startWorker() }
    }

    private fun updateQueue(chapterId: Long, transform: (QueuedDownload) -> QueuedDownload) {
        _queue.value = _queue.value.map { if (it.chapterId == chapterId) transform(it) else it }
    }

    private suspend fun fetch(chapterId: Long): Result<String> = withContext(Dispatchers.IO) {
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
            // A configured folder wins; without one this stays exactly where
            // downloads have always gone, so nothing already stored is orphaned.
            val location = storage.writeChapter(novel.id, chapterId, text.plain)
                ?: File(root, novel.id.toString())
                    .apply { mkdirs() }
                    .let { dir -> File(dir, "$chapterId.txt") }
                    .also { it.writeText(text.plain, Charsets.UTF_8) }
                    .absolutePath
            chapterDao.setDownloadState(chapterId, downloaded = true, path = location)
            setState(chapterId, DownloadState.DONE)
            location
        }.onFailure { setState(chapterId, DownloadState.FAILED) }
    }

    /** Reads a stored chapter back, whichever storage model wrote it. */
    fun readLocal(path: String): String? = storage.read(path)

    suspend fun delete(chapterId: Long) = withContext(Dispatchers.IO) {
        val chapter = chapterDao.getById(chapterId) ?: return@withContext
        chapter.downloadPath?.let { storage.delete(it) }
        chapterDao.setDownloadState(chapterId, downloaded = false, path = null)
        setState(chapterId, DownloadState.NONE)
    }

    suspend fun delete(chapterIds: List<Long>) {
        chapterIds.forEach { delete(it) }
    }

    private fun setState(id: Long, state: DownloadState) {
        _progress.value = _progress.value.toMutableMap().apply { put(id, state) }
    }
}

enum class DownloadState { NONE, RUNNING, DONE, FAILED }
