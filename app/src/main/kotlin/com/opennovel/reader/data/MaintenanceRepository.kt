package com.opennovel.reader.data

import com.opennovel.reader.data.db.ChapterDao
import com.opennovel.reader.data.db.NovelDao
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** What a maintenance action actually did, so the UI can report it. */
data class MaintenanceResult(
    val itemsRemoved: Int,
    val bytesFreed: Long = 0L,
) {
    fun describe(noun: String): String = buildString {
        if (itemsRemoved == 0) {
            append("Nothing to remove")
            return@buildString
        }
        append("Removed $itemsRemoved $noun")
        if (itemsRemoved != 1) append("s")
        if (bytesFreed > 0) append(" · ${formatSize(bytesFreed)} freed")
    }

    private fun formatSize(bytes: Long): String = when {
        bytes >= 1_073_741_824 -> "%.1f GB".format(bytes / 1_073_741_824.0)
        bytes >= 1_048_576 -> "%.1f MB".format(bytes / 1_048_576.0)
        bytes >= 1024 -> "%.0f KB".format(bytes / 1024.0)
        else -> "$bytes B"
    }
}

/**
 * The destructive housekeeping actions in Settings.
 *
 * Split out of [LibraryRepository] because these are the only operations that
 * delete rather than read or update, and they report what they removed so the
 * settings rows can confirm the work instead of appearing to do nothing.
 */
class MaintenanceRepository(
    private val novelDao: NovelDao,
    private val chapterDao: ChapterDao,
) {

    /** How many cached, non-library entries would be removed. */
    suspend fun cachedEntryCount(): Int = novelDao.countNotInLibrary()

    /**
     * Drops entries cached while browsing that were never shelved.
     *
     * Library entries are untouched: their chapters and history hang off them by
     * cascading foreign key, so a blanket delete would take a user's reading
     * progress with it.
     */
    suspend fun clearCachedEntries(): MaintenanceResult = withContext(Dispatchers.IO) {
        val removed = novelDao.deleteNotInLibrary()
        MaintenanceResult(itemsRemoved = removed)
    }

    /** Total size currently held by downloaded chapter files. */
    suspend fun downloadedSize(): Long = withContext(Dispatchers.IO) {
        chapterDao.allDownloadPaths().sumOf { path ->
            runCatching { File(path).length() }.getOrDefault(0L)
        }
    }

    /**
     * Deletes downloaded chapter files and clears the flags that pointed at
     * them, so nothing is left claiming to be downloaded when the file is gone.
     */
    suspend fun clearDownloadCache(): MaintenanceResult = withContext(Dispatchers.IO) {
        val paths = chapterDao.allDownloadPaths()
        var freed = 0L
        var removed = 0
        paths.forEach { path ->
            val file = File(path)
            val size = runCatching { file.length() }.getOrDefault(0L)
            if (runCatching { file.delete() }.getOrDefault(false)) {
                freed += size
                removed++
            }
        }
        // Flags are cleared even where a file had already vanished, so the two
        // cannot drift apart.
        chapterDao.clearAllDownloadFlags()
        MaintenanceResult(itemsRemoved = removed, bytesFreed = freed)
    }
}
