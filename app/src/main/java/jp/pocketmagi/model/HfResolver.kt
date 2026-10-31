package jp.pocketmagi.model

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL

/** [oid] is the LFS sha256 on Hugging Face; it changes whenever the uploader replaces the file. */
data class RemoteFile(val path: String, val size: Long, val oid: String = "") {
    val fileName: String get() = path.substringAfterLast('/')
}

data class ResolvedModel(val model: RemoteFile, val mmproj: RemoteFile?)

/**
 * Picks concrete GGUF files from a Hugging Face repo at runtime, so the catalog only
 * needs repo ids and quantization preferences (file names differ between uploaders).
 * The same rules are mirrored in scripts/check_models.py, which CI runs.
 */
object HfResolver {
    private val SPLIT = Regex("-\\d{5}-of-\\d{5}", RegexOption.IGNORE_CASE)
    private val AUX = listOf("mmproj", "mtp", "draft", "imatrix")

    fun resolveUrl(repo: String, path: String) =
        "https://huggingface.co/$repo/resolve/main/${path.split('/').joinToString("/") { java.net.URLEncoder.encode(it, "UTF-8").replace("+", "%20") }}?download=true"

    suspend fun resolve(spec: ModelSpec): ResolvedModel = withContext(Dispatchers.IO) {
        val files = listFiles(spec.repo)
        pick(spec, files) ?: throw IllegalStateException(
            "${spec.repo} に条件 (${spec.quantPrefer.joinToString()}) に合うGGUFが見つかりません"
        )
    }

    fun pick(spec: ModelSpec, files: List<RemoteFile>): ResolvedModel? {
        val ggufs = files.filter { it.path.endsWith(".gguf", ignoreCase = true) && !SPLIT.containsMatchIn(it.path) }
        val weights = ggufs.filter { f -> AUX.none { f.fileName.contains(it, ignoreCase = true) } }
        val model = firstByPreference(weights, spec.quantPrefer) ?: return null
        val mmproj = if (spec.needsMmproj) {
            val projs = ggufs.filter { it.fileName.contains("mmproj", ignoreCase = true) }
            firstByPreference(projs, spec.mmprojPrefer) ?: projs.firstOrNull() ?: return null
        } else null
        return ResolvedModel(model, mmproj)
    }

    private fun quantRegex(q: String) =
        Regex("(?<![A-Za-z0-9])" + Regex.escape(q) + "(?![A-Za-z0-9_])", RegexOption.IGNORE_CASE)

    private fun firstByPreference(files: List<RemoteFile>, prefs: List<String>): RemoteFile? {
        for (q in prefs) {
            val re = quantRegex(q)
            files.filter { re.containsMatchIn(it.fileName) }.minByOrNull { it.path.length }?.let { return it }
        }
        return null
    }

    private fun listFiles(repo: String): List<RemoteFile> {
        val out = mutableListOf<RemoteFile>()
        fun walk(dir: String, depth: Int) {
            val suffix = if (dir.isEmpty()) "" else "/$dir"
            val conn = URL("https://huggingface.co/api/models/$repo/tree/main$suffix").openConnection() as HttpURLConnection
            conn.connectTimeout = 15_000
            conn.readTimeout = 30_000
            conn.setRequestProperty("Accept", "application/json")
            try {
                if (conn.responseCode != 200) throw IllegalStateException("Hugging Face API エラー ${conn.responseCode} ($repo)")
                val arr = JSONArray(conn.inputStream.bufferedReader().use { it.readText() })
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    val path = o.getString("path")
                    when (o.optString("type")) {
                        "file" -> {
                            val lfs = o.optJSONObject("lfs")
                            val size = lfs?.optLong("size") ?: o.optLong("size")
                            val oid = lfs?.optString("oid") ?: o.optString("oid")
                            out += RemoteFile(path, size, oid)
                        }
                        "directory" -> if (depth < 1) walk(path, depth + 1)
                    }
                }
            } finally {
                conn.disconnect()
            }
        }
        walk("", 0)
        return out
    }
}
