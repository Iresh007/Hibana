package com.opennovel.reader

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import com.opennovel.reader.data.AppContainer

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
        }
    }

    companion object {
        const val TTS_CHANNEL_ID = "tts_playback"
        const val DOWNLOAD_CHANNEL_ID = "downloads"
    }
}
