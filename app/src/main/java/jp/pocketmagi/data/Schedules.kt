package jp.pocketmagi.data

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import org.json.JSONArray
import org.json.JSONObject
import java.time.DayOfWeek
import java.time.LocalDateTime
import java.time.ZoneId

/** A question deliberated automatically at a set time (毎日 / 平日 / 週末), with the result notified. */
data class Schedule(val id: Int, val question: String, val hour: Int, val minute: Int, val days: String, val enabled: Boolean)

object Schedules {
    const val ACTION = "jp.pocketmagi.SCHEDULED_DELIBERATION"
    val DAYS = listOf("daily" to "毎日", "weekdays" to "平日", "weekends" to "週末")

    private fun prefs(c: Context) = c.getSharedPreferences("settings", Context.MODE_PRIVATE)   // exported with the settings

    fun list(c: Context): List<Schedule> = try {
        val a = JSONArray(prefs(c).getString("schedules", "[]"))
        (0 until a.length()).mapNotNull { a.optJSONObject(it) }.map { o ->
            Schedule(o.getInt("id"), o.optString("question"), o.optInt("hour"), o.optInt("minute"), o.optString("days", "daily"), o.optBoolean("enabled", true))
        }
    } catch (e: Exception) { emptyList() }

    private fun save(c: Context, list: List<Schedule>) {
        val a = JSONArray()
        list.forEach { s -> a.put(JSONObject().put("id", s.id).put("question", s.question).put("hour", s.hour).put("minute", s.minute)
            .put("days", s.days).put("enabled", s.enabled)) }
        prefs(c).edit().putString("schedules", a.toString()).apply()
    }

    fun put(c: Context, s: Schedule) { save(c, list(c).filterNot { it.id == s.id } + s); arm(c, s) }

    fun remove(c: Context, id: Int) { cancel(c, id); save(c, list(c).filterNot { it.id == id }) }

    fun newId(c: Context) = (list(c).maxOfOrNull { it.id } ?: 0) + 1

    private fun fits(days: String, d: DayOfWeek) = when (days) {
        "weekdays" -> d != DayOfWeek.SATURDAY && d != DayOfWeek.SUNDAY
        "weekends" -> d == DayOfWeek.SATURDAY || d == DayOfWeek.SUNDAY
        else -> true
    }

    /** Next time this schedule fires, in epoch milliseconds. */
    fun next(s: Schedule, from: LocalDateTime = LocalDateTime.now()): Long {
        var t = from.withHour(s.hour).withMinute(s.minute).withSecond(0).withNano(0)
        if (!t.isAfter(from)) t = t.plusDays(1)
        while (!fits(s.days, t.dayOfWeek)) t = t.plusDays(1)
        return t.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
    }

    private fun pending(c: Context, id: Int): PendingIntent = PendingIntent.getBroadcast(
        c, 7000 + id, Intent(c, ScheduleReceiver::class.java).setAction(ACTION).putExtra("id", id),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    fun arm(c: Context, s: Schedule) {
        val am = c.getSystemService(AlarmManager::class.java) ?: return
        val pi = pending(c, s.id)
        am.cancel(pi)
        if (!s.enabled) return
        val at = next(s)
        // an exact alarm may start the deliberation service from the background
        if (am.canScheduleExactAlarms()) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
    }

    fun cancel(c: Context, id: Int) { c.getSystemService(AlarmManager::class.java)?.cancel(pending(c, id)) }

    fun armAll(c: Context) = list(c).forEach { arm(c, it) }
}

/** Fires a scheduled deliberation (and re-arms it for the next day); re-arms everything after a reboot. */
class ScheduleReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        when (i.action) {
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED -> Schedules.armAll(c)
            Schedules.ACTION -> {
                val s = Schedules.list(c).firstOrNull { it.id == i.getIntExtra("id", -1) } ?: return
                if (s.enabled) jp.pocketmagi.engine.Session.runScheduled(c, s.question)
                Schedules.arm(c, s)
            }
        }
    }
}
