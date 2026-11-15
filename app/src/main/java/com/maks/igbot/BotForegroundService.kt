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
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import com.maks.igbot.data.BotEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Сервіс підтримує запущені користувачем задачі, коли застосунок не на екрані.
 * Частковий wake lock не дає процесору заснути, коли екран вимкнений.
 */
class BotForegroundService : Service() {

    private var wakeLock: PowerManager.WakeLock? = null
    private var monitorJob: Job? = null
    private var wakeLockAcquiredAt = 0L
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val channelId = "igbot_background_channel"
    private val taskPrefs by lazy {
        getSharedPreferences("background_tasks", Context.MODE_PRIVATE)
    }

    private companion object {
        const val IDLE_STOP_MS = 2 * 60 * 1000L
        const val TASK_START_GRACE_MS = 15 * 1000L
        const val TAG = "BotForegroundService"
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        acquireWakeLock()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: if (intent == null) "RESTORE_TASKS" else "START_WORKER"

        if (action == "STOP_ALL") {
            taskPrefs.edit().clear().apply()
            serviceScope.launch {
                try {
                    BotEngine.ensureInit(this@BotForegroundService)
                    BotEngine.stopWorker(this@BotForegroundService)
                    BotEngine.stopScraper(this@BotForegroundService)
                } catch (e: Exception) {
                    Log.e(TAG, "Не вдалося зупинити задачі", e)
                } finally {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf(startId)
                }
            }
            return START_NOT_STICKY
        }

        when (action) {
            "START_WORKER" -> taskPrefs.edit()
                .putBoolean("worker_active", true)
                .putLong("worker_requested_at", System.currentTimeMillis())
                .apply()
            "START_SCRAPER" -> taskPrefs.edit()
                .putBoolean("scraper_active", true)
                .putString("scraper_source", intent?.getStringExtra("source_type") ?: "followers")
                .putString("scraper_target", intent?.getStringExtra("target") ?: "")
                .putInt("scraper_amount", intent?.getIntExtra("amount", 0) ?: 0)
                .putLong("scraper_requested_at", System.currentTimeMillis())
                .apply()
            "RESTORE_TASKS" -> Unit
            else -> {
                stopSelf(startId)
                return START_NOT_STICKY
            }
        }

        if (!taskPrefs.getBoolean("worker_active", false) &&
            !taskPrefs.getBoolean("scraper_active", false)
        ) {
            stopSelf(startId)
            return START_NOT_STICKY
        }

        // Переходимо у foreground до запуску Chaquopy/Python: його ініціалізація може бути повільною.
        startAsForeground()

        serviceScope.launch {
            try {
                BotEngine.ensureInit(this@BotForegroundService)
                if (taskPrefs.getBoolean("worker_active", false)) {
                    BotEngine.startWorker(this@BotForegroundService)
                }
                if (taskPrefs.getBoolean("scraper_active", false)) {
                    BotEngine.startScraper(
                        this@BotForegroundService,
                        taskPrefs.getString("scraper_source", "followers") ?: "followers",
                        taskPrefs.getString("scraper_target", "") ?: "",
                        taskPrefs.getInt("scraper_amount", 0)
                    )
                }
                startStatusUpdater()
            } catch (e: Exception) {
                Log.e(TAG, "Не вдалося відновити фонові задачі", e)
                taskPrefs.edit().clear().apply()
                try {
                    BotEngine.stopWorker(this@BotForegroundService)
                    BotEngine.stopScraper(this@BotForegroundService)
                } catch (stopError: Exception) {
                    Log.e(TAG, "Не вдалося очистити частково запущені задачі", stopError)
                }
                withContext(Dispatchers.Main) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf(startId)
                }
            }
        }

        // Android може відновити службу з null intent; активні задачі відновлюються з taskPrefs.
        return START_STICKY
    }

    private fun startAsForeground() {
        val notification = buildNotification("IGBot активний у фоні...")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                1,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(1, notification)
        }
    }

    @Synchronized
    private fun startStatusUpdater() {
        if (monitorJob?.isActive == true) return
        monitorJob = serviceScope.launch {
            var idleSince = 0L
            while (isActive) {
                try {
                    val dash = BotEngine.getDashboardJson(this@BotForegroundService)
                    val workerRunning = dash.optBoolean("worker_running", false)
                    val scraperRunning = dash.optBoolean("scraper_running", false)
                    val likesToday = dash.optInt("likes_today", 0)
                    val likesMax = dash.optInt("likes_day_max", 120)
                    val ready = dash.optInt("ready", 0)
                    renewWakeLockIfNeeded()

                    val now = System.currentTimeMillis()
                    val completed = taskPrefs.edit()
                    val workerRequestedAt = taskPrefs.getLong("worker_requested_at", now)
                    val scraperRequestedAt = taskPrefs.getLong("scraper_requested_at", now)
                    if (!workerRunning && now - workerRequestedAt >= TASK_START_GRACE_MS) {
                        completed.remove("worker_active").remove("worker_requested_at")
                    }
                    if (!scraperRunning && now - scraperRequestedAt >= TASK_START_GRACE_MS) {
                        completed.remove("scraper_active")
                            .remove("scraper_requested_at")
                            .remove("scraper_source")
                            .remove("scraper_target")
                            .remove("scraper_amount")
                    }
                    completed.apply()

                    if (!workerRunning && !scraperRunning) {
                        if (idleSince == 0L) idleSince = now
                        if (now - idleSince > IDLE_STOP_MS) {
                            withContext(Dispatchers.Main) {
                                stopForeground(STOP_FOREGROUND_REMOVE)
                                stopSelf()
                            }
                            return@launch
                        }
                    } else {
                        idleSince = 0L
                    }

                    val statusText = when {
                        workerRunning && scraperRunning ->
                            "Воркер + Парсер | ❤️ $likesToday/$likesMax | Черга: $ready"
                        workerRunning -> "Воркер працює | ❤️ $likesToday/$likesMax | Черга: $ready"
                        scraperRunning -> "Парсер збирає анкети... | Черга: $ready"
                        else -> "Очікування завдань | Готово в черзі: $ready"
                    }
                    val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                    nm.notify(1, buildNotification(statusText))
                } catch (e: Exception) {
                    Log.e(TAG, "Помилка оновлення стану", e)
                }
                delay(5000)
            }
        }
    }

    private fun buildNotification(text: String): Notification {
        val openIntent = Intent(this, MainActivity::class.java)
        val pendingOpen = PendingIntent.getActivity(
            this,
            0,
            openIntent,
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
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "IGBot::BackgroundWakeLock").apply {
            setReferenceCounted(false)
            acquire(12 * 60 * 60 * 1000L)
        }
        wakeLockAcquiredAt = SystemClock.elapsedRealtime()
    }

    private fun renewWakeLockIfNeeded() {
        val renewalIntervalMs = 10 * 60 * 60 * 1000L
        if (SystemClock.elapsedRealtime() - wakeLockAcquiredAt >= renewalIntervalMs) {
            if (wakeLock?.isHeld == true) wakeLock?.release()
            acquireWakeLock()
        }
    }

    override fun onDestroy() {
        monitorJob?.cancel()
        serviceScope.cancel()
        if (wakeLock?.isHeld == true) {
            wakeLock?.release()
        }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
