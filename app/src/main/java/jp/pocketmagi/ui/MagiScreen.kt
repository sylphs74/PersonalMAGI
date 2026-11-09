package jp.pocketmagi.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.addOutline
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.runtime.State
import androidx.compose.animation.core.LinearEasing
import androidx.compose.ui.Modifier
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.coerceIn
import androidx.compose.ui.unit.sp
import jp.pocketmagi.MagiViewModel
import jp.pocketmagi.engine.DeliberationState
import jp.pocketmagi.engine.WebResearch
import jp.pocketmagi.engine.Phase
import jp.pocketmagi.engine.Resolution
import jp.pocketmagi.engine.SageState
import jp.pocketmagi.engine.SageStatus
import jp.pocketmagi.engine.Verdict
import jp.pocketmagi.model.InstallState
import jp.pocketmagi.model.SageRole
import kotlinx.coroutines.delay
import java.io.File

@Composable
fun MagiScreen(vm: MagiViewModel) {
    val st by vm.state.collectAsState()
    var question by rememberSaveable { mutableStateOf("") }
    // shared from another app (共有 → PersonalMAGI): becomes the question, ready to deliberate
    val shared by vm.shared.collectAsState()
    LaunchedEffect(shared) {
        shared?.let { t ->
            question = if (question.isBlank()) t else question.trimEnd() + "\n" + t
            vm.shared.value = null
            if (WebResearch.urlsIn(t).isNotEmpty()) vm.message.value = "URLのページを読み込んで審議します。必要なら質問を書き足して「審議」を押してください"
        }
    }
    var detail by remember { mutableStateOf<SageState?>(null) }
    val running = st.phase == Phase.RUNNING

    // elapsed clock
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(running) {
        while (running) { now = System.currentTimeMillis(); delay(250) }
    }

    Column(Modifier.fillMaxSize().background(MagiColors.Bg)) {
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Header(st, if (running) now - st.startedAt else st.elapsedMs)
            SageBoard(st) { detail = it }
            if (!running) ReadinessLine(vm)
            if (st.proposer.status != SageStatus.SKIPPED) ProposerStrip(st) { detail = st.proposer }
            if (st.followUpOf.isNotBlank()) Text("追加審議（前回: ${st.followUpOf}）", style = MaterialTheme.typography.labelSmall,
                color = MagiColors.Cyan, maxLines = 2, overflow = TextOverflow.Ellipsis)
            // from the question on, everything can be selected and copied (long press)
            androidx.compose.foundation.text.selection.SelectionContainer {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (st.question.isNotBlank()) QuestionCard(st.question, st.hypothesis)
                    ResultCard(st)
                }
            }
            if (st.phase == Phase.ERROR) {
                Text("エラー: ${st.error}", color = MagiColors.Reject, style = MaterialTheme.typography.bodySmall)
            }
        }
        InputBar(
            question = question,
            onQuestion = { question = it },
            running = running,
            vm = vm,
            // the question moves into 審議事項 above, so the input is emptied once the deliberation starts
            onStart = { if (vm.start(question)) question = "" },
            onNew = { vm.clearFollowUp(); if (vm.start(question)) question = "" },
            canReset = st.phase != Phase.IDLE && !running,
        )
    }

    detail?.let { d ->
        val live = when (d.role) {
            SageRole.PROPOSER -> st.proposer
            else -> st.sages[d.role] ?: d
        }
        AlertDialog(
            onDismissRequest = { detail = null },
            confirmButton = { TextButton(onClick = { detail = null }) { Text("閉じる") } },
            title = { Text("${live.role.code}  ${live.role.persona}", style = MaterialTheme.typography.titleMedium) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text(live.modelName, style = MaterialTheme.typography.labelSmall, color = MagiColors.TextDim)
                    Spacer(Modifier.height(8.dp))
                    MdText(live.text.ifBlank { live.error ?: "（まだ出力はありません）" }, style = MaterialTheme.typography.bodyMedium)
                    if (live.stats.isNotBlank()) {
                        Spacer(Modifier.height(8.dp))
                        Text(live.stats, style = MaterialTheme.typography.labelSmall, color = MagiColors.TextDim)
                    }
                }
            },
            containerColor = MagiColors.Panel,
        )
    }
}

/** Shows which sages are not ready yet, so a tap on 審議 never looks like it did nothing. */
@Composable
private fun ReadinessLine(vm: MagiViewModel) {
    val states by vm.installStates.collectAsState()
    DisposableEffect(Unit) {
        vm.startPolling()
        onDispose { vm.stopPolling() }
    }
    var proxies = 0
    // roles on a cloud AI with a key need no downloaded model
    val notes = listOf(SageRole.MELCHIOR, SageRole.BALTHASAR, SageRole.CASPER)
        .filter { r -> vm.deliberation.engineOf(r)?.let { !vm.hasKey(it) } ?: true }.mapNotNull { r ->
        val spec = vm.deliberation.specFor(r) ?: return@mapNotNull null
        val use = vm.deliberation.usableSpecFor(r)
        val stand = if (use != null && use.id != spec.id && vm.store.local(use) != null) { proxies++; "（${use.name} が代理）" } else ""
        when (val st = states[spec.id]) {
            is InstallState.Installed -> null
            is InstallState.Downloading -> "${r.code} ${spec.name} ダウンロード中 ${(st.fraction * 100).toInt()}%$stand"
            else -> "${r.code} ${spec.name} 未導入$stand"
        }
    }
    if (notes.isEmpty()) return
    Text(
        notes.joinToString("\n") + when {
            proxies == notes.size -> "\n→ 代理のモデルで審議します"
            notes.size - proxies < 3 -> "\n→ 使えるモデルで審議します（モデルのない賢者は棄権）"
            else -> "\n→ モデル画面でダウンロードしてください"
        },
        style = MaterialTheme.typography.labelSmall, color = MagiColors.Pending,
    )
}

@Composable
private fun Header(st: DeliberationState, elapsedMs: Long) {
    Row(verticalAlignment = Alignment.Bottom) {
        Text("MAGI", style = MaterialTheme.typography.headlineLarge, color = MagiColors.Amber)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text("POCKET / LOCAL", style = MaterialTheme.typography.labelSmall, color = MagiColors.AmberDim)
            // while deliberating, a long status runs sideways like the model names, resting after each lap
            val running = st.startedAt > 0 && st.finishedAt == 0L
            key(st.stage) {
                Text(
                    st.stage.ifBlank { "審議事項を入力してください" },
                    style = MaterialTheme.typography.labelSmall, color = MagiColors.TextDim,
                    maxLines = 1, softWrap = false,
                    overflow = if (running) TextOverflow.Clip else TextOverflow.Ellipsis,
                    modifier = if (running) Modifier.basicMarquee(iterations = Int.MAX_VALUE, repeatDelayMillis = 1800, initialDelayMillis = 1000) else Modifier,
                )
            }
        }
        if (st.startedAt > 0) {
            Text(
                "%d:%02d".format(elapsedMs / 60000, (elapsedMs / 1000) % 60),
                style = MaterialTheme.typography.titleMedium, color = MagiColors.Amber,
            )
        }
    }
}

/**
 * The MAGI board as in the original: BALTHASAR on top, CASPER bottom left, MELCHIOR bottom right,
 * MAGI (the mediator that writes the result) in the middle, linked by lines; 提訴 (the proposer)
 * and 決議 (the resolution) in the upper corners.
 */
@Composable
private fun SageBoard(st: DeliberationState, onTap: (SageState) -> Unit) {
    val line = MagiColors.AmberDim
    androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxWidth()) {
        val w = maxWidth
        val cut = 22.dp
        val cornerW = w * 0.23f
        // The board is laid out so that BALTHASAR's lower cut corner, the connecting line and the
        // cut corner of CASPER (and of MELCHIOR on the right) lie on one 45° line: the gap between
        // the rows equals the horizontal distance the line has to cover.
        var sideW = w * 0.46f
        val gap = (sideW - w * 0.25f - cut * 2).coerceIn(36.dp, 64.dp)
        var topX = sideW - gap - cut * 2
        if (topX < cornerW + 4.dp) { topX = cornerW + 4.dp; sideW = topX + gap + cut * 2 }
        val sideY = PANEL_H + gap
        Box(Modifier.fillMaxWidth().height(sideY + PANEL_H).drawBehind {
            // connecting lines behind the panels, centred on the same outlines as the panel borders
            val W = size.width
            val c = cut.toPx()
            val top = PANEL_H.toPx()
            val side = sideY.toPx()
            val tx = topX.toPx()
            val sw = sideW.toPx()
            val stroke = BORDER_W.toPx()
            fun o(x: Float, y: Float) = androidx.compose.ui.geometry.Offset(x, y)
            drawLine(line, o(tx + c, top), o(sw - c, side), stroke, StrokeCap.Square)
            drawLine(line, o(W - tx - c, top), o(W - sw + c, side), stroke, StrokeCap.Square)
            val midY = side + PANEL_H.toPx() * 0.6f
            drawLine(line, o(sw, midY), o(W - sw, midY), stroke)
        })
        CornerBox("提訴", proposerLabel(st), proposerColor(st), Modifier.width(cornerW).height(58.dp)) { onTap(st.proposer) }
        CornerBox("決議", st.resolution?.label ?: "——", resolutionColor(st.resolution),
            Modifier.offset(x = w - cornerW).width(cornerW).height(58.dp), big = true) {}
        SagePanel(st.sages.getValue(SageRole.BALTHASAR), CutCornerShape(bottomStart = cut, bottomEnd = cut),
            Modifier.offset(x = topX).width(w - topX * 2).height(PANEL_H), onTap)
        // the mediator in the middle
        Column(
            Modifier.offset(y = PANEL_H).width(w).height(gap),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
        ) {
            Text("MAGI", fontSize = 26.sp, lineHeight = 26.sp, fontWeight = FontWeight.Black, letterSpacing = 4.sp,
                color = if (st.integrating) MagiColors.Amber else MagiColors.AmberDim)
            if (st.integrating) Text("審議結果をまとめ中", style = MaterialTheme.typography.labelSmall, color = MagiColors.Amber)
        }
        SagePanel(st.sages.getValue(SageRole.CASPER), CutCornerShape(topEnd = cut),
            Modifier.offset(y = sideY).width(sideW).height(PANEL_H), onTap)
        SagePanel(st.sages.getValue(SageRole.MELCHIOR), CutCornerShape(topStart = cut),
            Modifier.offset(x = w - sideW, y = sideY).width(sideW).height(PANEL_H), onTap)
    }
}

private val PANEL_H = 108.dp
private val BORDER_W = 2.dp

/**
 * A border drawn centred on the shape's outline (Modifier.border draws it inside), so that it lines
 * up exactly with the connecting lines and with the comet of [orbitBorder].
 */
private fun Modifier.outlineBorder(width: androidx.compose.ui.unit.Dp, color: Color, shape: Shape): Modifier =
    drawWithCache {
        val path = Path().apply { addOutline(shape.createOutline(size, layoutDirection, this@drawWithCache)) }
        val stroke = Stroke(width = width.toPx(), join = androidx.compose.ui.graphics.StrokeJoin.Miter)
        onDrawWithContent { drawContent(); drawPath(path, color, style = stroke) }
    }

private fun proposerLabel(st: DeliberationState): String = when (st.proposer.status) {
    SageStatus.SKIPPED -> "—"
    SageStatus.DONE -> if (st.hypothesis.isNotBlank()) "仮説あり" else "仮説なし"
    SageStatus.THINKING -> "検討中"
    SageStatus.LOADING -> "起動中"
    SageStatus.ERROR -> "障害"
    SageStatus.CANCELLED -> "中断"
    else -> st.proposer.status.label
}

@Composable
private fun proposerColor(st: DeliberationState): Color = when (st.proposer.status) {
    SageStatus.DONE -> MagiColors.Cyan
    SageStatus.THINKING, SageStatus.LOADING -> MagiColors.Amber
    SageStatus.ERROR -> MagiColors.Reject
    else -> MagiColors.AmberDim
}

@Composable
private fun resolutionColor(r: Resolution?): Color = when (r) {
    Resolution.PASSED -> MagiColors.Approve
    Resolution.REJECTED -> MagiColors.Reject
    Resolution.PENDING -> MagiColors.Pending
    null -> MagiColors.AmberDim
}

@Composable
private fun CornerBox(title: String, value: String, color: Color, modifier: Modifier, big: Boolean = false, onTap: () -> Unit) {
    Column(
        modifier.border(1.dp, color).background(MagiColors.Panel).clickable(onClick = onTap).padding(4.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
    ) {
        Text(title, style = MaterialTheme.typography.labelSmall, color = color)
        Text(value, fontSize = if (big) 18.sp else 13.sp, fontWeight = FontWeight.Black, color = color,
            maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
    }
}

private fun verdictColor(v: Verdict?): Color = when (v) {
    Verdict.APPROVE -> MagiColors.Approve
    Verdict.REJECT -> MagiColors.Reject
    Verdict.ABSTAIN -> MagiColors.Pending
    null -> MagiColors.Amber
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun SagePanel(s: SageState, shape: Shape, modifier: Modifier, onTap: (SageState) -> Unit) {
    val active = s.status == SageStatus.LOADING || s.status == SageStatus.THINKING
    val pulse = rememberInfiniteTransition(label = "pulse")
    val a by pulse.animateFloat(0.35f, 1f, infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "a")
    val decided = s.status == SageStatus.DONE || s.status == SageStatus.ERROR || s.status == SageStatus.MISSING
    val stopped = s.status == SageStatus.CANCELLED
    val color = when {
        stopped -> MagiColors.TextDim
        decided -> verdictColor(s.verdict)
        else -> MagiColors.Amber
    }
    val thinking = s.status == SageStatus.THINKING
    val orbit = pulse.animateFloat(0f, 1f, infiniteRepeatable(tween(1600, easing = LinearEasing)), label = "orbit")
    Box(
        modifier
            .outlineBorder(BORDER_W, if (thinking) MagiColors.AmberDim else color, shape)
            .orbitBorder(thinking, shape, MagiColors.Amber, orbit)
            .background(if (decided) color.copy(alpha = 0.18f) else MagiColors.Panel, shape)
            .clickable { onTap(s) }
            .padding(10.dp),
    ) {
        Column {
            Text(
                s.role.code, style = MaterialTheme.typography.titleMedium.copy(letterSpacing = 0.5.sp),
                color = color, maxLines = 1, softWrap = false, overflow = TextOverflow.Visible,
                fontSize = 16.sp,
            )
            // while this sage works, its model name runs sideways so all of it can be read,
            // resting a moment after each lap
            Text(
                s.modelName, style = MaterialTheme.typography.labelSmall, color = MagiColors.TextDim, maxLines = 1, softWrap = false,
                overflow = if (active) TextOverflow.Clip else TextOverflow.Ellipsis,
                modifier = if (active) Modifier.basicMarquee(iterations = Int.MAX_VALUE, repeatDelayMillis = 1800, initialDelayMillis = 600) else Modifier,
            )
            Spacer(Modifier.height(6.dp))
            val label = when {
                s.status == SageStatus.DONE -> s.verdict?.label ?: "—"
                s.status == SageStatus.ERROR -> "障害"
                s.status == SageStatus.MISSING -> "未導入"
                stopped -> "中断"
                // second turn of a sage whose first judgement was in the minority
                s.firstVerdict != null && active -> "反論中"
                else -> s.status.label
            }
            Text(
                label,
                fontSize = if (decided) 26.sp else 16.sp, fontWeight = FontWeight.Black, color = color,
                modifier = if (active) Modifier.alpha(a) else Modifier,
            )
        }
    }
}

/** The neutral proposer: shows what it is looking up and the hypothesis it put forward. */
@Composable
private fun ProposerStrip(st: DeliberationState, onTap: () -> Unit) {
    val p = st.proposer
    val t = rememberInfiniteTransition(label = "prop")
    val orbit = t.animateFloat(0f, 1f, infiniteRepeatable(tween(1600, easing = LinearEasing)), label = "propOrbit")
    val active = p.status == SageStatus.LOADING || p.status == SageStatus.THINKING
    Row(
        Modifier.fillMaxWidth().border(1.dp, MagiColors.Cyan.copy(alpha = 0.6f))
            .orbitBorder(active, RectangleShape, MagiColors.Cyan, orbit)
            .clickable(onClick = onTap).padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("提案", style = MaterialTheme.typography.labelMedium, color = MagiColors.Cyan)
        Spacer(Modifier.width(8.dp))
        val line = when {
            st.hypothesis.isNotBlank() -> "仮説: " + st.hypothesis + if (st.research.isNotEmpty()) "（Web資料${st.research.size}件）" else ""
            active && st.stage.startsWith("PROPOSER") -> st.stage.removePrefix("PROPOSER: ")
            p.status == SageStatus.CANCELLED -> "中断"
            else -> "${p.modelName} ${p.status.label}"
        }
        Text(line, style = MaterialTheme.typography.bodySmall, color = MagiColors.TextDim, maxLines = 2,
            overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
    }
}

/** The question under deliberation: two lines, with a corner toggle to show all of it. */
@Composable
private fun QuestionCard(question: String, hypothesis: String = "") {
    var expanded by rememberSaveable(question) { mutableStateOf(false) }
    var overflows by remember(question) { mutableStateOf(false) }
    Box(Modifier.fillMaxWidth().border(1.dp, MagiColors.AmberDim).padding(start = 12.dp, top = 10.dp, bottom = 10.dp, end = 12.dp)) {
        Column(Modifier.fillMaxWidth().padding(end = if (overflows || expanded) 72.dp else 0.dp)) {
            Text("審議事項", style = MaterialTheme.typography.labelMedium, color = MagiColors.Amber)
            Spacer(Modifier.height(4.dp))
            Text(
                question,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = if (expanded) Int.MAX_VALUE else 2,
                overflow = TextOverflow.Ellipsis,
                onTextLayout = { if (!expanded) overflows = it.hasVisualOverflow },
            )
            if (hypothesis.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                Text("仮説", style = MaterialTheme.typography.labelMedium, color = MagiColors.Cyan)
                Text(hypothesis, style = MaterialTheme.typography.bodyMedium)
            }
        }
        if (overflows || expanded) {
            Text(
                if (expanded) "閉じる ▲" else "全文 ▼",
                color = MagiColors.Amber, fontSize = 12.sp,
                modifier = Modifier.align(Alignment.TopEnd)
                    .border(1.dp, MagiColors.AmberDim)
                    .clickable { expanded = !expanded }
                    .padding(horizontal = 10.dp, vertical = 4.dp),
            )
        }
    }
}

@Composable
private fun ResultCard(st: DeliberationState) {
    if (st.integrated.isBlank() && !st.integrating) return
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.fillMaxWidth().border(1.dp, MagiColors.AmberDim).padding(12.dp)) {
            Text("審議結果", style = MaterialTheme.typography.labelMedium, color = MagiColors.Amber)
            Spacer(Modifier.height(6.dp))
            MdText(st.integrated.ifBlank { "生成中…" }, style = MaterialTheme.typography.bodyMedium)
        }
        if (st.roster.isNotBlank()) {
            Column(Modifier.fillMaxWidth().border(1.dp, MagiColors.AmberDim.copy(alpha = 0.6f)).padding(12.dp)) {
                Text("各賢者の判断", style = MaterialTheme.typography.labelMedium, color = MagiColors.Amber)
                Spacer(Modifier.height(6.dp))
                MdText(st.roster, style = MaterialTheme.typography.bodySmall)
            }
        }
        if (st.research.isNotEmpty() && !st.integrating && st.phase != Phase.RUNNING) SourcesCard(st.research.map { it.title to it.url })
    }
}

@Composable
private fun InputBar(
    question: String,
    onQuestion: (String) -> Unit,
    running: Boolean,
    vm: MagiViewModel,
    onStart: () -> Unit,
    onNew: () -> Unit,
    canReset: Boolean,
) {
    val context = LocalContext.current
    val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
    val followUp by vm.followUp.collectAsState()

    Column(
        Modifier.fillMaxWidth().background(MagiColors.InputBar).border(1.dp, MagiColors.AmberDim.copy(alpha = 0.5f))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // 追加審議 continues this result; 新規審議 starts over with the typed question
        if (followUp != null && !running) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("追加審議: 「${followUp!!.question}」の審議結果を踏まえます",
                    style = MaterialTheme.typography.labelSmall, color = MagiColors.Cyan, maxLines = 2,
                    overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = question,
                onValueChange = onQuestion,
                readOnly = running,
                placeholder = { Text(if (followUp != null) "追加の審議事項を入力" else "審議事項を入力", color = MagiColors.TextDim) },
                modifier = Modifier.weight(1f),
                maxLines = 4,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = MagiColors.Amber, unfocusedBorderColor = MagiColors.AmberDim,
                    cursorColor = MagiColors.Amber,
                ),
            )
            if (running) {
                Button(
                    onClick = { vm.cancel() },
                    colors = ButtonDefaults.buttonColors(containerColor = MagiColors.Reject),
                    shape = CutCornerShape(8.dp),
                ) { Text("中止", fontWeight = FontWeight.Bold) }
            } else {
                // after a result: continue it (追加審議) or start over (新規審議) with the typed question
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Button(
                        onClick = { keyboard?.hide(); focusManager.clearFocus(); onStart() },
                        enabled = question.isNotBlank(),
                        shape = CutCornerShape(8.dp),
                    ) { Text(if (followUp != null) "追加審議" else "審議", fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false) }
                    if (followUp != null) OutlinedButton(
                        onClick = { keyboard?.hide(); focusManager.clearFocus(); onNew() },
                        enabled = question.isNotBlank(),
                        shape = CutCornerShape(8.dp),
                    ) { Text("新規審議", maxLines = 1, softWrap = false) }
                }
            }
        }
    }
}

@Composable
private fun MediaButton(label: String, enabled: Boolean, modifier: Modifier, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick, enabled = enabled, modifier = modifier,
        shape = CutCornerShape(6.dp), border = BorderStroke(1.dp, MagiColors.AmberDim),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 4.dp, vertical = 6.dp),
    ) { Text(label, fontSize = 13.sp, maxLines = 1, softWrap = false, color = if (enabled) MagiColors.Amber else MagiColors.TextDim) }
}


/**
 * A bright "comet" that runs around the outline of [shape] while [enabled].
 * [progress] is read only in the draw phase, so the animation does not recompose.
 */
private fun Modifier.orbitBorder(enabled: Boolean, shape: Shape, color: Color, progress: State<Float>): Modifier =
    if (!enabled) this else drawWithCache {
        val outline = Path().apply { addOutline(shape.createOutline(size, layoutDirection, this@drawWithCache)) }
        val pm = PathMeasure().apply { setPath(outline, true) }
        val len = pm.length
        val seg = Path()
        val stroke = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Butt, join = androidx.compose.ui.graphics.StrokeJoin.Miter)
        val pieces = 10
        onDrawWithContent {
            drawContent()
            if (len <= 0f) return@onDrawWithContent
            val head = progress.value * len
            val tail = len * 0.3f
            for (i in 0 until pieces) {
                val a = head - tail + tail * i / pieces
                val b = head - tail + tail * (i + 1) / pieces
                val s0 = ((a % len) + len) % len
                val e0 = ((b % len) + len) % len
                seg.reset()
                if (e0 < s0) { pm.getSegment(s0, len, seg, true); pm.getSegment(0f, e0, seg, true) }
                else pm.getSegment(s0, e0, seg, true)
                val f = (i + 1f) / pieces
                drawPath(seg, color.copy(alpha = f * f), style = stroke)
            }
        }
    }

/** The web sources of a result, last and in their own box; a tap opens the page. */
@Composable
fun SourcesCard(sources: List<Pair<String, String>>) {
    if (sources.isEmpty()) return
    val uri = androidx.compose.ui.platform.LocalUriHandler.current
    Column(
        Modifier.fillMaxWidth().border(1.dp, MagiColors.AmberDim.copy(alpha = 0.4f)).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text("出典（${sources.size}件）", style = MaterialTheme.typography.labelMedium, color = MagiColors.Amber)
        sources.forEachIndexed { i, (title, url) ->
            Column(Modifier.fillMaxWidth().clickable(enabled = url.startsWith("http")) {
                try { uri.openUri(url) } catch (_: Exception) {}
            }) {
                Text("${i + 1}. $title", style = MaterialTheme.typography.bodySmall, color = MagiColors.Cyan,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(url.substringAfter("://").substringBefore('/'), style = MaterialTheme.typography.bodySmall, color = MagiColors.TextDim)
            }
        }
    }
}
