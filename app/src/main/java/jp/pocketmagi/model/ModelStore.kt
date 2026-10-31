package jp.pocketmagi.model

import android.app.DownloadManager
import android.content.Context
import java.io.File

sealed class InstallState {
    data object Missing : InstallState()
    data class Downloading(val doneBytes: Long, val totalBytes: Long, val speed: Long = 0, val note: String = "") : InstallState() {
        val fraction: Float get() = if (totalBytes > 0) doneBytes.toFloat() / totalBytes else 0f
    }
    data object Installed : InstallState()
    data class Failed(val reason: String) : InstallState()
}

data class LocalModel(val model: File, val mmproj: File?)

/**
 * Model files live in the app-specific external dir (no storage permission needed,
 * removed on uninstall). Downloads run through [FastDownloader] (parallel, resumable).
 */
class ModelStore(private val context: Context) {
    private val prefs = context.getSharedPreferences("models", Context.MODE_PRIVATE)

    init {
        // older versions used the system DownloadManager: stop those transfers
        val dm = context.getSystemService(DownloadManager::class.java)
        val e = prefs.edit()
        prefs.all.keys.filter { it.endsWith(".dl") }.forEach { k ->
            (prefs.all[k] as? Long)?.let { id -> try { dm?.remove(id) } catch (_: Exception) {} }
            e.remove(k)
        }
        e.apply()
    }

    val rootDir: File get() = File(context.getExternalFilesDir(null), "models").apply { mkdirs() }

    private fun dir(spec: ModelSpec) = File(rootDir, spec.id)

    private data class Part(val key: String, val name: String?, val size: Long)

    private fun parts(spec: ModelSpec): List<Part> {
        val list = mutableListOf(Part("model", prefs.getString("${spec.id}.model.name", null), prefs.getLong("${spec.id}.model.size", -1)))
        if (spec.needsMmproj) list += Part("mmproj", prefs.getString("${spec.id}.mmproj.name", null), prefs.getLong("${spec.id}.mmproj.size", -1))
        return list
    }

    fun local(spec: ModelSpec): LocalModel? {
        if (state(spec) != InstallState.Installed) return null
        val p = parts(spec)
        val m = File(dir(spec), p[0].name!!)
        val mm = p.getOrNull(1)?.let { File(dir(spec), it.name!!) }
        return LocalModel(m, mm)
    }

    fun state(spec: ModelSpec): InstallState {
        val ps = parts(spec)
        if (ps.any { it.name == null }) return InstallState.Missing
        var done = 0L
        var total = 0L
        var speed = 0L
        var note = ""
        var active = false
        for (p in ps) {
            val f = File(dir(spec), p.name!!)
            total += p.size
            if (f.exists() && f.length() == p.size) { done += p.size; continue }
            val pr = FastDownloader.progress("${spec.id}.${p.key}") ?: return InstallState.Missing
            pr.failed?.let { return InstallState.Failed(it) }
            active = true; done += pr.done; speed += pr.speed
            if (pr.note.isNotEmpty()) note = pr.note
        }
        return if (active || done < total) InstallState.Downloading(done, total, speed, note) else InstallState.Installed
    }

    /** Copies a .gguf file picked on the phone into the model folder of [spec] (a user-added model). */
    fun importLocal(spec: ModelSpec, uri: android.net.Uri, onProgress: (Long) -> Unit = {}) {
        val d = dir(spec).apply { mkdirs() }
        val name = (context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        } ?: "model.gguf").replace(Regex("[\\\\/]"), "_")
        val dest = File(d, name)
        val tmp = File(d, "$name.part")
        context.contentResolver.openInputStream(uri)?.use { input ->
            tmp.outputStream().use { out ->
                val buf = ByteArray(1 shl 20); var total = 0L
                while (true) { val r = input.read(buf); if (r < 0) break; out.write(buf, 0, r); total += r; onProgress(total) }
            }
        } ?: error("ファイルを開けません")
        // a GGUF file starts with the magic "GGUF"
        val magic = tmp.inputStream().use { val b = ByteArray(4); it.read(b); String(b, Charsets.US_ASCII) }
        if (magic != "GGUF") { tmp.delete(); error("GGUF形式のファイルではありません") }
        dest.delete(); tmp.renameTo(dest)
        prefs.edit().putString("${spec.id}.model.name", dest.name).putLong("${spec.id}.model.size", dest.length()).apply()
    }

    /** Resolves file names on Hugging Face, then queues whatever is not on disk yet (resuming partial files). */
    suspend fun download(spec: ModelSpec, wifiOnly: Boolean) {
        val resolved = HfResolver.resolve(spec)
        val d = dir(spec).apply { mkdirs() }
        val targets = listOfNotNull("model" to resolved.model, resolved.mmproj?.let { "mmproj" to it })
        val edit = prefs.edit()
        for ((key, rf) in targets) {
            edit.putString("${spec.id}.$key.name", rf.fileName).putLong("${spec.id}.$key.size", rf.size)
                .putString("${spec.id}.$key.oid", rf.oid)
        }
        edit.apply()
        for ((key, rf) in targets) {
            val f = File(d, rf.fileName)
            if (f.exists() && f.length() == rf.size) continue
            if (f.exists()) f.delete()
            FastDownloader.enqueue(
                context,
                FastDownloader.Job("${spec.id}.$key", HfResolver.resolveUrl(spec.repo, rf.path), f.absolutePath, rf.size, wifiOnly,
                    if (key == "mmproj") "${spec.name}（画像エンコーダ）" else spec.name),
            )
        }
    }

    /**
     * Compares an installed model with what Hugging Face serves now. An update is a different
     * file being chosen (e.g. a better quantization appeared) or the same file re-uploaded.
     * Returns (description, revision tag) or null when up to date.
     */
    suspend fun checkUpdate(spec: ModelSpec): Pair<String, String>? {
        if (state(spec) != InstallState.Installed) return null
        val r = HfResolver.resolve(spec)
        val changes = mutableListOf<String>()
        for ((key, rf) in listOfNotNull("model" to r.model, r.mmproj?.let { "mmproj" to it })) {
            val name = prefs.getString("${spec.id}.$key.name", null)
            val size = prefs.getLong("${spec.id}.$key.size", -1)
            val oid = prefs.getString("${spec.id}.$key.oid", "") ?: ""
            when {
                name != rf.fileName -> changes += "$name → ${rf.fileName}"
                size != rf.size -> changes += "${rf.fileName} が差し替え（サイズ変更）"
                oid.isNotEmpty() && rf.oid.isNotEmpty() && oid != rf.oid -> changes += "${rf.fileName} が差し替え"
                oid.isEmpty() && rf.oid.isNotEmpty() ->   // downloaded before oids were recorded: adopt as baseline
                    prefs.edit().putString("${spec.id}.$key.oid", rf.oid).apply()
            }
        }
        if (changes.isEmpty()) return null
        val tag = (r.model.oid.ifEmpty { r.model.size.toString() } + (r.mmproj?.oid ?: "")).take(10)
        return changes.joinToString("、") to tag
    }

    fun delete(spec: ModelSpec) {
        FastDownloader.cancel(context, "${spec.id}.")
        val edit = prefs.edit()
        for (key in listOf("model", "mmproj")) {
            edit.remove("${spec.id}.$key.name").remove("${spec.id}.$key.size").remove("${spec.id}.$key.oid")
        }
        edit.apply()
        dir(spec).deleteRecursively()
    }

    /** Deletes model folders whose id is not in the catalog any more. Returns freed bytes. */
    fun cleanupOrphans(knownIds: Set<String>): Long {
        var freed = 0L
        rootDir.listFiles()?.filter { it.isDirectory && it.name !in knownIds }?.forEach { d ->
            freed += d.walkTopDown().filter { it.isFile }.sumOf { it.length() }
            d.deleteRecursively()
        }
        return freed
    }

    fun usedBytes(): Long = rootDir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
}
