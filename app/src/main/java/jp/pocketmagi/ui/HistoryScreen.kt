package jp.pocketmagi.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import jp.pocketmagi.MagiViewModel
import jp.pocketmagi.data.HistoryEntry
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private fun resColor(r: String): Color = when (r) {
    "可決", "承認" -> MagiColors.Approve
    "否決" -> MagiColors.Reject
    else -> MagiColors.Pending
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun HistoryScreen(vm: MagiViewModel) {
    val all by vm.history.collectAsState()
    var open by remember { mutableStateOf<HistoryEntry?>(null) }
    val fmt = remember { SimpleDateFormat("M/d HH:mm", Locale.JAPAN) }
    var query by remember { mutableStateOf("") }
    // long press a record to start selecting; then tap to add or remove records
    var selected by remember { mutableStateOf(setOf<Long>()) }
    val selecting = selected.isNotEmpty()
    var confirmDelete by remember { mutableStateOf(false) }
    var redoOf by remember { mutableStateOf<HistoryEntry?>(null) }
    androidx.activity.compose.BackHandler(enabled = selecting) { selected = emptySet() }
    val q = query.trim()
    val list = all.filter { h ->
        q.isEmpty() || h.question.contains(q, true) || h.integrated.contains(q, true) || h.opinions.any { it.third.contains(q, true) }
    }.sortedWith(compareByDescending<HistoryEntry> { it.favorite }.thenByDescending { it.time })

    LazyColumn(
        Modifier.fillMaxSize().background(MagiColors.Bg).padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("LOG", style = MaterialTheme.typography.headlineLarge, color = MagiColors.Amber, modifier = Modifier.weight(1f))
                if (selecting) {
                    Text("${selected.size}件", style = MaterialTheme.typography.labelMedium, color = MagiColors.Amber)
                    TextButton(onClick = { selected = list.map { it.time }.toSet() }) { Text("全選択") }
                    TextButton(onClick = { confirmDelete = true }) { Text("削除", color = MagiColors.Reject) }
                    TextButton(onClick = { selected = emptySet() }) { Text("解除") }
                } else if (all.isNotEmpty()) TextButton(onClick = { vm.clearHistory() }) { Text("全消去") }
            }
            if (all.isNotEmpty()) androidx.compose.material3.OutlinedTextField(
                value = query, onValueChange = { query = it }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("記録を検索（質問・結果・賢者の回答）", color = MagiColors.TextDim) },
            )
            if (all.isNotEmpty() && !selecting) Text("長押しで選択して削除できます。☆で残したい記録をお気に入りに（件数・期間の上限で消えません）",
                style = MaterialTheme.typography.labelSmall, color = MagiColors.TextDim)
            if (all.isEmpty()) Text("まだ審議記録はありません", color = MagiColors.TextDim)
            else if (list.isEmpty()) Text("見つかりません", color = MagiColors.TextDim)
        }
        items(list, key = { it.time }) { h ->
            val isSel = h.time in selected
            Row(
                Modifier.fillMaxWidth()
                    .border(if (isSel) 2.dp else 1.dp, if (isSel) MagiColors.Amber else MagiColors.AmberDim)
                    .background(if (isSel) MagiColors.Amber.copy(alpha = 0.12f) else Color.Transparent)
                    .combinedClickable(
                        onClick = { if (selecting) selected = if (isSel) selected - h.time else selected + h.time else open = h },
                        onLongClick = { selected = if (isSel) selected - h.time else selected + h.time },
                    ).padding(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (selecting) androidx.compose.material3.Checkbox(checked = isSel, onCheckedChange = {
                    selected = if (isSel) selected - h.time else selected + h.time })
                Text(h.resolution.ifBlank { "—" }, color = resColor(h.resolution), style = MaterialTheme.typography.titleMedium)
                Column(Modifier.weight(1f).padding(start = 10.dp)) {
                    Text(h.question, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
                    Text(
                        "${fmt.format(Date(h.time))}  ${h.media}  ${h.elapsedMs / 1000}秒",
                        style = MaterialTheme.typography.labelSmall, color = MagiColors.TextDim,
                    )
                }
                if (!selecting) Text(if (h.favorite) "★" else "☆", color = if (h.favorite) MagiColors.Pending else MagiColors.TextDim,
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.clickable { vm.toggleFavorite(h) }.padding(start = 8.dp))
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }

    if (confirmDelete) AlertDialog(
        onDismissRequest = { confirmDelete = false },
        title = { Text("選んだ${selected.size}件の記録を削除しますか？") },
        confirmButton = { TextButton(onClick = { vm.deleteHistoryMany(selected); selected = emptySet(); confirmDelete = false }) { Text("削除", color = MagiColors.Reject) } },
        dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("やめる") } },
    )

    // もう一度: the same question, with the hypothesis rewritten by hand or a new one from the proposer
    redoOf?.let { h ->
        val prev = h.hypothesisOrParsed
        var text by remember(h.time) { mutableStateOf(prev) }
        val edited = text.isNotBlank() && text.trim() != prev
        AlertDialog(
            onDismissRequest = { redoOf = null },
            title = { Text("もう一度審議") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(h.question, style = MaterialTheme.typography.bodyMedium, maxLines = 4, overflow = TextOverflow.Ellipsis)
                    androidx.compose.material3.OutlinedTextField(
                        value = text, onValueChange = { text = it },
                        label = { Text("仮説（書き換えると、その仮説で審議）") },
                        placeholder = { Text("空欄なら提訴役が仮説を作ります") },
                        modifier = Modifier.fillMaxWidth(), minLines = 2, maxLines = 6,
                    )
                    Text(
                        if (prev.isNotBlank()) "「別の仮説で」は、提訴役が前回と違う立場の仮説を作り直します。" else "前回は仮説なしで審議しました。",
                        style = MaterialTheme.typography.labelSmall, color = MagiColors.TextDim,
                    )
                }
            },
            confirmButton = {
                TextButton(enabled = text.isNotBlank(), onClick = { redoOf = null; vm.redeliberate(h, hypothesis = text) }) {
                    Text(if (edited) "この仮説で審議" else "同じ仮説で審議")
                }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = { redoOf = null }) { Text("やめる") }
                    TextButton(onClick = { redoOf = null; vm.redeliberate(h, differ = true) }) {
                        Text(if (prev.isNotBlank()) "別の仮説で" else "仮説から作る")
                    }
                }
            },
        )
    }

    open?.let { h ->
        AlertDialog(
            onDismissRequest = { open = null },
            confirmButton = { TextButton(onClick = { open = null }) { Text("閉じる") } },
            dismissButton = {
                Row(Modifier.horizontalScroll(rememberScrollState())) {
                    TextButton(onClick = { vm.deleteHistory(h.time); open = null }) { Text("削除", color = MagiColors.Reject) }
                    TextButton(onClick = { open = null; redoOf = h }) { Text("もう一度") }
                    TextButton(onClick = { open = null; vm.followUpFrom(h) }) { Text("追加審議") }
                    val ctx = androidx.compose.ui.platform.LocalContext.current
                    TextButton(onClick = {
                        val send = android.content.Intent(android.content.Intent.ACTION_SEND).setType("text/plain")
                            .putExtra(android.content.Intent.EXTRA_TEXT, vm.shareText(h))
                        try { ctx.startActivity(android.content.Intent.createChooser(send, "審議記録を共有")) } catch (_: Exception) {}
                    }) { Text("共有") }
                }
            },
            title = { Text("決議: ${h.resolution}", color = resColor(h.resolution)) },
            text = {
                androidx.compose.foundation.text.selection.SelectionContainer {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(h.question, style = MaterialTheme.typography.bodyMedium)
                    if (h.followUpOf.isNotBlank()) Text("「${h.followUpOf}」の追加審議", style = MaterialTheme.typography.labelSmall, color = MagiColors.Cyan)
                    if (h.integrated.isNotBlank()) {
                        Column(Modifier.fillMaxWidth().border(1.dp, MagiColors.AmberDim).padding(10.dp)) {
                            Text("審議結果", style = MaterialTheme.typography.labelMedium, color = MagiColors.Amber)
                            // older records carried the per-sage lines inside the result
                            MdText(h.integrated.substringBefore("\n\n各賢者の判断:").substringBefore("\n\n出典（Web）"), style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                    if (h.opinions.isNotEmpty()) {
                        Column(Modifier.fillMaxWidth().border(1.dp, MagiColors.AmberDim.copy(alpha = 0.6f)).padding(10.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("各賢者の判断", style = MaterialTheme.typography.labelMedium, color = MagiColors.Amber)
                            h.opinions.forEach { (code, verdict, text) ->
                                Text("${code.substringBefore('-')}  $verdict", style = MaterialTheme.typography.labelMedium, color = resColor(verdict))
                                MdText(text, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                    // sources: own box at the end (older records kept them inside the result text)
                    val src = h.sources.ifEmpty {
                        h.integrated.substringAfter("\n\n出典（Web）:\n", "").lines().filter { it.startsWith("・") }.map { l ->
                            val t = l.removePrefix("・"); val u = t.substringAfterLast(' ')
                            t.substringBeforeLast(' ') to u
                        }
                    }
                    SourcesCard(src)
                }
                }
            },
            containerColor = MagiColors.Panel,
        )
    }
}
