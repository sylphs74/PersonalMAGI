package jp.pocketmagi.data

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import org.json.JSONArray
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Updates of the app itself from the project's GitHub Releases (where CI publishes every build).
 * A private repository needs a read-only token; the downloaded APK is handed to Android's installer.
 */
object AppUpdate {
    const val REPO = "sylphs74/PersonalMAGI"

    data class Release(val build: Int, val tag: String, val assetName: String, val size: Long, val apiUrl: String, val browserUrl: String)

    private fun conn(url: String, token: String?, accept: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 20_000; readTimeout = 60_000; instanceFollowRedirects = false
            setRequestProperty("User-Agent", "PersonalMAGI-updater")
            setRequestProperty("Accept", accept)
            if (!token.isNullOrBlank()) setRequestProperty("Authorization", "Bearer $token")
        }

    /** Newest release with an APK, or null when there is none. */
    fun latest(token: String?): Release? {
        val c = conn("https://api.github.com/repos/$REPO/releases?per_page=5", token, "application/vnd.github+json")
        try {
            when (c.responseCode) {
                200 -> {}
                401 -> error("トークンが無効です")
                404 -> error("リポジトリまたはリリースが見つかりません")
                else -> error("HTTP ${c.responseCode}")
            }
            val a = JSONArray(c.inputStream.bufferedReader().use { it.readText() })
            for (i in 0 until a.length()) {
                val r = a.optJSONObject(i) ?: continue
                val tag = r.optString("tag_name")
                val build = Regex("(\\d+)$").find(tag)?.groupValues?.get(1)?.toIntOrNull() ?: continue
                val assets = r.optJSONArray("assets") ?: continue
                for (k in 0 until assets.length()) {
                    val x = assets.optJSONObject(k) ?: continue
                    if (x.optString("name").endsWith(".apk"))
                        return Release(build, tag, x.optString("name"), x.optLong("size"), x.optString("url"), x.optString("browser_download_url"))
                }
            }
            return null
        } finally { c.disconnect() }
    }

    fun currentBuild(c: Context): Long = try {
        androidx.core.content.pm.PackageInfoCompat.getLongVersionCode(c.packageManager.getPackageInfo(c.packageName, 0))
    } catch (e: Exception) { 0 }

    /**
     * Downloads the APK. With a token the asset is fetched through the API, whose redirect to the
     * storage server must be followed without the token.
     */
    fun download(c: Context, r: Release, token: String?, onProgress: (Long, Long) -> Unit): File {
        val dir = File(c.cacheDir, "updates").apply { mkdirs(); listFiles()?.forEach { it.delete() } }
        val out = File(dir, r.assetName)
        var url = if (token.isNullOrBlank()) r.browserUrl else r.apiUrl
        var auth = token
        repeat(6) {
            val h = conn(url, auth, "application/octet-stream")
            try {
                val code = h.responseCode
                if (code in 300..399) {
                    url = h.getHeaderField("Location") ?: error("HTTP $code")
                    auth = null                        // never send the token to the storage server
                    return@repeat
                }
                if (code != 200) error("HTTP $code")
                val total = h.contentLengthLong.takeIf { it > 0 } ?: r.size
                h.inputStream.use { input ->
                    out.outputStream().use { o ->
                        val buf = ByteArray(256 * 1024); var done = 0L
                        while (true) { val n = input.read(buf); if (n < 0) break; o.write(buf, 0, n); done += n; onProgress(done, total) }
                    }
                }
                return out
            } finally { h.disconnect() }
        }
        error("リダイレクトが多すぎます")
    }

    /** Opens Android's installer for the downloaded APK (asks for the install permission first if needed). */
    fun install(c: Context, apk: File): Boolean {
        if (!c.packageManager.canRequestPackageInstalls()) {
            c.startActivity(Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                android.net.Uri.parse("package:${c.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return false
        }
        val uri = FileProvider.getUriForFile(c, "${c.packageName}.files", apk)
        c.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK))
        return true
    }
}
