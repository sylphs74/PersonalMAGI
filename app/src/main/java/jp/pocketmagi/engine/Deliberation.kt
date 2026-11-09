package jp.pocketmagi.engine

import android.content.Context
import jp.pocketmagi.data.MagiSettings
import jp.pocketmagi.model.ModelCatalog
import jp.pocketmagi.model.ModelSpec
import jp.pocketmagi.model.ModelStore
import jp.pocketmagi.model.SageRole
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

enum class Verdict(val label: String) { APPROVE("承認"), REJECT("否決"), ABSTAIN("保留") }

enum class Resolution(val label: String) { PASSED("可決"), REJECTED("否決"), PENDING("保留") }

enum class SageStatus(val label: String) {
    WAITING("待機"), LOADING("起動中"), THINKING("審議中"), DONE("完了"), ERROR("エラー"), MISSING("未導入"), CANCELLED("中断"), SKIPPED("—")
}

data class SageState(
    val role: SageRole,
    val modelName: String = "",
    val status: SageStatus = SageStatus.WAITING,
    val verdict: Verdict? = null,
    val text: String = "",
    val stats: String = "",
    val error: String? = null,
    /** Verdict of the first round when a rebuttal round followed. */
    val firstVerdict: Verdict? = null,
)

enum class Phase { IDLE, RUNNING, DONE, ERROR, CANCELLED }

/**
 * A finished deliberation that the next question continues (追加審議): its question and result,
 * with the round before it, kept short enough for the small local models.
 */
data class FollowUp(val question: String, val summary: String) {
    companion object {
        fun of(st: DeliberationState, previous: FollowUp?): FollowUp {
            val result = st.integrated.substringBefore("\n\nクラウドAIの使用量").substringBefore("\n\n出典（Web）").trim()
            val now = "質問: ${st.question}\n$result".take(900)
            val before = previous?.let { "（その前）" + it.summary.take(400) + "\n" } ?: ""
            return FollowUp(st.question, before + now)
        }
    }
}

data class DeliberationState(
    val phase: Phase = Phase.IDLE,
    val question: String = "",
    val stage: String = "",
    /** Neutral proposer that turns the question into a hypothesis for the sages to judge. */
    val proposer: SageState = SageState(SageRole.PROPOSER, status = SageStatus.SKIPPED),
    val hypothesis: String = "",
    val research: List<Material> = emptyList(),
    val sages: Map<SageRole, SageState> = JUDGES.associateWith { SageState(it) },
    val resolution: Resolution? = null,
    val integrating: Boolean = false,
    val integrated: String = "",
    /** Each sage's verdict and answer, built by the app; shown apart from the result. */
    val roster: String = "",
    /** The question this one continues (追加審議), or "". */
    val followUpOf: String = "",
    val error: String? = null,
    val startedAt: Long = 0,
    val finishedAt: Long = 0,
) {
    val elapsedMs: Long get() = if (startedAt == 0L) 0 else (if (finishedAt > 0) finishedAt else System.currentTimeMillis()) - startedAt

    companion object {
        /** Evaluation order: smallest first, MELCHIOR last so it stays loaded for the integration. */
        val JUDGES = listOf(SageRole.CASPER, SageRole.BALTHASAR, SageRole.MELCHIOR)
    }
}

object VerdictParser {
    private val LINE = Regex("判定\\s*[:：]\\s*([^\\n]*)")

    fun parse(text: String): Verdict {
        val head = LINE.find(text)?.groupValues?.get(1) ?: text.take(40)
        val rej = listOf("否決", "反対", "却下", "不承認").any { head.contains(it) }
        val app = listOf("承認", "可決", "賛成").any { head.contains(it) }
        return when {
            rej -> Verdict.REJECT
            app -> Verdict.APPROVE
            else -> Verdict.ABSTAIN
        }
    }

    fun resolve(verdicts: Collection<Verdict>): Resolution {
        val a = verdicts.count { it == Verdict.APPROVE }
        val r = verdicts.count { it == Verdict.REJECT }
        return when {
            a >= 2 -> Resolution.PASSED
            r >= 2 -> Resolution.REJECTED
            else -> Resolution.PENDING
        }
    }
}

/**
 * Runs one MAGI session: PROPOSER -> CASPER -> BALTHASAR -> MELCHIOR -> integration.
 * Models are loaded one at a time to fit an 8 GB phone.
 */
class Deliberation(
    private val context: Context,
    private val store: ModelStore,
    private val currentSettings: () -> MagiSettings,
) {
    /** Physical memory of this phone in GB (as reported by Android, a little under the nominal size). */
    val deviceRamGb: Double by lazy {
        val mi = android.app.ActivityManager.MemoryInfo()
        context.getSystemService(android.app.ActivityManager::class.java)?.getMemoryInfo(mi)
        mi.totalMem / 1e9
    }

    /** The model "auto" would pick for [role] on this phone, ignoring whether it is downloaded. */
    fun recommended(role: SageRole): ModelSpec? {
        val list = ModelCatalog.forRole(context, role)
        // hot, or low on battery without a charger: the standard model even if the phone could run the larger one
        val stateOk = !currentSettings().deviceAware || DeviceState.blocksHigh(context) == null
        return list.firstOrNull { it.tier == "high" && deviceRamGb >= it.minRamGb && stateOk }
            ?: list.firstOrNull { it.isDefault } ?: list.firstOrNull()
    }

    /** Why 自動 is on the standard models right now (battery, heat), or null. */
    fun deviceNote(): String? = if (currentSettings().deviceAware) DeviceState.blocksHigh(context) else null

    private val _state = MutableStateFlow(DeliberationState())
    val state: StateFlow<DeliberationState> = _state

    fun reset() { _state.value = DeliberationState() }

    /**
     * Model used for [role]: the user's manual choice, or for "auto" the recommended model for this
     * phone when it is downloaded (otherwise the standard one, so a phone change never blocks).
     */
    fun judgeSpec(role: SageRole, settings: MagiSettings = currentSettings()): ModelSpec? = specFor(role, settings)

    /** Same rule for every role, the proposer included. */
    fun specFor(role: SageRole, settings: MagiSettings = currentSettings()): ModelSpec? {
        val list = ModelCatalog.forRole(context, role)
        val standard = list.firstOrNull { it.isDefault } ?: list.firstOrNull()
        val choice = settings.roleChoice[role.name] ?: "auto"
        if (choice != "auto") return list.firstOrNull { it.baseId == choice || it.id == choice } ?: standard
        val rec = recommended(role)
        return if (rec != null && store.local(rec) != null) rec else standard
    }

    /**
     * What actually runs for [role]: the chosen model when it is on the phone, otherwise another
     * downloaded model of the same role (standard first). So while a new model downloads, or after
     * a download was stopped, the sage keeps deliberating with what it already has.
     */
    fun usableSpecFor(role: SageRole, settings: MagiSettings = currentSettings()): ModelSpec? {
        val chosen = specFor(role, settings)
        if (chosen != null && store.local(chosen) != null) return chosen
        val list = ModelCatalog.forRole(context, role).filter { store.local(it) != null }
        return list.firstOrNull { it.isDefault } ?: list.firstOrNull() ?: chosen
    }

    private fun standardSpec(role: SageRole): ModelSpec? =
        ModelCatalog.forRole(context, role).firstOrNull { it.isDefault } ?: ModelCatalog.forRole(context, role).firstOrNull()

    private val keys = jp.pocketmagi.data.ApiKeys(context)

    /** What runs a role: a local model, or a cloud AI with the user's key. */
    sealed class Brain {
        abstract val label: String
        data class Local(val spec: ModelSpec, val files: jp.pocketmagi.model.LocalModel, override val label: String) : Brain()
        data class Remote(val provider: CloudProvider, val model: String, val key: String) : Brain() {
            // shown like a local model: just the model's name
            override val label get() = if (provider == CloudProvider.PERPLEXITY) "Perplexity ${model}" else model
            override fun toString() = label   // never print the key
        }
    }

    /** Engine chosen for [role] in the settings: "local" or a cloud provider. */
    fun engineOf(role: SageRole, settings: MagiSettings = currentSettings()): CloudProvider? =
        CloudProvider.of(settings.roleEngine[role.name])

    private fun localBrain(role: SageRole, settings: MagiSettings): Brain.Local? {
        val use = usableSpecFor(role, settings) ?: return null
        val files = store.local(use) ?: return null
        val chosen = specFor(role, settings)
        return Brain.Local(use, files, if (chosen != null && use.id != chosen.id) "代理: ${use.name}" else use.name)
    }

    private val modelCache = mutableMapOf<CloudProvider, List<String>>()

    /** The phone's model for [role] as on 自動, used when the chosen cloud AI cannot answer. */
    private fun fallbackLocal(role: SageRole, settings: MagiSettings): Brain.Local? =
        localBrain(role, settings.copy(roleChoice = settings.roleChoice + (role.name to "auto")))?.let { b ->
            Brain.Local(b.spec, b.files, "代理: ${b.spec.name}")
        }

    /**
     * The brain for [role]. A cloud AI without a key, or whose model list cannot be read, falls back
     * to the local model of that role when there is one (shown as 代理).
     */
    private suspend fun brainFor(role: SageRole, settings: MagiSettings): Pair<Brain?, String?> {
        val p = engineOf(role, settings) ?: return localBrain(role, settings) to null
        if (jp.pocketmagi.data.CloudCost.overCap(context))
            return fallbackLocal(role, settings) to "今月のクラウドAI費用が上限（¥${jp.pocketmagi.data.CloudCost.cap(context)}）に達したため端末内で審議"
        if (p == CloudProvider.CUSTOM && Cloud.customBase.isBlank()) return fallbackLocal(role, settings) to "カスタムAPIのURLが未設定"
        // an OpenAI-compatible server may need no key
        val key = keys.get(p.id) ?: if (p == CloudProvider.CUSTOM) "" else null
        if (key == null || (key.isBlank() && p != CloudProvider.CUSTOM)) return fallbackLocal(role, settings) to "${p.label} のAPIキーが未登録"
        val chosen = settings.roleCloudModel[role.name].orEmpty()
        if (chosen.isNotBlank()) return Brain.Remote(p, chosen, key) to null
        val model = try {
            val list = modelCache[p] ?: Cloud.listModels(p, key).also { modelCache[p] = it }
            Cloud.recommendedModel(p, list)
        } catch (e: Exception) { null }
        return if (model != null) Brain.Remote(p, model, key) to null
        else fallbackLocal(role, settings) to "${p.label} に接続できません"
    }

    /** Sages that can take part neither on a cloud AI (key registered) nor on a downloaded model. */
    fun missingJudges(settings: MagiSettings = currentSettings()): List<SageRole> =
        DeliberationState.JUDGES.filter { r ->
            val p = engineOf(r, settings)
            val cloudOk = p != null && cloudReady(p)
            !cloudOk && usableSpecFor(r, settings)?.let { store.local(it) } == null
        }

    /** A cloud AI can be used: its key is registered (the custom API needs its URL instead). */
    fun cloudReady(p: CloudProvider): Boolean = if (p == CloudProvider.CUSTOM) Cloud.customBase.isNotBlank() else keys.has(p.id)

    private var cloudIn = 0
    private var cloudOut = 0
    private var cloudYen = 0.0
    private var costRecorded = false

    /** Adds this deliberation's cloud cost to the month once, however it ended. */
    private fun recordCost() {
        if (costRecorded) return
        costRecorded = true
        jp.pocketmagi.data.CloudCost.add(context, cloudYen)
    }

    /**
     * One answer from [brain]. A local brain must already be loaded. Cloud AIs get no prefill, so
     * the prefix is put back when they leave it out.
     */
    private suspend fun ask(brain: Brain, system: String, user: String, maxTokens: Int, temperature: Float,
                            prefill: String, onText: (String) -> Unit): Pair<String, String> = when (brain) {
        is Brain.Local -> {
            val sb = StringBuilder(prefill)
            val out = LlamaBridge.generate(system, user, emptyList(), maxTokens, temperature, prefill) { piece ->
                sb.append(piece); onText(sb.toString())
            }
            out to LlamaBridge.lastStats()
        }
        is Brain.Remote -> {
            val r = Cloud.chat(brain.provider, brain.model, brain.key, system, user, maxOf(maxTokens * 3, 1200))
            synchronized(this) {
                cloudIn += r.inTokens; cloudOut += r.outTokens
                cloudYen += jp.pocketmagi.data.CloudCost.yen(context, brain.provider.id, r.inTokens, r.outTokens, r.costUsd)
            }
            if (r.sources.isNotEmpty()) addResearch(r.sources)
            var text = r.text
            val head = prefill.trim().trimEnd(':', '：')
            if (head.isNotEmpty() && !text.trimStart().startsWith(head)) text = prefill + text
            onText(text)
            text to "${brain.label}\n入力 ${r.inTokens} tok / 出力 ${r.outTokens} tok / ${"%.1f".format(r.ms / 1000.0)}秒"
        }
    }

    /** The local model now in memory (the integrator writes with it). */
    private var loadedBrain: Brain.Local? = null

    private fun ctxFor(b: Brain.Local, wanted: Int) = wanted.coerceIn(2048, maxOf(2048, b.spec.nCtx))

    private suspend fun loadLocal(b: Brain.Local, role: SageRole, settings: MagiSettings, ctx: Int): String {
        LlamaBridge.label = "${role.code} / ${b.spec.name}"
        // never beyond the model's own context: a larger KV cache can exhaust the phone's memory
        // (a 7B model with 7k context needs over 7 GB) and the model was not trained for it
        loadedBrain = b
        return LlamaBridge.load(b.files.model, null, ctxFor(b, ctx), settings.threads, 0,
            settings.useGpu && b.spec.name !in settings.gpuDeny)
    }

    suspend fun run(question: String, settings: MagiSettings, followUp: FollowUp? = null) {
        val baseCtx = 3072
        val t0 = System.currentTimeMillis()
        cloudIn = 0; cloudOut = 0; cloudYen = 0.0; costRecorded = false
        _state.value = DeliberationState(phase = Phase.RUNNING, question = question, startedAt = t0, stage = "準備中",
            followUpOf = followUp?.question ?: "")
        try {
            LlamaBridge.init(context)
            val roles = listOf(SageRole.PROPOSER) + DeliberationState.JUDGES + SageRole.INTEGRATOR
            val brains = mutableMapOf<SageRole, Brain?>()
            val notes = mutableMapOf<SageRole, String?>()
            for (r in roles) { val (b, n) = brainFor(r, settings); brains[r] = b; notes[r] = n }
            _state.update { st ->
                st.copy(
                    proposer = brains[SageRole.PROPOSER]?.let { SageState(SageRole.PROPOSER, it.label, SageStatus.WAITING) }
                        ?: SageState(SageRole.PROPOSER, status = SageStatus.SKIPPED),
                    sages = DeliberationState.JUDGES.associateWith { r ->
                        SageState(r, brains[r]?.label ?: "", if (brains[r] == null) SageStatus.MISSING else SageStatus.WAITING,
                            error = notes[r])
                    },
                )
            }
            // the previous result, for a follow-up (it takes the place of the old media block in the prompts)
            val visual: String? = followUp?.summary

            // 0. Pages whose URL is in the question (typed, or shared from another app) are read first
            val pages = if (settings.webEnabled) WebResearch.urlsIn(question).takeIf { it.isNotEmpty() }?.let {
                stage("ページを読み込み中"); WebResearch.pages(question)
            } ?: emptyList() else emptyList()
            if (pages.isNotEmpty()) addResearch(pages)

            // 1. Neutral proposer: a hypothesis, from what the web says today
            var hypothesis: String? = null
            var researchText: String? = null
            val pBrain = brains[SageRole.PROPOSER]
            if (pBrain != null) { try {
                checkCancel()
                var pBackend = ""
                if (pBrain is Brain.Local) {
                    setProposer { it.copy(status = SageStatus.LOADING) }
                    stage("PROPOSER: ${pBrain.spec.name} を起動中")
                    pBackend = loadLocal(pBrain, SageRole.PROPOSER, settings, 4096)
                    checkCancel()
                }
                // a cloud AI that fails (4xx/5xx, network) is replaced by the phone's model on 自動
                var pb: Brain = pBrain
                suspend fun pAsk(system: String, user: String, max: Int, prefill: String, onText: (String) -> Unit): Pair<String, String> {
                    val b = pb
                    return try {
                        ask(b, system, user, max, 0.2f, prefill, onText)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        if (b !is Brain.Remote || _state.value.phase == Phase.CANCELLED) throw e
                        val fb = fallbackLocal(SageRole.PROPOSER, settings) ?: throw e
                        setProposer { it.copy(modelName = fb.label, status = SageStatus.LOADING,
                            text = "${b.provider.label} が使えないため端末内で続行（${e.message?.take(80)}）\n") }
                        stage("PROPOSER: ${fb.spec.name} を起動中（${b.provider.label} の代理）")
                        pBackend = loadLocal(fb, SageRole.PROPOSER, settings, 4096)
                        checkCancel()
                        pb = fb
                        setProposer { it.copy(status = SageStatus.THINKING) }
                        ask(fb, system, user, max, 0.2f, prefill, onText)
                    }
                }
                setProposer { it.copy(status = SageStatus.THINKING) }
                stage("PROPOSER: 仮説を検討中")
                val (first, _) = pAsk(Prompts.PROPOSE_SYSTEM, Prompts.proposeUser(question, visual, null, settings.webEnabled),
                    120, "") { t -> setProposer { it.copy(text = t) } }
                checkCancel()
                val plan = Prompts.parsePlan(Prompts.clean(first))
                val log = StringBuilder()
                val lookups = if (settings.webEnabled) Prompts.refineLookups(question, plan, followUp?.question) else emptyList()
                val found = mutableListOf<Material>().apply { addAll(pages) }
                for ((kind, q) in lookups) {
                    checkCancel()
                    stage("PROPOSER: Web調査中（$kind ${q.take(20)}）")
                    log.append("調査: $kind $q\n")
                    found += WebResearch.run(kind, q)
                }
                if (lookups.isNotEmpty()) addResearch(WebResearch.ordered(found))
                val finance = WebResearch.isFinance(question)
                var stats = ""
                if (found.isNotEmpty()) {
                    stage("PROPOSER: 調査結果から仮説を作成中")
                    var (second, st2) = pAsk(Prompts.PROPOSE_SYSTEM, Prompts.proposeWithResearch(question, WebResearch.asText(found, 2200), finance, visual),
                        if (finance) 160 else 100, Prompts.PROPOSE_PREFILL) { }
                    checkCancel()
                    var cleaned = Prompts.clean(second)
                    hypothesis = Prompts.parsePlan(cleaned).hypothesis
                    // a hypothesis written from the model's training date (a past year as the future) is redone once
                    if (Prompts.isStale(hypothesis)) {
                        stage("PROPOSER: 今日の日付で仮説を作り直し中")
                        val again = pAsk(Prompts.PROPOSE_SYSTEM,
                            Prompts.proposeWithResearch(question, WebResearch.asText(found, 2200), finance, visual) + Prompts.staleNote(hypothesis!!),
                            if (finance) 160 else 100, Prompts.PROPOSE_PREFILL) { }
                        checkCancel()
                        second = again.first; st2 = again.second
                        cleaned = Prompts.clean(second)
                        hypothesis = Prompts.parsePlan(cleaned).hypothesis
                    }
                    stats = st2
                    // stocks named in the hypothesis (or with a code in the material) are checked against
                    // six months of prices and recent reports, so the sages judge on data, not opinions
                    if (finance) {
                        val have = found.filter { it.kind == "株価" }.map { it.title }
                        val names = (Prompts.parseStocks(cleaned) + WebResearch.codesIn(found))
                            .filter { n -> have.none { it.contains(n) } }.distinct().take((3 - have.size).coerceAtLeast(0))
                        for (n in names) {
                            checkCancel()
                            stage("PROPOSER: 株価を確認中（${n.take(16)}）")
                            log.append("調査: 株価 $n\n")
                            found += WebResearch.run("株価", n)
                        }
                        addResearch(found)
                    }
                    researchText = cite(found)
                } else if (lookups.isNotEmpty()) log.append("（調べた結果が得られませんでした）\n")
                if (!Prompts.isRealHypothesis(hypothesis)) hypothesis = plan.hypothesis
                    ?: Prompts.clean(first).lines().firstOrNull { it.isNotBlank() && !it.startsWith("調査") }?.trim()
                if (stats.isBlank()) stats = if (pb is Brain.Local) LlamaBridge.lastStats() else pb.label
                // plans ("〜を調べる"), platitudes and stale-dated claims are not answers: the sages then answer directly
                val h = hypothesis?.takeIf { Prompts.isRealHypothesis(it) && !Prompts.isStale(it) }
                hypothesis = h
                _state.update { it.copy(hypothesis = h ?: "") }
                setProposer {
                    it.copy(status = SageStatus.DONE, text = (log.toString() + "仮説: " + (h ?: "なし（3賢者が質問に直接答えます）")).trim(),
                        stats = if (pBackend.isNotBlank()) "$pBackend\n$stats" else stats)
                }
                if (pb is Brain.Local) LlamaBridge.unload()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // no hypothesis is not fatal: the sages then answer the question directly
                if (_state.value.phase == Phase.CANCELLED) throw CancellationException("cancelled")
                try { LlamaBridge.unload() } catch (_: Exception) {}
                hypothesis = null
                _state.update { it.copy(hypothesis = "") }
                setProposer { it.copy(status = SageStatus.ERROR, error = e.message,
                    text = (it.text + "\n仮説なし（${e.message?.take(80)}）").trim()) }
            } }
            // Perplexity's citations may have been added while it worked
            if (researchText == null && _state.value.research.isNotEmpty()) researchText = cite(_state.value.research)
            // room for what a sage may look up by itself when it lacks material
            val ctx = baseCtx + ((researchText?.length ?: 0) + (visual?.length ?: 0) + 1023) / 1024 * 1024 +
                (if (settings.webEnabled) 1024 else 0)
            // material the sages gathered themselves; shared, so the sages after them start better informed
            val extra = java.util.Collections.synchronizedList(mutableListOf<Material>())
            /**
             * Material for a prompt. For a local model it is cut to what fits its context next to the
             * instructions, the question and the answer ([reserve] = characters already taken by the rest).
             */
            fun researchFor(b: Brain? = null, reserve: Int = 0): String? {
                val more = synchronized(extra) { extra.toList() }
                val full = if (more.isEmpty()) researchText
                    else listOfNotNull(researchText, "（審議者が追加で調べた資料）\n" + cite(more, 1000)).joinToString("\n\n")
                if (b !is Brain.Local || full == null) return full
                // roughly one token per Japanese character, the worst case
                val budget = (ctxFor(b, ctx) - 1100 - (visual?.length ?: 0) - settings.judgeMaxTokens - reserve).coerceAtLeast(400)
                if (full.length <= budget) return full
                val base = _state.value.research.filter { m -> more.none { it.url == m.url && it.title == m.title } }
                val mine = if (more.isEmpty()) 0 else budget / 4
                return listOfNotNull(cite(base, budget - mine).takeIf { it.isNotBlank() },
                    if (more.isEmpty()) null else "（審議者が追加で調べた資料）\n" + cite(more, mine)).joinToString("\n\n")
            }

            // 2. Three sages, independently: cloud AIs in parallel while the local models run one by one
            val opinions = java.util.Collections.synchronizedMap(mutableMapOf<SageRole, String>())
            // sages whose cloud AI failed: they deliberate again on the phone's model after the others
            val retry = java.util.Collections.synchronizedList(mutableListOf<Pair<SageRole, String>>())
            // the brain each sage actually used (a local stand-in included), for the rebuttal round
            val used = java.util.Collections.synchronizedMap(mutableMapOf<SageRole, Brain>())
            suspend fun judge(role: SageRole, given: Brain? = brains[role]) {
                checkCancel()
                val b = given
                if (b == null) {
                    setSage(role) { it.copy(status = SageStatus.MISSING, verdict = Verdict.ABSTAIN,
                        error = (notes[role]?.let { "$it。" } ?: "") + "モデル未導入（この審議では棄権扱い）") }
                    return
                }
                try {
                    var backend = ""
                    if (b is Brain.Local) {
                        setSage(role) { it.copy(status = SageStatus.LOADING) }
                        stage("${role.code}: ${b.spec.name} を起動中")
                        backend = loadLocal(b, role, settings, ctx)
                        checkCancel()
                    }
                    setSage(role) { it.copy(status = SageStatus.THINKING) }
                    if (b is Brain.Local) stage("${role.code} 審議中")
                    val system = Prompts.judgeSystem(role, settings.speechStyle, hypothesis != null)
                    var (raw, stats) = ask(b, system, Prompts.judgeUser(question, visual, null, hypothesis, researchFor(b)),
                        settings.judgeMaxTokens, 0.3f, Prompts.JUDGE_PREFILL,
                    ) { t -> setSage(role) { it.copy(text = Prompts.clean(t, true)) } }
                    var out = Prompts.clean(raw, singleJudgement = true)
                    checkCancel()
                    // short of material: the sage says what it needs, the app looks it up, the sage judges again
                    if (settings.webEnabled && Prompts.lacksMaterial(out)) {
                        if (b is Brain.Local) stage("${role.code}: 足りない材料を調べています")
                        setSage(role) { it.copy(text = out + "\n\n（判断材料が足りないため、自分で調べています）") }
                        val (req, _) = ask(b, Prompts.SAGE_RESEARCH_SYSTEM,
                            Prompts.sageResearchUser(question, hypothesis, out), 70, 0.2f, "調査: ") { }
                        checkCancel()
                        val wants = Prompts.sageLookups(req, question, followUp?.question)
                        val mine = mutableListOf<Material>()
                        for ((kind, q) in wants) {
                            checkCancel()
                            if (b is Brain.Local) stage("${role.code}: 調査中（$kind ${q.take(20)}）")
                            mine += WebResearch.run(kind, q)
                        }
                        val known = (_state.value.research + synchronized(extra) { extra.toList() }).map { it.url + it.title }.toSet()
                        val fresh = mine.filter { (it.url + it.title) !in known }
                        if (fresh.isNotEmpty()) {
                            extra += fresh
                            addResearch(fresh)
                            if (b is Brain.Local) stage("${role.code}: 調べた材料で審議中")
                            val again = ask(b, system, Prompts.judgeUser(question, visual, null, hypothesis, researchFor(b)),
                                settings.judgeMaxTokens, 0.3f, Prompts.JUDGE_PREFILL,
                            ) { t -> setSage(role) { it.copy(text = Prompts.clean(t, true)) } }
                            raw = again.first; stats = again.second + "\n自分で調べた資料 ${fresh.size}件: " + wants.joinToString("、") { "${it.first} ${it.second}" }
                            out = Prompts.clean(raw, singleJudgement = true)
                            checkCancel()
                        }
                    }
                    val v = VerdictParser.parse(out)
                    setSage(role) { it.copy(status = SageStatus.DONE, verdict = v, text = out,
                        stats = if (backend.isNotBlank()) "$backend\n$stats" else stats) }
                    opinions[role] = out
                    used[role] = b
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    if (_state.value.phase == Phase.CANCELLED) throw CancellationException("cancelled")
                    if (b is Brain.Remote) {
                        val why = "${b.provider.label}: ${e.message?.take(100)}"
                        retry += role to why
                        setSage(role) { it.copy(status = SageStatus.WAITING, text = "", error = "$why → 端末内のモデルで審議します") }
                    } else setSage(role) { it.copy(status = SageStatus.ERROR, verdict = Verdict.ABSTAIN, error = e.message) }
                }
            }
            kotlinx.coroutines.coroutineScope {
                val remote = DeliberationState.JUDGES.filter { brains[it] is Brain.Remote }.map { r -> async { judge(r) } }
                if (remote.isNotEmpty() && DeliberationState.JUDGES.none { brains[it] is Brain.Local }) stage("クラウドAIが審議中")
                for (r in DeliberationState.JUDGES) if (brains[r] !is Brain.Remote) judge(r)
                if (remote.isNotEmpty()) stage("クラウドAIの回答待ち")
                remote.awaitAll()
            }
            for ((r, why) in retry.toList()) {
                checkCancel()
                val fb = fallbackLocal(r, settings)
                if (fb == null) {
                    setSage(r) { it.copy(status = SageStatus.ERROR, verdict = Verdict.ABSTAIN,
                        error = "$why。端末内のモデルもないため棄権") }
                } else {
                    setSage(r) { it.copy(modelName = fb.label, error = null) }
                    judge(r, fb)
                }
            }

            // 2b. Rebuttal round: each sage reads the other two and may change its mind once
            val firstRound = DeliberationState.JUDGES.mapNotNull { r -> opinions[r]?.let { r to it } }
            // only those who disagree reconsider: when all agree there is nothing to argue about,
            // and a sage already on the majority side is not asked again (each local turn costs minutes)
            val firstVerdicts = firstRound.associate { (r, _) -> r to (_state.value.sages[r]?.verdict ?: Verdict.ABSTAIN) }
            val majority = firstVerdicts.values.groupingBy { it }.eachCount().filterValues { it >= 2 }.keys.firstOrNull()
            val dissenters = when {
                firstVerdicts.values.distinct().size <= 1 -> emptyList()
                majority != null -> firstVerdicts.filterValues { it != majority }.keys.toList()
                else -> firstVerdicts.keys.toList()
            }
            if (settings.rebuttal && firstRound.size >= 2 && dissenters.isEmpty()) stage("再審議: 全員の判定が一致したため省略")
            if (settings.rebuttal && firstRound.size >= 2 && dissenters.isNotEmpty()) {
                suspend fun reconsider(role: SageRole) {
                    checkCancel()
                    val b = used[role] ?: return
                    val own = opinions[role] ?: return
                    try {
                        if (b is Brain.Local && LlamaBridge.loadedPath != b.files.model.absolutePath) {
                            setSage(role) { it.copy(status = SageStatus.LOADING, firstVerdict = it.verdict) }
                            stage("再審議: ${role.code} が準備中（${b.spec.name} を起動）")
                            loadLocal(b, role, settings, ctx)
                            checkCancel()
                        }
                        val first = _state.value.sages[role]?.verdict
                        setSage(role) { it.copy(status = SageStatus.THINKING, firstVerdict = first,
                            text = "再審議: 他の賢者の意見を読んで考え直しています…\n\n（最初の判断）\n$own") }
                        if (b is Brain.Local) stage("再審議: ${role.code} が見直し中（" +
                            firstRound.filter { it.first != role }.joinToString("・") { it.first.code } + " の意見を読んで）")
                        // a short excerpt of the material is enough here: the first round already used it
                        val (raw, stats) = ask(b, Prompts.judgeSystem(role, settings.speechStyle, hypothesis != null),
                            Prompts.rebuttalUser(question, visual, hypothesis, researchFor(b, 1400)?.take(900), own,
                                firstRound.filter { it.first != role }),
                            settings.judgeMaxTokens, 0.3f, Prompts.JUDGE_PREFILL,
                        ) { t -> setSage(role) { it.copy(text = Prompts.clean(t, true)) } }
                        val out = Prompts.clean(raw, singleJudgement = true)
                        checkCancel()
                        val v = VerdictParser.parse(out)
                        setSage(role) { it.copy(status = SageStatus.DONE, verdict = v, text = out,
                            stats = it.stats + "\n再審議: " + (first?.label ?: "—") + " → " + v.label + "\n" + stats) }
                        opinions[role] = out
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        if (_state.value.phase == Phase.CANCELLED) throw CancellationException("cancelled")
                        // the first-round opinion stands
                        setSage(role) { it.copy(status = SageStatus.DONE, stats = it.stats + "\n再審議失敗: ${e.message?.take(80)}") }
                    }
                }
                stage("再審議: " + dissenters.joinToString("・") { it.code } + "（少数意見）が判断を見直します")
                kotlinx.coroutines.coroutineScope {
                    val remote = dissenters.filter { used[it] is Brain.Remote }.map { r -> async { reconsider(r) } }
                    // the model still loaded (normally MELCHIOR) goes first, so one load is saved
                    for (r in firstRound.map { it.first }.reversed()) if (r in dissenters && used[r] !is Brain.Remote) reconsider(r)
                    remote.awaitAll()
                }
            }

            checkCancel()
            // 3. Vote
            val verdicts = _state.value.sages.values.map { it.verdict ?: Verdict.ABSTAIN }
            val res = VerdictParser.resolve(verdicts)
            _state.update { it.copy(resolution = res) }

            // 4. Result: by the chosen integrator (locally: the sage model still loaded, normally MELCHIOR)
            val ordered = DeliberationState.JUDGES.mapNotNull { r -> opinions[r]?.let { r to it } }
            val header = (if (hypothesis != null) "仮説: $hypothesis\n" else "") + "決議: ${res.label}（${voteSummary(verdicts)}）\n"
            // the per-sage lines are built by the app from the parsed replies: names and
            // verdicts are always right, whatever the integrating model writes
            val roster = DeliberationState.JUDGES.reversed().mapNotNull { r ->
                val s = _state.value.sages[r] ?: return@mapNotNull null
                val v = if (s.status == SageStatus.DONE) {
                    val now = s.verdict?.label ?: "保留"
                    s.firstVerdict?.takeIf { it != s.verdict }?.let { "${it.label}→$now（再考）" } ?: now
                } else "棄権"
                val body = if (s.status == SageStatus.DONE) Prompts.answerOf(s.text) else (s.error ?: "未導入のため棄権")
                "・${r.code}（${r.persona}）$v: $body"
            }.joinToString("\n")
            var iBrain: Brain? = brains[SageRole.INTEGRATOR]?.takeIf { it is Brain.Remote }
            if (iBrain == null && ordered.isNotEmpty()) {
                // locally the result is written by the sage model still loaded (normally MELCHIOR);
                // when every sage ran in the cloud, a downloaded model is loaded for it
                val any = listOf(SageRole.MELCHIOR, SageRole.BALTHASAR, SageRole.CASPER, SageRole.PROPOSER)
                    .firstNotNullOfOrNull { localBrain(it, settings) }
                if (LlamaBridge.loadedPath == null && any != null) {
                    stage("審議結果のモデルを起動中")
                    loadLocal(any, SageRole.INTEGRATOR, settings, ctx)
                    checkCancel()
                }
                if (LlamaBridge.loadedPath != null) iBrain = any
            }
            if (ordered.isNotEmpty() && iBrain != null) { try {
                stage("審議結果をまとめ中")
                _state.update { it.copy(integrating = true) }
                val iSystem = Prompts.integrateSystem(settings.speechStyle, hypothesis != null)
                // the sages' opinions take room too
                fun iUserFor(b: Brain?) = Prompts.integrateUser(question, visual, ordered, res.label, voteSummary(verdicts), null, hypothesis,
                    researchFor(b, 1600))
                val iUser = iUserFor(if (iBrain is Brain.Local) loadedBrain else iBrain)
                val onI: (String) -> Unit = { t -> _state.update { it.copy(integrated = header + Prompts.clean(t)) } }
                val ib: Brain = iBrain
                val (out, _) = try {
                    ask(ib, iSystem, iUser, settings.integrateMaxTokens, 0.2f, Prompts.INTEGRATE_PREFILL, onI)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // the cloud integrator failed: the phone's model writes the result instead
                    if (ib !is Brain.Remote || _state.value.phase == Phase.CANCELLED) throw e
                    val any = listOf(SageRole.MELCHIOR, SageRole.BALTHASAR, SageRole.CASPER, SageRole.PROPOSER)
                        .firstNotNullOfOrNull { fallbackLocal(it, settings) } ?: throw e
                    if (LlamaBridge.loadedPath == null) {
                        stage("審議結果のモデルを起動中（${ib.provider.label} の代理）")
                        loadLocal(any, SageRole.INTEGRATOR, settings, ctx)
                        checkCancel()
                    }
                    stage("審議結果をまとめ中（${ib.provider.label} の代理で端末内）")
                    ask(any, iSystem, iUserFor(loadedBrain ?: any), settings.integrateMaxTokens, 0.2f, Prompts.INTEGRATE_PREFILL, onI)
                }
                checkCancel()
                val body = Prompts.clean(out).lines()
                    .filterNot { l -> listOf("観点", "各賢者", "MELCHIOR", "BALTHASAR", "CASPER").any { l.trimStart().startsWith(it) } }
                    .joinToString("\n").trim()
                // the sources are listed in their own box (state.research), not inside the result;
                // dates and figures that no source contains are pointed out
                val check = FactCheck.note(body, _state.value.research, question + "\n" + (hypothesis ?: ""))
                val usage = if (cloudIn + cloudOut > 0) {
                    recordCost()
                    "\n\nクラウドAIの使用量: 入力 $cloudIn / 出力 $cloudOut トークン（約¥%,.0f、今月 約¥%,.0f）"
                        .format(cloudYen, jp.pocketmagi.data.CloudCost.monthTotal(context))
                } else ""
                _state.update { it.copy(integrated = header + body + check + usage, roster = roster, integrating = false) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (_state.value.phase == Phase.CANCELLED) throw CancellationException("cancelled")
                // no model could write the result: the vote and the sages' answers still stand
                _state.update { it.copy(integrated = header + "（審議結果の文章を作れませんでした: ${e.message?.take(100)}）",
                    roster = roster, integrating = false) }
            } } else {
                _state.update { it.copy(integrated = header.trim(), roster = roster) }
            }
            LlamaBridge.unload()
            recordCost()
            _state.update { it.copy(phase = Phase.DONE, stage = "審議終了", finishedAt = System.currentTimeMillis()) }
        } catch (e: CancellationException) {
            LlamaBridge.unload()
            recordCost()
            _state.update { stopActive(it).copy(phase = Phase.CANCELLED, stage = "中断", integrating = false, finishedAt = System.currentTimeMillis()) }
        } catch (e: Exception) {
            LlamaBridge.unload()
            recordCost()
            if (_state.value.phase == Phase.CANCELLED) {
                _state.update { stopActive(it).copy(stage = "中断", integrating = false, finishedAt = System.currentTimeMillis()) }
                return
            }
            _state.update {
                stopActive(it).copy(phase = Phase.ERROR, error = e.message ?: e.toString(), stage = "エラー", integrating = false,
                    finishedAt = System.currentTimeMillis())
            }
        }
    }

    fun cancel() {
        if (_state.value.phase == Phase.RUNNING) {
            _state.update { stopActive(it).copy(phase = Phase.CANCELLED, stage = "中断中…", integrating = false) }
            LlamaBridge.cancel()
        }
    }

    /** Proposer/sages still waiting, loading or thinking are marked as stopped, so no panel keeps animating. */
    private fun stopActive(st: DeliberationState): DeliberationState {
        fun fix(s: SageState) =
            if (s.status == SageStatus.WAITING || s.status == SageStatus.LOADING || s.status == SageStatus.THINKING)
                s.copy(status = SageStatus.CANCELLED) else s
        return st.copy(proposer = fix(st.proposer), sages = st.sages.mapValues { (_, s) -> fix(s) })
    }

    private fun voteSummary(v: Collection<Verdict>): String =
        listOf(Verdict.APPROVE, Verdict.REJECT, Verdict.ABSTAIN)
            .map { k -> k to v.count { it == k } }.filter { it.second > 0 }
            .joinToString("・") { (k, n) -> "${k.label}$n" }

    private fun checkCancel() {
        if (_state.value.phase == Phase.CANCELLED) throw CancellationException("cancelled")
    }

    private fun stage(s: String) = _state.update { it.copy(stage = s) }

    /** New sources go to the end of the list, so numbers already cited never change. */
    private fun addResearch(items: List<Material>) = _state.update { st ->
        val known = st.research.map { it.url + "|" + it.title }.toMutableSet()
        st.copy(research = st.research + items.filter { known.add(it.url + "|" + it.title) })
    }

    /** Material numbered as in the sources list, for citing as [n]. */
    private fun cite(items: List<Material>, budget: Int = 2600) = WebResearch.asText(items, budget, _state.value.research)
    // once stopped, late updates from the still-unwinding run must not revive the panels
    private fun setProposer(f: (SageState) -> SageState) =
        _state.update { if (it.phase == Phase.CANCELLED) it else it.copy(proposer = f(it.proposer)) }
    private fun setSage(role: SageRole, f: (SageState) -> SageState) =
        _state.update { st -> if (st.phase == Phase.CANCELLED) st else st.copy(sages = st.sages + (role to f(st.sages.getValue(role)))) }
}
