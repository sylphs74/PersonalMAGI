package jp.pocketmagi.data

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Music while the sages deliberate (looped) and when they finish (once). The chosen files are
 * copied into the app, so they keep working even if the original is moved or deleted.
 */
object Sounds {
    const val RUNNING = "running"
    const val DONE = "done"

    private var loop: MediaPlayer? = null
    private var once: MediaPlayer? = null

    private fun dir(c: Context) = File(c.filesDir, "sounds").apply { mkdirs() }
    fun file(c: Context, kind: String) = File(dir(c), kind)
    fun has(c: Context, kind: String) = file(c, kind).let { it.exists() && it.length() > 0 }

    /** Copies the picked audio file; returns its display name. */
    suspend fun import(c: Context, uri: Uri, kind: String): String = withContext(Dispatchers.IO) {
        val name = c.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cur ->
            if (cur.moveToFirst()) cur.getString(0) else null
        } ?: "音声ファイル"
        val tmp = File(dir(c), "$kind.tmp")
        c.contentResolver.openInputStream(uri)?.use { input -> tmp.outputStream().use { input.copyTo(it) } }
            ?: error("ファイルを開けません")
        // check that it can actually be played before replacing the current one
        val probe = MediaPlayer()
        try { probe.setDataSource(tmp.path); probe.prepare() } catch (e: Exception) {
            tmp.delete(); throw IllegalArgumentException("再生できない形式です")
        } finally { probe.release() }
        tmp.renameTo(file(c, kind))
        name
    }

    fun remove(c: Context, kind: String) { stopAll(); file(c, kind).delete() }

    private fun player(c: Context, kind: String, looping: Boolean): MediaPlayer? = try {
        MediaPlayer().apply {
            setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            setDataSource(file(c, kind).path)
            isLooping = looping
            prepare()
        }
    } catch (e: Exception) { null }

    /** Starts the deliberation music (looped) when it is switched on and a file is set. */
    @Synchronized
    fun startRunning(c: Context, s: MagiSettings) {
        stopRunning()
        if (!s.musicRunningOn || !has(c, RUNNING)) return
        loop = player(c, RUNNING, true)?.also { it.start() }
    }

    @Synchronized
    fun stopRunning() {
        loop?.let { try { it.stop() } catch (_: Exception) {}; it.release() }
        loop = null
    }

    /** Plays the completion music once. */
    @Synchronized
    fun playDone(c: Context, s: MagiSettings) {
        if (!s.musicDoneOn || !has(c, DONE)) return
        playOnce(c, DONE)
    }

    /** Preview from the settings screen. */
    @Synchronized
    fun preview(c: Context, kind: String) {
        stopAll()
        if (has(c, kind)) playOnce(c, kind)
    }

    @Synchronized
    private fun playOnce(c: Context, kind: String) {
        once?.release()
        once = player(c, kind, false)?.also { mp ->
            mp.setOnCompletionListener { synchronized(this) { it.release(); if (once === it) once = null } }
            mp.start()
        }
    }

    /** Stops the completion music (or a preview) if it is playing. */
    @Synchronized
    fun stopDone() {
        once?.let { try { it.stop() } catch (_: Exception) {}; it.release() }
        once = null
    }

    @Synchronized
    fun stopAll() {
        stopRunning()
        once?.let { try { it.stop() } catch (_: Exception) {}; it.release() }
        once = null
    }
}
