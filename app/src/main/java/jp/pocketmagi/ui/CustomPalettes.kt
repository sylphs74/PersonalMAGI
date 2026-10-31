package jp.pocketmagi.ui

import android.content.Context
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

/** A VS Code theme extension found on Open VSX. */
data class ThemeExt(val namespace: String, val name: String, val displayName: String, val downloads: Long, val download: String)

/**
 * Colour schemes made or imported by the user: written in the app's own JSON format, or converted
 * from VS Code colour themes (searched on Open VSX, or a .json / .vsix file).
 */
object CustomPalettes {
    val list = mutableStateListOf<Palette>()

    /** The colour roles of a palette, with their names in the editor. */
    val ROLES = listOf(
        "bg" to "背景", "panel" to "パネル", "accent" to "アクセント（枠・見出し）", "accentDim" to "補助線・控えめな枠",
        "text" to "文字", "textDim" to "補助の文字", "approve" to "承認", "reject" to "否決", "pending" to "保留",
        "eye" to "強調（提案・出典）", "inputBar" to "入力欄",
    )

    private fun file(c: Context) = File(c.filesDir, "palettes.json")

    fun load(c: Context) {
        list.clear()
        try {
            val a = JSONArray(file(c).readText())
            for (i in 0 until a.length()) fromJson(a.getJSONObject(i))?.let { list += it }
        } catch (_: Exception) {}
    }

    private fun save(c: Context) {
        val a = JSONArray(); list.forEach { a.put(toJson(it)) }
        file(c).writeText(a.toString(2))
    }

    /** Adds or replaces (same id) a palette. */
    fun put(c: Context, p: Palette) {
        val i = list.indexOfFirst { it.id == p.id }
        if (i >= 0) list[i] = p else list += p
        save(c)
    }

    fun remove(c: Context, id: String) { list.removeAll { it.id == id }; save(c) }

    fun newId() = "custom-" + System.currentTimeMillis().toString(36)

    // ---- the app's own format

    fun hex(c: Color): String = "#%06X".format(c.toArgb() and 0xFFFFFF)

    fun get(p: Palette, role: String): Color = when (role) {
        "bg" -> p.bg; "panel" -> p.panel; "accent" -> p.accent; "accentDim" -> p.accentDim; "text" -> p.text
        "textDim" -> p.textDim; "approve" -> p.approve; "reject" -> p.reject; "pending" -> p.pending; "eye" -> p.eye
        else -> p.inputBar
    }

    fun with(p: Palette, role: String, c: Color): Palette = when (role) {
        "bg" -> p.copy(bg = c); "panel" -> p.copy(panel = c); "accent" -> p.copy(accent = c); "accentDim" -> p.copy(accentDim = c)
        "text" -> p.copy(text = c); "textDim" -> p.copy(textDim = c); "approve" -> p.copy(approve = c); "reject" -> p.copy(reject = c)
        "pending" -> p.copy(pending = c); "eye" -> p.copy(eye = c); else -> p.copy(inputBar = c)
    }

    fun toJson(p: Palette): JSONObject = JSONObject().put("id", p.id).put("label", p.label).apply {
        ROLES.forEach { (r, _) -> put(r, hex(get(p, r))) }
    }

    fun fromJson(o: JSONObject): Palette? = try {
        var p = Palettes.Magi.copy(id = o.optString("id").ifBlank { newId() }, label = o.optString("label").ifBlank { "カスタム" })
        ROLES.forEach { (r, _) -> parse(o.optString(r))?.let { p = with(p, r, it) } }
        p
    } catch (e: Exception) { null }

    /** #RGB, #RRGGBB or #RRGGBBAA (the alpha is dropped). */
    fun parse(s: String?): Color? {
        val h = s?.trim()?.removePrefix("#") ?: return null
        val rgb = when (h.length) {
            3 -> h.map { "$it$it" }.joinToString("")
            4 -> h.take(3).map { "$it$it" }.joinToString("")
            6, 8 -> h.take(6)
            else -> return null
        }
        return rgb.toLongOrNull(16)?.let { Color(0xFF000000 or it) }
    }

    // ---- VS Code colour themes

    private const val UA = "PersonalMAGI/0.1 (personal on-device assistant)"

    /** Theme extensions on Open VSX (the open registry of VS Code extensions), most downloaded first. */
    fun search(query: String): List<ThemeExt> {
        val q = java.net.URLEncoder.encode(query.trim(), "UTF-8")
        val o = JSONObject(httpText("https://open-vsx.org/api/-/search?category=Themes&size=30&sortBy=downloadCount&sortOrder=desc&query=$q"))
        val a = o.optJSONArray("extensions") ?: return emptyList()
        return (0 until a.length()).mapNotNull { i ->
            val e = a.optJSONObject(i) ?: return@mapNotNull null
            val dl = e.optJSONObject("files")?.optString("download").orEmpty()
            val name = e.optString("name")
            // icon and product-icon themes have no colours to take
            if (dl.isBlank() || Regex("icon", RegexOption.IGNORE_CASE).containsMatchIn(name)) null
            else ThemeExt(e.optString("namespace"), name, e.optString("displayName").ifBlank { name }, e.optLong("downloadCount"), dl)
        }
    }

    /** Downloads the extension and converts every colour theme in it. */
    fun fromExtension(ext: ThemeExt): List<Palette> = fromVsix(httpBytes(ext.download))

    /** Colour themes inside a .vsix (a zip with extension/package.json listing the theme files). */
    fun fromVsix(bytes: ByteArray): List<Palette> {
        val files = mutableMapOf<String, String>()
        ZipInputStream(bytes.inputStream()).use { z ->
            while (true) {
                val e = z.nextEntry ?: break
                if (!e.isDirectory && (e.name.endsWith(".json") || e.name.endsWith(".jsonc"))) files[e.name] = z.readBytes().toString(Charsets.UTF_8)
            }
        }
        val pkg = files["extension/package.json"]?.let { JSONObject(stripJsonc(it)) } ?: error("テーマの拡張機能ではありません")
        val themes = pkg.optJSONObject("contributes")?.optJSONArray("themes") ?: error("カラーテーマが入っていません")
        val out = mutableListOf<Palette>()
        for (i in 0 until themes.length()) {
            val t = themes.optJSONObject(i) ?: continue
            val path = "extension/" + t.optString("path").removePrefix("./")
            val label = t.optString("label").ifBlank { pkg.optString("displayName") }
            try {
                val colors = collectColors(path, files)
                if (colors.isNotEmpty()) out += fromVsCodeColors(label, colors)
            } catch (_: Exception) {}
        }
        if (out.isEmpty()) error("取り込めるカラーテーマがありません")
        return out
    }

    /** A single VS Code theme .json (with "colors"), or the app's own palette format. */
    fun fromThemeJson(text: String, fallbackName: String): List<Palette> {
        val stripped = stripJsonc(text)
        val trimmed = stripped.trimStart()
        if (trimmed.startsWith("[")) {
            val a = JSONArray(stripped); return (0 until a.length()).mapNotNull { fromJson(a.getJSONObject(it))?.copy(id = newId() + it) }
        }
        val o = JSONObject(stripped)
        if (o.has("colors")) {
            val map = mutableMapOf<String, String>()
            val c = o.getJSONObject("colors"); c.keys().forEach { k -> map[k] = c.optString(k) }
            return listOf(fromVsCodeColors(o.optString("name").ifBlank { fallbackName }, map))
        }
        return listOfNotNull(fromJson(o)?.copy(id = newId()))
    }

    /** "colors" of a theme file, after the theme it includes (one level is enough in practice). */
    private fun collectColors(path: String, files: Map<String, String>, depth: Int = 0): Map<String, String> {
        val o = JSONObject(stripJsonc(files[path] ?: return emptyMap()))
        val out = mutableMapOf<String, String>()
        val inc = o.optString("include")
        if (inc.isNotBlank() && depth < 3) {
            val base = path.substringBeforeLast('/')
            out += collectColors(normalize("$base/$inc"), files, depth + 1)
        }
        o.optJSONObject("colors")?.let { c -> c.keys().forEach { k -> c.optString(k).takeIf { it.startsWith("#") }?.let { out[k] = it } } }
        return out
    }

    private fun normalize(p: String): String {
        val parts = mutableListOf<String>()
        p.split('/').forEach { s -> when (s) { "", "." -> {}; ".." -> if (parts.isNotEmpty()) parts.removeAt(parts.size - 1); else -> parts += s } }
        return parts.joinToString("/")
    }

    /** Maps VS Code workbench colours onto the app's roles, falling back sensibly when a key is missing. */
    fun fromVsCodeColors(label: String, c: Map<String, String>): Palette {
        fun pick(vararg keys: String): Color? = keys.firstNotNullOfOrNull { parse(c[it]) }
        val bg = pick("editor.background", "sideBar.background", "panel.background") ?: Color(0xFF1E1E1E)
        val dark = bg.luminance() < 0.4f
        fun shade(x: Color, f: Float) = if (dark) lerp(x, Color.White, f) else lerp(x, Color.Black, f)
        val text = pick("editor.foreground", "foreground") ?: if (dark) Color(0xFFD4D4D4) else Color(0xFF333333)
        var accent = pick("activityBarBadge.background", "button.background", "progressBar.background", "tab.activeBorderTop",
            "textLink.foreground", "editorCursor.foreground", "focusBorder") ?: text
        if (kotlin.math.abs(accent.luminance() - bg.luminance()) < 0.12f) accent = pick("textLink.foreground", "terminal.ansiBlue") ?: text
        val panel = pick("sideBar.background", "editorWidget.background", "editorGroupHeader.tabsBackground", "activityBar.background")
            ?.takeIf { it != bg } ?: shade(bg, 0.05f)
        return Palette(
            id = newId(), label = label.ifBlank { "VS Code テーマ" },
            bg = bg, panel = panel,
            accent = accent,
            accentDim = pick("editorLineNumber.foreground", "panel.border", "editorIndentGuide.activeBackground", "input.border")
                ?: lerp(accent, bg, 0.55f),
            approve = pick("terminal.ansiGreen", "gitDecoration.addedResourceForeground", "editorGutter.addedBackground") ?: Color(0xFF2BD67B),
            reject = pick("terminal.ansiRed", "errorForeground", "editorError.foreground") ?: Color(0xFFFF3D3D),
            pending = pick("terminal.ansiYellow", "editorWarning.foreground", "list.warningForeground") ?: Color(0xFFFFC94A),
            text = text,
            textDim = pick("descriptionForeground", "tab.inactiveForeground", "editorLineNumber.foreground") ?: lerp(text, bg, 0.4f),
            eye = pick("terminal.ansiCyan", "textLink.foreground", "terminal.ansiBlue") ?: accent,
            inputBar = pick("input.background", "statusBar.background", "panel.background") ?: shade(bg, 0.03f),
        )
    }

    private fun lerp(a: Color, b: Color, t: Float) = Color(
        red = a.red + (b.red - a.red) * t, green = a.green + (b.green - a.green) * t, blue = a.blue + (b.blue - a.blue) * t,
    )

    /** JSON with comments and trailing commas (as VS Code allows) to plain JSON. */
    fun stripJsonc(s: String): String {
        val sb = StringBuilder(s.length)
        var i = 0; var inStr = false
        while (i < s.length) {
            val ch = s[i]
            if (inStr) {
                sb.append(ch)
                if (ch == '\\' && i + 1 < s.length) { sb.append(s[i + 1]); i += 2; continue }
                if (ch == '"') inStr = false
                i++; continue
            }
            when {
                ch == '"' -> { inStr = true; sb.append(ch); i++ }
                ch == '/' && i + 1 < s.length && s[i + 1] == '/' -> { while (i < s.length && s[i] != '\n') i++ }
                ch == '/' && i + 1 < s.length && s[i + 1] == '*' -> { i += 2; while (i + 1 < s.length && !(s[i] == '*' && s[i + 1] == '/')) i++; i += 2 }
                else -> { sb.append(ch); i++ }
            }
        }
        return sb.toString().replace(Regex(",(\\s*[}\\]])"), "$1").removePrefix("﻿")
    }

    private fun httpText(url: String) = httpBytes(url).toString(Charsets.UTF_8)

    private fun httpBytes(url: String): ByteArray {
        var u = url
        repeat(5) {
            val c = URL(u).openConnection() as HttpURLConnection
            c.connectTimeout = 20_000; c.readTimeout = 60_000; c.instanceFollowRedirects = true
            c.setRequestProperty("User-Agent", UA)
            try {
                val code = c.responseCode
                if (code in 300..399) { u = c.getHeaderField("Location") ?: error("HTTP $code"); return@repeat }
                if (code != 200) error("HTTP $code")
                return c.inputStream.use { it.readBytes() }
            } finally { c.disconnect() }
        }
        error("リダイレクトが多すぎます")
    }
}
