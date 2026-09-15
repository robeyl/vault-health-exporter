package com.vaulthealth.exporter.watch

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.vaulthealth.exporter.MainActivity
import com.vaulthealth.exporter.R
import com.vaulthealth.exporter.VaultHealthApp
import com.vaulthealth.exporter.domain.DeltaRun
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.time.ZoneId

/**
 * Near-immediate export watcher.
 *
 * Health Connect has no push/change-notification API, so "immediate" means polling the change
 * token on a short interval. A foreground service is the only reliable way to do that on a
 * schedule shorter than WorkManager's 15-minute periodic minimum.
 *
 * This is deliberately user-controlled and off by default: it keeps a low-priority notification
 * visible and costs battery.
 */
class WatcherService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val container = (application as VaultHealthApp).container
        createChannel()
        startForegroundCompat(getString(R.string.watcher_running))

        scope.launch {
            while (isActive) {
                val prefs = container.prefs.snapshot()
                val intervalSeconds = prefs.watcherSeconds
                if (intervalSeconds <= 0) {
                    stopSelf()
                    return@launch
                }
                val treeUri = prefs.treeUri?.let(Uri::parse)
                if (treeUri != null) {
                    val result = runCatching {
                        container.deltaEngine.run(treeUri, ZoneId.systemDefault(), allowRoutes = false)
                    }.getOrNull()
                    updateNotification(describe(result))
                } else {
                    updateNotification(getString(R.string.watcher_no_folder))
                }
                delay(intervalSeconds * 1000L)
            }
        }
        return START_STICKY
    }

    private fun describe(run: DeltaRun?): String = when (run) {
        is DeltaRun.Completed -> "Exported ${run.fileName} (${run.upserts} new)"
        DeltaRun.NoChanges -> getString(R.string.watcher_idle)
        is DeltaRun.NeedsSnapshot -> "Snapshot needed"
        is DeltaRun.Failed -> "Export failed: ${run.reason}"
        null -> getString(R.string.watcher_running)
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun startForegroundCompat(text: String) {
        val notification = buildNotification(text)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun updateNotification(text: String) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, buildNotification(text))
    }

    private fun buildNotification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(open)
            .build()
    }

    private fun createChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.watcher_channel),
                    NotificationManager.IMPORTANCE_LOW,
                ),
            )
        }
    }

    companion object {
        private const val CHANNEL_ID = "vault-health-watcher"
        private const val NOTIFICATION_ID = 4711

        fun start(context: Context) {
            val intent = Intent(context, WatcherService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, WatcherService::class.java))
        }
    }
}
