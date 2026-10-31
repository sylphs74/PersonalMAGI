package jp.pocketmagi.engine

import android.content.Context
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.Executors

/**
 * Single-threaded gateway to the native llama.cpp engine.
 * Only one model is resident at a time; every native call runs on [dispatcher].
 */
object LlamaBridge {
    private val dispatcher = Executors.newSingleThreadExecutor { r ->
        Thread(null, r, "pocketmagi-llama", 64L * 1024 * 1024)
    }.asCoroutineDispatcher()

    @Volatile private var initialized = false

    /** Set by the app: breadcrumbs for crash reports. [label] names the current sage/model. */
    @Volatile var guard: jp.pocketmagi.data.CrashGuard? = null
    @Volatile var label: String = ""
    @Volatile private var onGpu = false
    @Volatile var loadedPath: String? = null
        private set

    fun interface TokenListener {
        fun onToken(utf8: ByteArray): Boolean
    }

    suspend fun init(context: Context) {
        withContext(dispatcher) {
            if (!initialized) {
                System.loadLibrary("pocketmagi")
                val cache = java.io.File(context.cacheDir, "cl-cache").apply { mkdirs() }
                val log = guard?.logFile?.absolutePath ?: ""
                nativeInit(context.applicationInfo.nativeLibraryDir, cache.absolutePath, log)
                initialized = true
            }
        }
    }

    suspend fun systemInfo(context: Context): String {
        init(context)
        return withContext(dispatcher) { nativeSystemInfo() }
    }

    /** GPU devices (one per line) found on this phone; empty when only the CPU is usable. */
    suspend fun gpuDevices(context: Context): String {
        init(context)
        return withContext(dispatcher) { nativeGpuDevices() }
    }

    /** GPUs found but not used because their drivers are known to crash (Adreno 6xx). */
    suspend fun unusableGpus(context: Context): String {
        init(context)
        return withContext(dispatcher) { nativeUnusableGpus() }
    }

    /**
     * Loads a model. With [useGpu], tries the GPU first and silently falls back to the CPU
     * if the GPU load fails. Returns what the model ended up running on.
     */
    suspend fun load(model: File, mmproj: File?, nCtx: Int, threads: Int, imageMaxTokens: Int, useGpu: Boolean): String =
        withContext(dispatcher) {
            loadedPath = null
            step("$label / 読み込み", useGpu)
            var err = nativeLoad(model.absolutePath, mmproj?.absolutePath, nCtx, threads, imageMaxTokens, useGpu)
            var note = ""
            if (err != null && useGpu) {
                note = "（GPU読み込み失敗のためCPU）"
                step("$label / 読み込み(CPU再試行)", false)
                err = nativeLoad(model.absolutePath, mmproj?.absolutePath, nCtx, threads, imageMaxTokens, false)
            }
            if (err != null) throw IllegalStateException(err)
            loadedPath = model.absolutePath
            val backend = nativeBackend()
            onGpu = backend.startsWith("GPU")
            nativeMark("loaded on $backend")
            backend + note
        }

    suspend fun generate(
        system: String,
        user: String,
        images: List<ByteArray>,
        maxTokens: Int,
        temperature: Float,
        prefill: String = "",
        onPiece: (String) -> Unit,
    ): String = withContext(dispatcher) {
        val listener = TokenListener { bytes ->
            onPiece(String(bytes, Charsets.UTF_8))
            true
        }
        step("$label / ${if (images.isEmpty()) "生成" else "画像解析・生成"}", onGpu)
        val out = nativeGenerate(
            system.toByteArray(Charsets.UTF_8),
            user.toByteArray(Charsets.UTF_8),
            prefill.toByteArray(Charsets.UTF_8),
            if (images.isEmpty()) null else images.toTypedArray(),
            maxTokens,
            temperature,
            listener,
        )
        prefill + String(out ?: ByteArray(0), Charsets.UTF_8)
    }

    suspend fun lastStats(): String = withContext(dispatcher) { nativeLastStats() }

    suspend fun unload() {
        withContext(dispatcher) {
            nativeUnload()
            loadedPath = null
            onGpu = false
            guard?.leave()
        }
    }

    private fun step(stage: String, gpu: Boolean) {
        guard?.enter(stage + if (gpu) "（GPU）" else "（CPU）", gpu)
        nativeMark(stage + if (gpu) " [GPU]" else " [CPU]")
    }

    suspend fun mark(text: String) = withContext(dispatcher) { if (initialized) nativeMark(text) }

    /** Safe from any thread: aborts the running decode/generation as soon as possible. */
    fun cancel() = nativeCancel()

    @JvmStatic private external fun nativeInit(libDir: String, cacheDir: String, logPath: String)
    @JvmStatic private external fun nativeMark(text: String)
    @JvmStatic private external fun nativeGpuDevices(): String
    @JvmStatic private external fun nativeUnusableGpus(): String
    @JvmStatic private external fun nativeBackend(): String
    @JvmStatic private external fun nativeSystemInfo(): String
    @JvmStatic private external fun nativeLoad(model: String, mmproj: String?, nCtx: Int, threads: Int, imageMaxTokens: Int, useGpu: Boolean): String?
    @JvmStatic private external fun nativeUnload()
    @JvmStatic private external fun nativeCancel()
    @JvmStatic private external fun nativeLastStats(): String
    @JvmStatic private external fun nativeGenerate(
        system: ByteArray,
        user: ByteArray,
        prefill: ByteArray,
        images: Array<ByteArray>?,
        maxTokens: Int,
        temperature: Float,
        listener: TokenListener?,
    ): ByteArray?
}
