package jp.pocketmagi.engine

import jp.pocketmagi.model.SageRole

object Prompts {

    private val personas = mapOf(
        SageRole.MELCHIOR to "あなたはMAGIシステムの MELCHIOR。「科学者」としての人格を持ち、論理・根拠・事実の正確さを最優先に判断する。",
        SageRole.BALTHASAR to "あなたはMAGIシステムの BALTHASAR。「母」としての人格を持ち、人や社会への影響・安全・守るべきものを最優先に判断する。",
        SageRole.CASPER to "あなたはMAGIシステムの CASPER。「女」としての人格を持ち、気持ち・直感・暮らしの実感を最優先に判断する。",
    )

    fun judgeSystem(role: SageRole, style: String = "standard", hypothesis: Boolean = false): String =
        personas.getValue(role) + (if (hypothesis) hypothesisRules else judgeRules) + Styles.instruction(style)

    fun integrateSystem(style: String = "standard", hypothesis: Boolean = false): String =
        (if (hypothesis) INTEGRATE_HYPOTHESIS else INTEGRATE_SYSTEM) + Styles.instruction(style)

    private const val COMMON_RULES = """- 質問の分野（歴史・技術・生活・作品など）に合わせて答える。技術の質問でなければ技術的な話にしない
- 他の誰でも言える一般論（「目的を明確に」「適切に管理する」など）だけで終わらせない
- 【参考資料】【調査資料】があれば、その内容を最優先の根拠にする。資料と自分の知識が食い違うときは資料に従う
- 【調査資料】は、株価データ・公的機関・企業・報道機関・証券会社などの一次情報に近いものを優先し、個人の意見は参考程度にする
- 日付・数値・出来事・人物の発言などの事実は、【調査資料】【参考資料】に書かれたものだけを書く。資料にない事実を自分の知識で付け足さない（知識は古く、間違っている可能性が高い）
- 【調査資料】を根拠にした文の末尾には、その資料の番号を [1] のように付ける
- 質問が具体的な企業・製品・技術・人物などを求めているなら、固有名詞を挙げて具体的に答える
- 自分の知識は学習時点のもので古い可能性がある。天気・ニュース・価格など今の情報は、資料にない限り分からないものとして扱い、作らない
- 見出し・太字・箇条書き記号（#、*、-、|）は使わない。普通の文章で書く"""

    /** Rules when the neutral proposer has put forward a hypothesis to judge. */
    private val hypothesisRules = """
他の審議者とは独立して、【仮説】が審議事項への答えとして妥当かを評価し、必ず次の3項目の形式だけで日本語で答えること。

判定: 承認（仮説は妥当）または 否決（仮説は妥当でない）
回答: 承認なら仮説を補う具体的な一言、否決なら自分の対案を具体的に（3文以内）
理由: その判定に至った自分の観点（2文以内）

守ること:
- 判断材料がなく妥当とも妥当でないとも言えないときは否決とし、回答に「判断材料不足」と書く
""" + COMMON_RULES + """

例:
判定: 否決
回答: 対案は「明日の東京は雨」です。
理由: 調査資料の予報では、明日の降水確率が80%だからです。"""

    private val judgeRules = """
他の審議者とは独立して判断し、必ず次の3項目の形式だけで日本語で答えること。

判定: 承認 または 否決
回答: 質問への自分の答え。質問の条件に即して、具体的な提案・判断を5文以内で
理由: その判定に至った自分の専門の観点（2文以内）

守ること:
- 質問が「はい/いいえ」で答えられない場合は、自分の回答を自信を持って勧められるなら「承認」、勧められない・判断材料が足りないなら「否決」とする
- 質問の分野（歴史・技術・生活・作品など）に合わせて答える。技術の質問でなければ技術的な話にしない
- 他の誰でも言える一般論（「目的を明確に」「適切に管理する」など）だけで終わらせない
- 前提が足りないときは、置いた仮定を一言書いてから答える
- 【参考資料】【調査資料】があれば、その内容を最優先の根拠にする。資料と自分の知識が食い違うときは資料に従う
- 日付・数値・出来事・人物の発言などの事実は、資料に書かれたものだけを書く。資料にない事実を自分の知識で付け足さない
- 【調査資料】を根拠にした文の末尾には、その資料の番号を [1] のように付ける
- 質問が具体的な企業・製品・技術・人物などを求めているなら、固有名詞を挙げて具体的に答える
- 自分の知識は学習時点のもので古い可能性がある（サポート終了・上場廃止・バージョン更新など）。数値や最新の状況は確かな場合だけ書き、そうでなければ「最新情報の確認が必要」と添える
- 見出し・太字・箇条書き記号（#、*、-、|）は使わない。普通の文章で書く

例:
判定: 否決
回答: 今すぐ決めるより、費用と日程を確認してから判断するのがよいです。（仮定: 予算の上限が決まっていない）
理由: 判断に必要な条件がまだ分からず、急ぐ利点も見当たらないためです。"""

    /** Second round: the sage's own first answer and the other sages' answers, to reconsider once. */
    fun rebuttalUser(question: String, prior: String?, hypothesis: String?, research: String?, own: String,
                     others: List<Pair<SageRole, String>>): String = buildString {
        append(judgeUser(question, prior, null, hypothesis, research))
        append("\n\n【あなたの最初の判断】\n").append(own.trim())
        append("\n\n【他の審議者の判断】\n")
        others.forEach { (r, t) -> append("・").append(r.code).append(": ").append(answerOf(t).ifBlank { t }.trim().take(400)).append("\n") }
        append("\n他の審議者の意見を踏まえて考え直し、同じ3項目の形式で最終判断を書く。")
        append("意見を変えるなら、理由に何が決め手になったかを書く。根拠のない同調はしない。資料と食い違う意見には従わない。")
    }

    /** The app writes the first words of every sage's reply so the format cannot drift. */
    const val JUDGE_PREFILL = "判定: "

    /** Start of the integrated answer; the per-sage lines are added by the app itself. */
    const val INTEGRATE_PREFILL = "結論: "

    fun judgeUser(question: String, visual: String?, references: String? = null,
                  hypothesis: String? = null, research: String? = null): String = buildString {
        append("【今日】").append(WebResearch.today()).append("（学習時点ではなく、この日付を「今」として判断する）\n\n")
        if (!research.isNullOrBlank()) append("【調査資料】（Webで調べた内容）\n").append(research.trim()).append("\n\n")
        if (!references.isNullOrBlank()) {
            append("【参考資料】（利用者が添付した資料の抜粋）\n")
            append(references.trim())
            append("\n\n")
        }
        if (!visual.isNullOrBlank()) {
            append("【前回の審議】（今回の審議事項はこの続き。前回の結論を踏まえて考える）\n")
            append(visual.trim())
            append("\n\n")
        }
        append("【審議事項】\n")
        append(question.trim())
        if (!hypothesis.isNullOrBlank()) append("\n\n【仮説】（中立の提案ユニットが立てたもの）\n").append(hypothesis.trim())
    }

    /** Integration when a hypothesis was judged: say whether it stands and, if not, what replaces it. */
    const val INTEGRATE_HYPOTHESIS = """あなたはMAGIシステムの統合ユニットです。仮説に対する審議者の評価と多数決の結果だけをもとに、利用者への結論を日本語で書く。

厳守:
- 結論だけを2〜3文の普通の文章で書く。見出し・箇条書き・審議者の名前は書かない
- 仮説が可決なら仮説を答えとして示し、否決なら審議者が出した対案のうち多い方を答えとして示す
- 審議者が挙げていない事実・固有名詞・数値を新しく作り足さない（【調査資料】【参考資料】に書かれている事実は使ってよい）
- 審議者の意見と【調査資料】が食い違う事実（日付・数値・出来事）は、【調査資料】に従う
- 審議者や資料の番号 [n] が付いた事実を使うときは、その番号も残す
- 審議者が「判断材料不足」とした場合は、その旨を正直に書く
- 意見が割れているときは、割れていることを一言添える"""

    // ---- neutral proposer

    const val PROPOSE_SYSTEM = """あなたはMAGIシステムの提案ユニット。3人の審議者とは別の中立の立場で、審議事項に対する「仮説」を一つだけ立てる。
仮説の良し悪しの細かい評価はしない（それは審議者の役目）。
仮説は、審議者が「承認」か「否決」で評価できる、一文の具体的な主張にする。
審議事項が「〜すべきか」「〜は本当か」のように可否を問う形なら、それをそのまま肯定の主張にする。
仮説は「〜を調べる」「〜が重要」「〜を見る必要がある」のような行動や一般論にしない。審議事項への答えそのもの（「〜である」「〜になる見込みが高い」）にする。
仮説は必ず一つの立場を取る。「判断できない」「資料だけでは分からない」「不明」「〜次第」は仮説ではない。
資料が足りなくても、一般的な知識と論理から最もありそうな答えを選んで言い切る（どれだけ確かかは審議者が評価する）。
「〜と仮定して」のような仮定の審議事項は、その仮定が成り立つ前提で答える。
日付・数値・出来事は、資料に書かれたものだけを使う。
見出し・太字・箇条書き記号は使わない。"""

    fun proposeUser(question: String, visual: String?, references: String?, webEnabled: Boolean): String = buildString {
        append("【今日】").append(WebResearch.today()).append("（あなたの学習時点ではなく、この日付を「今」として考える）\n\n")
        if (!references.isNullOrBlank()) append("【参考資料】\n").append(references.trim()).append("\n\n")
        if (!visual.isNullOrBlank()) append("【前回の審議】（今回の審議事項はこの続き。前回の結論を踏まえて考える）\n").append(visual.trim()).append("\n\n")
        append("【審議事項】\n").append(question.trim()).append("\n\n")
        if (webEnabled) {
            append("あなたの知識は過去のある時点で止まっている。次のどちらか一方だけを書くこと。\n")
            append("(1) 自分の知識と資料だけで仮説を立てられるなら、1行で:\n仮説: （一文の主張）\n")
            append("(2) 自分の知識にない情報（今の価格・相場・順位・最近の出来事・新しい製品や制度・知らない言葉など）が必要なら、")
            append("審議事項の内容そのものを調べる行を3行まで:\n")
            append("調査: ウェブ （審議事項を調べる検索語）\n調査: ニュース （最近の動きを調べる検索語）\n")
            append("調査: 株価 （銘柄名または証券コード。株・銘柄の質問のとき）\n調査: 事典 （意味を知らない言葉）\n")
            append("調査: 法令 （法律の言葉。法律の質問のとき）\n調査: GitHub （ソフトウェア名。版やリリースの質問のとき）\n")
            append("天気を調べてよいのは、審議事項が天気・気温・雨を尋ねているときだけ（そのときは「調査: 天気 地名」）。")
        } else {
            append("調べることはできないので、手元の知識と資料だけで仮説を1行で書くこと。分からないときは「判断材料なし」と書く。\n仮説: （一文の主張）")
        }
    }

    fun proposeWithResearch(question: String, research: String, finance: Boolean = false, prior: String? = null): String = buildString {
        append("【今日】").append(WebResearch.today()).append("（あなたの学習時点ではなく、この日付を「今」として考える。今日より前の出来事を「〜される見込み」と書かない）\n\n")
        append("【調査資料】\n").append(research.trim()).append("\n\n")
        if (!prior.isNullOrBlank()) append("【前回の審議】（今回の審議事項はこの続き。前回の結論を踏まえて考える）\n").append(prior.trim()).append("\n\n")
        append("【審議事項】\n").append(question.trim()).append("\n\n")
        append("調査資料のうち審議事項に関係する部分だけを使い、審議事項への答えとなる仮説を一文で書くこと。関係のない資料は無視する。")
        append("資料に答えが直接書かれていなくても、資料と一般的な知識から最もありそうな答えを選んで言い切ること。「判断できない」「資料だけでは」とは書かない。")
        if (finance) append("\n仮説で銘柄を挙げたときは、その次の行に「銘柄: 名前、名前、名前」と書く。")
    }

    const val PROPOSE_PREFILL = "仮説: "

    data class ProposerPlan(val hypothesis: String?, val lookups: List<Pair<String, String>>)

    private val WEATHER_Q = Regex("天気|気温|降水|雨|雪|晴れ|曇り|台風|予報|傘|暑い|寒い|風が|最高気温|最低気温")
    private val CURRENT_Q = Regex("最新|今日|明日|今週|来週|今月|今年|現在|いま|今の|最近|ニュース|株価|銘柄|為替|相場|価格|値段|料金|ランキング|おすすめ|オススメ|発売|新型|新製品|20[2-9]\\d年")
    private val PLACEHOLDER = setOf("", "地名", "調べる言葉", "検索語", "審議事項を調べる検索語", "意味を知らない言葉",
        "事実を調べる検索語", "銘柄名。株の質問のとき", "銘柄名", "なし",
        "最近の動きを調べる検索語", "銘柄名または証券コード", "銘柄名または証券コード。株・銘柄の質問のとき")

    /**
     * Lookups actually run: the proposer's own requests, minus weather unless the question asks about
     * weather and minus template placeholders; a question about current things is always looked up.
     */
    fun refineLookups(question: String, plan: ProposerPlan, priorQuestion: String? = null): List<Pair<String, String>> {
        val q = WebResearch.urlsIn(question).fold(question) { acc, u -> acc.replace(u, " ") }.trim().replace(Regex("\\s+"), " ").ifBlank { question.trim() }
        fun strip(t: String) = t.replace(Regex("(を|について)?(理由とともに|理由付きで)?\\d*つ?(提案|教え|挙げ|選ん|まとめ|紹介)(して|て|で)?(ください|下さい|ほしい|欲しい)?[。？?！!]*$"), "")
        // a follow-up ("日本への影響は？") is searched together with the topic of the previous question
        val fallback = (if (priorQuestion.isNullOrBlank()) strip(q) else strip(priorQuestion.trim()).take(30) + " " + strip(q)).take(60)
        val asked = WEATHER_Q.containsMatchIn(q)
        val out = plan.lookups.mapNotNull { (kind, query) ->
            when {
                kind == "天気" && !asked -> null
                kind == "天気" -> kind to query
                kind == "株価" && query in PLACEHOLDER -> null
                query in PLACEHOLDER -> kind to fallback
                else -> kind to query
            }
        }.distinct().toMutableList()
        if (out.isEmpty() && plan.lookups.isNotEmpty()) out += "ウェブ" to fallback
        if (out.none { it.first == "ウェブ" || it.first == "天気" || it.first == "ニュース" } && CURRENT_Q.containsMatchIn(q))
            out += if (asked) "天気" to q else "ウェブ" to fallback
        // the proposer is a small model and cannot tell what it does not know: unless the request is
        // creative or mechanical (translate, write, calculate...), basic facts and recent reports are
        // always looked up, so the sages argue from sources instead of from memory
        // the field decides where the primary source is: laws → e-Gov, software versions → the project's releases
        if (LAW_Q.containsMatchIn(q) && out.none { it.first == "法令" }) out += "法令" to fallback
        if (TECH_Q.containsMatchIn(q) && out.none { it.first == "GitHub" })
            Regex("[A-Za-z][A-Za-z0-9._+-]{1,}").findAll(q).map { it.value }.firstOrNull { it.lowercase() !in setOf("github", "oss", "api", "eol", "the") }
                ?.let { out += "GitHub" to it }
        if (!NO_WEB_Q.containsMatchIn(q)) {
            if (out.none { it.first == "ニュース" }) out += "ニュース" to fallback
            if (!WebResearch.isFinance(q) && out.none { it.first == "事典" }) out += "事典" to fallback
            if (out.none { it.first == "ウェブ" }) out += "ウェブ" to fallback
        }
        return out.take(5)
    }

    private val LAW_Q = Regex("法律|法令|条文|違法|合法|罰則|規制|法改正|施行|条例|政令|省令|民法|刑法|労働基準|著作権|個人情報保護|道路交通法|税法")
    private val TECH_Q = Regex("バージョン|リリース|アップデート|最新版|GitHub|ライブラリ|OSS|オープンソース|EOL|サポート期限|対応版|\\bv\\d")

    private val NO_WEB_Q = Regex("翻訳|訳して|英訳|和訳|書いて|作って|作成して|考えて|計算|要約|言い換え|添削|校正|詩を|物語|小説|俳句|短歌|川柳|なぞなぞ|しりとり|ダジャレ|名前を(考|付)|プログラム|コード")

    private val PREDICTIVE = Regex("見込み|予定|予想|予測|される|になる|始まる|発売|発表|開催|登場|公開|実施|リリース|だろう|と見られ")

    /**
     * A claim about the future dated in a past year ("2024年に発売される見込み" written on 2026-10-31):
     * the model reasoned from its training date, not from today.
     */
    fun isStale(h: String?): Boolean {
        if (h.isNullOrBlank()) return false
        val now = java.time.LocalDate.now().year
        val years = Regex("(20\\d{2})年").findAll(h).mapNotNull { it.groupValues[1].toIntOrNull() }.toList()
        return years.isNotEmpty() && years.max() < now && PREDICTIVE.containsMatchIn(h)
    }

    /** Asks for a hypothesis other than the one judged last time. */
    fun differentNote(previous: String): String =
        "\n\n注意: 前回の審議では仮説「${previous.trim()}」を検討した。今回はそれとは異なる立場か観点から、別の仮説を一文で書くこと（同じ主張の言い換えは不可）。"

    fun staleNote(h: String): String =
        "\n\n注意: 今日は${WebResearch.today()}。前の仮説「$h」は過去の年を未来の予定として書いていて誤り。" +
            "今日の日付と調査資料の日付を基準に、今の時点での答えを一文で書き直すこと。"

    private val NON_COMMITTAL = Regex(
        "判断(でき|は|が)(ない|難しい|困難)|判断できず|(分から|わから)ない|不明(である|だ|です)?。?$|(言え|断定でき|予測でき|結論づけられ|見通せ)ない|" +
            "(資料|情報|材料)(だけ|のみ)では|判断材料(が|は)?(なし|ない|不足)|次第(である|だ|です)?。?$|一概に"
    )

    /** "Cannot be judged from the material" takes no side, so the sages would have nothing to judge. */
    fun isNonCommittal(h: String?): Boolean = !h.isNullOrBlank() && NON_COMMITTAL.containsMatchIn(h)

    fun commitNote(h: String): String =
        "\n\n注意: 前の仮説「$h」は立場を取っておらず、仮説になっていない。資料が足りなくても、一般的な知識と論理から" +
            "最もありそうな答えを一つ選び、「〜である」「〜できる」「〜できない」のように言い切る一文で書き直すこと。"

    /** A hypothesis must be an answer, not a plan ("〜を調べる"), a platitude or a refusal to take a side. */
    fun isRealHypothesis(h: String?): Boolean {
        if (h.isNullOrBlank() || h.length < 6) return false
        if (isNonCommittal(h)) return false
        val t = h.trim().trimEnd('。', '.')
        return !Regex("(調べる|調査する|確認する|検討する|見る必要がある|必要がある|が重要(である|だ)?|を見極める|注視する|判断材料なし)$").containsMatchIn(t) &&
            !t.contains("を調べ")
    }

    private val LACKS = Regex("判断材料(が)?不足|材料(が)?不足|材料がない|情報(が)?(不足|足りない|ない|ありません)|判断できな|判断でき(ず|ません)|わから(ない|ず)|分から(ない|ず)|不明|確認が必要|確認できな|最新情報の確認")

    /** The sage could not decide for want of facts (and would abstain or reject only for that). */
    fun lacksMaterial(reply: String): Boolean = LACKS.containsMatchIn(reply)

    const val SAGE_RESEARCH_SYSTEM = """あなたはMAGIシステムの審議者。いまの判断に足りない事実を、Webで調べるための検索語だけを書く。説明や判断は書かない。"""

    fun sageResearchUser(question: String, hypothesis: String?, reply: String): String = buildString {
        append("【今日】").append(WebResearch.today()).append("\n\n")
        append("【審議事項】\n").append(question.trim()).append("\n\n")
        if (!hypothesis.isNullOrBlank()) append("【仮説】\n").append(hypothesis.trim()).append("\n\n")
        append("【あなたの暫定の判断】\n").append(reply.trim().take(500)).append("\n\n")
        append("判断に足りない事実を調べる行を2行まで書く（審議事項の具体的な中身を検索語にする）:\n")
        append("調査: ニュース （最近の動きを調べる検索語）\n調査: ウェブ （事実を調べる検索語）\n調査: 事典 （意味を知らない言葉）\n調査: 株価 （銘柄名。株の質問のとき）")
    }

    /** What a sage asked to look up; the question itself when it named nothing usable. */
    fun sageLookups(text: String, question: String, priorQuestion: String?): List<Pair<String, String>> {
        val asked = parsePlan(if (text.trimStart().startsWith("調査")) text else "調査: $text").lookups
            .filter { (k, q) -> q !in PLACEHOLDER && (k != "天気" || WEATHER_Q.containsMatchIn(question)) }
        if (asked.isNotEmpty()) return asked.distinct().take(2)
        val base = refineLookups(question, ProposerPlan(null, emptyList()), priorQuestion)
        return base.filter { it.first == "ニュース" || it.first == "ウェブ" }.map { (k, q) -> if (k == "ウェブ") k to "$q 最新" else k to q }.take(2)
    }

    /** Stock names the proposer listed after its hypothesis ("銘柄: A、B、C"). */
    fun parseStocks(text: String): List<String> =
        Regex("銘柄\\s*[:：]\\s*([^\\n]+)").find(text)?.groupValues?.get(1)
            ?.split('、', ',', '，', '・', '/', '／')?.map { it.trim().trim('「', '」', '（', '）', '(', ')', '。') }
            ?.filter { it.length in 2..30 && it !in setOf("名前", "銘柄名") }?.distinct()?.take(3) ?: emptyList()

    fun parsePlan(text: String): ProposerPlan {
        val lookups = Regex("調査\\s*[:：]\\s*(天気|事典|ウェブ|ニュース|株価|法令|GitHub)\\s*([^\\n]*)").findAll(text)
            .map { it.groupValues[1] to it.groupValues[2].trim().trim('（', '）', '(', ')') }.take(2).toList()
        val hyp = Regex("仮説\\s*[:：]\\s*([^\\n]+)").find(text)?.groupValues?.get(1)?.trim()?.trim('（', '）')
        return ProposerPlan(hyp?.takeIf { it.isNotBlank() && it != "一文の主張" }, lookups)
    }

    const val INTEGRATE_SYSTEM = """あなたはMAGIシステムの統合ユニットです。審議者の意見と多数決の結果だけをもとに、利用者への結論を日本語で書く。

厳守:
- 結論だけを2〜3文の普通の文章で書く。見出し・箇条書き・審議者の名前は書かない
- 具体的な答えを求められているときは、審議者が挙げた具体的な名前・候補をそのまま使う
- 審議者が挙げていない事実・固有名詞・数値を新しく作り足さない（【調査資料】【参考資料】に書かれている事実は使ってよい）
- 審議者の意見と【調査資料】が食い違う事実（日付・数値・出来事）は、【調査資料】に従う
- 審議者や資料の番号 [n] が付いた事実を使うときは、その番号も残す
- 審議者が「確認が必要」とした内容は、断定に言い換えない
- 意見が割れているときは、割れていることを一言添える"""

    fun integrateUser(question: String, visual: String?, opinions: List<Pair<SageRole, String>>, resolution: String, votes: String,
                      references: String? = null, hypothesis: String? = null, research: String? = null): String =
        buildString {
            if (!research.isNullOrBlank()) append("【調査資料】\n").append(research.trim()).append("\n\n")
            if (!references.isNullOrBlank()) append("【参考資料】\n").append(references.trim()).append("\n\n")
            append("【審議事項】\n").append(question.trim()).append("\n\n")
            if (!hypothesis.isNullOrBlank()) append("【仮説】\n").append(hypothesis.trim()).append("\n\n")
            if (!visual.isNullOrBlank()) append("【前回の審議】（今回の審議事項はこの続き。前回の結論を踏まえて考える）\n").append(visual.trim()).append("\n\n")
            for ((role, text) in opinions) {
                append("【${role.code}（${role.persona}）】\n").append(text.trim()).append("\n\n")
            }
            append("【多数決の結果】").append(resolution).append("（").append(votes).append("）")
            append("\n\n上の意見だけを使って、結論を2〜3文で書いてください。")
        }

    private val ANSWER = Regex("回答\\s*[:：]\\s*([\\s\\S]*?)(?=\\n\\s*理由\\s*[:：]|$)")

    /** The "回答:" part of a sage's reply (falls back to the whole reply without the 判定 line). */
    fun answerOf(reply: String): String =
        ANSWER.find(reply)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() }
            ?: reply.lines().filterNot { it.trimStart().startsWith("判定") }.joinToString(" ").trim()

    private val LEAK_MARKERS = listOf(
        "<|im_end|>", "<|im_start|>", "<¦im_end¦>", "<¦im_start¦>", "<|eot_id|>", "<|start_header_id|>",
        "<|end|>", "<|endoftext|>", "<end_of_turn>", "<start_of_turn>", "<|user|>", "<|assistant|>", "<|system|>", "</s>",
    )

    /**
     * Removes anything a model wrote after leaving its turn: chat-template markers and, for sages,
     * a second "判定:" block (the model starting to play another participant).
     */
    fun clean(text: String, singleJudgement: Boolean = false): String {
        var t = text
        for (m in LEAK_MARKERS) { val i = t.indexOf(m); if (i >= 0) t = t.substring(0, i) }
        if (singleJudgement) {
            val first = t.indexOf("判定")
            if (first >= 0) {
                val second = Regex("\\n\\s*判定\\s*[:：]").find(t, first + 2)
                if (second != null) t = t.substring(0, second.range.first)
            }
        }
        // a line that restates our own instructions means the model is echoing the prompt
        val echo = listOf("あなたはMAGIシステム", "他の審議者とは独立して", "守ること:", "必ず次の3項目", "口調の例:", "口調: 回答", "【審議事項】", "【調査資料】", "【前回の審議】")
        val lines = t.lines()
        val cut = lines.indexOfFirst { l -> echo.any { l.contains(it) } }
        if (cut >= 0) t = lines.take(cut).joinToString("\n")
        return t.trim()
    }
}
