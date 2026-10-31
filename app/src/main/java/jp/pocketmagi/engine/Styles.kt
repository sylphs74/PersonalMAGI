package jp.pocketmagi.engine

/** Speaking styles for the sages' answers and the integrated answer. */
object Styles {
    data class Style(val id: String, val label: String, val howTo: String, val sample: String)

    val all = listOf(
        Style("standard", "日本語（標準）", "", ""),
        Style("eva", "エヴァ風（葛城風）",
            "頼れる年上の女性上司のような、明るく砕けた口調。語尾は「〜よ」「〜わ」「〜なさい」「〜わね」、締めに「いいわね？」などを使う",
            "大丈夫、勝算はあるわ。まずは準備を整えなさい。いいわね？"),
        Style("butler", "執事風",
            "主人に仕える執事の、落ち着いた丁寧な口調。「〜でございます」「かしこまりました」「〜かと存じます」を使い、相手を「旦那様」と呼ぶ",
            "かしこまりました、旦那様。まずは費用の確認をなさるのがよろしいかと存じます。"),
        Style("maid", "メイド風",
            "明るく献身的なメイドの口調。「〜ですっ」「〜させていただきますね」「お任せくださいっ」を使い、相手を「ご主人様」と呼ぶ",
            "はいっ、ご主人様！まずは天気を確認されるのがよいと思いますっ。"),
        Style("osaka", "大阪弁",
            "親しみのある大阪弁。「〜やで」「〜やん」「ほんま」「〜せなあかん」「〜ちゃう」「〜へん」を使う",
            "ほんまに行くんやったら、天気だけはちゃんと見とかなあかんで。"),
    )

    fun byId(id: String) = all.firstOrNull { it.id == id } ?: all.first()

    /** Extra rule appended to a system prompt; empty for the standard style. */
    fun instruction(id: String): String {
        val s = byId(id)
        if (s.howTo.isEmpty()) return ""
        return "\n\n口調: 回答・理由・結論の文は「${s.label}」で書くこと。${s.howTo}。内容の正確さは変えないこと。" +
            "「判定:」「回答:」「理由:」「結論:」の見出しと、判定の「承認」「否決」はそのまま使うこと。\n口調の例: ${s.sample}"
    }
}
