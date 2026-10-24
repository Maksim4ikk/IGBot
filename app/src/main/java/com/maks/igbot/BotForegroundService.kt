package com.maks.igbot

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
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.maks.igbot.data.BotEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Фоновий сервіс Android, який не дає телефону приспати бота після блокування екрана
 */
class BotForegroundService : Service() {

    private var wakeLock: PowerManager.WakeLock? = null
    private var monitorJob: Job? = null
    private val channelId = "igbot_background_channel"

    override fun onCreate() {
        super.onCreate()
        BotEngine.ensureInit(this)
        createNotificationChannel()
        acquireWakeLock()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: "START_WORKER"

        if (action == "STOP_ALL") {
            BotEngine.stopWorker(this)
            BotEngine.stopScraper(this)
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        val notification = buildNotification("IGBot активний у фоні...")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(1, notification)
        }

        if (action == "START_WORKER") {
            BotEngine.startWorker(this)
        } else if (action == "START_SCRAPER") {
            val stype = intent?.getStringExtra("source_type") ?: "followers"
            val target = intent?.getStringExtra("target") ?: ""
            val amount = intent?.getIntExtra("amount", 100) ?: 100
            BotEngine.startScraper(this, stype, target, amount)
        }

        startStatusUpdater()
        return START_STICKY
    }

    private fun startStatusUpdater() {
        if (monitorJob?.isActive == true) return
        monitorJob = CoroutineScope(Dispatchers.IO).launch {
            while (isActive) {
                try {
                    val dash = BotEngine.getDashboardJson(this@BotForegroundService)
                    val wRun = dash.optBoolean("worker_running", false)
                    val sRun = dash.optBoolean("scraper_running", false)
                    val likesToday = dash.optInt("likes_today", 0)
                    val likesMax = dash.optInt("likes_day_max", 120)
                    val ready = dash.optInt("ready", 0)

                    val statusText = when {
                        wRun && sRun -> "Воркер + Парсер | ❤️ $likesToday/$likesMax | Черга: $ready"
                        wRun -> "Воркер працює | ❤️ $likesToday/$likesMax | Черга: $ready"
                        sRun -> "Парсер збирає анкети... | Черга: $ready"
                        else -> "Очікування завдань | Готово в черзі: $ready"
                    }

                    val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                    nm.notify(1, buildNotification(statusText))
                } catch (_: Exception) {
                }
                delay(5000)
            }
        }
    }

    private fun buildNotification(text: String): Notification {
        val openIntent = Intent(this, MainActivity::class.java)
        val pendingOpen = PendingIntent.getActivity(
            this, 0, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, channelId)
            .setContentTitle("IGBot Автоматизація")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(pendingOpen)
            .setOngoing(true)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "Фонова робота IGBot",
                NotificationManager.IMPORTANCE_LOW
            )
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)
        }
    }

    private fun acquireWakeLock() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "IGBot::BackgroundWakeLock")
        wakeLock?.acquire(12 * 60 * 60 * 1000L) // до 12 годин безперервно
    }

    override fun onDestroy() {
        monitorJob?.cancel()
        if (wakeLock?.isHeld == true) {
            wakeLock?.release()
        }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}