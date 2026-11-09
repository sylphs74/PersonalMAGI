package jp.pocketmagi.data

import android.content.Context
import jp.pocketmagi.engine.DeliberationState
import jp.pocketmagi.model.SageRole
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class HistoryEntry(
    val time: Long,
    val question: String,
    val media: String,
    val visual: String,
    val resolution: String,
    val integrated: String,
    val opinions: List<Triple<String, String, String>>, // code, verdict, text
    val elapsedMs: Long,
    /** Web sources (title, url) listed under the result. */
    val sources: List<Pair<String, String>> = emptyList(),
    /** The question this deliberation continued, or "". */
    val followUpOf: String = "",
    /** Starred: listed first and never removed by the retention rules. */
    val favorite: Boolean = false,
    /** The hypothesis the sages judged, or "" (older records: read from the result's first line). */
    val hypothesis: String = "",
) {
    val hypothesisOrParsed: String
        get() = hypothesis.ifBlank {
            integrated.lineSequence().take(3).firstOrNull { it.startsWith("仮説: ") }?.removePrefix("仮説: ")?.trim() ?: ""
        }
}

class HistoryStore(context: Context) {
    private val file = File(context.filesDir, "history.json")

    @Synchronized
    fun load(): List<HistoryEntry> {
        if (!file.exists()) return emptyList()
        return try {
            val arr = JSONArray(file.readText())
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                val ops = o.getJSONArray("opinions")
                HistoryEntry(
                    time = o.getLong("time"),
                    question = o.getString("question"),
                    media = o.optString("media"),
                    visual = o.optString("visual"),
                    resolution = o.optString("resolution"),
                    integrated = o.optString("integrated"),
                    opinions = (0 until ops.length()).map { j ->
                        val p = ops.getJSONObject(j)
                        Triple(p.getString("code"), p.optString("verdict"), p.optString("text"))
                    },
                    elapsedMs = o.optLong("elapsedMs"),
                    sources = o.optJSONArray("sources")?.let { a ->
                        (0 until a.length()).mapNotNull { k -> a.optJSONObject(k)?.let { it.optString("title") to it.optString("url") } }
                    } ?: emptyList(),
                    followUpOf = o.optString("followUpOf"),
                    favorite = o.optBoolean("favorite"),
                    hypothesis = o.optString("hypothesis"),
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /** Retention: keep at most [maxCount] entries (0 = unlimited) younger than [maxDays] days (0 = unlimited). */
    @Synchronized
    fun add(st: DeliberationState, mediaLabel: String, maxCount: Int, maxDays: Int) {
        val list = listOf(
            HistoryEntry(
                time = st.startedAt,
                question = st.question,
                media = mediaLabel,
                visual = "",
                resolution = st.resolution?.label ?: "",
                integrated = st.integrated,
                opinions = listOf(SageRole.MELCHIOR, SageRole.BALTHASAR, SageRole.CASPER).mapNotNull { r ->
                    st.sages[r]?.let { Triple(r.code, it.verdict?.label ?: "", it.text.ifBlank { it.error ?: "" }) }
                },
                elapsedMs = st.elapsedMs,
                sources = st.research.map { it.title to it.url },
                followUpOf = st.followUpOf,
                hypothesis = st.hypothesis,
            )
        ) + load()
        write(retain(list, maxCount, maxDays))
    }

    /** Applies the retention rules to what is stored now (e.g. after the settings change). */
    @Synchronized
    fun prune(maxCount: Int, maxDays: Int) {
        val cur = load()
        val kept = retain(cur, maxCount, maxDays)
        if (kept.size != cur.size) write(kept)
    }

    @Synchronized
    fun delete(time: Long) = write(load().filterNot { it.time == time })

    @Synchronized
    fun deleteMany(times: Set<Long>) = write(load().filterNot { it.time in times })

    @Synchronized
    fun setFavorite(time: Long, on: Boolean) = write(load().map { if (it.time == time) it.copy(favorite = on) else it })

    private fun retain(list: List<HistoryEntry>, maxCount: Int, maxDays: Int): List<HistoryEntry> {
        // starred records always stay; the limits apply to the others
        val keep = list.filter { it.favorite }
        var out = list.filterNot { it.favorite }.sortedByDescending { it.time }
        if (maxDays > 0) {
            val cutoff = System.currentTimeMillis() - maxDays * 86_400_000L
            out = out.filter { it.time >= cutoff }
        }
        if (maxCount > 0) out = out.take(maxCount)
        return (keep + out).sortedByDescending { it.time }
    }

    private fun write(list: List<HistoryEntry>) {
        val arr = JSONArray()
        list.forEach { h ->
            arr.put(JSONObject().apply {
                put("time", h.time); put("question", h.question); put("media", h.media)
                put("visual", h.visual); put("resolution", h.resolution); put("integrated", h.integrated)
                put("elapsedMs", h.elapsedMs)
                put("followUpOf", h.followUpOf)
                put("favorite", h.favorite)
                if (h.hypothesis.isNotBlank()) put("hypothesis", h.hypothesis)
                put("sources", JSONArray().apply { h.sources.forEach { (t, u) -> put(JSONObject().put("title", t).put("url", u)) } })
                put("opinions", JSONArray().apply {
                    h.opinions.forEach { (c, v, t) -> put(JSONObject().put("code", c).put("verdict", v).put("text", t)) }
                })
            })
        }
        file.writeText(arr.toString())
    }

    @Synchronized
    fun clear() { file.delete() }

    /** All records as stored, for the settings export. */
    @Synchronized
    fun raw(): JSONArray = try { if (file.exists()) JSONArray(file.readText()) else JSONArray() } catch (e: Exception) { JSONArray() }

    /** Adds exported records that are not here yet (same start time = same record). Returns how many were added. */
    @Synchronized
    fun merge(arr: JSONArray): Int {
        val cur = raw()
        val list = (0 until cur.length()).mapNotNull { cur.optJSONObject(it) }.toMutableList()
        val times = list.map { it.optLong("time") }.toMutableSet()
        var n = 0
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            if (o.has("time") && o.has("question") && o.has("opinions") && times.add(o.getLong("time"))) { list += o; n++ }
        }
        if (n > 0) file.writeText(JSONArray(list.sortedByDescending { it.optLong("time") }).toString())
        return n
    }
}
