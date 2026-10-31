package jp.pocketmagi.engine

/**
 * A light check of the written result against the sources: dates and figures that appear in no
 * source (nor in the question) are listed, and so are citation numbers that point at no source.
 */
object FactCheck {
    private val FIGURES = listOf(
        Regex("\\d{4}年\\d{1,2}月\\d{1,2}日"),
        Regex("\\d{4}年\\d{1,2}月"),
        Regex("\\d{4}年"),
        Regex("\\d{1,2}月\\d{1,2}日"),
        Regex("[\\d,.]+\\s?[%％]"),
        Regex("[\\d,.]+\\s?(兆|億|万)?(円|ドル|ユーロ|元)"),
        Regex("[\\d,.]+\\s?(人|件|社|倍|ポイント|℃|度|万人|億人|km|kg|台)"),
    )

    private fun norm(s: String) = s.replace(",", "").replace("，", "").replace(" ", "").replace("％", "%")

    fun note(result: String, sources: List<Material>, extra: String): String {
        val text = norm(sources.joinToString("\n") { it.title + "\n" + it.text } + "\n" + extra)
        val found = mutableListOf<String>()
        var rest = result
        for (re in FIGURES) {
            for (m in re.findAll(rest)) {
                val v = m.value.trim()
                if (v.length >= 2 && !text.contains(norm(v))) found += v
            }
            rest = re.replace(rest, " ")      // a full date is not checked again as a bare year
        }
        val badCites = Regex("\\[(\\d{1,2})]").findAll(result).mapNotNull { it.groupValues[1].toIntOrNull() }
            .filter { it < 1 || it > sources.size }.distinct().toList()
        if (found.isEmpty() && badCites.isEmpty()) return ""
        val parts = mutableListOf<String>()
        if (found.isNotEmpty()) parts += "資料で確認できない日付・数値: " + found.distinct().take(8).joinToString("、")
        if (badCites.isNotEmpty()) parts += "存在しない出典番号: " + badCites.joinToString("、") { "[$it]" }
        return "\n\n⚠ " + parts.joinToString(" ／ ")
    }
}
