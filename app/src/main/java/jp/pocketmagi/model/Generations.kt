package jp.pocketmagi.model

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Newer generations of the same model family (same uploader, same size), e.g.
 * Sarashina2.2-3B -> Sarashina2.3-3B. A candidate is downloaded next to the current model,
 * test-loaded, and only then made active ("promoted"); if it does not run on the bundled
 * engine it is rejected and never offered again.
 */
object Generations {
    /** A replacement for a catalog entry: a newer generation, or a newer revision of the same repo. */
    data class Candidate(val repo: String, val label: String)

    /** An update the user has not decided on yet. */
    data class Offer(val candidate: Candidate, val description: String)

    private fun prefs(c: Context) = c.getSharedPreferences("generations", Context.MODE_PRIVATE)

    /** Version of a repo within [f], as numbers to compare (generation, then revision). */
    fun versionOf(f: Family, repo: String): List<Int>? {
        val m = f.pattern.find(repo.substringAfter('/')) ?: return null
        val ver = m.groups["ver"]?.value ?: return null
        val sub = try { m.groups["sub"]?.value } catch (e: IllegalArgumentException) { null }
        val nums = ver.split('.') + listOf("#") + (sub?.takeIf { it.isNotEmpty() }?.split('.') ?: emptyList())
        return nums.map { if (it == "#") -1 else it.toIntOrNull() ?: 0 }
    }

    private fun newer(a: List<Int>, b: List<Int>): Boolean {
        // compare generation part first, then revision; missing parts count as 0
        fun split(v: List<Int>): Pair<List<Int>, List<Int>> {
            val i = v.indexOf(-1)
            return if (i < 0) v to emptyList() else v.subList(0, i) to v.subList(i + 1, v.size)
        }
        fun cmp(x: List<Int>, y: List<Int>): Int {
            for (k in 0 until maxOf(x.size, y.size)) {
                val d = (x.getOrElse(k) { 0 }).compareTo(y.getOrElse(k) { 0 })
                if (d != 0) return d
            }
            return 0
        }
        val (ga, ra) = split(a); val (gb, rb) = split(b)
        val g = cmp(ga, gb)
        return if (g != 0) g > 0 else cmp(ra, rb) > 0
    }

    fun label(repo: String) = repo.substringAfter('/').replace(Regex("[-_]gguf$", RegexOption.IGNORE_CASE), "")

    private fun derive(base: ModelSpec, c: Candidate): ModelSpec {
        val f = base.family
        val oldVer = f?.pattern?.find(base.repo.substringAfter('/'))?.groups?.get("ver")?.value
        val newVer = f?.pattern?.find(c.repo.substringAfter('/'))?.groups?.get("ver")?.value
        val name = if (oldVer != null && newVer != null && base.name.contains(oldVer)) base.name.replace(oldVer, newVer) else c.label
        return base.copy(id = "${base.id}@${c.label}", repo = c.repo, name = name, baseId = base.id)
    }

    private fun read(c: Context, key: String): Candidate? =
        prefs(c).getString(key, null)?.let { JSONObject(it) }?.let { Candidate(it.getString("repo"), it.getString("label")) }

    private fun write(c: Context, key: String, v: Candidate?) {
        val e = prefs(c).edit()
        if (v == null) e.remove(key) else e.putString(key, JSONObject().put("repo", v.repo).put("label", v.label).toString())
        e.apply()
    }

    /** The spec to use for a catalog entry: the promoted newer generation if there is one. */
    fun effective(c: Context, base: ModelSpec): ModelSpec = read(c, "${base.id}.active")?.let { derive(base, it) } ?: base

    fun pending(c: Context, base: ModelSpec): ModelSpec? = read(c, "${base.id}.pending")?.let { derive(base, it) }

    fun setPending(c: Context, base: ModelSpec, cand: Candidate) = write(c, "${base.id}.pending", cand)

    fun offer(c: Context, base: ModelSpec): Offer? =
        prefs(c).getString("${base.id}.offer", null)?.let { JSONObject(it) }
            ?.let { Offer(Candidate(it.getString("repo"), it.getString("label")), it.getString("desc")) }

    fun setOffer(c: Context, base: ModelSpec, o: Offer?) {
        val e = prefs(c).edit()
        if (o == null) e.remove("${base.id}.offer")
        else e.putString("${base.id}.offer", JSONObject().put("repo", o.candidate.repo).put("label", o.candidate.label).put("desc", o.description).toString())
        e.apply()
    }

    /** User chose not to take this one: hide it until something newer appears. */
    fun decline(c: Context, base: ModelSpec) {
        val o = offer(c, base) ?: return
        addRejected(c, o.candidate.label)
        setOffer(c, base, null)
    }

    fun isRejected(c: Context, label: String) = label in prefs(c).getStringSet("rejected", emptySet())!!

    private fun addRejected(c: Context, label: String) {
        val set = prefs(c).getStringSet("rejected", emptySet())!!.toMutableSet().apply { add(label) }
        prefs(c).edit().putStringSet("rejected", set).apply()
    }

    fun candidateSpec(base: ModelSpec, cand: Candidate): ModelSpec = derive(base, cand)

    fun promote(c: Context, base: ModelSpec) {
        val p = read(c, "${base.id}.pending") ?: return
        write(c, "${base.id}.active", p)
        write(c, "${base.id}.pending", null)
    }

    /** The pending candidate did not run: forget it and never offer it again. */
    fun reject(c: Context, base: ModelSpec) {
        val p = read(c, "${base.id}.pending") ?: return
        addRejected(c, p.label)
        write(c, "${base.id}.pending", null)
    }

    /** Looks for a newer same-size generation of [current] on Hugging Face. */
    suspend fun findNewer(c: Context, base: ModelSpec, current: ModelSpec): Candidate? = withContext(Dispatchers.IO) {
        val f = base.family ?: return@withContext null
        val curVer = versionOf(f, current.repo) ?: return@withContext null
        val rejected = prefs(c).getStringSet("rejected", emptySet())!!.map { it.substringBefore('+') }.toSet()
        var best: Pair<String, List<Int>>? = null
        for (author in f.authors) {
            val url = "https://huggingface.co/api/models?author=${URLEncoder.encode(author, "UTF-8")}" +
                "&search=${URLEncoder.encode(f.query, "UTF-8")}&limit=200"
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = 15_000; conn.readTimeout = 30_000
            try {
                if (conn.responseCode != 200) continue
                val arr = JSONArray(conn.inputStream.bufferedReader().use { it.readText() })
                for (i in 0 until arr.length()) {
                    val id = arr.getJSONObject(i).optString("id")
                    if (!id.startsWith("$author/") || id == current.repo || label(id) in rejected) continue
                    val v = versionOf(f, id) ?: continue
                    if (newer(v, curVer) && (best == null || newer(v, best!!.second))) best = id to v
                }
            } finally { conn.disconnect() }
        }
        val repo = best?.first ?: return@withContext null
        val cand = Candidate(repo, label(repo))
        // must have a usable file (same quantization rules) before it is offered
        try { HfResolver.resolve(derive(base, cand)) } catch (e: Exception) { return@withContext null }
        cand
    }
}
