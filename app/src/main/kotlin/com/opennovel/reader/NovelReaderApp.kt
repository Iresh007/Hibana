package com.opennovel.reader

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import com.opennovel.reader.data.AppContainer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Application entry point. Holds the manual dependency-injection container so
 * that ViewModels and services can reach the database, repositories, downloader,
 * and the source/extension manager without a DI framework.
 */
class NovelReaderApp : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        createNotificationChannels()
        // Discover + register installed Mihon/Manatan/IReader extensions off the main thread.
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            runCatching { container.loadInstalledExtensions() }
            // Re-arm scheduled library updates; WorkManager keeps them across
            // reboots, but re-applying keeps the schedule in sync with settings.
            runCatching {
                com.opennovel.reader.update.UpdateScheduler.apply(this@NovelReaderApp, container.settingsRepository)
            }
        }
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(
                NotificationChannel(
                    TTS_CHANNEL_ID,
                    "Text-to-speech playback",
                    NotificationManager.IMPORTANCE_LOW,
                ),
            )
            manager.createNotificationChannel(
                NotificationChannel(
                    DOWNLOAD_CHANNEL_ID,
                    "Chapter downloads",
                    NotificationManager.IMPORTANCE_LOW,
                ),
            )
            // Split from the result channel so a sweep's progress can be silent
            // while its result still reaches the user.
            manager.createNotificationChannel(
                NotificationChannel(
                    LIBRARY_PROGRESS_CHANNEL_ID,
                    "Library update progress",
                    NotificationManager.IMPORTANCE_LOW,
                ),
            )
            manager.createNotificationChannel(
                NotificationChannel(
                    LIBRARY_RESULT_CHANNEL_ID,
                    "New chapters",
                    NotificationManager.IMPORTANCE_DEFAULT,
                ),
            )
        }
    }

    companion object {
        const val TTS_CHANNEL_ID = "tts_playback"
        const val DOWNLOAD_CHANNEL_ID = "downloads"
        const val LIBRARY_PROGRESS_CHANNEL_ID = "library_update_progress"
        const val LIBRARY_RESULT_CHANNEL_ID = "library_update_result"
    }
}
