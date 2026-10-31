package jp.pocketmagi.data

import android.content.Context
import java.io.File

data class CrashReport(
    val stage: String,      // e.g. "CASPER / Rakuten AI 2.0 mini / 生成"
    val onGpu: Boolean,
    val logTail: String,    // last lines of the engine log of the crashed session
)

/**
 * Breadcrumbs for native crashes, which cannot be caught: before each risky step the
 * current stage is written to a file and removed when the session ends normally.
 * If the file is still there on the next launch, the app died during that stage.
 */
class CrashGuard(context: Context) {
    private val dir = context.filesDir
    private val stageFile = File(dir, "stage.txt")
    val logFile = File(dir, "engine.log")
    val prevLogFile = File(dir, "engine.prev.log")
    val kotlinCrashFile = File(dir, "crash_kotlin.txt")

    @Synchronized
    fun enter(stage: String, onGpu: Boolean) {
        try { stageFile.writeText("${if (onGpu) 1 else 0}\n$stage") } catch (_: Exception) {}
    }

    @Synchronized
    fun leave() { stageFile.delete() }

    /** Call once at startup, before the native engine opens a fresh log. */
    @Synchronized
    fun collectPreviousCrash(): CrashReport? {
        if (logFile.exists()) {
            prevLogFile.delete()
            logFile.renameTo(prevLogFile)
        }
        val kotlinCrash = kotlinCrashFile.takeIf { it.exists() }?.readText()?.also { kotlinCrashFile.delete() }
        if (!stageFile.exists() && kotlinCrash == null) return null
        val lines = stageFile.takeIf { it.exists() }?.readLines() ?: emptyList()
        stageFile.delete()
        val onGpu = lines.firstOrNull() == "1"
        val stage = lines.drop(1).joinToString(" ").ifBlank { "不明" }
        val tail = buildString {
            if (prevLogFile.exists()) append(prevLogFile.readLines().takeLast(60).joinToString("\n"))
            if (kotlinCrash != null) append("\n--- Kotlin ---\n").append(kotlinCrash.take(4000))
        }
        return CrashReport(stage, onGpu, tail)
    }

    fun currentLog(): String {
        val cur = logFile.takeIf { it.exists() }?.readLines()?.takeLast(120)?.joinToString("\n") ?: ""
        val prev = prevLogFile.takeIf { it.exists() }?.readLines()?.takeLast(60)?.joinToString("\n") ?: ""
        return buildString {
            append("=== 今回のセッション ===\n").append(cur.ifBlank { "(まだ記録なし)" })
            if (prev.isNotBlank()) append("\n\n=== 前回のセッション ===\n").append(prev)
        }
    }

    /** Records uncaught Kotlin exceptions so they show up in the next report. */
    fun installUncaughtHandler() {
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try { kotlinCrashFile.writeText("thread=${t.name}\n" + e.stackTraceToString()) } catch (_: Exception) {}
            prev?.uncaughtException(t, e)
        }
    }
}
