package jp.pocketmagi.data

import android.content.Context
import android.graphics.Typeface
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.util.zip.ZipInputStream

data class UserFont(val id: String, val name: String, val file: File)

/**
 * User-registered fonts. Accepts a single .ttf/.otf/.ttc or a .zip that contains them.
 * Files are copied into app storage, so the original can be deleted afterwards.
 */
class FontStore(private val context: Context) {
    private val dir: File get() = File(context.filesDir, "fonts").apply { mkdirs() }

    private val fontExt = setOf("ttf", "otf", "ttc")
    private val maxFontBytes = 64L * 1024 * 1024   // a single CJK font can be ~20–30 MB
    private val maxZipEntries = 200

    fun list(): List<UserFont> =
        dir.listFiles()?.filter { it.isFile && it.extension.lowercase() in fontExt }
            ?.sortedBy { it.name.lowercase() }
            ?.map { UserFont(it.name, it.nameWithoutExtension, it) } ?: emptyList()

    @OptIn(ExperimentalTextApi::class)
    fun family(id: String): FontFamily? {
        if (id.isBlank()) return null
        val f = File(dir, id)
        if (!f.isFile) return null
        return try { FontFamily(Font(f)) } catch (e: Exception) { null }
    }

    fun delete(id: String) { File(dir, id).takeIf { it.isFile }?.delete() }

    /** Returns the names that were registered. Throws with a readable message if nothing usable was found. */
    suspend fun import(uri: Uri): List<String> = withContext(Dispatchers.IO) {
        val name = displayName(uri) ?: "font"
        val ext = name.substringAfterLast('.', "").lowercase()
        val added = mutableListOf<String>()
        val input = context.contentResolver.openInputStream(uri) ?: throw IllegalStateException("ファイルを開けません")
        input.use { stream ->
            when {
                ext in fontExt -> save(name, stream)?.let { added += it }
                ext == "zip" || looksLikeZip(uri) -> added += fromZip(stream)
                else -> throw IllegalStateException("対応形式は .ttf / .otf / .ttc / .zip です")
            }
        }
        if (added.isEmpty()) throw IllegalStateException("使えるフォントが見つかりませんでした")
        added
    }

    private fun fromZip(stream: InputStream): List<String> {
        val out = mutableListOf<String>()
        ZipInputStream(stream.buffered()).use { zip ->
            var n = 0
            while (true) {
                val e = zip.nextEntry ?: break
                if (++n > maxZipEntries) break
                if (e.isDirectory) continue
                // never trust paths inside the archive: keep only the bare file name
                val base = e.name.substringAfterLast('/').substringAfterLast('\\')
                if (base.startsWith(".") || base.startsWith("__MACOSX")) continue
                if (base.substringAfterLast('.', "").lowercase() !in fontExt) continue
                save(base, zip)?.let { out += it }
            }
        }
        return out
    }

    /** Copies at most [maxFontBytes]; keeps the file only if Android can parse it as a font. */
    private fun save(rawName: String, src: InputStream): String? {
        val safe = rawName.replace(Regex("[^A-Za-z0-9._\\-\\u3040-\\u30ff\\u4e00-\\u9fff]"), "_").take(80)
        var target = File(dir, safe)
        var i = 1
        while (target.exists()) {
            target = File(dir, "${safe.substringBeforeLast('.')}_$i.${safe.substringAfterLast('.')}"); i++
        }
        val tmp = File(dir, "${target.name}.part")
        var total = 0L
        tmp.outputStream().use { out ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val r = src.read(buf)
                if (r < 0) break
                total += r
                if (total > maxFontBytes) { tmp.delete(); return null }
                out.write(buf, 0, r)
            }
        }
        val ok = try { Typeface.Builder(tmp).build() != null } catch (e: Exception) { false }
        if (!ok) { tmp.delete(); return null }
        tmp.renameTo(target)
        return target.nameWithoutExtension
    }

    private fun displayName(uri: Uri): String? =
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }

    private fun looksLikeZip(uri: Uri): Boolean =
        context.contentResolver.getType(uri)?.contains("zip") == true
}
