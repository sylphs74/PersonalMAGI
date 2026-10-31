package jp.pocketmagi.model

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Parallel, resumable downloader. A file is split into 16 MB blocks fetched over several HTTP
 * range connections at once; finished blocks are recorded in a ".blocks" file so a download
 * continues where it stopped (app killed, network lost). The queue is persisted and processed
 * one file at a time by [DownloadService].
 */
object FastDownloader {
    data class Job(val key: String, val url: String, val dest: String, val size: Long, val wifiOnly: Boolean, val title: String)
    data class Progress(val done: Long, val total: Long, val speed: Long = 0, val note: String = "", val failed: String? = null)

    private const val BLOCK = 16L * 1024 * 1024
    private const val RETRIES = 6

    /** Simultaneous connections per file (set from the settings). */
    @Volatile var connections = 6

    private val progress = ConcurrentHashMap<String, Progress>()
    private val cancelled: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val lock = Any()

    fun progress(key: String): Progress? = progress[key]

    // ---- persisted queue

    private fun prefs(c: Context) = c.getSharedPreferences("fastdl", Context.MODE_PRIVATE)

    fun queue(c: Context): List<Job> = synchronized(lock) {
        try {
            val a = JSONArray(prefs(c).getString("queue", "[]"))
            (0 until a.length()).map { i ->
                val o = a.getJSONObject(i)
                Job(o.getString("key"), o.getString("url"), o.getString("dest"), o.getLong("size"), o.getBoolean("wifi"), o.getString("title"))
            }
        } catch (e: Exception) { emptyList() }
    }

    private fun saveQueue(c: Context, list: List<Job>) = synchronized(lock) {
        val a = JSONArray()
        list.forEach {
            a.put(JSONObject().put("key", it.key).put("url", it.url).put("dest", it.dest).put("size", it.size)
                .put("wifi", it.wifiOnly).put("title", it.title))
        }
        prefs(c).edit().putString("queue", a.toString()).apply()
    }

    fun enqueue(c: Context, job: Job) {
        synchronized(lock) { saveQueue(c, queue(c).filterNot { it.key == job.key } + job) }
        cancelled.remove(job.key)
        progress[job.key] = Progress(alreadyDone(job), job.size, note = "待機中")
        DownloadService.start(c)
    }

    /** Stops and forgets every job whose key starts with [prefix]; partial files are deleted. */
    fun cancel(c: Context, prefix: String) {
        val gone = synchronized(lock) {
            val q = queue(c)
            saveQueue(c, q.filterNot { it.key.startsWith(prefix) })
            q.filter { it.key.startsWith(prefix) }
        }
        gone.forEach { j ->
            cancelled.add(j.key)
            File(j.dest + ".part").delete(); File(j.dest + ".blocks").delete()
        }
        progress.keys.filter { it.startsWith(prefix) }.forEach { progress.remove(it) }
    }

    /** Forgets queued jobs for models that are no longer in the catalog (their folder is about to go). */
    fun dropOrphans(c: Context, knownIds: Set<String>) {
        val gone = synchronized(lock) {
            val q = queue(c)
            val (keep, drop) = q.partition { File(it.dest).parentFile?.name in knownIds }
            if (drop.isNotEmpty()) saveQueue(c, keep)
            drop
        }
        gone.forEach { j -> cancelled.add(j.key); progress.remove(j.key) }
    }

    /** Called at app start: shows queued jobs as waiting and restarts the service. */
    fun resumeIfNeeded(c: Context) {
        val q = queue(c)
        if (q.isEmpty()) return
        q.forEach { j -> progress.putIfAbsent(j.key, Progress(alreadyDone(j), j.size, note = "再開待ち")) }
        DownloadService.start(c)
    }

    private fun alreadyDone(j: Job): Long {
        val map = File(j.dest + ".blocks")
        if (!map.exists()) return 0
        val n = blocks(j.size)
        val bits = map.readBytes()
        var d = 0L
        for (i in 0 until minOf(n, bits.size)) if (bits[i].toInt() == 1) d += blockLen(j.size, i)
        return d
    }

    private fun blocks(size: Long) = ((size + BLOCK - 1) / BLOCK).toInt()
    private fun blockLen(size: Long, i: Int) = minOf(BLOCK, size - i * BLOCK)

    private fun metered(c: Context): Boolean {
        val cm = c.getSystemService(ConnectivityManager::class.java) ?: return false
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return true
        return !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }

    // ---- processing (called by the service)

    suspend fun runAll(c: Context, onTick: (Job, Progress) -> Unit) {
        while (true) {
            val job = queue(c).firstOrNull() ?: break
            try {
                runJob(c, job, onTick)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                // a broken job (folder removed, disk full...) is reported and dropped, never retried forever
                synchronized(lock) { saveQueue(c, queue(c).filterNot { it.key == job.key }) }
                progress[job.key] = Progress(0, job.size, failed = "保存に失敗（${e.message}）")
            }
        }
    }

    private class Fatal(msg: String) : Exception(msg)

    private suspend fun runJob(c: Context, job: Job, onTick: (Job, Progress) -> Unit) = withContext(Dispatchers.IO) {
        val dest = File(job.dest)
        val part = File(job.dest + ".part")
        val map = File(job.dest + ".blocks")
        fun dequeue() = synchronized(lock) { saveQueue(c, queue(c).filterNot { it.key == job.key }) }

        if (dest.exists() && dest.length() == job.size) { dequeue(); progress.remove(job.key); return@withContext }
        dest.parentFile?.mkdirs()
        val n = blocks(job.size)
        if (!part.exists() || part.length() != job.size) {
            map.delete()
            RandomAccessFile(part, "rw").use { it.setLength(job.size) }
        }
        val bits = ByteArray(n).also { b ->
            if (map.exists()) map.readBytes().copyInto(b, 0, 0, minOf(n, map.length().toInt()))
            else RandomAccessFile(map, "rw").use { it.setLength(n.toLong()) }
        }
        val done = AtomicLong((0 until n).filter { bits[it].toInt() == 1 }.sumOf { blockLen(job.size, it) })
        val next = AtomicInteger(0)
        var fatal: String? = null
        var note = ""

        coroutineScope {
            val ticker = launch {
                var last = done.get(); var lastT = System.currentTimeMillis()
                while (isActive) {
                    delay(1000)
                    val d = done.get(); val t = System.currentTimeMillis()
                    val speed = if (t > lastT) (d - last) * 1000 / (t - lastT) else 0
                    last = d; lastT = t
                    val p = Progress(d, job.size, speed, note)
                    if (!cancelled.contains(job.key)) { progress[job.key] = p; onTick(job, p) }
                }
            }
            val workers = (1..connections.coerceIn(1, 8)).map {
                launch {
                    RandomAccessFile(part, "rw").use { raf ->
                        val buf = ByteArray(256 * 1024)
                        while (isActive && fatal == null && !cancelled.contains(job.key)) {
                            var i: Int
                            do { i = next.getAndIncrement() } while (i < n && bits[i].toInt() == 1)
                            if (i >= n) break
                            val start = i * BLOCK
                            val end = start + blockLen(job.size, i) - 1
                            var attempt = 0
                            while (true) {
                                while (job.wifiOnly && metered(c) && !cancelled.contains(job.key)) { note = "Wi-Fi接続待ち"; delay(5000) }
                                note = ""
                                var got = 0L
                                try {
                                    val conn = URL(job.url).openConnection() as HttpURLConnection
                                    conn.connectTimeout = 20_000; conn.readTimeout = 60_000
                                    conn.setRequestProperty("Range", "bytes=$start-$end")
                                    conn.setRequestProperty("Accept-Encoding", "identity")
                                    try {
                                        val code = conn.responseCode
                                        if (code != 206) throw if (code == 200) Fatal("サーバーが分割ダウンロードに非対応") else java.io.IOException("HTTP $code")
                                        raf.seek(start)
                                        conn.inputStream.use { input ->
                                            while (true) {
                                                if (cancelled.contains(job.key)) return@launch
                                                val r = input.read(buf); if (r < 0) break
                                                if (got + r > end - start + 1) throw java.io.IOException("想定より多いデータ")
                                                raf.write(buf, 0, r); got += r; done.addAndGet(r.toLong())
                                            }
                                        }
                                        if (got != end - start + 1) throw java.io.IOException("途中で切断")
                                    } finally { conn.disconnect() }
                                    synchronized(bits) {
                                        bits[i] = 1
                                        RandomAccessFile(map, "rw").use { m -> m.seek(i.toLong()); m.write(1) }
                                    }
                                    break
                                } catch (e: Fatal) {
                                    done.addAndGet(-got); fatal = e.message; break
                                } catch (e: Exception) {
                                    done.addAndGet(-got)
                                    if (++attempt >= RETRIES) { fatal = "通信エラー（${e.message}）"; break }
                                    note = "再接続中"
                                    delay(1500L * attempt)
                                }
                            }
                        }
                    }
                }
            }
            workers.joinAll()
            ticker.cancel()
        }

        when {
            cancelled.contains(job.key) -> { part.delete(); map.delete(); progress.remove(job.key) }
            fatal != null -> {
                dequeue()   // keep the partial file: "再試行" resumes from the finished blocks
                progress[job.key] = Progress(done.get(), job.size, failed = fatal)
            }
            bits.all { it.toInt() == 1 } -> {
                dest.delete()
                if (part.renameTo(dest)) { map.delete(); dequeue(); progress.remove(job.key) }
                else { dequeue(); progress[job.key] = Progress(done.get(), job.size, failed = "保存に失敗") }
            }
        }
    }
}
