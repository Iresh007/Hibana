package com.opennovel.reader.tts

import android.app.Notification
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import com.opennovel.reader.MainActivity
import com.opennovel.reader.NovelReaderApp
import com.opennovel.reader.R

/**
 * Foreground service that keeps TTS playback alive when the app is backgrounded
 * and surfaces a playback notification. The actual engine lives in [TtsManager]
 * (held on the app container) so UI and service share one instance.
 */
class TtsService : LifecycleService() {

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_STOP -> {
                (application as NovelReaderApp).container.ttsManager.stop()
                stopSelf()
            }
            else -> startForeground(NOTIF_ID, buildNotification())
        }
        return START_STICKY
    }

    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, NovelReaderApp.TTS_CHANNEL_ID)
            .setContentTitle("Reading aloud")
            .setContentText("Text-to-speech is active")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(open)
            .setOngoing(true)
            .build()
    }

    companion object {
        const val ACTION_STOP = "com.opennovel.reader.tts.STOP"
        private const val NOTIF_ID = 1001

        fun isSupported() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP
    }
}
