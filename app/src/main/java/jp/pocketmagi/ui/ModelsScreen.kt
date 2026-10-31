package jp.pocketmagi.ui

import androidx.compose.foundation.background
import androidx.compose.material3.FilterChip
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import jp.pocketmagi.MagiViewModel
import jp.pocketmagi.model.InstallState
import jp.pocketmagi.model.ModelSpec
import jp.pocketmagi.model.SageRole

@Composable
fun ModelsScreen(vm: MagiViewModel) {
    val states by vm.installStates.collectAsState()
    val settings by vm.settings.collectAsState()
    val offers by vm.offers.collectAsState()
    val checking by vm.checkingUpdates.collectAsState()
    val lastCheck by vm.lastUpdateCheck.collectAsState()
    val genNotes by vm.generationNotes.collectAsState()
    DisposableEffect(Unit) {
        vm.startPolling()
        onDispose { vm.stopPolling() }
    }
    val cloudModels by vm.cloudModels.collectAsState()
    androidx.compose.runtime.LaunchedEffect(Unit) { vm.loadCloudModels() }
    val order = listOf(SageRole.PROPOSER, SageRole.MELCHIOR, SageRole.BALTHASAR, SageRole.CASPER)
    val specs = vm.catalog.sortedBy { order.indexOf(it.role) }
    val usedGb = vm.store.usedBytes() / 1e9

    LazyColumn(
        Modifier.fillMaxSize().background(MagiColors.Bg).padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Spacer(Modifier.height(12.dp))
            Text("MODELS", style = MaterialTheme.typography.headlineLarge, color = MagiColors.Amber)
            Text(
                "使用中 %.1f GB".format(usedGb),
                style = MaterialTheme.typography.bodySmall, color = MagiColors.TextDim,
            )
            Spacer(Modifier.height(8.dp))
            Button(onClick = { vm.downloadAllNeeded() }, shape = CutCornerShape(8.dp), modifier = Modifier.fillMaxWidth()) {
                Text("必要なモデルをまとめてダウンロード")
            }
            Text(
                if (settings.wifiOnly) "Wi-Fi接続時のみダウンロードします（設定で変更可）" else "モバイル回線でもダウンロードします",
                style = MaterialTheme.typography.labelSmall, color = MagiColors.TextDim,
            )
            Spacer(Modifier.height(6.dp))
            PresetRow(vm)
            AddModelButton(vm)
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = { vm.checkUpdates() }, enabled = !checking, shape = CutCornerShape(6.dp)) {
                    Text(if (checking) "確認中…" else "更新を確認")
                }
                Text(
                    "  最終確認: " + if (lastCheck == 0L) "未確認" else
                        java.text.SimpleDateFormat("M/d HH:mm", java.util.Locale.JAPAN).format(java.util.Date(lastCheck)),
                    style = MaterialTheme.typography.labelSmall, color = MagiColors.TextDim,
                )
            }
        }
        for (role in order + SageRole.INTEGRATOR) {
            val group = specs.filter { it.role == role }
            // one picker per role, on top: the phone's models and the cloud AIs with a registered key side by side
            item(key = "ai-${role.name}") { AiChoice(vm, role, group, settings, cloudModels) }
            if (group.isEmpty()) continue
            items(group, key = { it.id }) { spec ->
                ModelRow(
                    spec = spec,
                    state = states[spec.id] ?: InstallState.Missing,
                    onDownload = { vm.download(spec) },
                    onDelete = { vm.delete(spec) },
                    update = offers[spec.baseId],
                    onUpdate = { vm.acceptOffer(spec) },
                    onDecline = { vm.declineOffer(spec) },
                    generationNote = genNotes[spec.baseId],
                    inUse = vm.deliberation.engineOf(role, settings) == null && vm.deliberation.specFor(role, settings)?.id == spec.id,
                    onRemove = if (spec.tier == "custom") ({ vm.removeCustomModel(spec) }) else null,
                )
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
private fun ModelRow(
    spec: ModelSpec,
    state: InstallState,
    onDownload: () -> Unit,
    onDelete: () -> Unit,
    update: String? = null,
    onUpdate: () -> Unit = {},
    onDecline: () -> Unit = {},
    generationNote: String? = null,
    inUse: Boolean = false,
    onRemove: (() -> Unit)? = null,
) {
    Column(
        Modifier.fillMaxWidth().border(1.dp, if (state == InstallState.Installed) MagiColors.Approve.copy(alpha = 0.6f) else MagiColors.AmberDim)
            .padding(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(when (spec.role) { SageRole.PROPOSER -> "PROPOSER（仮説の作成）"; else -> spec.role.code }, style = MaterialTheme.typography.labelMedium, color = MagiColors.Amber)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(spec.name, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f, fill = false))
                    if (spec.tier == "high") Text("  高性能", style = MaterialTheme.typography.labelSmall, color = MagiColors.Cyan)
                    if (spec.tier == "custom") Text("  追加", style = MaterialTheme.typography.labelSmall, color = MagiColors.Cyan)
                    if (inUse) Text("  使用中", style = MaterialTheme.typography.labelSmall, color = MagiColors.Approve)
                }
                Text(
                    if (spec.tier == "custom") "ユーザー追加・" + (if (jp.pocketmagi.model.CustomModels.isLocalFile(spec)) "端末のファイル" else spec.repo)
                    else "${spec.vendor}・${if (spec.madeInJapan) "日本製" else "海外製"}・${spec.license}・約${spec.approxGb}GB",
                    style = MaterialTheme.typography.labelSmall, color = MagiColors.TextDim,
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        when (state) {
            is InstallState.Missing -> OutlinedButton(onClick = onDownload, shape = CutCornerShape(6.dp)) { Text("ダウンロード") }
            is InstallState.Downloading -> {
                LinearProgressIndicator(progress = { state.fraction }, modifier = Modifier.fillMaxWidth(), color = MagiColors.Amber)
                Text(
                    "%.2f / %.2f GB　%.1f MB/s%s".format(
                        state.doneBytes / 1e9, state.totalBytes / 1e9, state.speed / 1e6,
                        if (state.note.isNotEmpty()) "　${state.note}" else if (state.speed == 0L && state.doneBytes < state.totalBytes) "　待機中" else ""),
                    style = MaterialTheme.typography.labelSmall, color = MagiColors.TextDim,
                )
                OutlinedButton(onClick = onDelete, shape = CutCornerShape(6.dp)) { Text("中止して削除") }
            }
            is InstallState.Installed -> Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (update != null) "更新あり" else "導入済み",
                        color = if (update != null) MagiColors.Pending else MagiColors.Approve,
                        style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f),
                    )
                    OutlinedButton(onClick = onDelete, shape = CutCornerShape(6.dp)) { Text("削除") }
                }
                if (update != null) {
                    Text(update, style = MaterialTheme.typography.labelSmall, color = MagiColors.TextDim)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Button(onClick = onUpdate, shape = CutCornerShape(6.dp)) { Text("更新する") }
                        Spacer(Modifier.width(6.dp))
                        OutlinedButton(onClick = onDecline, shape = CutCornerShape(6.dp)) { Text("見送る") }
                    }
                    Text("ダウンロード中も今のモデルで審議できます", style = MaterialTheme.typography.labelSmall, color = MagiColors.TextDim)
                }
            }
            is InstallState.Failed -> Row(verticalAlignment = Alignment.CenterVertically) {
                Text(state.reason, color = MagiColors.Reject, style = MaterialTheme.typography.labelSmall, modifier = Modifier.weight(1f))
                OutlinedButton(onClick = onDownload, shape = CutCornerShape(6.dp)) { Text("再試行") }
            }
        }
        if (generationNote != null) {
            Spacer(Modifier.height(4.dp))
            Text(generationNote, style = MaterialTheme.typography.labelSmall, color = MagiColors.Cyan)
        }
        onRemove?.let { androidx.compose.material3.TextButton(onClick = it) { Text("一覧から外す", color = MagiColors.Reject) } }
    }
}


/** Which model a sage uses: automatic (by this phone's memory) or a fixed choice. */
/**
 * Which AI runs a role. The phone's models (自動 / 標準 / 高性能) and every cloud AI whose key is
 * registered are offered as equal choices; the recommended cloud AI for the role is marked ★.
 */
@Composable
private fun AiChoice(
    vm: MagiViewModel, role: SageRole, group: List<ModelSpec>,
    settings: jp.pocketmagi.data.MagiSettings, cloudModels: Map<String, List<String>>,
) {
    val provider = jp.pocketmagi.engine.CloudProvider.of(settings.roleEngine[role.name])
    val localChoice = settings.roleChoice[role.name] ?: "auto"
    val advice = jp.pocketmagi.engine.Cloud.ADVICE[role]
    val registered = jp.pocketmagi.engine.CloudProvider.entries.filter { vm.hasKey(it) }
    fun pickLocal(v: String) = vm.updateSettings(settings.copy(
        roleEngine = settings.roleEngine + (role.name to "local"),
        roleChoice = settings.roleChoice + (role.name to v),
    ))
    fun pickCloud(p: jp.pocketmagi.engine.CloudProvider) = vm.updateSettings(settings.copy(
        roleEngine = settings.roleEngine + (role.name to p.id),
        roleCloudModel = settings.roleCloudModel + (role.name to ""),
    ))
    Column(
        Modifier.fillMaxWidth().padding(top = 10.dp).border(1.dp, MagiColors.Amber.copy(alpha = 0.7f)).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            when (role) {
                SageRole.PROPOSER -> "PROPOSER（仮説の作成）が使うAI"
                SageRole.INTEGRATOR -> "審議結果のまとめ役が使うAI"
                else -> "${role.code} が使うAI"
            },
            style = MaterialTheme.typography.titleSmall, color = MagiColors.Amber,
        )
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            when {
                role == SageRole.INTEGRATOR ->
                    FilterChip(selected = provider == null, onClick = { pickLocal("auto") }, label = { Text("端末内") })
                group.size > 1 -> {
                    FilterChip(selected = provider == null && localChoice == "auto", onClick = { pickLocal("auto") }, label = { Text("自動") })
                    group.forEach { g ->
                        FilterChip(selected = provider == null && localChoice == g.baseId, onClick = { pickLocal(g.baseId) },
                            label = { Text(when (g.tier) { "high" -> "高性能"; "custom" -> g.name.take(18); else -> "標準" }) })
                    }
                }
                else -> FilterChip(selected = provider == null, onClick = { pickLocal("auto") }, label = { Text("端末内") })
            }
            registered.forEach { p ->
                FilterChip(selected = provider == p, onClick = { pickCloud(p) },
                    label = { Text(if (advice?.provider == p) "★${p.label}" else p.label) })
            }
            // a cloud AI chosen earlier whose key was removed stays visible, so the choice can be changed
            if (provider != null && provider !in registered) {
                FilterChip(selected = true, onClick = { pickLocal("auto") }, label = { Text("${provider.label}（キー未登録）") })
            }
        }
        advice?.let { a ->
            Text(
                "★おすすめ: ${a.provider.label} — ${a.why}" + if (!vm.hasKey(a.provider)) "（設定タブでAPIキーを登録すると選べます）" else "",
                style = MaterialTheme.typography.labelSmall, color = MagiColors.Cyan,
            )
        }
        if (provider == null) {
            Text(
                when {
                    role == SageRole.INTEGRATOR -> "端末内: 最後に審議した端末内の賢者モデルがまとめます"
                    group.size > 1 -> "この端末のメモリ %.1fGB → 自動では %s（未ダウンロードなら標準で審議）。機種変更しても自動なら端末に合わせて切り替わります"
                        .format(vm.deliberation.deviceRamGb, vm.deliberation.recommended(role)?.name ?: "—") +
                        (vm.deliberation.deviceNote()?.let { "。いまは$it のため標準を使います" } ?: "")
                    else -> "端末内: 下のモデルを使います（通信不要）"
                },
                style = MaterialTheme.typography.labelSmall, color = MagiColors.TextDim,
            )
        } else {
            if (!vm.hasKey(provider)) {
                Text("${provider.label} のAPIキーが未登録です。未登録の間は端末内のモデルが代わりに審議します",
                    style = MaterialTheme.typography.labelSmall, color = MagiColors.Reject)
            }
            val models = cloudModels[provider.id].orEmpty()
            val chosen = settings.roleCloudModel[role.name].orEmpty()
            val rec = jp.pocketmagi.engine.Cloud.recommendedModel(provider, models)
            fun label(m: String) = if (provider == jp.pocketmagi.engine.CloudProvider.PERPLEXITY) jp.pocketmagi.engine.Cloud.effortLabel(m) else m
            Text("${provider.label} のモデル", style = MaterialTheme.typography.labelSmall, color = MagiColors.TextDim)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(selected = chosen.isBlank(), onClick = {
                    vm.updateSettings(settings.copy(roleCloudModel = settings.roleCloudModel + (role.name to "")))
                }, label = { Text("おすすめ" + (rec?.let { "（${label(it)}）" } ?: "")) })
                models.take(15).forEach { m ->
                    FilterChip(selected = chosen == m, onClick = {
                        vm.updateSettings(settings.copy(roleCloudModel = settings.roleCloudModel + (role.name to m)))
                    }, label = { Text(label(m)) })
                }
            }
            if (models.isEmpty() && vm.hasKey(provider)) {
                Text("モデル一覧を取得中…（取得できないときは設定タブの「接続テスト」）", style = MaterialTheme.typography.labelSmall, color = MagiColors.TextDim)
            }
        }
    }
}

/**
 * Adds any local LLM: a GGUF repository on Hugging Face (downloaded like the built-in models) or a
 * .gguf file already on the phone, for the chosen role. Cloud APIs are added in 設定 → クラウドAI.
 */
@Composable
private fun AddModelButton(vm: MagiViewModel) {
    var open by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    val importing by vm.importingModel.collectAsState()
    Spacer(Modifier.height(6.dp))
    OutlinedButton(onClick = { open = true }, enabled = importing == null, shape = CutCornerShape(6.dp), modifier = Modifier.fillMaxWidth()) {
        Text(importing ?: "＋ LLMを追加（Hugging Face／端末の .gguf）")
    }
    if (!open) return
    var role by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(SageRole.MELCHIOR) }
    var fromFile by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    var repo by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf("") }
    var name by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf("") }
    var quant by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf("Q4_K_M, Q4_0") }
    var ctx by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf("4096") }
    val pick = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) { vm.addFileModel(role, uri, name, ctx.toIntOrNull()?.coerceIn(1024, 32768) ?: 4096); open = false }
    }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = { open = false },
        title = { Text("LLMを追加") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("使う役割", style = MaterialTheme.typography.labelMedium, color = MagiColors.Amber)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(SageRole.PROPOSER, SageRole.MELCHIOR, SageRole.BALTHASAR, SageRole.CASPER).forEach { r ->
                        FilterChip(selected = role == r, onClick = { role = r }, label = { Text(r.code) })
                    }
                }
                Text("入手先", style = MaterialTheme.typography.labelMedium, color = MagiColors.Amber)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(selected = !fromFile, onClick = { fromFile = false }, label = { Text("Hugging Face") })
                    FilterChip(selected = fromFile, onClick = { fromFile = true }, label = { Text("端末の .gguf") })
                }
                if (!fromFile) {
                    androidx.compose.material3.OutlinedTextField(value = repo, onValueChange = { repo = it.trim() }, singleLine = true,
                        label = { Text("リポジトリ（作者/名前）") }, placeholder = { Text("例: mmnga/xxxx-gguf") }, modifier = Modifier.fillMaxWidth())
                    androidx.compose.material3.OutlinedTextField(value = quant, onValueChange = { quant = it }, singleLine = true,
                        label = { Text("量子化の優先順（カンマ区切り）") }, modifier = Modifier.fillMaxWidth())
                }
                androidx.compose.material3.OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true,
                    label = { Text("表示名（空欄なら自動）") }, modifier = Modifier.fillMaxWidth())
                androidx.compose.material3.OutlinedTextField(value = ctx, onValueChange = { ctx = it.filter { c -> c.isDigit() }.take(5) }, singleLine = true,
                    label = { Text("コンテキスト長（トークン）") }, modifier = Modifier.fillMaxWidth())
                Text("llama.cpp で動く GGUF 形式なら追加できます。メモリに収まる大きさ（8GB端末なら 4GB 程度まで）を選んでください。" +
                    "クラウドの API は「設定 → クラウドAI → カスタムAPI」で追加します",
                    style = MaterialTheme.typography.labelSmall, color = MagiColors.TextDim)
            }
        },
        confirmButton = {
            androidx.compose.material3.TextButton(
                enabled = fromFile || repo.contains('/'),
                onClick = {
                    val n = ctx.toIntOrNull()?.coerceIn(1024, 32768) ?: 4096
                    if (fromFile) pick.launch(arrayOf("application/octet-stream", "*/*"))
                    else { vm.addHfModel(role, repo, name, quant, n); open = false }
                },
            ) { Text(if (fromFile) "ファイルを選ぶ" else "追加") }
        },
        dismissButton = { androidx.compose.material3.TextButton(onClick = { open = false }) { Text("やめる") } },
    )
}

/** Presets: built-in configurations and the user's own, applied with one tap. */
@Composable
private fun PresetRow(vm: MagiViewModel) {
    val mine by vm.presets.collectAsState()
    var naming by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    var name by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf("") }
    var removing by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<String?>(null) }
    Spacer(Modifier.height(8.dp))
    Text("プリセット（タップで切り替え）", style = MaterialTheme.typography.labelMedium, color = MagiColors.Amber)
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        FilterChip(selected = false, onClick = { vm.applyBuiltInPreset("fast") }, label = { Text("速さ重視") })
        FilterChip(selected = false, onClick = { vm.applyBuiltInPreset("accurate") }, label = { Text("精度重視") })
        FilterChip(selected = false, onClick = { vm.applyBuiltInPreset("offline") }, label = { Text("オフライン") })
        mine.forEach { n ->
            FilterChip(selected = false, onClick = { vm.applyPreset(n) }, label = { Text(n) },
                trailingIcon = { Text("×", color = MagiColors.TextDim, modifier = Modifier.clickable { removing = n }) })
        }
        androidx.compose.material3.TextButton(onClick = { naming = true; name = "" }) { Text("＋今の構成を保存") }
    }
    if (naming) androidx.compose.material3.AlertDialog(
        onDismissRequest = { naming = false },
        title = { Text("今の構成を保存") },
        text = {
            Column {
                Text("各役割のAI・口調・Web調査・反論ラウンドを保存します", style = MaterialTheme.typography.labelSmall, color = MagiColors.TextDim)
                androidx.compose.material3.OutlinedTextField(value = name, onValueChange = { name = it.take(20) }, singleLine = true, label = { Text("名前") })
            }
        },
        confirmButton = { androidx.compose.material3.TextButton(enabled = name.isNotBlank(), onClick = { vm.savePreset(name); naming = false }) { Text("保存") } },
        dismissButton = { androidx.compose.material3.TextButton(onClick = { naming = false }) { Text("やめる") } },
    )
    removing?.let { n ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { removing = null },
            title = { Text("プリセット「$n」を削除しますか？") },
            confirmButton = { androidx.compose.material3.TextButton(onClick = { vm.deletePreset(n); removing = null }) { Text("削除", color = MagiColors.Reject) } },
            dismissButton = { androidx.compose.material3.TextButton(onClick = { removing = null }) { Text("やめる") } },
        )
    }
}
