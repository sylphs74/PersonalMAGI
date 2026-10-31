package jp.pocketmagi.engine

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.Charset
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/** One piece of material found on the web, passed to the sages with its source. */
data class Material(val kind: String, val title: String, val text: String, val url: String)

/**
 * Key-free web lookups used by the proposer when it lacks material. Sources close to the original
 * are preferred: price data (Yahoo Finance chart), news outlets and securities firms (Google News),
 * public bodies and the press over personal blogs (search ranking), weather (Open-Meteo),
 * encyclopedia (Japanese Wikipedia).
 */
object WebResearch {
    private const val UA = "PersonalMAGI/0.1 (personal on-device assistant)"

    fun today(): String {
        val d = LocalDate.now()
        return d.format(DateTimeFormatter.ofPattern("yyyy年M月d日（E）", Locale.JAPAN))
    }

    suspend fun run(kind: String, query: String): List<Material> = withContext(Dispatchers.IO) {
        try {
            when (kind) {
                "天気" -> listOfNotNull(weather(query.ifBlank { "東京" }))
                "事典" -> listOfNotNull(wikipedia(query))
                "株価" -> listOfNotNull(stock(query))
                "ニュース" -> listOfNotNull(news(query))
                "法令" -> listOfNotNull(law(query))
                "GitHub" -> listOfNotNull(github(query))
                else -> web(query)
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private val FINANCE_Q = Regex("株|銘柄|NISA|ニーサ|投資|配当|優待|証券|上場|決算|ETF")
    fun isFinance(question: String) = FINANCE_Q.containsMatchIn(question)

    /** Price data first, then news, encyclopedia and general pages: the order the sages read them in. */
    fun ordered(materials: List<Material>): List<Material> = materials.distinctBy { it.url + it.title }
        .sortedBy { when (it.kind) { "ページ", "株価", "天気", "法令", "GitHub" -> 0; "ニュース" -> 1; "事典" -> 2; else -> 3 } }

    // ---- source quality: primary and established sources up, personal blogs down

    private val PRIMARY = listOf(".go.jp", ".lg.jp", "jpx.co.jp", "boj.or.jp", ".ac.jp", "nhk.or.jp", "e-gov.go.jp", "tdnet")
    private val ESTABLISHED = listOf(
        "nikkei.com", "reuters.com", "bloomberg.co.jp", "bloomberg.com", "jiji.com", "kyodonews", "asahi.com", "yomiuri.co.jp",
        "mainichi.jp", "sankei.com", "toyokeizai.net", "diamond.jp", "diamond.co.jp", "itmedia.co.jp", "impress.co.jp",
        "kabutan.jp", "quick.co.jp", "minkabu.jp", "finance.yahoo.co.jp", "news.yahoo.co.jp", "traders.co.jp", "fisco.co.jp",
        "morningstar.co.jp", "wealthadvisor.co.jp", "sbisec.co.jp", "rakuten-sec.co.jp", "monex.co.jp", "matsui.co.jp",
        "nomura.co.jp", "daiwa.jp", "smbcnikko.co.jp", "mizuho-sc.com", "okasan.co.jp", "kabu.com", "click-sec.com",
        "wikipedia.org", "bbc.com", "cnn.co.jp", "web.nhk", "afpbb.com", "jiia.or.jp", "nri.com", "dlri.co.jp",
    )
    private val PERSONAL = listOf(
        "ameblo.jp", "hatenablog", "hateblo.jp", "hatenadiary", "note.com", "fc2.com", "livedoor.blog", "blog.livedoor",
        "seesaa.net", "blog.goo.ne.jp", "chiebukuro", "5ch.net", "2ch.", "matome", "wordpress.com", "blogspot",
        "jugem.jp", "exblog.jp", "muragon", "/blog/", "://blog.",
    )

    fun sourceScore(url: String): Int {
        val u = url.lowercase()
        return when {
            PERSONAL.any { u.contains(it) } -> -2
            PRIMARY.any { u.substringAfter("://").substringBefore('/').endsWith(it.trimStart('.')) || u.contains(it) } -> 3
            ESTABLISHED.any { u.contains(it) } -> 2
            else -> 0
        }
    }

    // ---- stock prices (Yahoo Finance chart API, no key)

    private val CODE = Regex("(?<![0-9A-Z])([0-9]{3}[0-9A-Z])(?![0-9A-Z])")

    /** Securities codes written next to names in found material, e.g. トヨタ(7203) or <7203>. */
    fun codesIn(materials: List<Material>): List<String> =
        materials.flatMap { m -> Regex("[（(＜<]([0-9]{3}[0-9A-Z])[)）＞>]").findAll(m.title + " " + m.text).map { it.groupValues[1] }.toList() }
            .filterNot { it.toIntOrNull()?.let { n -> n in 1990..2035 } ?: false }
            .distinct()

    /** (code, name) from a code or a company name (the code comes from the Wikipedia infobox). */
    private fun resolveCode(query: String): Pair<String, String>? {
        val q = query.trim()
        CODE.find(q)?.let { m ->
            val code = m.groupValues[1]
            if (code.toIntOrNull()?.let { it in 1990..2035 } != true || q.length <= 4)
                return code to q.replace(code, "").trim(' ', '　', '(', ')', '（', '）').ifBlank { code }
        }
        val api = "https://ja.wikipedia.org/w/api.php?format=json&utf8=1"
        val hits = getJson("$api&action=query&list=search&srlimit=3&srsearch=" + enc(q))
            .optJSONObject("query")?.optJSONArray("search") ?: return null
        for (i in 0 until hits.length()) {
            val title = hits.optJSONObject(i)?.optString("title").orEmpty()
            if (title.isBlank()) continue
            val pages = getJson("$api&action=query&prop=revisions&rvprop=content&rvslots=main&rvsection=0&redirects=1&titles=" + enc(title))
                .optJSONObject("query")?.optJSONObject("pages") ?: continue
            val page = pages.keys().asSequence().map { pages.getJSONObject(it) }.firstOrNull() ?: continue
            val text = page.optJSONArray("revisions")?.optJSONObject(0)?.optJSONObject("slots")?.optJSONObject("main")?.optString("*").orEmpty()
            Regex("上場情報\\s*\\|\\s*東証[^|}]*\\|\\s*([0-9]{3}[0-9A-Z])").find(text)?.let { return it.groupValues[1] to title }
        }
        return null
    }

    private fun yen(v: Double) = if (v >= 100) "%,.0f".format(v) else "%,.1f".format(v)

    /** Six months of daily closes, summarised: latest, change, range, month-end closes, recent headlines. */
    fun stock(query: String): Material? {
        val (code, name) = resolveCode(query) ?: return null
        val r = getJson("https://query1.finance.yahoo.com/v8/finance/chart/$code.T?range=6mo&interval=1d")
            .optJSONObject("chart")?.optJSONArray("result")?.optJSONObject(0) ?: return null
        val meta = r.optJSONObject("meta") ?: return null
        val ts = r.optJSONArray("timestamp") ?: return null
        val closes = r.optJSONObject("indicators")?.optJSONArray("quote")?.optJSONObject(0)?.optJSONArray("close") ?: return null
        val off = meta.optLong("gmtoffset", 32400)
        val pts = (0 until minOf(ts.length(), closes.length())).mapNotNull { i ->
            val c = closes.optDouble(i); if (c.isNaN()) null
            else java.time.Instant.ofEpochSecond(ts.getLong(i) + off).atZone(java.time.ZoneOffset.UTC).toLocalDate() to c
        }
        if (pts.size < 2) return null
        val fmt = DateTimeFormatter.ofPattern("M/d", Locale.JAPAN)
        val (d0, c0) = pts.first(); val (d1, c1) = pts.last()
        val hi = pts.maxBy { it.second }; val lo = pts.minBy { it.second }
        val months = pts.groupBy { it.first.withDayOfMonth(1) }.toSortedMap().map { (m, v) -> "${m.monthValue}月 ${yen(v.last().second)}" }
        val label = name.ifBlank { meta.optString("longName") }
        val sb = StringBuilder()
        sb.append("$label（証券コード$code、${meta.optString("longName")}）株価データ（Yahoo!ファイナンス、日足6か月）\n")
        sb.append("直近終値 ${yen(c1)}円（${d1.format(fmt)}）。6か月前 ${yen(c0)}円（${d0.format(fmt)}）から")
            .append("%+.1f%%".format((c1 / c0 - 1) * 100)).append("。\n")
        sb.append("期間高値 ${yen(hi.second)}円（${hi.first.format(fmt)}）、安値 ${yen(lo.second)}円（${lo.first.format(fmt)}）")
        val h52 = meta.optDouble("fiftyTwoWeekHigh"); val l52 = meta.optDouble("fiftyTwoWeekLow")
        if (!h52.isNaN() && !l52.isNaN()) sb.append("、52週高値 ${yen(h52)}円・安値 ${yen(l52)}円")
        sb.append("。\n月末終値: ").append(months.joinToString(" / "))
        try {
            val heads = newsItems("$label 株", 2)
            if (heads.isNotEmpty()) sb.append("\n最近の報道: ").append(heads.joinToString(" / "))
        } catch (_: Exception) {}
        return Material("株価", "$label（$code）株価6か月", sb.toString(), "https://finance.yahoo.co.jp/quote/$code.T")
    }

    // ---- laws (e-Gov 法令API v2, no key): the text of Japanese laws as published by the government

    private fun law(query: String): Material? {
        val q = query.replace(Regex("について|とは|[？?。]"), " ").trim()
        val o = getJson("https://laws.e-gov.go.jp/api/2/keyword?limit=5&keyword=" + enc(q))
        val items = o.optJSONArray("items")
        val lines = mutableListOf<String>()
        if (items != null) for (i in 0 until items.length()) {
            val it = items.optJSONObject(i) ?: continue
            val title = it.optJSONObject("revision_info")?.optString("law_title").orEmpty()
            val num = it.optJSONObject("law_info")?.optString("law_num").orEmpty()
            val enf = it.optJSONObject("revision_info")?.optString("amendment_enforcement_date").orEmpty()
            val sents = it.optJSONArray("sentences")?.let { a -> (0 until a.length()).mapNotNull { k -> a.optJSONObject(k)?.optString("text")?.let { t -> strip(t) } } }.orEmpty()
            lines += "$title（$num${if (enf.isNotBlank()) "、$enf 施行" else ""}）: " + sents.take(2).joinToString(" ／ ")
        }
        if (lines.isEmpty()) {
            val laws = getJson("https://laws.e-gov.go.jp/api/2/laws?limit=5&law_title=" + enc(q)).optJSONArray("laws") ?: return null
            for (i in 0 until laws.length()) {
                val r = laws.optJSONObject(i)?.optJSONObject("revision_info") ?: continue
                lines += r.optString("law_title") + "（" + r.optString("amendment_enforcement_date") + " 時点の改正が施行中）"
            }
        }
        if (lines.isEmpty()) return null
        return Material("法令", "e-Gov法令検索「$q」", lines.joinToString("\n").take(1200), "https://laws.e-gov.go.jp/")
    }

    // ---- software releases (GitHub API, no key): what the project itself published

    private fun github(query: String): Material? {
        val gh = mapOf("Accept" to "application/vnd.github+json")
        val repo = getJson("https://api.github.com/search/repositories?per_page=1&q=" + enc(query), gh)
            .optJSONArray("items")?.optJSONObject(0) ?: return null
        val full = repo.optString("full_name")
        val sb = StringBuilder("$full（${repo.optString("description").take(100)}、★${repo.optInt("stargazers_count")}）\n")
        val rel = try { org.json.JSONArray(getText("https://api.github.com/repos/$full/releases?per_page=3", gh)) } catch (e: Exception) { org.json.JSONArray() }
        if (rel.length() > 0) {
            for (i in 0 until rel.length()) {
                val r = rel.optJSONObject(i) ?: continue
                sb.append("リリース ").append(r.optString("tag_name")).append("（").append(r.optString("published_at").take(10)).append("）")
                    .append(strip(r.optString("body")).take(160)).append("\n")
            }
        } else {
            val tags = try { org.json.JSONArray(getText("https://api.github.com/repos/$full/tags?per_page=5", gh)) } catch (e: Exception) { org.json.JSONArray() }
            if (tags.length() > 0) sb.append("最近のタグ: ").append((0 until tags.length()).mapNotNull { tags.optJSONObject(it)?.optString("name") }.joinToString("、"))
        }
        sb.append("\n最終更新 ").append(repo.optString("pushed_at").take(10))
        return Material("GitHub", "GitHub $full のリリース", sb.toString().take(1000), "https://github.com/$full/releases")
    }

    // ---- news (Google News RSS, no key): outlets and securities firms, newest first

    private fun newsItems(query: String, max: Int): List<String> {
        val xml = getText("https://news.google.com/rss/search?hl=ja&gl=JP&ceid=JP:ja&q=" + enc(query))
        val now = LocalDate.now()
        data class Item(val title: String, val source: String, val srcUrl: String, val date: LocalDate?)
        val items = Regex("<item>([\\s\\S]*?)</item>").findAll(xml).mapNotNull { m ->
            val body = m.groupValues[1]
            val title = Regex("<title>([\\s\\S]*?)</title>").find(body)?.groupValues?.get(1)?.let { t -> strip(t.replace("<![CDATA[", "").replace("]]>", "")) } ?: return@mapNotNull null
            val src = Regex("<source[^>]*>([\\s\\S]*?)</source>").find(body)?.groupValues?.get(1)?.let { s -> strip(s) }.orEmpty()
            val srcUrl = Regex("<source url=\"([^\"]+)\"").find(body)?.groupValues?.get(1).orEmpty()
            val date = Regex("<pubDate>([^<]+)</pubDate>").find(body)?.groupValues?.get(1)?.let { d ->
                try { java.time.ZonedDateTime.parse(d.trim(), DateTimeFormatter.RFC_1123_DATE_TIME).toLocalDate() } catch (e: Exception) { null }
            }
            Item(title.removeSuffix(" - $src").trim(), src, srcUrl, date)
        }.toList()
        val fresh = items.filter { it.date == null || it.date.isAfter(now.minusDays(183)) }.ifEmpty { items }
        val fmt = DateTimeFormatter.ofPattern("M/d", Locale.JAPAN)
        return fresh.filter { sourceScore(it.srcUrl) >= 0 }
            .sortedWith(compareByDescending<Item> { sourceScore(it.srcUrl) }.thenByDescending { it.date })
            .take(max)
            .map { i -> (i.date?.format(fmt)?.plus(" ") ?: "") + (if (i.source.isNotBlank()) "${i.source}: " else "") + i.title }
    }

    private fun news(query: String): Material? {
        val lines = newsItems(query, 5)
        if (lines.isEmpty()) return null
        return Material("ニュース", "ニュース検索「$query」", lines.joinToString("\n"),
            "https://news.google.com/search?hl=ja&gl=JP&ceid=JP:ja&q=" + enc(query))
    }

    // ---- weather

    private val WMO = mapOf(
        0 to "快晴", 1 to "晴れ", 2 to "晴れ時々曇り", 3 to "曇り", 45 to "霧", 48 to "霧",
        51 to "霧雨", 53 to "霧雨", 55 to "霧雨", 56 to "着氷性の霧雨", 57 to "着氷性の霧雨",
        61 to "小雨", 63 to "雨", 65 to "大雨", 66 to "着氷性の雨", 67 to "着氷性の雨",
        71 to "小雪", 73 to "雪", 75 to "大雪", 77 to "霧雪", 80 to "にわか雨", 81 to "にわか雨", 82 to "激しいにわか雨",
        85 to "にわか雪", 86 to "激しいにわか雪", 95 to "雷雨", 96 to "雹を伴う雷雨", 99 to "雹を伴う雷雨",
    )

    private fun weather(place: String): Material? {
        val name = place.replace(Regex("(の)?(天気|予報|明日|今日|明後日|週末|今週)"), "").trim().ifBlank { "東京" }
        val geo = getJson("https://geocoding-api.open-meteo.com/v1/search?count=1&language=ja&format=json&name=" + enc(name))
        val r = geo.optJSONArray("results")?.optJSONObject(0) ?: return null
        val lat = r.getDouble("latitude"); val lon = r.getDouble("longitude")
        val label = listOf(r.optString("admin1"), r.optString("name")).filter { it.isNotBlank() }.distinct().joinToString(" ")
        val f = getJson(
            "https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon&timezone=auto&forecast_days=7" +
                "&daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max"
        )
        val d = f.getJSONObject("daily")
        val days = d.getJSONArray("time")
        val sb = StringBuilder("$label の天気予報（今日は${today()}）\n")
        for (i in 0 until days.length()) {
            val date = LocalDate.parse(days.getString(i))
            val code = d.getJSONArray("weather_code").optInt(i, -1)
            sb.append(date.format(DateTimeFormatter.ofPattern("M/d（E）", Locale.JAPAN))).append(" ")
                .append(WMO[code] ?: "不明").append(" 最高")
                .append(d.getJSONArray("temperature_2m_max").optDouble(i).toInt()).append("℃ 最低")
                .append(d.getJSONArray("temperature_2m_min").optDouble(i).toInt()).append("℃ 降水確率")
                .append(d.getJSONArray("precipitation_probability_max").optInt(i, -1).let { if (it < 0) "—" else "$it%" })
                .append("\n")
        }
        return Material("天気", "$label の天気予報", sb.toString().trim(), "https://open-meteo.com/")
    }

    // ---- encyclopedia

    private fun wikipedia(term: String): Material? {
        val api = "https://ja.wikipedia.org/w/api.php?format=json&utf8=1"
        val hit = getJson("$api&action=query&list=search&srlimit=1&srsearch=" + enc(term))
            .optJSONObject("query")?.optJSONArray("search")?.optJSONObject(0) ?: return null
        val title = hit.getString("title")
        val pages = getJson("$api&action=query&prop=extracts&exintro=1&explaintext=1&redirects=1&titles=" + enc(title))
            .optJSONObject("query")?.optJSONObject("pages") ?: return null
        val page = pages.keys().asSequence().map { pages.getJSONObject(it) }.firstOrNull() ?: return null
        val text = page.optString("extract").trim().take(1200)
        if (text.isBlank()) return null
        return Material("事典", "Wikipedia「$title」", text, "https://ja.wikipedia.org/wiki/" + enc(title).replace("+", "%20"))
    }

    // ---- general web search (no key: DuckDuckGo lite HTML)

    private fun web(query: String): List<Material> {
        val conn = URL("https://lite.duckduckgo.com/lite/").openConnection() as HttpURLConnection
        conn.requestMethod = "POST"; conn.doOutput = true
        conn.connectTimeout = 15_000; conn.readTimeout = 20_000
        conn.setRequestProperty("User-Agent", UA)
        conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
        conn.outputStream.use { it.write(("q=" + enc(query) + "&kl=jp-jp").toByteArray()) }
        val html = try { conn.inputStream.bufferedReader().use { it.readText() } } finally { conn.disconnect() }

        val links = Regex("""<a[^>]+href="([^"]+)"[^>]*class=['"]result-link['"][^>]*>([\s\S]*?)</a>""").findAll(html).toList()
        val snippets = Regex("""<td[^>]*class=['"]result-snippet['"][^>]*>([\s\S]*?)</td>""").findAll(html).map { strip(it.groupValues[1]) }.toList()
        data class Hit(val url: String, val title: String, val snippet: String, val rank: Int)
        val hits = links.take(10).mapIndexed { i, m ->
            var url = m.groupValues[1].replace("&amp;", "&")
            Regex("[?&]uddg=([^&]+)").find(url)?.let { url = URLDecoder.decode(it.groupValues[1], "UTF-8") }
            if (url.startsWith("//")) url = "https:$url"
            Hit(url, strip(m.groupValues[2]), snippets.getOrNull(i).orEmpty(), i)
        }.filter { it.title.isNotBlank() }
        // public bodies, the press and securities firms before personal blogs; search order otherwise
        val ranked = hits.sortedWith(compareByDescending<Hit> { sourceScore(it.url) }.thenBy { it.rank })
            .let { r -> r.filter { sourceScore(it.url) >= 0 }.ifEmpty { r } }
        val out = mutableListOf<Material>()
        ranked.take(3).forEachIndexed { i, h ->
            var text = h.snippet
            // the first two pages are read for a little more substance than the snippet
            if (i < 2) pageText(h.url)?.let { if (it.length > text.length) text = it }
            if (text.isNotBlank()) out += Material("ウェブ", h.title, text.take(500), h.url)
        }
        return out
    }

    private val URL_RE = Regex("https?://[^\\s　「」『』（）()<>\"]+")

    fun urlsIn(text: String): List<String> = URL_RE.findAll(text).map { it.value.trimEnd('.', '、', '。', ',') }.distinct().take(3).toList()

    /** Pages whose URL is in the question (typed or shared): their text becomes material. */
    suspend fun pages(question: String): List<Material> = withContext(Dispatchers.IO) {
        urlsIn(question).mapNotNull { u -> try { page(u) } catch (e: Exception) { null } }
    }

    private fun page(url: String): Material? {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000; conn.readTimeout = 20_000; conn.instanceFollowRedirects = true
        conn.setRequestProperty("User-Agent", UA)
        try {
            if (conn.responseCode != 200) return null
            val bytes = conn.inputStream.use { s ->
                val out = java.io.ByteArrayOutputStream(); val buf = ByteArray(32 * 1024)
                while (out.size() < 1_500_000) { val r = s.read(buf); if (r < 0) break; out.write(buf, 0, r) }
                out.toByteArray()
            }
            val head = String(bytes, 0, minOf(bytes.size, 4000), Charsets.ISO_8859_1)
            val cs = (Regex("charset=([\\w-]+)", RegexOption.IGNORE_CASE).find(conn.contentType ?: "")
                ?: Regex("""<meta[^>]+charset=["']?([\w-]+)""", RegexOption.IGNORE_CASE).find(head))?.groupValues?.get(1) ?: "UTF-8"
            val html = String(bytes, try { Charset.forName(cs) } catch (e: Exception) { Charsets.UTF_8 })
            val title = Regex("(?is)<title[^>]*>(.*?)</title>").find(html)?.groupValues?.get(1)?.let { strip(it) }?.take(80) ?: url
            val main = Regex("(?is)<(article|main)[^>]*>(.*?)</\\1>").find(html)?.groupValues?.get(2) ?: html
            val body = main.replace(Regex("(?is)<(script|style|noscript|header|footer|nav|aside)[^>]*>.*?</\\1>"), " ")
            val text = strip(body).take(2500)
            return if (text.isBlank()) null else Material("ページ", title, text, url)
        } finally { conn.disconnect() }
    }

    private fun pageText(url: String): String? = try {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 10_000; conn.readTimeout = 15_000
        conn.setRequestProperty("User-Agent", UA)
        try {
            if (conn.responseCode != 200) null
            else {
                val bytes = conn.inputStream.use { s ->
                    val out = java.io.ByteArrayOutputStream(); val buf = ByteArray(32 * 1024)
                    while (out.size() < 600_000) { val r = s.read(buf); if (r < 0) break; out.write(buf, 0, r) }
                    out.toByteArray()
                }
                val head = String(bytes, 0, minOf(bytes.size, 4000), Charsets.ISO_8859_1)
                val cs = (Regex("charset=([\\w-]+)", RegexOption.IGNORE_CASE).find(conn.contentType ?: "")
                    ?: Regex("""<meta[^>]+charset=["']?([\w-]+)""", RegexOption.IGNORE_CASE).find(head))
                    ?.groupValues?.get(1) ?: "UTF-8"
                val html = String(bytes, try { Charset.forName(cs) } catch (e: Exception) { Charsets.UTF_8 })
                val body = html.replace(Regex("(?is)<(script|style|noscript|header|footer|nav)[^>]*>.*?</\\1>"), " ")
                strip(body).take(700).ifBlank { null }
            }
        } finally { conn.disconnect() }
    } catch (e: Exception) { null }

    // ---- helpers

    private fun strip(html: String) = html.replace(Regex("<[^>]+>"), " ")
        .replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
        .replace("&quot;", "\"").replace("&#39;", "'").replace("&#x27;", "'")
        .replace(Regex("\\s+"), " ").trim()

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private fun getText(url: String, headers: Map<String, String> = emptyMap()): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000; conn.readTimeout = 20_000
        conn.setRequestProperty("User-Agent", UA)
        headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
        try {
            if (conn.responseCode != 200) throw java.io.IOException("HTTP ${conn.responseCode}")
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally { conn.disconnect() }
    }

    private fun getJson(url: String, headers: Map<String, String> = emptyMap()): JSONObject {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000; conn.readTimeout = 20_000
        conn.setRequestProperty("User-Agent", UA)
        headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
        try {
            if (conn.responseCode != 200) throw java.io.IOException("HTTP ${conn.responseCode}")
            return JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
        } finally { conn.disconnect() }
    }

    /** Text block for the prompts, within [budget] characters. */
    /**
     * Material for the prompts, within [budget] characters. With [numbering] (the sources list shown
     * under the result) each block carries its number there, so the AIs can cite it as [n].
     */
    fun asText(materials: List<Material>, budget: Int = 2600, numbering: List<Material>? = null): String {
        val sb = StringBuilder()
        val items = if (numbering == null) ordered(materials)
            else materials.sortedBy { m -> numbering.indexOfFirst { it.url == m.url && it.title == m.title }.let { if (it < 0) Int.MAX_VALUE else it } }
        for (m in items) {
            val n = numbering?.indexOfFirst { it.url == m.url && it.title == m.title }?.takeIf { it >= 0 }?.plus(1)
            val block = (if (n != null) "[$n] ${m.title}" else "〔${m.title}〕") + "\n${m.text}\n"
            if (sb.length + block.length > budget) { sb.append(block.take((budget - sb.length).coerceAtLeast(0))); break }
            sb.append(block)
        }
        return sb.toString().trim()
    }
}
