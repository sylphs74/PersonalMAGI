package jp.pocketmagi

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import jp.pocketmagi.data.FontStore
import jp.pocketmagi.data.HistoryEntry
import jp.pocketmagi.data.UserFont
import jp.pocketmagi.data.HistoryStore
import jp.pocketmagi.data.MagiSettings
import jp.pocketmagi.data.SettingsStore
import jp.pocketmagi.engine.Deliberation
import jp.pocketmagi.engine.DeliberationState
import jp.pocketmagi.engine.LlamaBridge
import jp.pocketmagi.engine.Phase
import jp.pocketmagi.model.InstallState
import jp.pocketmagi.model.Generations
import jp.pocketmagi.model.ModelCatalog
import jp.pocketmagi.model.ModelSpec
import jp.pocketmagi.model.ModelStore
import jp.pocketmagi.model.SageRole
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MagiViewModel(app: Application) : AndroidViewModel(app) {
    val store = ModelStore(app)
    private val settingsStore = SettingsStore(app)
    private val historyStore = HistoryStore(app)
    val fontStore = FontStore(app)
    val apiKeys = jp.pocketmagi.data.ApiKeys(app)
    /** provider id -> models listed by that provider (filled when a key is saved or tested). */
    val cloudModels = MutableStateFlow<Map<String, List<String>>>(emptyMap())
    val cloudStatus = MutableStateFlow<Map<String, String>>(emptyMap())
    /** The result the next question continues (追加審議); null = a new deliberation. */
    val followUp = MutableStateFlow<jp.pocketmagi.engine.FollowUp?>(null)
    val crashGuard = jp.pocketmagi.data.CrashGuard(app)
    /** Set when the previous session died inside the engine; shown once as a dialog. */
    val crashReport = MutableStateFlow<jp.pocketmagi.data.CrashReport?>(null)
    val diagnostics = MutableStateFlow<String?>(null)
    val fonts = MutableStateFlow<List<UserFont>>(fontStore.list())
    /** Shared with the foreground service: survives leaving the app and a recreated screen. */
    val deliberation = jp.pocketmagi.engine.Session.deliberation(app)

    val settings = MutableStateFlow(settingsStore.load())
    val message = MutableStateFlow<String?>(null)
    val installStates = MutableStateFlow<Map<String, InstallState>>(emptyMap())
    val history = MutableStateFlow<List<HistoryEntry>>(emptyList())
    val systemInfo = MutableStateFlow("")
    val gpuDevices = MutableStateFlow<String?>(null)
    /** Asks the root to switch tabs (e.g. to 審議 when re-deliberating a record). */
    val requestTab = MutableStateFlow<Int?>(null)
    val unusableGpus = MutableStateFlow("")
    /** base id -> description of an update the user can take or decline */
    val offers = MutableStateFlow<Map<String, String>>(emptyMap())
    val checkingUpdates = MutableStateFlow(false)
    val lastUpdateCheck = MutableStateFlow(app.getSharedPreferences("models", 0).getLong("lastUpdateCheck", 0L))

    /** Effective catalog (newer generations applied); re-read after a promotion. */
    val catalog: List<ModelSpec> get() = ModelCatalog.all(getApplication())
    /** base id -> status of a newer generation being downloaded / verified */
    val generationNotes = MutableStateFlow<Map<String, String>>(emptyMap())
    private var verifying = false

    private var runJob: Job? = null
    private var pollJob: Job? = null
    private var pollers = 0

    init {
        jp.pocketmagi.model.FastDownloader.connections = settings.value.dlConnections
        jp.pocketmagi.engine.Cloud.customBase = settings.value.customApiBase
        jp.pocketmagi.engine.Cloud.customModel = settings.value.customApiModel
        // downloads of models that were removed from the catalog (e.g. EYE) must not be resumed
        val knownIds = try {
            (catalog.map { it.id } + ModelCatalog.base(app).mapNotNull { Generations.pending(app, it)?.id }).toSet()
        } catch (e: Exception) { null }
        if (knownIds != null) jp.pocketmagi.model.FastDownloader.dropOrphans(app, knownIds)
        crashGuard.installUncaughtHandler()
        LlamaBridge.guard = crashGuard
        val previous = crashGuard.collectPreviousCrash()
        // after an app error (not an engine crash) downloads are not resumed automatically, so a
        // broken download cannot crash every launch; they continue from the models tab
        if (previous?.logTail?.contains("--- Kotlin ---") != true) jp.pocketmagi.model.FastDownloader.resumeIfNeeded(app)
        previous?.let { r ->
            crashReport.value = r
            // died while test-loading a new generation: drop it so it is not retried
            if (r.stage.startsWith("検証")) {
                for (b in ModelCatalog.base(app)) Generations.pending(app, b)?.let { p -> store.delete(p); Generations.reject(app, b) }
            }
            // a crash on the GPU: that model runs on the CPU from now on (stage = "ROLE / model name / step")
            if (r.onGpu) {
                val model = r.stage.split(" / ").getOrNull(1)?.trim()
                if (!model.isNullOrBlank()) updateSettings(settings.value.copy(gpuDeny = settings.value.gpuDeny + model))
                else updateSettings(settings.value.copy(useGpu = false))
            }
        }
        viewModelScope.launch(Dispatchers.IO) {
            // remove files of models that are no longer in the catalog (e.g. replaced sages)
            try {
                if (knownIds != null) store.cleanupOrphans(knownIds)
                java.io.File(app.filesDir, "refs").deleteRecursively()   // the reference feature was removed
            } catch (e: Exception) { }
            refreshInstall()
        }
        refreshInstall()
        loadOffers()
        maybeAutoCheckUpdates()
        jp.pocketmagi.data.Schedules.armAll(app)   // after an update or a restore the alarms are set again
        // the app itself: at most once a day (public repository, no token needed)
        if (System.currentTimeMillis() - app.getSharedPreferences("models", 0).getLong("lastAppCheck", 0L) > 86_400_000L) checkAppUpdate(false)
        viewModelScope.launch(Dispatchers.IO) {
            val s = settings.value
            historyStore.prune(s.historyMaxCount, s.historyMaxDays)
            // a run that was going on when the process was killed: keep a trace of it in the records
            jp.pocketmagi.engine.Session.takeLostRun(app)?.let { (q, t) ->
                if (s.historyEnabled) historyStore.add(
                    jp.pocketmagi.engine.DeliberationState(question = q, startedAt = t, finishedAt = t),
                    "中断（アプリが終了）", s.historyMaxCount, s.historyMaxDays,
                )
                message.value = "前回の審議はアプリが終了したため中断されました。記録から再審議できます"
            }
            history.value = historyStore.load()
        }
        // records written by a run that outlived an earlier screen
        viewModelScope.launch {
            jp.pocketmagi.engine.Session.historyVersion.collect { v ->
                if (v > 0) history.value = withContext(Dispatchers.IO) { historyStore.load() }
            }
        }
    }

    fun updateSettings(s: MagiSettings) {
        val old = settings.value
        jp.pocketmagi.model.FastDownloader.connections = s.dlConnections
        settings.value = s
        settingsStore.save(s)
        jp.pocketmagi.engine.Cloud.customBase = s.customApiBase
        jp.pocketmagi.engine.Cloud.customModel = s.customApiModel
        // the deliberation music follows its switch at once, also in the middle of a deliberation
        if (old.musicRunningOn != s.musicRunningOn || old.musicRunningName != s.musicRunningName) {
            if (deliberation.state.value.phase == Phase.RUNNING && !deliberation.state.value.integrating && s.musicRunningOn)
                jp.pocketmagi.data.Sounds.startRunning(getApplication(), s)
            else if (!s.musicRunningOn) jp.pocketmagi.data.Sounds.stopRunning()
        }
        // switching the completion music off stops it if it is playing; switching it on plays nothing
        if (old.musicDoneOn && !s.musicDoneOn) jp.pocketmagi.data.Sounds.stopDone()
        if (old.historyMaxCount != s.historyMaxCount || old.historyMaxDays != s.historyMaxDays) {
            viewModelScope.launch(Dispatchers.IO) {
                historyStore.prune(s.historyMaxCount, s.historyMaxDays)
                history.value = historyStore.load()
            }
        }
    }

    fun importFont(uri: Uri) = viewModelScope.launch {
        try {
            val added = fontStore.import(uri)
            fonts.value = fontStore.list()
            message.value = "フォントを登録しました: " + added.joinToString()
        } catch (e: Exception) {
            message.value = "フォント登録に失敗: ${e.message}"
        }
    }

    fun deleteFont(id: String) {
        val s = settings.value
        updateSettings(
            s.copy(
                bodyFontId = if (s.bodyFontId == id) "" else s.bodyFontId,
                headFontId = if (s.headFontId == id) "" else s.headFontId,
            )
        )
        fontStore.delete(id)
        fonts.value = fontStore.list()
    }

    fun refreshInstall() {
        val app = getApplication<Application>()
        val pend = ModelCatalog.base(app).mapNotNull { b -> Generations.pending(app, b)?.let { b to it } }
        installStates.value = catalog.associate { it.id to store.state(it) } + pend.associate { (_, p) -> p.id to store.state(p) }
        val notes = mutableMapOf<String, String>()
        for ((b, p) in pend) {
            notes[b.id] = when (val st = store.state(p)) {
                is InstallState.Downloading -> "更新版 ${p.name} をダウンロード中 ${(st.fraction * 100).toInt()}%（その間は今のモデルで審議）"
                is InstallState.Installed -> "更新版 ${p.name} の動作確認待ち"
                is InstallState.Failed -> "更新版 ${p.name} のダウンロード失敗: ${st.reason}"
                InstallState.Missing -> "更新版 ${p.name} を準備中"
            }
            if (store.state(p) == InstallState.Installed) verifyGeneration(b, p)
        }
        generationNotes.value = notes
    }

    /** Test-loads a downloaded newer generation; switches to it only if it actually runs. */
    private fun verifyGeneration(base: ModelSpec, cand: ModelSpec) {
        if (verifying || deliberation.state.value.phase == Phase.RUNNING) return
        verifying = true
        viewModelScope.launch {
            val app = getApplication<Application>()
            val old = Generations.effective(app, base)
            val ok = try {
                val local = store.local(cand) ?: error("files missing")
                LlamaBridge.init(app)
                LlamaBridge.label = "検証 / ${cand.name}"
                LlamaBridge.load(local.model, null, 2048, settings.value.threads, 0, false)
                val out = LlamaBridge.generate("", "こんにちは。一言で自己紹介してください。", emptyList(), 16, 0.2f) {}
                out.isNotBlank()
            } catch (e: Exception) {
                false
            } finally {
                try { LlamaBridge.unload() } catch (_: Exception) {}
            }
            if (ok) {
                Generations.promote(app, base)
                ModelCatalog.invalidate()
                if (old.id != cand.id) store.delete(old)
                message.value = "${cand.name} に切り替えました（動作確認済み）"
            } else {
                store.delete(cand)
                Generations.reject(app, base)
                message.value = "${cand.name} はこのアプリの推論エンジンでは動かないため、${old.name} を使い続けます"
            }
            verifying = false
            refreshInstall()
        }
    }

    /** Polls DownloadManager while the models screen is visible. */
    /** Reference-counted so two screens can share the poller while switching tabs. */
    fun startPolling() {
        pollers++
        if (pollJob?.isActive == true) return
        pollJob = viewModelScope.launch(Dispatchers.IO) {
            while (isActive) {
                refreshInstall()
                delay(1000)
            }
        }
    }

    fun stopPolling() {
        pollers = (pollers - 1).coerceAtLeast(0)
        if (pollers == 0) pollJob?.cancel()
    }

    fun download(spec: ModelSpec) = viewModelScope.launch {
        try {
            store.download(spec, settings.value.wifiOnly)
            message.value = "${spec.name} のダウンロードを開始しました"
        } catch (e: Exception) {
            message.value = "${spec.name}: ${e.message}"
        }
        refreshInstall()
    }

    fun downloadAllNeeded() = viewModelScope.launch {
        val s = settings.value
        // the model each role would use (manual choice, or the recommendation for this phone)
        // roles running on a cloud AI need no download
        val needed = (DeliberationState.JUDGES + SageRole.PROPOSER).filter { deliberation.engineOf(it, s) == null }.mapNotNull { r ->
            val choice = s.roleChoice[r.name] ?: "auto"
            if (choice == "auto") deliberation.recommended(r) else deliberation.specFor(r, s)
        }
        for (spec in needed) {
            if (jp.pocketmagi.model.CustomModels.isLocalFile(spec)) continue
            if (store.state(spec) is InstallState.Installed || store.state(spec) is InstallState.Downloading) continue
            try { store.download(spec, s.wifiOnly) } catch (e: Exception) { message.value = "${spec.name}: ${e.message}" }
        }
        refreshInstall()
    }

    /**
     * Checks every installed model against Hugging Face (manually or by the weekly auto-check).
     * Nothing is downloaded here: updates are only offered, and the user decides.
     */
    fun checkUpdates(manual: Boolean = true) = viewModelScope.launch {
        if (checkingUpdates.value) return@launch
        checkingUpdates.value = true
        val app = getApplication<Application>()
        var failed = 0
        var fresh = 0
        for (base in ModelCatalog.base(app)) {
            if (base.tier == "custom") continue                          // user-added models are not tracked
            if (Generations.pending(app, base) != null) continue        // already updating
            val spec = Generations.effective(app, base)
            if (store.state(spec) != InstallState.Installed) continue
            try {
                // a newer generation wins over a revision of the current file
                val gen = Generations.findNewer(app, base, spec)
                val offer = if (gen != null) {
                    val newName = Generations.candidateSpec(base, gen).name
                    Generations.Offer(gen, "新しい世代: ${spec.name} → $newName")
                } else store.checkUpdate(spec)?.let { (desc, tag) ->
                    val label = Generations.label(spec.repo) + "+" + tag
                    if (Generations.isRejected(app, label)) null
                    else Generations.Offer(Generations.Candidate(spec.repo, label), desc)
                }
                val before = Generations.offer(app, base)
                Generations.setOffer(app, base, offer)
                if (offer != null && offer.candidate.label != before?.candidate?.label) fresh++
            } catch (e: Exception) { failed++ }
        }
        loadOffers()
        val now = System.currentTimeMillis()
        app.getSharedPreferences("models", 0).edit().putLong("lastUpdateCheck", now).apply()
        lastUpdateCheck.value = now
        checkingUpdates.value = false
        if (manual || fresh > 0) {
            message.value = when {
                offers.value.isNotEmpty() -> "モデルの更新が${offers.value.size}件あります（モデル画面で更新するか選べます）"
                failed > 0 -> "更新を確認できないモデルがありました（通信を確認してください）"
                else -> "すべてのモデルは最新です"
            }
        }
    }

    private fun loadOffers() {
        val app = getApplication<Application>()
        offers.value = ModelCatalog.base(app).mapNotNull { b -> Generations.offer(app, b)?.let { b.id to it.description } }.toMap()
    }

    /** User accepted: download next to the current files, which keep serving deliberations meanwhile. */
    fun acceptOffer(spec: ModelSpec) = viewModelScope.launch {
        val app = getApplication<Application>()
        val base = ModelCatalog.base(app).firstOrNull { it.id == spec.baseId } ?: return@launch
        val o = Generations.offer(app, base) ?: return@launch
        try {
            Generations.setPending(app, base, o.candidate)
            Generations.setOffer(app, base, null)
            Generations.pending(app, base)?.let { store.download(it, settings.value.wifiOnly) }
            message.value = "更新をダウンロードします。完了して動作確認できるまで、今のモデルで審議します"
        } catch (e: Exception) {
            Generations.reject(app, base)
            message.value = "${spec.name}: ${e.message}"
        }
        loadOffers()
        refreshInstall()
    }

    fun declineOffer(spec: ModelSpec) {
        val app = getApplication<Application>()
        ModelCatalog.base(app).firstOrNull { it.id == spec.baseId }?.let { Generations.decline(app, it) }
        loadOffers()
    }

    private fun maybeAutoCheckUpdates() {
        if (!settings.value.autoUpdateCheck) return
        if (System.currentTimeMillis() - lastUpdateCheck.value < 7L * 86_400_000L) return
        val cm = getApplication<Application>().getSystemService(android.net.ConnectivityManager::class.java)
        val caps = cm?.getNetworkCapabilities(cm.activeNetwork) ?: return
        val unmetered = caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
        if (unmetered) checkUpdates(manual = false)
    }

    fun delete(spec: ModelSpec) {
        store.delete(spec)
        refreshInstall()
    }

    /** Runs a recorded question again with the current models and settings. */
    fun redeliberate(h: HistoryEntry) {
        if (deliberation.state.value.phase == Phase.RUNNING) {
            message.value = "審議中です。終わってから再審議してください"
            return
        }
        requestTab.value = 0
        start(h.question, redo = true)
    }

    /** Starts a deliberation; false when it could not start (the input is then kept). */
    fun start(question: String, redo: Boolean = false): Boolean {
        if (deliberation.state.value.phase == Phase.RUNNING) return false
        if (verifying) {
            message.value = "更新したモデルを動作確認中です。数十秒後にもう一度押してください"
            return false
        }
        val q = question.trim().ifEmpty { return false }
        // run with whichever sages are ready; the rest abstain
        if (deliberation.missingJudges().size == 3) {
            message.value = "審議できる賢者がいません。モデル画面で1体以上ダウンロードするか、クラウドAIを選んでAPIキーを登録してください"
            return false
        }
        val app = getApplication<Application>()
        val runSettings = settings.value
        val hist = historyStore
        // runs in the process-wide session scope (not the screen's), kept alive by a foreground service
        runJob = jp.pocketmagi.engine.Session.scope.launch {
            jp.pocketmagi.engine.Session.markRunning(app, q, System.currentTimeMillis())
            val prior = if (redo) null else followUp.value
            deliberation.run(q, runSettings, prior)
            jp.pocketmagi.engine.Session.clearRunning(app)
            val st = deliberation.state.value
            // a finished result can be continued by the next question
            if (st.phase == Phase.DONE && st.integrated.isNotBlank()) followUp.value = jp.pocketmagi.engine.FollowUp.of(st, prior)
            if (st.phase == Phase.DONE && runSettings.historyEnabled) {
                val label = when { redo -> "再審議"; prior != null -> "追加審議"; else -> "" }
                withContext(Dispatchers.IO) {
                    hist.add(st, label, runSettings.historyMaxCount, runSettings.historyMaxDays)
                }
                jp.pocketmagi.engine.Session.historyVersion.value++
            }
        }
        jp.pocketmagi.engine.DeliberationService.start(app)
        return true
    }

    fun cancel() = deliberation.cancel()

    fun clearFollowUp() { followUp.value = null }

    /** Text shared from another app, waiting to be put into the question field. */
    val shared = MutableStateFlow<String?>(null)

    fun shareIn(text: String) {
        shared.value = text.take(4000)
        requestTab.value = 0
    }

    // ---- user-added local models (Hugging Face repository or a .gguf file on the phone)

    val importingModel = MutableStateFlow<String?>(null)

    private fun selectFor(role: SageRole, id: String) = updateSettings(settings.value.copy(
        roleChoice = settings.value.roleChoice + (role.name to id),
        roleEngine = settings.value.roleEngine + (role.name to "local"),
    ))

    fun addHfModel(role: SageRole, repo: String, name: String, quant: String, nCtx: Int) = viewModelScope.launch {
        val r = repo.trim().removePrefix("https://huggingface.co/").trim('/')
        if (!Regex("^[\\w.-]+/[\\w.-]+$").matches(r)) { message.value = "「作者/リポジトリ名」の形で入力してください"; return@launch }
        val q = quant.split(',', '、', ' ').map { it.trim() }.filter { it.isNotEmpty() }.ifEmpty { listOf("Q4_K_M", "Q4_0") }
        val spec = jp.pocketmagi.model.CustomModels.spec(jp.pocketmagi.model.CustomModels.newId(), role,
            name.trim().ifBlank { r.substringAfter('/').replace(Regex("(?i)[-_]gguf$"), "") }, r, q, nCtx)
        try {
            withContext(Dispatchers.IO) { jp.pocketmagi.model.HfResolver.resolve(spec) }   // the repository must hold a matching GGUF
            jp.pocketmagi.model.CustomModels.add(getApplication(), spec)
            selectFor(role, spec.id)
            refreshInstall()
            message.value = "「${spec.name}」を ${role.code} に追加しました。ダウンロードすると使えます"
        } catch (e: Exception) {
            message.value = "追加できません: ${e.message}"
        }
    }

    fun addFileModel(role: SageRole, uri: Uri, name: String, nCtx: Int) = viewModelScope.launch {
        val app = getApplication<Application>()
        val display = name.trim().ifBlank { uri.lastPathSegment?.substringAfterLast('/')?.substringBeforeLast('.') ?: "追加モデル" }
        val spec = jp.pocketmagi.model.CustomModels.spec(jp.pocketmagi.model.CustomModels.newId(), role, display,
            jp.pocketmagi.model.CustomModels.LOCAL, emptyList(), nCtx)
        importingModel.value = "取り込み中…"
        try {
            withContext(Dispatchers.IO) {
                store.importLocal(spec, uri) { done -> importingModel.value = "取り込み中… %.2f GB".format(done / 1e9) }
            }
            jp.pocketmagi.model.CustomModels.add(app, spec)
            selectFor(role, spec.id)
            refreshInstall()
            message.value = "「${spec.name}」を ${role.code} に追加しました"
        } catch (e: Exception) {
            store.delete(spec)
            message.value = "取り込めません: ${e.message}"
        } finally { importingModel.value = null }
    }

    fun removeCustomModel(spec: ModelSpec) {
        store.delete(spec)
        jp.pocketmagi.model.CustomModels.remove(getApplication(), spec.id)
        val s = settings.value
        if (s.roleChoice[spec.role.name] == spec.id) updateSettings(s.copy(roleChoice = s.roleChoice + (spec.role.name to "auto")))
        refreshInstall()
        message.value = "「${spec.name}」を一覧から外しました"
    }

    // ---- updates of the app itself (GitHub Releases)

    val appRelease = MutableStateFlow<jp.pocketmagi.data.AppUpdate.Release?>(null)
    val appUpdateStatus = MutableStateFlow("")

    fun saveGithubToken(t: String) { apiKeys.set("github", t); checkAppUpdate(true) }
    fun hasGithubToken() = apiKeys.has("github")

    fun checkAppUpdate(manual: Boolean = true) = viewModelScope.launch {
        val app = getApplication<Application>()
        if (manual) appUpdateStatus.value = "確認中…"
        try {
            val r = withContext(Dispatchers.IO) { jp.pocketmagi.data.AppUpdate.latest(apiKeys.get("github")) }
            app.getSharedPreferences("models", 0).edit().putLong("lastAppCheck", System.currentTimeMillis()).apply()
            val cur = jp.pocketmagi.data.AppUpdate.currentBuild(app)
            if (r != null && r.build > cur) {
                appRelease.value = r
                appUpdateStatus.value = "新しい版があります: 0.1.${r.build}（今は 0.1.$cur）"
                if (!manual) message.value = "PersonalMAGI 0.1.${r.build} が出ています（設定 → 診断・このアプリについて）"
            } else {
                appRelease.value = null
                appUpdateStatus.value = "最新です（0.1.$cur）"
            }
        } catch (e: Exception) {
            appUpdateStatus.value = "確認できません: ${e.message}"
        }
    }

    fun installAppUpdate() = viewModelScope.launch {
        val r = appRelease.value ?: return@launch
        val app = getApplication<Application>()
        try {
            val f = withContext(Dispatchers.IO) {
                jp.pocketmagi.data.AppUpdate.download(app, r, apiKeys.get("github")) { d, t ->
                    appUpdateStatus.value = "ダウンロード中 %.1f / %.1f MB".format(d / 1e6, t / 1e6)
                }
            }
            appUpdateStatus.value = "インストール画面を開きます"
            if (!jp.pocketmagi.data.AppUpdate.install(app, f))
                appUpdateStatus.value = "「この提供元のアプリを許可」をオンにしてから、もう一度押してください"
        } catch (e: Exception) {
            appUpdateStatus.value = "ダウンロードできません: ${e.message}"
        }
    }

    // ---- scheduled deliberations

    val schedules = MutableStateFlow(jp.pocketmagi.data.Schedules.list(app))

    fun putSchedule(s: jp.pocketmagi.data.Schedule) {
        val app = getApplication<Application>()
        jp.pocketmagi.data.Schedules.put(app, s)
        schedules.value = jp.pocketmagi.data.Schedules.list(app)
        if (s.enabled) {
            val at = java.text.SimpleDateFormat("M/d HH:mm", java.util.Locale.JAPAN).format(java.util.Date(jp.pocketmagi.data.Schedules.next(s)))
            message.value = "次回は $at に審議します"
        }
    }

    fun removeSchedule(id: Int) {
        jp.pocketmagi.data.Schedules.remove(getApplication(), id)
        schedules.value = jp.pocketmagi.data.Schedules.list(getApplication())
    }

    // ---- presets

    val presets = MutableStateFlow(jp.pocketmagi.data.Presets.list(app).map { it.first })

    private val ROLES = listOf(SageRole.PROPOSER, SageRole.MELCHIOR, SageRole.BALTHASAR, SageRole.CASPER, SageRole.INTEGRATOR)

    /** Built-in presets, worked out for this phone and the keys registered. */
    fun applyBuiltInPreset(kind: String) {
        val s = settings.value
        val app = getApplication<Application>()
        val next = when (kind) {
            // the standard local model everywhere: fastest on the phone
            "fast" -> s.copy(
                roleEngine = ROLES.associate { it.name to "local" },
                roleChoice = s.roleChoice + ROLES.mapNotNull { r ->
                    ModelCatalog.forRole(app, r).firstOrNull { it.isDefault }?.let { r.name to it.baseId } },
                rebuttal = false, webEnabled = true)
            // the recommended cloud AI for each role where its key is registered, with the rebuttal round
            "accurate" -> s.copy(
                roleEngine = ROLES.associate { r ->
                    val p = jp.pocketmagi.engine.Cloud.ADVICE[r]?.provider?.takeIf { hasKey(it) }
                        ?: jp.pocketmagi.engine.CloudProvider.entries.firstOrNull { hasKey(it) }
                    r.name to (p?.id ?: "local") },
                roleCloudModel = ROLES.associate { it.name to "" },
                rebuttal = true, webEnabled = true)
            // nothing leaves the phone
            "offline" -> s.copy(roleEngine = ROLES.associate { it.name to "local" },
                roleChoice = s.roleChoice + ROLES.associate { it.name to "auto" }, webEnabled = false)
            else -> return
        }
        updateSettings(next)
        message.value = when (kind) {
            "fast" -> "速さ重視: すべて端末内の標準モデルにしました"
            "accurate" -> if (jp.pocketmagi.engine.CloudProvider.entries.none { hasKey(it) }) "精度重視: クラウドAIのキーがないため端末内のまま、反論ラウンドを有効にしました"
                else "精度重視: 役割ごとのおすすめクラウドAIと反論ラウンドにしました"
            else -> "オフライン: 端末内のモデルだけで、Web調査なしにしました"
        }
    }

    fun applyPreset(name: String) {
        val o = jp.pocketmagi.data.Presets.list(getApplication()).firstOrNull { it.first == name }?.second ?: return
        updateSettings(jp.pocketmagi.data.Presets.apply(settings.value, o))
        message.value = "プリセット「$name」にしました"
    }

    fun savePreset(name: String) {
        if (name.isBlank()) return
        jp.pocketmagi.data.Presets.save(getApplication(), name.trim(), settings.value)
        presets.value = jp.pocketmagi.data.Presets.list(getApplication()).map { it.first }
        message.value = "今の構成を「${name.trim()}」として保存しました"
    }

    fun deletePreset(name: String) {
        jp.pocketmagi.data.Presets.delete(getApplication(), name)
        presets.value = jp.pocketmagi.data.Presets.list(getApplication()).map { it.first }
    }

    // ---- colour schemes (own, imported, VS Code themes)

    val themeResults = MutableStateFlow<List<jp.pocketmagi.ui.ThemeExt>>(emptyList())
    val themeSearching = MutableStateFlow(false)

    fun searchThemes(q: String) = viewModelScope.launch {
        themeSearching.value = true
        try {
            themeResults.value = withContext(Dispatchers.IO) { jp.pocketmagi.ui.CustomPalettes.search(q) }
            if (themeResults.value.isEmpty()) message.value = "見つかりませんでした"
        } catch (e: Exception) {
            message.value = "検索できません: ${e.message}"
        } finally { themeSearching.value = false }
    }

    private fun addPalettes(ps: List<jp.pocketmagi.ui.Palette>) {
        val app = getApplication<Application>()
        ps.forEach { jp.pocketmagi.ui.CustomPalettes.put(app, it) }
        ps.firstOrNull()?.let { updateSettings(settings.value.copy(theme = it.id)) }
        message.value = if (ps.size == 1) "「${ps[0].label}」を取り込みました" else "${ps.size}件のカラースキームを取り込みました（最初の「${ps[0].label}」を適用）"
    }

    fun importThemeExt(ext: jp.pocketmagi.ui.ThemeExt) = viewModelScope.launch {
        message.value = "「${ext.displayName}」を取り込み中…"
        try {
            addPalettes(withContext(Dispatchers.IO) { jp.pocketmagi.ui.CustomPalettes.fromExtension(ext) })
        } catch (e: Exception) {
            message.value = "取り込めません: ${e.message}"
        }
    }

    fun importPaletteFile(uri: Uri) = viewModelScope.launch {
        try {
            val app = getApplication<Application>()
            val ps = withContext(Dispatchers.IO) {
                val bytes = app.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: error("読み込めません")
                val name = uri.lastPathSegment?.substringAfterLast('/')?.substringBeforeLast('.') ?: "テーマ"
                if (bytes.size > 2 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte()) jp.pocketmagi.ui.CustomPalettes.fromVsix(bytes)
                else jp.pocketmagi.ui.CustomPalettes.fromThemeJson(bytes.toString(Charsets.UTF_8), name)
            }
            if (ps.isEmpty()) error("カラーが見つかりません")
            addPalettes(ps)
        } catch (e: Exception) {
            message.value = "取り込めません: ${e.message}"
        }
    }

    fun savePalette(p: jp.pocketmagi.ui.Palette) {
        jp.pocketmagi.ui.CustomPalettes.put(getApplication(), p)
        updateSettings(settings.value.copy(theme = p.id))
        message.value = "「${p.label}」を保存しました"
    }

    fun deletePalette(id: String) {
        jp.pocketmagi.ui.CustomPalettes.remove(getApplication(), id)
        if (settings.value.theme == id) updateSettings(settings.value.copy(theme = "magi"))
    }

    fun exportPalette(p: jp.pocketmagi.ui.Palette, uri: Uri) = viewModelScope.launch {
        try {
            val app = getApplication<Application>()
            withContext(Dispatchers.IO) {
                app.contentResolver.openOutputStream(uri, "wt")?.use { it.write(jp.pocketmagi.ui.CustomPalettes.toJson(p).toString(2).toByteArray()) }
            }
            message.value = "「${p.label}」を書き出しました"
        } catch (e: Exception) { message.value = "書き出しに失敗: ${e.message}" }
    }

    // ---- music

    fun pickSound(kind: String, uri: Uri) = viewModelScope.launch {
        try {
            val name = jp.pocketmagi.data.Sounds.import(getApplication(), uri, kind)
            val s = settings.value
            updateSettings(if (kind == jp.pocketmagi.data.Sounds.RUNNING) s.copy(musicRunningName = name, musicRunningOn = true)
                else s.copy(musicDoneName = name, musicDoneOn = true))
            message.value = "「$name」を設定しました"
        } catch (e: Exception) {
            message.value = "音楽を設定できません: ${e.message}"
        }
    }

    fun removeSound(kind: String) {
        jp.pocketmagi.data.Sounds.remove(getApplication(), kind)
        val s = settings.value
        updateSettings(if (kind == jp.pocketmagi.data.Sounds.RUNNING) s.copy(musicRunningName = "", musicRunningOn = false)
            else s.copy(musicDoneName = "", musicDoneOn = false))
    }

    fun previewSound(kind: String) = jp.pocketmagi.data.Sounds.preview(getApplication(), kind)
    fun stopSound() = jp.pocketmagi.data.Sounds.stopAll()

    // ---- export / import of the settings (and, with a password, the API keys)

    fun exportSettings(uri: Uri, password: String?, withHistory: Boolean) = viewModelScope.launch {
        try {
            val app = getApplication<Application>()
            val json = withContext(Dispatchers.IO) {
                jp.pocketmagi.data.Backup.export(app, apiKeys, password, if (withHistory) historyStore.raw() else null)
            }
            withContext(Dispatchers.IO) {
                app.contentResolver.openOutputStream(uri, "wt")?.use { it.write(json.toByteArray(Charsets.UTF_8)) } ?: error("書き込めません")
            }
            message.value = "設定" + (if (withHistory) "・審議記録" else "") +
                (if (password.isNullOrEmpty()) "を書き出しました（APIキーは含みません）" else "・APIキー（暗号化）を書き出しました")
        } catch (e: Exception) {
            message.value = "書き出しに失敗: ${e.message}"
        }
    }

    /** A picked settings file waiting for the password of its API keys. */
    val pendingImport = MutableStateFlow<String?>(null)

    fun readImport(uri: Uri) = viewModelScope.launch {
        try {
            val app = getApplication<Application>()
            val json = withContext(Dispatchers.IO) {
                app.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) } ?: error("読み込めません")
            }
            if (jp.pocketmagi.data.Backup.hasKeys(json)) pendingImport.value = json else applyImport(json, null)
        } catch (e: Exception) {
            message.value = "読み込みに失敗: ${e.message}"
        }
    }

    fun applyImport(json: String, password: String?) = viewModelScope.launch {
        try {
            val app = getApplication<Application>()
            val r = withContext(Dispatchers.IO) { jp.pocketmagi.data.Backup.import(app, json, apiKeys, password, historyStore) }
            pendingImport.value = null
            jp.pocketmagi.ui.CustomPalettes.load(app)
            val s = settingsStore.load()
            settings.value = s
            jp.pocketmagi.model.FastDownloader.connections = s.dlConnections
            cloudModels.value = emptyMap(); cloudStatus.value = emptyMap()
            loadCloudModels()
            if (r.records > 0) {
                withContext(Dispatchers.IO) {
                    historyStore.prune(s.historyMaxCount, s.historyMaxDays)
                    history.value = historyStore.load()
                }
            }
            message.value = "設定を読み込みました" + listOfNotNull(
                r.keys.takeIf { it > 0 }?.let { "APIキー $it 件" },
                r.records.takeIf { it > 0 }?.let { "審議記録 $it 件を追加" },
            ).joinToString("・").let { if (it.isEmpty()) "" else "（$it）" }
        } catch (e: jp.pocketmagi.data.Backup.WrongPassword) {
            message.value = "パスワードが違います"
        } catch (e: Exception) {
            pendingImport.value = null
            message.value = "読み込みに失敗: ${e.message}"
        }
    }

    /** Continue a recorded deliberation: its result becomes the context of the next question. */
    fun followUpFrom(h: HistoryEntry) {
        if (deliberation.state.value.phase == Phase.RUNNING) { message.value = "審議中です。終わってから追加審議してください"; return }
        val result = h.integrated.substringBefore("\n\n各賢者の判断:").substringBefore("\n\n出典（Web）").substringBefore("\n\nクラウドAIの使用量").trim()
        followUp.value = jp.pocketmagi.engine.FollowUp(h.question, "質問: ${h.question}\n$result".take(900))
        requestTab.value = 0
        message.value = "「${h.question.take(20)}」の結果を踏まえて追加審議できます"
    }

    fun newSession() {
        if (deliberation.state.value.phase == Phase.RUNNING) return
        deliberation.reset()
        followUp.value = null
    }

    fun deleteHistory(time: Long) = viewModelScope.launch(Dispatchers.IO) {
        historyStore.delete(time)
        history.value = historyStore.load()
    }

    fun deleteHistoryMany(times: Set<Long>) = viewModelScope.launch(Dispatchers.IO) {
        historyStore.deleteMany(times)
        history.value = historyStore.load()
    }

    fun toggleFavorite(h: HistoryEntry) = viewModelScope.launch(Dispatchers.IO) {
        historyStore.setFavorite(h.time, !h.favorite)
        history.value = historyStore.load()
    }

    /** A record as plain text for sharing (question, result, each sage, sources). */
    fun shareText(h: HistoryEntry): String = buildString {
        append("【審議事項】\n").append(h.question).append("\n\n")
        append("【審議結果】\n").append(h.integrated.substringBefore("\n\n各賢者の判断:").substringBefore("\n\n出典（Web）").trim()).append("\n\n")
        if (h.opinions.isNotEmpty()) {
            append("【各賢者の判断】\n")
            h.opinions.forEach { (c, v, t) -> append("・").append(c).append(" ").append(v).append(": ").append(jp.pocketmagi.engine.Prompts.answerOf(t).ifBlank { t }.trim()).append("\n") }
            append("\n")
        }
        if (h.sources.isNotEmpty()) {
            append("【出典】\n")
            h.sources.forEachIndexed { i, (t, u) -> append("[${i + 1}] ").append(t).append(" ").append(u).append("\n") }
        }
        append("\n— PersonalMAGI")
    }

    fun clearHistory() = viewModelScope.launch(Dispatchers.IO) {
        historyStore.clear()
        history.value = emptyList()
    }

    fun detectGpu() = viewModelScope.launch {
        unusableGpus.value = try { LlamaBridge.unusableGpus(getApplication()) } catch (e: Throwable) { "" }
        gpuDevices.value = try { LlamaBridge.gpuDevices(getApplication()) } catch (e: Throwable) { "" }
    }

    fun dismissCrash() { crashReport.value = null }

    // ---- cloud AIs

    /** Whether a cloud AI can be chosen: key registered (the custom API: its URL is set). */
    fun hasKey(p: jp.pocketmagi.engine.CloudProvider) = deliberation.cloudReady(p)

    /** Saves (or with a blank value removes) a key, then checks it by listing the provider's models. */
    fun saveKey(p: jp.pocketmagi.engine.CloudProvider, key: String) {
        apiKeys.set(p.id, key)
        if (key.isBlank()) {
            cloudStatus.value = cloudStatus.value + (p.id to "キー未登録")
            cloudModels.value = cloudModels.value - p.id
        } else testKey(p)
    }

    fun testKey(p: jp.pocketmagi.engine.CloudProvider) = viewModelScope.launch {
        val key = apiKeys.get(p.id) ?: if (p == jp.pocketmagi.engine.CloudProvider.CUSTOM) "" else null
        if (p == jp.pocketmagi.engine.CloudProvider.CUSTOM && settings.value.customApiBase.isBlank()) {
            cloudStatus.value = cloudStatus.value + (p.id to "URL未設定"); return@launch
        }
        if (key == null || (key.isBlank() && p != jp.pocketmagi.engine.CloudProvider.CUSTOM)) {
            cloudStatus.value = cloudStatus.value + (p.id to "キー未登録"); return@launch
        }
        cloudStatus.value = cloudStatus.value + (p.id to "確認中…")
        try {
            val list = jp.pocketmagi.engine.Cloud.listModels(p, key)
            cloudModels.value = cloudModels.value + (p.id to list)
            val rec = jp.pocketmagi.engine.Cloud.recommendedModel(p, list)
            cloudStatus.value = cloudStatus.value + (p.id to "接続OK（${list.size}件" + (rec?.let { "、おすすめ $it" } ?: "") + "）")
        } catch (e: Exception) {
            cloudStatus.value = cloudStatus.value + (p.id to "接続できません: ${e.message?.take(120)}")
        }
    }

    /** Model lists for providers that have a key, so the pickers can show them. */
    fun loadCloudModels() {
        for (p in jp.pocketmagi.engine.CloudProvider.entries) if (hasKey(p) && cloudModels.value[p.id] == null) testKey(p)
    }

    fun showDiagnostics() = viewModelScope.launch(Dispatchers.IO) { diagnostics.value = crashGuard.currentLog() }

    fun hideDiagnostics() { diagnostics.value = null }

    fun loadSystemInfo() = viewModelScope.launch {
        // the engine runs one job at a time: during a deliberation this waits until it ends
        systemInfo.value = if (deliberation.state.value.phase == Phase.RUNNING) "審議中です。終わり次第ここに表示します…" else "取得中…"
        systemInfo.value = try { LlamaBridge.systemInfo(getApplication()).ifBlank { "（エンジンから情報が返りませんでした）" } } catch (e: Throwable) { "取得失敗: ${e.message}" }
    }

    val state: StateFlow<jp.pocketmagi.engine.DeliberationState> get() = deliberation.state
}
