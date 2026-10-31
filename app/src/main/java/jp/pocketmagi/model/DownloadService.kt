package jp.pocketmagi.model

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
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import jp.pocketmagi.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** Keeps model downloads running while the app is in the background. */
class DownloadService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO +
        kotlinx.coroutines.CoroutineExceptionHandler { _, e -> android.util.Log.e("PersonalMAGI", "download", e) })
    @Volatile private var running = false
    private var lastNotify = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ensureChannel()
        try {
            ServiceCompat.startForeground(
                this, ID, build("ダウンロードを準備中", "", 0, 0),
                if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0,
            )
        } catch (e: Exception) {
            stopSelf(); return START_NOT_STICKY
        }
        if (!running) {
            running = true
            scope.launch {
                try {
                    FastDownloader.runAll(this@DownloadService) { job, p ->
                        val now = System.currentTimeMillis()
                        if (now - lastNotify < 1000) return@runAll
                        lastNotify = now
                        val pct = if (p.total > 0) (p.done * 100 / p.total).toInt() else 0
                        val text = "%.2f / %.2f GB　%.1f MB/s%s".format(
                            p.done / 1e9, p.total / 1e9, p.speed / 1e6, if (p.note.isNotEmpty()) "　${p.note}" else "")
                        getSystemService(NotificationManager::class.java)?.notify(ID, build(job.title, text, pct, 100))
                    }
                } finally {
                    running = false
                    ServiceCompat.stopForeground(this@DownloadService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun ensureChannel() {
        val nm = getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "モデルのダウンロード", NotificationManager.IMPORTANCE_LOW))
        }
    }

    private fun build(title: String, text: String, progress: Int, max: Int): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("PersonalMAGI: $title")
            .setContentText(text)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setContentIntent(open)
            .setProgress(max, progress, max == 0)
            .build()
    }

    companion object {
        private const val ID = 4201
        private const val CHANNEL = "downloads"

        fun start(c: Context) {
            try { ContextCompat.startForegroundService(c, Intent(c, DownloadService::class.java)) } catch (_: Exception) {}
        }
    }
}
