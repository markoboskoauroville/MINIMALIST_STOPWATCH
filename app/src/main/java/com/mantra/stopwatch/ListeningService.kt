package com.mantra.stopwatch

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder

/**
 * LISTENING WITH THE STOPWATCH OFF SCREEN.
 *
 * Until v23 the microphone closed when the screen did, which meant voice commands worked only
 * while you were looking at the app — and looking at the app is exactly when your hands are free
 * to press a button. Baba said so at v9: it is a hands-free thing or it is nothing.
 *
 * WHAT IT COSTS, AND IT IS NOT NEGOTIABLE. Android will not let an app hold the microphone in the
 * background without a foreground service, and a foreground service means a permanent entry in
 * the notification shade for as long as voice is on. That is deliberate on Android's part and it
 * is right: an app that can hear you must say so where you cannot miss it. So the notification is
 * not an inconvenience to be worked around, it is the honest price of the feature, and switching
 * voice off removes it immediately.
 *
 * WHY THE ENGINE MOVED IN HERE. It cannot live in the screen any more, because the screen goes
 * away. VoiceHub holds it; this service decides when it exists. The activity subscribes to what
 * it needs and unsubscribes when it leaves, and neither of them owns the microphone.
 */
class ListeningService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            VoiceHub.stop()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        startForeground(NOTIFICATION_ID, notification())
        VoiceHub.start(applicationContext)

        // NOT sticky. If Android kills this to reclaim memory, it must stay dead rather than
        // silently reopening the microphone at some later moment the person did not ask for and
        // will not remember agreeing to.
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        VoiceHub.stop()
        super.onDestroy()
    }

    private fun notification(): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // LOW, not DEFAULT: this must be visible and must never make a sound. The only sound
            // this app makes is the Go word.
            val channel = NotificationChannel(CHANNEL, "Voice commands", NotificationManager.IMPORTANCE_LOW)
            channel.setShowBadge(false)
            manager.createNotificationChannel(channel)
        }

        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this,
            1,
            Intent(this, ListeningService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE,
        )

        return Notification.Builder(this, CHANNEL)
            .setContentTitle("Stopwatch is listening")
            // Says what it is doing and what it costs, because a notification that only names the
            // app tells a person nothing they could act on.
            .setContentText("Say start, pause, reset or lap")
            .setSmallIcon(android.R.drawable.presence_audio_online)
            .setOngoing(true)
            .setContentIntent(open)
            // The way out is in the notification itself. Making somebody open the app to switch
            // off the thing the notification is telling them about is the wrong way round.
            .addAction(Notification.Action.Builder(null, "Stop listening", stop).build())
            .build()
    }

    companion object {
        const val CHANNEL = "listening"
        const val NOTIFICATION_ID = 1
        const val ACTION_STOP = "com.mantra.stopwatch.STOP_LISTENING"

        fun start(context: Context) {
            val intent = Intent(context, ListeningService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, ListeningService::class.java).setAction(ACTION_STOP)
            )
        }
    }
}
