package jp.pocketmagi.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Named configurations: which AI runs each role, the speaking style, web research and the rebuttal
 * round. Kept with the settings (so they are exported too).
 */
object Presets {
    private fun prefs(c: Context) = c.getSharedPreferences("settings", Context.MODE_PRIVATE)

    fun list(c: Context): List<Pair<String, JSONObject>> = try {
        val a = JSONArray(prefs(c).getString("presets", "[]"))
        (0 until a.length()).mapNotNull { a.optJSONObject(it) }.map { it.optString("name") to it }
    } catch (e: Exception) { emptyList() }

    private fun map(m: Map<String, String>) = JSONObject().apply { m.forEach { (k, v) -> put(k, v) } }
    private fun unmap(o: JSONObject?): Map<String, String> = o?.let { j -> j.keys().asSequence().associateWith { j.optString(it) } } ?: emptyMap()

    fun save(c: Context, name: String, s: MagiSettings) {
        val o = JSONObject().put("name", name)
            .put("roleEngine", map(s.roleEngine)).put("roleChoice", map(s.roleChoice)).put("roleCloudModel", map(s.roleCloudModel))
            .put("speechStyle", s.speechStyle).put("webEnabled", s.webEnabled).put("rebuttal", s.rebuttal)
        val rest = list(c).filter { it.first != name }.map { it.second }
        prefs(c).edit().putString("presets", JSONArray(rest + o).toString()).apply()
    }

    fun delete(c: Context, name: String) {
        prefs(c).edit().putString("presets", JSONArray(list(c).filter { it.first != name }.map { it.second }).toString()).apply()
    }

    fun apply(s: MagiSettings, o: JSONObject): MagiSettings = s.copy(
        roleEngine = s.roleEngine + unmap(o.optJSONObject("roleEngine")),
        roleChoice = s.roleChoice + unmap(o.optJSONObject("roleChoice")),
        roleCloudModel = s.roleCloudModel + unmap(o.optJSONObject("roleCloudModel")),
        speechStyle = o.optString("speechStyle", s.speechStyle),
        webEnabled = o.optBoolean("webEnabled", s.webEnabled),
        rebuttal = o.optBoolean("rebuttal", s.rebuttal),
    )
}
