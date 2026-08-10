package com.opennovel.reader.update

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.opennovel.reader.MainActivity
import com.opennovel.reader.NovelReaderApp
import com.opennovel.reader.R
import com.opennovel.reader.data.RefreshReport

/**
 * Surfaces what a background library sweep is doing and what it found.
 *
 * A scheduled update that posts nothing is indistinguishable from one that never
 * ran — the user has no way to tell a working schedule from a broken one, which
 * is exactly the complaint the Updates tab already had.
 *
 * Every post is best-effort. On Android 13+ notifications need a runtime grant
 * the user may have refused, and a refused permission must not take down a
 * background worker, so posting degrades to a no-op rather than throwing.
 */
class LibraryUpdateNotifier(private val context: Context) {

    private val manager = NotificationManagerCompat.from(context)

    private val allowed: Boolean
        get() = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED

    /** Progress while the sweep runs. Silent, and dismissed when it finishes. */
    fun progress(done: Int, total: Int) {
        if (!allowed) return
        val notification = NotificationCompat.Builder(context, NovelReaderApp.LIBRARY_PROGRESS_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("Checking for new chapters")
            .setContentText(if (total > 0) "$done of $total" else null)
            .setProgress(total.coerceAtLeast(1), done, total == 0)
            .setOngoing(true)
            .setSilent(true)
            .setOnlyAlertOnce(true)
            .build()
        post(PROGRESS_ID, notification)
    }

    /**
     * The outcome. A sweep that found nothing still posts, but silently and
     * without a heads-up, so a working schedule is visible without becoming
     * noise every six hours.
     */
    fun result(report: RefreshReport) {
        manager.cancel(PROGRESS_ID)
        if (!allowed) return

        val foundSomething = report.newChapters > 0
        val notification = NotificationCompat.Builder(context, NovelReaderApp.LIBRARY_RESULT_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle(if (foundSomething) "New chapters" else "Library up to date")
            .setContentText(report.summary())
            .setStyle(NotificationCompat.BigTextStyle().bigText(report.summary()))
            .setSilent(!foundSomething)
            .setAutoCancel(true)
            .setContentIntent(openApp())
            .build()
        post(RESULT_ID, notification)
    }

    fun clearProgress() = manager.cancel(PROGRESS_ID)

    // Every caller gates on [allowed], but lint cannot follow the check across a
    // property, and the runCatching is the real guarantee: the grant can be
    // revoked between the check and the post, and losing a notification must
    // never take down the sweep that was running.
    @SuppressLint("MissingPermission")
    private fun post(id: Int, notification: android.app.Notification) {
        runCatching { manager.notify(id, notification) }
    }

    private fun openApp(): PendingIntent? = runCatching {
        PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }.getOrNull()

    private companion object {
        const val PROGRESS_ID = 4101
        const val RESULT_ID = 4102
    }
}
