package jp.pocketmagi.ui

import androidx.compose.foundation.background
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import jp.pocketmagi.data.UserFont
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import jp.pocketmagi.MagiViewModel
import kotlin.math.roundToInt

@Composable
fun SettingsScreen(vm: MagiViewModel) {
    val s by vm.settings.collectAsState()
    val info by vm.systemInfo.collectAsState()
    val fonts by vm.fonts.collectAsState()
    val gpu by vm.gpuDevices.collectAsState()
    val unusable by vm.unusableGpus.collectAsState()
    val diag by vm.diagnostics.collectAsState()
    diag?.let { LogDialog("診断ログ", "", it) { vm.hideDiagnostics() } }
    LaunchedEffect(Unit) { if (gpu == null) vm.detectGpu() }
    val pickFont = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { vm.importFont(it) }
    }

    // the settings are a menu: each item opens its own page (back returns to the menu)
    var page by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf("") }
    androidx.activity.compose.BackHandler(enabled = page.isNotEmpty()) { page = "" }
    val context = androidx.compose.ui.platform.LocalContext.current
    val ver = remember {
        try {
            val pi = context.packageManager.getPackageInfo(context.packageName, 0)
            "Ver. ${pi.versionName}（build ${androidx.core.content.pm.PackageInfoCompat.getLongVersionCode(pi)}）"
        } catch (e: Exception) { "Ver. 不明" }
    }

    androidx.compose.runtime.key(page) {
    Column(
        Modifier.fillMaxSize().background(MagiColors.Bg).verticalScroll(rememberScrollState()).padding(16.dp),
    ) {
        if (page.isEmpty()) {
            Text("CONFIG", style = MaterialTheme.typography.headlineLarge, color = MagiColors.Amber)
            Spacer(Modifier.height(12.dp))
            SETTINGS_MENU.forEach { (id, title, hint) -> MenuRow(title, hint) { page = id } }
            Spacer(Modifier.height(12.dp))
            Text("PersonalMAGI $ver", style = MaterialTheme.typography.labelSmall, color = MagiColors.TextDim)
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { page = "" }) { Text("← 設定") }
                Text(SETTINGS_MENU.firstOrNull { it.first == page }?.second ?: "", style = MaterialTheme.typography.titleMedium, color = MagiColors.Amber)
            }
            when (page) {
                "style" -> {
        Section("口調")
        Text("3賢者の回答と審議結果の口調です（判定の「承認／否決」はそのまま）", style = MaterialTheme.typography.labelSmall, color = MagiColors.TextDim)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            jp.pocketmagi.engine.Styles.all.forEach { st ->
                FilterChip(selected = s.speechStyle == st.id, onClick = { vm.updateSettings(s.copy(speechStyle = st.id)) }, label = { Text(st.label) })
            }
        }
        jp.pocketmagi.engine.Styles.byId(s.speechStyle).sample.takeIf { it.isNotEmpty() }?.let {
            Text("例: $it", style = MaterialTheme.typography.bodySmall, color = MagiColors.TextDim)
        }

                }
                "look" -> {
        Section("テーマ")
        Palettes.all.forEach { p ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().clickable { vm.updateSettings(s.copy(theme = p.id)) }.padding(vertical = 4.dp),
            ) {
                RadioButton(selected = s.theme == p.id, onClick = { vm.updateSettings(s.copy(theme = p.id)) })
                Text(p.label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                listOf(p.bg, p.accent, p.eye, p.approve, p.reject).forEach { c ->
                    Box(Modifier.padding(start = 3.dp).size(18.dp).background(c).border(1.dp, p.accentDim))
                }
            }
        }

                    CustomPaletteSection(vm, s)
        Section("フォント")
        Text(
            ".ttf / .otf / .ttc、またはそれらを含む .zip を登録できます（1ファイル64MBまで）",
            style = MaterialTheme.typography.labelSmall, color = MagiColors.TextDim,
        )
        OutlinedButton(onClick = { pickFont.launch(arrayOf("*/*")) }, shape = CutCornerShape(6.dp)) { Text("フォントを追加") }
        FontChooser("本文フォント", "システム標準", s.bodyFontId, fonts) { vm.updateSettings(s.copy(bodyFontId = it)) }
        FontChooser("見出し・数値フォント", "等幅（標準）", s.headFontId, fonts) { vm.updateSettings(s.copy(headFontId = it)) }
        fonts.forEach { f ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    f.name, fontFamily = vm.fontStore.family(f.id), style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.weight(1f), maxLines = 1,
                )
                TextButton(onClick = { vm.deleteFont(f.id) }) { Text("削除") }
            }
        }

                }
                "music" -> MusicSection(vm, s)
                "deliberation" -> {
                    Section("反論ラウンド")
                    SwitchRow("反論ラウンド", "最初の判定が割れたとき、少数派の賢者が他の意見を読んで1回だけ考え直します（全員一致なら省略）。端末内のモデルでは1人あたり数分かかります",
                        s.rebuttal) { vm.updateSettings(s.copy(rebuttal = it)) }
                    Section("Web調査")
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 8.dp)) {
            Column(Modifier.weight(1f)) {
                Text("提案役がWebで調べる", style = MaterialTheme.typography.bodyMedium)
                Text("質問ごとに、基本情報（Wikipedia）・最近の報道（Google ニュース）・Web検索、株の質問では株価（Yahoo!ファイナンス）を調べて3賢者に渡します。翻訳・作文・計算などは調べません。オンのときは、質問をもとにした検索語が外部に送られます",
                    style = MaterialTheme.typography.labelSmall, color = MagiColors.TextDim)
            }
            Switch(checked = s.webEnabled, onCheckedChange = { vm.updateSettings(s.copy(webEnabled = it)) })
        }
                    Section("出力の長さ")
        IntSlider("各賢者の最大出力トークン", s.judgeMaxTokens, 96..400, "審議時間に直結します", step = 8) {
            vm.updateSettings(s.copy(judgeMaxTokens = it))
        }
        IntSlider("審議結果の最大出力トークン", s.integrateMaxTokens, 128..600, "", step = 8) {
            vm.updateSettings(s.copy(integrateMaxTokens = it))
        }

                }
                "engine" -> {
                    SwitchRow("端末の状態で自動切替", "「自動」のとき、本体が熱い・電池温度が高い・充電せず残量30%未満のいずれかなら、高性能ではなく標準モデルを使います",
                        s.deviceAware) { vm.updateSettings(s.copy(deviceAware = it)) }
        Section("推論")
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
            Column(Modifier.weight(1f)) {
                Text("GPUを使う（Adreno / OpenCL）", style = MaterialTheme.typography.bodyMedium)
                Text(
                    when {
                        gpu == null -> "GPUを確認中…"
                        gpu!!.isBlank() && unusable.isNotBlank() -> "$unusable は非対応のためCPUで動作します（Adreno 7xx以降のみ対応）"
                        gpu!!.isBlank() -> "使えるGPUが見つかりません。CPUで動作します"
                        else -> "検出: " + gpu
                    },
                    style = MaterialTheme.typography.labelSmall, color = MagiColors.TextDim,
                )
            }
            Switch(checked = s.useGpu, onCheckedChange = { vm.updateSettings(s.copy(useGpu = it)) })
        }
        if (s.gpuDeny.isNotEmpty()) {
            Text("GPUを使わないモデル（GPU処理中に終了したため）", style = MaterialTheme.typography.labelSmall, color = MagiColors.TextDim)
            s.gpuDeny.forEach { m ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(m, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                    TextButton(onClick = { vm.updateSettings(s.copy(gpuDeny = s.gpuDeny - m)) }) { Text("GPUに戻す") }
                }
            }
        }

        IntSlider("CPUスレッド数", s.threads, 2..8, "多くの端末は4が目安。増やすと発熱しやすくなります") {
            vm.updateSettings(s.copy(threads = it))
        }
                    Section("ダウンロード")
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 8.dp)) {
            Column(Modifier.weight(1f)) {
                Text("Wi-Fi接続時のみダウンロード", style = MaterialTheme.typography.bodyMedium)
                Text("標準モデルは合計 約7.6GB、高性能はさらに約18GB", style = MaterialTheme.typography.labelSmall, color = MagiColors.TextDim)
            }
            Switch(checked = s.wifiOnly, onCheckedChange = { vm.updateSettings(s.copy(wifiOnly = it)) })
        }
        IntSlider("ダウンロードの同時接続数", s.dlConnections, 1..8, "多いほど速くなりやすい（回線や配信側の状況による）。不安定なら減らしてください") {
            vm.updateSettings(s.copy(dlConnections = it))
        }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 8.dp)) {
            Column(Modifier.weight(1f)) {
                Text("モデルの更新を自動で確認", style = MaterialTheme.typography.bodyMedium)
                Text("起動時に週1回まで、Wi-Fi接続時のみ。ダウンロードは手動", style = MaterialTheme.typography.labelSmall, color = MagiColors.TextDim)
            }
            Switch(checked = s.autoUpdateCheck, onCheckedChange = { vm.updateSettings(s.copy(autoUpdateCheck = it)) })
        }

                }
                "cloud" -> { CloudKeysSection(vm); CostSection() }
                "history" -> {
        Section("審議記録")
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
            Text("審議結果を記録する", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            Switch(checked = s.historyEnabled, onCheckedChange = { vm.updateSettings(s.copy(historyEnabled = it)) })
        }
        ChoiceRow("保存件数の上限", listOf(20, 50, 100, 300, 0), s.historyMaxCount, { if (it == 0) "無制限" else "${it}件" }) {
            vm.updateSettings(s.copy(historyMaxCount = it))
        }
        ChoiceRow("保存期間", listOf(7, 30, 90, 365, 0), s.historyMaxDays, { if (it == 0) "無期限" else "${it}日" }) {
            vm.updateSettings(s.copy(historyMaxDays = it))
        }
        Text(
            "両方を設定した場合は、両方の条件を満たす記録だけが残ります。上限を変えると古い記録はすぐ削除されます",
            style = MaterialTheme.typography.labelSmall, color = MagiColors.TextDim,
        )

                }
                "backup" -> BackupSection(vm)
                "schedule" -> ScheduleSection(vm)
                "about" -> {
        Spacer(Modifier.height(16.dp))
        Text("推論エンジン情報", style = MaterialTheme.typography.labelMedium, color = MagiColors.Amber)
        TextButton(onClick = { vm.loadSystemInfo() }) { Text("取得する") }
        if (info.isNotBlank()) Text(info, style = MaterialTheme.typography.labelSmall, color = MagiColors.TextDim)
        TextButton(onClick = { vm.showDiagnostics() }) { Text("診断ログを見る") }

        Section("このアプリについて")
        Text("PersonalMAGI $ver", style = MaterialTheme.typography.bodyMedium)
        AppUpdateSection(vm)
                }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
    }
}

/** Items of the settings menu: page id, title, what is inside. */
private val SETTINGS_MENU = listOf(
    Triple("style", "口調", "3賢者と審議結果の話し方"),
    Triple("look", "表示", "テーマ・カラースキーム（取り込み・自作）・フォント"),
    Triple("music", "音楽", "審議中・審議完了で流す曲"),
    Triple("deliberation", "審議", "Web調査、出力の長さ"),
    Triple("engine", "端末内モデル", "GPU・CPUスレッド・ダウンロード・更新確認"),
    Triple("cloud", "クラウドAI", "APIキー・カスタムAPI・費用と上限"),
    Triple("schedule", "定期審議", "決まった時刻に自動で審議して通知"),
    Triple("history", "審議記録", "記録の有無・件数・期間"),
    Triple("backup", "書き出し・読み込み", "設定・審議記録・APIキーの保存と復元"),
    Triple("about", "診断・このアプリについて", "推論エンジン情報・診断ログ・バージョン"),
)

@Composable
private fun MenuRow(title: String, hint: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).border(1.dp, MagiColors.AmberDim.copy(alpha = 0.5f))
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = MagiColors.Text)
            Text(hint, style = MaterialTheme.typography.labelSmall, color = MagiColors.TextDim)
        }
        Text("›", style = MaterialTheme.typography.titleLarge, color = MagiColors.Amber)
    }
    Spacer(Modifier.height(6.dp))
}

/** API keys of the cloud AIs: stored encrypted on this phone, checked by listing the provider's models. */
@Composable
private fun CloudKeysSection(vm: MagiViewModel) {
    val status by vm.cloudStatus.collectAsState()
    val uri = androidx.compose.ui.platform.LocalUriHandler.current
    Section("クラウドAI（APIキー）")
    Text(
        "モデル画面で賢者・提案役・まとめ役にクラウドAIを選ぶと使われます。各社の開発者向けAPIキー（従量課金）が必要で、" +
            "月額プラン（ChatGPT Plus、Claude Pro など）では使えません。クラウドAIを選んだ役割では、質問とWeb調査の資料が各社に送られます。" +
            "キーはこの端末に暗号化して保存し、記録やログには出しません。",
        style = MaterialTheme.typography.labelSmall, color = MagiColors.TextDim,
    )
    jp.pocketmagi.engine.CloudProvider.entries.forEach { p ->
        var text by remember(p) { mutableStateOf("") }
        var has by remember(p) { mutableStateOf(vm.hasKey(p)) }
        Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(p.label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                Text(status[p.id] ?: if (has) "登録済み" else "未登録",
                    style = MaterialTheme.typography.labelSmall, color = if (has) MagiColors.Approve else MagiColors.TextDim)
            }
            Text(p.keyHint, style = MaterialTheme.typography.labelSmall, color = MagiColors.TextDim)
            // the page where the key is issued opens in the browser
            if (p.keyUrl.isNotBlank()) Text(
                "キーの取得: ${p.keyUrl}",
                style = MaterialTheme.typography.labelSmall.copy(textDecoration = androidx.compose.ui.text.style.TextDecoration.Underline),
                color = MagiColors.Cyan,
                modifier = Modifier.clickable { try { uri.openUri(p.keyUrl) } catch (_: Exception) {} }.padding(vertical = 4.dp),
            )
            if (p == jp.pocketmagi.engine.CloudProvider.CUSTOM) CustomApiFields(vm) { has = vm.hasKey(p) }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                androidx.compose.material3.OutlinedTextField(
                    value = text, onValueChange = { text = it.trim() }, singleLine = true,
                    placeholder = { Text(if (has) "••••••••（登録済み）" else "APIキーを貼り付け", color = MagiColors.TextDim) },
                    visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { vm.saveKey(p, text); has = text.isNotBlank() || has; text = "" }, enabled = text.isNotBlank()) { Text("保存") }
            }
            Row {
                TextButton(onClick = { vm.testKey(p) }, enabled = has) { Text("接続テスト") }
                TextButton(onClick = { vm.saveKey(p, ""); has = false }, enabled = has) { Text("削除") }
            }
        }
    }
}

@Composable
private fun IntSlider(label: String, value: Int, range: IntRange, hint: String, step: Int = 1, onChange: (Int) -> Unit) {
    // the number can also be typed; any value inside the range is accepted (not only slider steps)
    var text by remember(value) { mutableStateOf(value.toString()) }
    val focus = LocalFocusManager.current
    fun commit() {
        val v = text.trim().toIntOrNull()?.coerceIn(range.first, range.last) ?: value
        text = v.toString()
        if (v != value) onChange(v)
    }
    Column(Modifier.padding(vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(label, style = MaterialTheme.typography.bodyMedium)
                Text("${range.first}〜${range.last}", style = MaterialTheme.typography.labelSmall, color = MagiColors.TextDim)
            }
            BasicTextField(
                value = text,
                onValueChange = { t -> text = t.filter { it.isDigit() }.take(6) },
                singleLine = true,
                textStyle = MaterialTheme.typography.labelMedium.copy(color = MagiColors.Amber, textAlign = TextAlign.End),
                cursorBrush = SolidColor(MagiColors.Amber),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { commit(); focus.clearFocus() }),
                modifier = Modifier.width(80.dp).border(1.dp, MagiColors.AmberDim)
                    .padding(horizontal = 8.dp, vertical = 6.dp)
                    .onFocusChanged { if (!it.isFocused) commit() },
            )
        }
        val steps = ((range.last - range.first) / step - 1).coerceAtLeast(0)
        Slider(
            value = value.toFloat().coerceIn(range.first.toFloat(), range.last.toFloat()),
            onValueChange = { v -> onChange(((v - range.first) / step).roundToInt() * step + range.first) },
            valueRange = range.first.toFloat()..range.last.toFloat(),
            steps = steps,
            colors = SliderDefaults.colors(thumbColor = MagiColors.Amber, activeTrackColor = MagiColors.Amber),
        )
        if (hint.isNotBlank()) Text(hint, style = MaterialTheme.typography.labelSmall, color = MagiColors.TextDim)
    }
}


@Composable
private fun Section(title: String) {
    Spacer(Modifier.height(18.dp))
    Text(title, style = MaterialTheme.typography.labelMedium, color = MagiColors.Amber)
    Spacer(Modifier.height(4.dp))
}

@Composable
private fun ChoiceRow(label: String, options: List<Int>, value: Int, fmt: (Int) -> String, onPick: (Int) -> Unit) {
    Column(Modifier.padding(vertical = 4.dp)) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            options.forEach { o ->
                FilterChip(selected = value == o, onClick = { onPick(o) }, label = { Text(fmt(o)) })
            }
        }
    }
}

@Composable
private fun FontChooser(label: String, defaultLabel: String, selected: String, fonts: List<UserFont>, onPick: (String) -> Unit) {
    Column(Modifier.padding(vertical = 4.dp)) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FilterChip(selected = selected.isBlank(), onClick = { onPick("") }, label = { Text(defaultLabel) })
            fonts.forEach { f ->
                FilterChip(selected = selected == f.id, onClick = { onPick(f.id) }, label = { Text(f.name, maxLines = 1) })
            }
        }
    }
}

/** Music while deliberating (looped) and on completion (once), each switched on or off. */
@Composable
private fun MusicSection(vm: MagiViewModel, s: jp.pocketmagi.data.MagiSettings) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var pickFor by remember { mutableStateOf("") }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null && pickFor.isNotEmpty()) vm.pickSound(pickFor, uri)
    }
    Section("音楽")
    Text("審議中はくり返し、審議完了では1回流します（中断・エラーのときは流しません）。音量は端末のメディア音量です",
        style = MaterialTheme.typography.labelSmall, color = MagiColors.TextDim)
    listOf(
        Triple(jp.pocketmagi.data.Sounds.RUNNING, "審議中に流す", s.musicRunningOn to s.musicRunningName),
        Triple(jp.pocketmagi.data.Sounds.DONE, "審議完了で流す", s.musicDoneOn to s.musicDoneName),
    ).forEach { (kind, label, state) ->
        val (on, name) = state
        val has = jp.pocketmagi.data.Sounds.has(context, kind)
        Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(label, style = MaterialTheme.typography.bodyMedium)
                    Text(
                        when {
                            has -> "曲: $name"
                            name.isNotBlank() -> "曲: $name（この端末にはファイルがありません。選び直してください）"
                            else -> "曲が未選択です"
                        },
                        style = MaterialTheme.typography.labelSmall, color = MagiColors.TextDim,
                    )
                }
                Switch(checked = on && has, enabled = has, onCheckedChange = {
                    vm.updateSettings(if (kind == jp.pocketmagi.data.Sounds.RUNNING) s.copy(musicRunningOn = it) else s.copy(musicDoneOn = it))
                })
            }
            Row {
                TextButton(onClick = { pickFor = kind; pick.launch(arrayOf("audio/*")) }) { Text(if (has) "曲を変える" else "曲を選ぶ") }
                TextButton(onClick = { vm.previewSound(kind) }, enabled = has) { Text("試聴") }
                TextButton(onClick = { vm.stopSound() }, enabled = has) { Text("停止") }
                TextButton(onClick = { vm.removeSound(kind) }, enabled = has || name.isNotBlank()) { Text("解除") }
            }
        }
    }
}

/** Settings (and the API keys, encrypted with a password) to a file and back. */
@Composable
private fun BackupSection(vm: MagiViewModel) {
    var askExport by remember { mutableStateOf(false) }
    var withKeys by remember { mutableStateOf(true) }
    var withHistory by remember { mutableStateOf(true) }
    var pw by remember { mutableStateOf("") }
    var pw2 by remember { mutableStateOf("") }
    var exportPw by remember { mutableStateOf<String?>(null) }
    val pending by vm.pendingImport.collectAsState()
    var importPw by remember { mutableStateOf("") }
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) vm.exportSettings(uri, exportPw, withHistory)
        exportPw = null
    }
    val open = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.readImport(uri)
    }
    Section("設定の書き出し・読み込み")
    Text("設定と審議記録を1つのファイルに書き出し、機種変更先などで読み込めます（記録は今ある記録に追加されます）。APIキーは、書き出すときに決めたパスワードで暗号化して含められます" +
        "（パスワードはアプリに保存されません）。音楽ファイル・フォント・ダウンロード済みモデルは含みません",
        style = MaterialTheme.typography.labelSmall, color = MagiColors.TextDim)
    Row {
        TextButton(onClick = { askExport = true; pw = ""; pw2 = "" }) { Text("書き出す") }
        TextButton(onClick = { open.launch(arrayOf("application/json", "text/plain", "*/*")) }) { Text("読み込む") }
    }
    if (askExport) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { askExport = false },
            title = { Text("設定を書き出す") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        androidx.compose.material3.Checkbox(checked = withHistory, onCheckedChange = { withHistory = it })
                        Text("審議記録も含める", style = MaterialTheme.typography.bodySmall)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        androidx.compose.material3.Checkbox(checked = withKeys, onCheckedChange = { withKeys = it })
                        Text("APIキーも含める（パスワードで暗号化）", style = MaterialTheme.typography.bodySmall)
                    }
                    if (withKeys) {
                        androidx.compose.material3.OutlinedTextField(value = pw, onValueChange = { pw = it }, singleLine = true,
                            label = { Text("パスワード（8文字以上）") },
                            visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
                        androidx.compose.material3.OutlinedTextField(value = pw2, onValueChange = { pw2 = it }, singleLine = true,
                            label = { Text("パスワード（確認）") },
                            visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
                        if (pw2.isNotEmpty() && pw != pw2) Text("パスワードが一致しません", color = MagiColors.Reject, style = MaterialTheme.typography.labelSmall)
                    }
                }
            },
            confirmButton = {
                TextButton(enabled = !withKeys || (pw.length >= 8 && pw == pw2), onClick = {
                    exportPw = if (withKeys) pw else null
                    askExport = false
                    val day = java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.JAPAN).format(java.util.Date())
                    save.launch("PersonalMAGI-settings-$day.json")
                }) { Text("保存先を選ぶ") }
            },
            dismissButton = { TextButton(onClick = { askExport = false }) { Text("やめる") } },
        )
    }
    pending?.let { json ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { vm.pendingImport.value = null; importPw = "" },
            title = { Text("APIキーのパスワード") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("このファイルには暗号化されたAPIキーが含まれています。書き出したときのパスワードを入力してください",
                        style = MaterialTheme.typography.bodySmall)
                    androidx.compose.material3.OutlinedTextField(value = importPw, onValueChange = { importPw = it }, singleLine = true,
                        label = { Text("パスワード") },
                        visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
                }
            },
            confirmButton = {
                TextButton(enabled = importPw.isNotEmpty(), onClick = { vm.applyImport(json, importPw); importPw = "" }) { Text("読み込む") }
            },
            dismissButton = {
                TextButton(onClick = { vm.applyImport(json, null); importPw = "" }) { Text("キーなしで読み込む") }
            },
        )
    }
}

/**
 * Own colour schemes: made here, taken from a file (the app's format, a VS Code theme .json or a
 * .vsix), or searched among the VS Code themes on Open VSX.
 */
@Composable
private fun CustomPaletteSection(vm: MagiViewModel, s: jp.pocketmagi.data.MagiSettings) {
    var editing by remember { mutableStateOf<Palette?>(null) }
    var searching by remember { mutableStateOf(false) }
    var exporting by remember { mutableStateOf<Palette?>(null) }
    val open = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let { vm.importPaletteFile(it) } }
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        val p = exporting; if (uri != null && p != null) vm.exportPalette(p, uri); exporting = null
    }
    Section("カラースキーム（取り込み・自作）")
    CustomPalettes.list.forEach { p ->
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().clickable { vm.updateSettings(s.copy(theme = p.id)) }.padding(vertical = 4.dp),
        ) {
            RadioButton(selected = s.theme == p.id, onClick = { vm.updateSettings(s.copy(theme = p.id)) })
            Column(Modifier.weight(1f)) {
                Text(p.label, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
                Row {
                    listOf(p.bg, p.accent, p.eye, p.approve, p.reject).forEach { c ->
                        Box(Modifier.padding(end = 3.dp).size(14.dp).background(c).border(1.dp, p.accentDim))
                    }
                }
            }
            TextButton(onClick = { editing = p }) { Text("編集") }
            TextButton(onClick = {
                exporting = p; save.launch(p.label.replace(Regex("[\\\\/:*?\"<>|]"), "_") + ".json")
            }) { Text("書出") }
            TextButton(onClick = { vm.deletePalette(p.id) }) { Text("削除", color = MagiColors.Reject) }
        }
    }
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        OutlinedButton(onClick = { searching = true }, shape = CutCornerShape(6.dp)) { Text("VS Codeテーマを探す") }
        OutlinedButton(onClick = { open.launch(arrayOf("application/json", "application/octet-stream", "application/zip", "*/*")) },
            shape = CutCornerShape(6.dp)) { Text("ファイルから") }
        OutlinedButton(onClick = {
            val base = Palettes.byId(s.theme)
            editing = base.copy(id = CustomPalettes.newId(), label = base.label + " のコピー")
        }, shape = CutCornerShape(6.dp)) { Text("新しく作る") }
    }
    Text("VS Code のカラーテーマ（Open VSX で公開されているもの）を検索して取り込めます。.json（VS Code テーマ／このアプリの形式）や .vsix も読み込めます。" +
        "「新しく作る」は今のテーマを元に、色を1つずつ変えられます",
        style = MaterialTheme.typography.labelSmall, color = MagiColors.TextDim)

    if (searching) ThemeSearchDialog(vm) { searching = false }
    editing?.let { p -> PaletteEditor(p, onSave = { vm.savePalette(it); editing = null }, onDismiss = { editing = null }) }
}

@Composable
private fun ThemeSearchDialog(vm: MagiViewModel, onDismiss: () -> Unit) {
    var q by remember { mutableStateOf("") }
    val results by vm.themeResults.collectAsState()
    val busy by vm.themeSearching.collectAsState()
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("VS Codeテーマを探す") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    androidx.compose.material3.OutlinedTextField(value = q, onValueChange = { q = it }, singleLine = true,
                        placeholder = { Text("例: dracula, tokyo night, monokai") }, modifier = Modifier.weight(1f),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { vm.searchThemes(q) }))
                    TextButton(onClick = { vm.searchThemes(q) }, enabled = !busy) { Text(if (busy) "検索中" else "検索") }
                }
                androidx.compose.foundation.lazy.LazyColumn(Modifier.height(320.dp)) {
                    items(results.size) { i ->
                        val e = results[i]
                        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(e.displayName, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
                                Text("${e.namespace}　${"%,d".format(e.downloads)} DL", style = MaterialTheme.typography.labelSmall, color = MagiColors.TextDim)
                            }
                            TextButton(onClick = { vm.importThemeExt(e) }) { Text("取り込む") }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("閉じる") } },
    )
}

/** Edits a palette colour by colour (hex), with a small live preview. */
@Composable
private fun PaletteEditor(start: Palette, onSave: (Palette) -> Unit, onDismiss: () -> Unit) {
    var p by remember(start.id) { mutableStateOf(start) }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("カラースキームを編集") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                androidx.compose.material3.OutlinedTextField(value = p.label, onValueChange = { p = p.copy(label = it) }, singleLine = true,
                    label = { Text("名前") })
                // preview
                Column(Modifier.fillMaxWidth().background(p.bg).border(1.dp, p.accentDim).padding(8.dp)) {
                    Row(Modifier.fillMaxWidth().background(p.panel).border(2.dp, p.accent).padding(6.dp)) {
                        Text("MELCHIOR", color = p.accent, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                        Text("承認", color = p.approve); Text(" 否決", color = p.reject); Text(" 保留", color = p.pending)
                    }
                    Text("本文の文字", color = p.text, style = MaterialTheme.typography.bodySmall)
                    Text("補助の文字", color = p.textDim, style = MaterialTheme.typography.labelSmall)
                    Text("出典・提案", color = p.eye, style = MaterialTheme.typography.labelSmall)
                    Box(Modifier.fillMaxWidth().background(p.inputBar).padding(4.dp)) { Text("入力欄", color = p.textDim, style = MaterialTheme.typography.labelSmall) }
                }
                CustomPalettes.ROLES.forEach { (role, name) ->
                    var text by remember(start.id, role) { mutableStateOf(CustomPalettes.hex(CustomPalettes.get(start, role))) }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(22.dp).background(CustomPalettes.get(p, role)).border(1.dp, MagiColors.AmberDim))
                        Text(name, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f).padding(horizontal = 8.dp))
                        BasicTextField(
                            value = text,
                            onValueChange = { t ->
                                text = t.take(9)
                                CustomPalettes.parse(t)?.let { c -> p = CustomPalettes.with(p, role, c) }
                            },
                            singleLine = true,
                            textStyle = MaterialTheme.typography.labelMedium.copy(color = MagiColors.Amber, textAlign = TextAlign.End),
                            cursorBrush = SolidColor(MagiColors.Amber),
                            modifier = Modifier.width(92.dp).border(1.dp, MagiColors.AmberDim).padding(6.dp),
                        )
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onSave(p.copy(label = p.label.ifBlank { "カスタム" })) }) { Text("保存して適用") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("やめる") } },
    )
}

/** URL and model of an OpenAI-compatible API (Mistral, OpenRouter, a server on the home network, …). */
@Composable
private fun CustomApiFields(vm: MagiViewModel, onChanged: () -> Unit) {
    val s by vm.settings.collectAsState()
    var base by remember { mutableStateOf(s.customApiBase) }
    var model by remember { mutableStateOf(s.customApiModel) }
    Text("OpenAI 互換の API なら何でも使えます。URL は /chat/completions の手前まで（例: https://api.mistral.ai/v1 、http://192.168.1.10:11434/v1）",
        style = MaterialTheme.typography.labelSmall, color = MagiColors.TextDim)
    androidx.compose.material3.OutlinedTextField(value = base, onValueChange = { base = it.trim() }, singleLine = true,
        label = { Text("URL") }, modifier = Modifier.fillMaxWidth(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
    androidx.compose.material3.OutlinedTextField(value = model, onValueChange = { model = it.trim() }, singleLine = true,
        label = { Text("モデル名（空欄ならサーバーの一覧から）") }, modifier = Modifier.fillMaxWidth())
    TextButton(onClick = {
        vm.updateSettings(s.copy(customApiBase = base, customApiModel = model))
        onChanged()
        vm.testKey(jp.pocketmagi.engine.CloudProvider.CUSTOM)
    }) { Text("URLとモデル名を保存して接続テスト") }
}

@Composable
private fun SwitchRow(title: String, hint: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 6.dp)) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            Text(hint, style = MaterialTheme.typography.labelSmall, color = MagiColors.TextDim)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

/** Cloud AI cost: this month's total, a monthly cap, the exchange rate and per-provider prices. */
@Composable
private fun CostSection() {
    val c = androidx.compose.ui.platform.LocalContext.current
    var refresh by remember { mutableStateOf(0) }
    val month = remember(refresh) { jp.pocketmagi.data.CloudCost.monthTotal(c) }
    var cap by remember { mutableStateOf(jp.pocketmagi.data.CloudCost.cap(c).toString()) }
    var rate by remember { mutableStateOf("%.1f".format(jp.pocketmagi.data.CloudCost.rate(c))) }
    Section("クラウドAIの費用")
    Text("今月の概算: ¥%,.0f".format(month) + (jp.pocketmagi.data.CloudCost.cap(c).takeIf { it > 0 }?.let { "（上限 ¥%,d）".format(it) } ?: ""),
        style = MaterialTheme.typography.bodyMedium)
    Text("トークン数×下の単価×為替で計算した目安です（Perplexity は返ってきた実費を使います）。上限に達すると、その月はクラウドAIを選んだ役割も端末内のモデルで審議します（上限0は無制限）",
        style = MaterialTheme.typography.labelSmall, color = MagiColors.TextDim)
    Spacer(Modifier.height(6.dp))
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        androidx.compose.material3.OutlinedTextField(value = cap, onValueChange = { cap = it.filter { ch -> ch.isDigit() }.take(7) }, singleLine = true,
            label = { Text("月の上限（円）", maxLines = 1) }, placeholder = { Text("0で無制限") }, modifier = Modifier.weight(1f), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
        androidx.compose.material3.OutlinedTextField(value = rate, onValueChange = { rate = it.filter { ch -> ch.isDigit() || ch == '.' }.take(7) }, singleLine = true,
            label = { Text("1ドル（円）", maxLines = 1) }, modifier = Modifier.weight(1f), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
    }
    Text("単価（ドル／100万トークン、入力・出力）", style = MaterialTheme.typography.labelSmall, color = MagiColors.TextDim)
    val prices = remember { jp.pocketmagi.engine.CloudProvider.entries.associate { p ->
        val (i, o) = jp.pocketmagi.data.CloudCost.price(c, p.id); p.id to (mutableStateOf(i.toString()) to mutableStateOf(o.toString())) } }
    jp.pocketmagi.engine.CloudProvider.entries.forEach { p ->
        val (pi, po) = prices.getValue(p.id)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(p.label, style = MaterialTheme.typography.bodySmall, modifier = Modifier.width(88.dp))
            androidx.compose.material3.OutlinedTextField(value = pi.value, onValueChange = { pi.value = it.filter { ch -> ch.isDigit() || ch == '.' }.take(8) },
                singleLine = true, label = { Text("入力") }, modifier = Modifier.weight(1f), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
            androidx.compose.material3.OutlinedTextField(value = po.value, onValueChange = { po.value = it.filter { ch -> ch.isDigit() || ch == '.' }.take(8) },
                singleLine = true, label = { Text("出力") }, modifier = Modifier.weight(1f), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
        }
    }
    Row {
        TextButton(onClick = {
            jp.pocketmagi.data.CloudCost.setCap(c, cap.toIntOrNull() ?: 0)
            rate.toDoubleOrNull()?.takeIf { it > 0 }?.let { jp.pocketmagi.data.CloudCost.setRate(c, it) }
            prices.forEach { (id, v) -> jp.pocketmagi.data.CloudCost.setPrice(c, id, v.first.value.toDoubleOrNull() ?: 0.0, v.second.value.toDoubleOrNull() ?: 0.0) }
            refresh++
        }) { Text("保存") }
        TextButton(onClick = { jp.pocketmagi.data.CloudCost.resetMonth(c); refresh++ }) { Text("今月の集計をリセット") }
    }
}

/** Updates of the app from GitHub Releases: check, download, open the installer. */
@Composable
private fun AppUpdateSection(vm: MagiViewModel) {
    val rel by vm.appRelease.collectAsState()
    val status by vm.appUpdateStatus.collectAsState()
    var token by remember { mutableStateOf("") }
    var has by remember { mutableStateOf(vm.hasGithubToken()) }
    Section("アプリの更新")
    Text("GitHub の Releases に新しい版があるか確認し、ダウンロードしてインストール画面を開きます（1日1回自動で確認）。" +
        "トークンは不要です。確認の回数制限にかかるときだけ、読み取り専用のトークンを登録してください",
        style = MaterialTheme.typography.labelSmall, color = MagiColors.TextDim)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        androidx.compose.material3.OutlinedTextField(value = token, onValueChange = { token = it.trim() }, singleLine = true,
            placeholder = { Text(if (has) "••••（登録済み）" else "GitHubトークン", color = MagiColors.TextDim) },
            visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(), modifier = Modifier.weight(1f))
        TextButton(onClick = { vm.saveGithubToken(token); has = token.isNotBlank() || has; token = "" }, enabled = token.isNotBlank()) { Text("保存") }
    }
    Row {
        TextButton(onClick = { vm.checkAppUpdate(true) }) { Text("更新を確認") }
        if (rel != null) TextButton(onClick = { vm.installAppUpdate() }) { Text("ダウンロードしてインストール") }
    }
    if (status.isNotBlank()) Text(status, style = MaterialTheme.typography.labelSmall, color = if (rel != null) MagiColors.Pending else MagiColors.TextDim)
}

/** Questions deliberated automatically at a set time; the result is notified and recorded. */
@Composable
private fun ScheduleSection(vm: MagiViewModel) {
    val list by vm.schedules.collectAsState()
    var editing by remember { mutableStateOf<jp.pocketmagi.data.Schedule?>(null) }
    Text("決まった時刻に、今の設定（各役割のAI・Web調査など）で自動的に審議し、結果を通知して記録に残します。" +
        "時刻には端末がスリープしていても起動します（電池の最適化で遅れることがあります）",
        style = MaterialTheme.typography.labelSmall, color = MagiColors.TextDim)
    list.forEach { sc ->
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
            .border(1.dp, MagiColors.AmberDim.copy(alpha = 0.5f)).clickable { editing = sc }.padding(8.dp)) {
            Column(Modifier.weight(1f)) {
                Text(sc.question, style = MaterialTheme.typography.bodyMedium, maxLines = 2)
                Text("%s %02d:%02d".format(jp.pocketmagi.data.Schedules.DAYS.firstOrNull { it.first == sc.days }?.second ?: "毎日", sc.hour, sc.minute),
                    style = MaterialTheme.typography.labelSmall, color = MagiColors.TextDim)
            }
            Switch(checked = sc.enabled, onCheckedChange = { vm.putSchedule(sc.copy(enabled = it)) })
        }
    }
    OutlinedButton(onClick = {
        editing = jp.pocketmagi.data.Schedule(-1, "", 7, 0, "daily", true)
    }, shape = CutCornerShape(6.dp)) { Text("＋ 定期審議を追加") }
    editing?.let { e ->
        var q by remember(e.id) { mutableStateOf(e.question) }
        var hh by remember(e.id) { mutableStateOf("%02d".format(e.hour)) }
        var mm by remember(e.id) { mutableStateOf("%02d".format(e.minute)) }
        var days by remember(e.id) { mutableStateOf(e.days) }
        val ctx = androidx.compose.ui.platform.LocalContext.current
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text(if (e.id < 0) "定期審議を追加" else "定期審議を編集") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    androidx.compose.material3.OutlinedTextField(value = q, onValueChange = { q = it }, label = { Text("審議事項") },
                        modifier = Modifier.fillMaxWidth(), maxLines = 4)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        androidx.compose.material3.OutlinedTextField(value = hh, onValueChange = { hh = it.filter { c -> c.isDigit() }.take(2) },
                            label = { Text("時") }, singleLine = true, modifier = Modifier.width(80.dp), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                        Text(":")
                        androidx.compose.material3.OutlinedTextField(value = mm, onValueChange = { mm = it.filter { c -> c.isDigit() }.take(2) },
                            label = { Text("分") }, singleLine = true, modifier = Modifier.width(80.dp), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        jp.pocketmagi.data.Schedules.DAYS.forEach { (id, label) -> FilterChip(selected = days == id, onClick = { days = id }, label = { Text(label) }) }
                    }
                }
            },
            confirmButton = {
                TextButton(enabled = q.isNotBlank() && (hh.toIntOrNull() ?: 99) in 0..23 && (mm.toIntOrNull() ?: 99) in 0..59, onClick = {
                    vm.putSchedule(jp.pocketmagi.data.Schedule(if (e.id < 0) jp.pocketmagi.data.Schedules.newId(ctx) else e.id,
                        q.trim(), hh.toInt(), mm.toInt(), days, e.enabled))
                    editing = null
                }) { Text("保存") }
            },
            dismissButton = {
                Row {
                    if (e.id >= 0) TextButton(onClick = { vm.removeSchedule(e.id); editing = null }) { Text("削除", color = MagiColors.Reject) }
                    TextButton(onClick = { editing = null }) { Text("やめる") }
                }
            },
        )
    }
}
