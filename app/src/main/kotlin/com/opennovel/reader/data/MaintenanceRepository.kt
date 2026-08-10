package com.opennovel.reader.data

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
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
    private val context: Context,
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

    /**
     * Total size currently held by downloaded chapter files.
     *
     * A download can live either in app-private storage (a filesystem path) or
     * in a user-chosen folder (a `content://` document URI). Measuring only the
     * former would silently report 0 B once a custom download folder is in use,
     * which would then disable the row that clears them.
     */
    suspend fun downloadedSize(): Long = withContext(Dispatchers.IO) {
        chapterDao.allDownloadPaths().sumOf { location -> sizeOf(location) }
    }

    private fun sizeOf(location: String): Long = runCatching {
        if (!location.startsWith("content://")) return@runCatching File(location).length()
        val uri = Uri.parse(location)
        context.contentResolver.query(
            uri,
            arrayOf(DocumentsContract.Document.COLUMN_SIZE),
            null,
            null,
            null,
        )?.use { cursor ->
            if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getLong(0) else 0L
        } ?: 0L
    }.getOrDefault(0L)

    /**
     * Deletes downloaded chapter files and clears the flags that pointed at
     * them, so nothing is left claiming to be downloaded when the file is gone.
     */
    suspend fun clearDownloadCache(): MaintenanceResult = withContext(Dispatchers.IO) {
        val locations = chapterDao.allDownloadPaths()
        var freed = 0L
        var removed = 0
        locations.forEach { location ->
            val size = sizeOf(location)
            val deleted = runCatching {
                if (location.startsWith("content://")) {
                    DocumentsContract.deleteDocument(context.contentResolver, Uri.parse(location))
                } else {
                    File(location).delete()
                }
            }.getOrDefault(false)
            if (deleted) {
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
