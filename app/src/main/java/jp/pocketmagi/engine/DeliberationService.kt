package jp.pocketmagi.engine

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
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import jp.pocketmagi.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Foreground service that keeps the process (and the CPU, via a wake lock) alive while the sages
 * deliberate, so leaving the app or turning the screen off does not kill the run.
 */
class DeliberationService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    @Volatile private var running = false
    private var wake: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    @Volatile private var scheduled = false

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ensureChannels()
        if (intent?.getBooleanExtra("scheduled", false) == true) scheduled = true
        try {
            ServiceCompat.startForeground(
                this, ID_RUNNING, build(CH_RUNNING, "審議を開始しています", "", ongoing = true),
                if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0,
            )
        } catch (e: Exception) {
            stopSelf(); return START_NOT_STICKY
        }
        if (!running) {
            running = true
            wake = getSystemService(PowerManager::class.java)
                ?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "PersonalMAGI:deliberation")
                ?.apply { setReferenceCounted(false); acquire(60 * 60 * 1000L) }
            scope.launch {
                val d = Session.deliberation(this@DeliberationService)
                try {
                    // the run flips to RUNNING right after it is launched
                    withTimeoutOrNull(10_000) { d.state.first { it.phase == Phase.RUNNING } } ?: return@launch
                    val settings = jp.pocketmagi.data.SettingsStore(this@DeliberationService).load()
                    jp.pocketmagi.data.Sounds.startRunning(this@DeliberationService, settings)
                    val nm = getSystemService(NotificationManager::class.java)
                    var lastStage = ""
                    var donePlayed = false
                    val end = d.state.first { st ->
                        // the completion music starts with the result, not after it is written
                        if (!donePlayed && st.phase == Phase.RUNNING && st.integrating) {
                            donePlayed = true
                            jp.pocketmagi.data.Sounds.stopRunning()
                            jp.pocketmagi.data.Sounds.playDone(this@DeliberationService,
                                jp.pocketmagi.data.SettingsStore(this@DeliberationService).load())
                        }
                        if (st.phase == Phase.RUNNING && st.stage != lastStage) {
                            lastStage = st.stage
                            nm?.notify(ID_RUNNING, build(CH_RUNNING, "審議中: ${st.stage}", st.question, ongoing = true))
                        }
                        st.phase != Phase.RUNNING
                    }
                    jp.pocketmagi.data.Sounds.stopRunning()
                    // no result text was written (e.g. no model for it): play when the deliberation ends
                    if (end.phase == Phase.DONE && !donePlayed) jp.pocketmagi.data.Sounds.playDone(this@DeliberationService,
                        jp.pocketmagi.data.SettingsStore(this@DeliberationService).load())
                    // a scheduled run is always reported; an ordinary one only when the app is not on screen
                    if (!MainActivity.visible || scheduled) {
                        val title = when (end.phase) {
                            Phase.DONE -> "審議終了: ${end.resolution?.label ?: "—"}"
                            Phase.CANCELLED -> "審議を中断しました"
                            else -> "審議でエラーが発生しました"
                        }
                        val conclusion = end.integrated.lines().firstOrNull { it.startsWith("結論") }?.removePrefix("結論:")?.trim().orEmpty()
                        nm?.notify(ID_RESULT, build(CH_RESULT, (if (scheduled) "定期審議 " else "") + title,
                            if (conclusion.isNotBlank()) "${end.question}\n$conclusion" else end.question, ongoing = false))
                    }
                } finally {
                    jp.pocketmagi.data.Sounds.stopRunning()
                    running = false
                    scheduled = false
                    wake?.let { if (it.isHeld) it.release() }
                    ServiceCompat.stopForeground(this@DeliberationService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        wake?.let { if (it.isHeld) it.release() }
        scope.cancel()
        super.onDestroy()
    }

    private fun ensureChannels() {
        val nm = getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CH_RUNNING) == null)
            nm.createNotificationChannel(NotificationChannel(CH_RUNNING, "審議の進行状況", NotificationManager.IMPORTANCE_LOW))
        if (nm.getNotificationChannel(CH_RESULT) == null)
            nm.createNotificationChannel(NotificationChannel(CH_RESULT, "審議の結果", NotificationManager.IMPORTANCE_DEFAULT))
    }

    private fun build(channel: String, title: String, text: String, ongoing: Boolean): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, channel)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(text)
            .setOnlyAlertOnce(ongoing)
            .setOngoing(ongoing)
            .setAutoCancel(!ongoing)
            .setContentIntent(open)
            .apply { if (ongoing) setProgress(0, 0, true) else setStyle(NotificationCompat.BigTextStyle().bigText(text)) }
            .build()
    }

    companion object {
        private const val ID_RUNNING = 4301
        private const val ID_RESULT = 4302
        private const val CH_RUNNING = "deliberation"
        private const val CH_RESULT = "deliberation_result"

        fun start(c: Context, scheduled: Boolean = false) {
            try {
                ContextCompat.startForegroundService(c, Intent(c, DeliberationService::class.java).putExtra("scheduled", scheduled))
            } catch (_: Exception) {}
        }
    }
}
