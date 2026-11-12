package com.opennovel.reader.update

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.opennovel.reader.NovelReaderApp
import com.opennovel.reader.data.AutoBackupFrequency
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Writes a Tachiyomi-compatible `.tachibk` into the app's own backup folder on a
 * schedule and keeps only the newest few. The folder is app-owned, so no storage
 * permission or folder picker is needed; use the manual export to copy a file
 * somewhere permanent.
 */
class AutoBackupWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext as? NovelReaderApp ?: return Result.success()
        return try {
            val dir = backupDir(applicationContext).apply { mkdirs() }
            val stamp = SimpleDateFormat("yyyy-MM-dd_HH-mm", Locale.US).format(Date())
            val target = File(dir, "hibana_$stamp.tachibk")
            val partial = File(dir, target.name + ".part")
            partial.outputStream().use { app.container.backupManager.export(it) }
            if (!partial.renameTo(target)) error("Could not finalise backup file")
            dir.listFiles { f -> f.name.endsWith(".tachibk") }
                ?.sortedByDescending { it.lastModified() }
                ?.drop(KEEP)
                ?.forEach { it.delete() }
            Result.success()
        } catch (t: Throwable) {
            Result.retry()
        }
    }

    companion object {
        const val NAME = "auto_backup"
        const val KEEP = 5

        fun backupDir(context: Context): File =
            File(context.getExternalFilesDir(null) ?: context.filesDir, "backups")

        fun apply(context: Context, frequency: AutoBackupFrequency) {
            val wm = WorkManager.getInstance(context)
            val days = when (frequency) {
                AutoBackupFrequency.OFF -> { wm.cancelUniqueWork(NAME); return }
                AutoBackupFrequency.DAILY -> 1L
                AutoBackupFrequency.EVERY_2_DAYS -> 2L
                AutoBackupFrequency.WEEKLY -> 7L
            }
            val request = PeriodicWorkRequestBuilder<AutoBackupWorker>(days, TimeUnit.DAYS).build()
            wm.enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
        }
    }
}
