package com.redtrigger

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.database.ContentObserver
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings

/**
 * Foreground service that watches nubia_game_scene and nubia_game_mode.
 *
 * SystemMgr.checkGameScene() resets nubia_game_scene to 0 on every
 * activity resume (notifyActivityResumed). This observer immediately
 * re-sets it to keep triggers alive system-wide.
 */
class TriggerService : Service() {

    companion object {
        private const val CHANNEL_ID = "redtrigger_service"
        private const val NOTIFICATION_ID = 1

        @Volatile
        var isRunning = false
            private set

        @Volatile
        var resetCount = 0
            private set
    }

    /** Set to true during shutdown to prevent observer race conditions */
    @Volatile
    private var shuttingDown = false

    private var sceneObserver: ContentObserver? = null
    private var modeObserver: ContentObserver? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        DebugLog.log("Service", "onStartCommand called")
        shuttingDown = false
        startForeground(NOTIFICATION_ID, buildNotification())
        DebugLog.log("Service", "Foreground notification posted")
        startObserving()
        DebugLog.log("Service", "ContentObservers registered")
        startInputReader()
        isRunning = true
        DebugLog.log("Service", "Service fully started, isRunning=true")
        return START_STICKY
    }

    private fun startInputReader() {
        try {
            // Respect the Shizuku capture toggle from preferences
            val prefs = getSharedPreferences("RedTriggerPrefs", MODE_PRIVATE)
            val shizukuEnabled = prefs.getBoolean("capture_shizuku", true)
            if (!shizukuEnabled) {
                DebugLog.log("Shizuku", "Shizuku capture disabled by user preference, skipping InputReader")
                return
            }

            // Also apply the injection preference so InputReader picks it up at connect time
            InputReader.injectionEnabled = prefs.getBoolean("capture_inject", true)

            val shizukuAvailable = rikka.shizuku.Shizuku.pingBinder()
            DebugLog.log("Shizuku", "pingBinder=$shizukuAvailable")
            if (shizukuAvailable) {
                val permission = rikka.shizuku.Shizuku.checkSelfPermission()
                DebugLog.log("Shizuku", "checkSelfPermission=$permission (0=granted)")
                InputReader.onTrigger = { trigger, isDown ->
                    if (isDown) {
                        DebugLog.log("Trigger", "${trigger.name} pressed")
                    }
                }
                InputReader.init(this)
                InputReader.start()
                DebugLog.log("Shizuku", "InputReader.start() called")
            } else {
                DebugLog.log("Shizuku", "Not available, input reading disabled")
            }
        } catch (e: Exception) {
            DebugLog.log("Shizuku", "ERROR: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    private fun startObserving() {
        val handler = Handler(Looper.getMainLooper())

        // Watch nubia_game_scene — the one SystemMgr resets on activity changes
        sceneObserver = object : ContentObserver(handler) {
            override fun onChange(selfChange: Boolean) {
                if (shuttingDown) return
                val value = Settings.Global.getInt(contentResolver, "nubia_game_scene", 0)
                if (value == 0) {
                    Settings.Global.putInt(contentResolver, "nubia_game_scene", 1)
                    Settings.Global.putInt(contentResolver, "cc_game_mis_operate", 0)
                    resetCount++
                    DebugLog.log("Watchdog", "nubia_game_scene reset to 0 by system, re-set to 1 (#$resetCount)")
                }
            }
        }
        contentResolver.registerContentObserver(
            Settings.Global.getUriFor("nubia_game_scene"),
            false,
            sceneObserver!!
        )

        // Watch nubia_game_mode too — verify if system resets this one
        modeObserver = object : ContentObserver(handler) {
            override fun onChange(selfChange: Boolean) {
                if (shuttingDown) return
                val value = Settings.Global.getInt(contentResolver, "nubia_game_mode", 0)
                if (value != 1) {
                    Settings.Global.putInt(contentResolver, "nubia_game_mode", 1)
                    DebugLog.log("Watchdog", "nubia_game_mode was $value, re-set to 1")
                }
            }
        }
        contentResolver.registerContentObserver(
            Settings.Global.getUriFor("nubia_game_mode"),
            false,
            modeObserver!!
        )
    }

    private fun stopObserving() {
        sceneObserver?.let { contentResolver.unregisterContentObserver(it) }
        modeObserver?.let { contentResolver.unregisterContentObserver(it) }
        sceneObserver = null
        modeObserver = null
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Shoulder Triggers",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Keeps shoulder triggers active system-wide"
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent, PendingIntent.FLAG_IMMUTABLE
        )

        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Triggers active")
            .setContentText("Shoulder triggers enabled system-wide")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        shuttingDown = true
        stopObserving()
        InputReader.stop()
        isRunning = false
        resetCount = 0
        DebugLog.log("Service", "Destroyed")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
