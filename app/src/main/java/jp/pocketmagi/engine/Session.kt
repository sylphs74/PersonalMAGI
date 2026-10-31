package jp.pocketmagi.engine

import android.content.Context
import jp.pocketmagi.data.SettingsStore
import jp.pocketmagi.model.ModelStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File

/**
 * Process-wide home of the running deliberation. It does not belong to the screen, so leaving
 * the app (or the system recreating the screen) does not lose or restart it; [DeliberationService]
 * keeps the process alive while it runs.
 */
object Session {
    @Volatile private var d: Deliberation? = null

    /** Deliberations run here, not in a ViewModel scope that dies with the screen. */
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Bumped whenever a record is written, so a recreated screen reloads the history list. */
    val historyVersion = MutableStateFlow(0)

    fun deliberation(c: Context): Deliberation = d ?: synchronized(this) {
        d ?: run {
            val app = c.applicationContext
            val settings = SettingsStore(app)
            Deliberation(app, ModelStore(app)) { settings.load() }.also { d = it }
        }
    }

    /**
     * A scheduled deliberation: runs with the current settings in the background, is recorded, and
     * the result is notified (the foreground service keeps the process alive).
     */
    fun runScheduled(c: Context, question: String) {
        val app = c.applicationContext
        val d = deliberation(app)
        if (d.state.value.phase == Phase.RUNNING) return
        val settings = SettingsStore(app).load()
        scope.launch {
            markRunning(app, question, System.currentTimeMillis())
            d.run(question, settings)
            clearRunning(app)
            val st = d.state.value
            if (st.phase == Phase.DONE && settings.historyEnabled) {
                jp.pocketmagi.data.HistoryStore(app).add(st, "定期審議", settings.historyMaxCount, settings.historyMaxDays)
                historyVersion.value++
            }
        }
        DeliberationService.start(app, scheduled = true)
    }

    // ---- marker of a run in progress, to report runs lost to a killed process

    private fun marker(c: Context) = File(c.applicationContext.filesDir, "running.json")

    fun markRunning(c: Context, question: String, startedAt: Long) {
        try { marker(c).writeText(JSONObject().put("q", question).put("t", startedAt).toString()) } catch (_: Exception) {}
    }

    fun clearRunning(c: Context) { marker(c).delete() }

    /** A run that was in progress when the process died: (question, startedAt), consumed once. */
    fun takeLostRun(c: Context): Pair<String, Long>? {
        val f = marker(c)
        if (!f.exists()) return null
        if (d?.state?.value?.phase == Phase.RUNNING) return null
        return try {
            val o = JSONObject(f.readText()); f.delete()
            o.getString("q") to o.getLong("t")
        } catch (e: Exception) { f.delete(); null }
    }
}
