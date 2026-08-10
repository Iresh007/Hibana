package com.opennovel.reader.update

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.opennovel.reader.NovelReaderApp

/**
 * Refreshes every library novel's chapter list in the background.
 *
 * Runs through [com.opennovel.reader.data.LibraryRepository.refreshLibrary],
 * which is per-novel fault tolerant, so one dead source cannot fail the whole
 * run. Work is retried rather than failed on unexpected errors, since the usual
 * cause is transient connectivity.
 *
 * Fixed-time schedules (weekly/monthly and the daily variants) reschedule
 * themselves here: WorkManager's periodic work cannot guarantee a wall-clock
 * time, so [UpdateScheduler] enqueues one-shot work and the worker queues the
 * next occurrence once it finishes.
 */
class LibraryUpdateWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext as? NovelReaderApp ?: return Result.success()
        val notifier = LibraryUpdateNotifier(applicationContext)
        return try {
            val report = app.container.libraryRepository.refreshLibrary { done, total ->
                notifier.progress(done, total)
            }
            notifier.result(report)
            Result.success()
        } catch (t: Throwable) {
            // Clear the progress notification rather than leaving an ongoing one
            // pinned for a run that has stopped.
            notifier.clearProgress()
            Result.retry()
        } finally {
            // Keep one-shot chains alive even if the refresh failed.
            runCatching {
                val settings = app.container.settingsRepository
                UpdateScheduler.rescheduleIfOneShot(applicationContext, settings)
            }
        }
    }

    companion object {
        const val PERIODIC_NAME = "library_update_periodic"
        const val ONE_SHOT_NAME = "library_update_oneshot"
    }
}
