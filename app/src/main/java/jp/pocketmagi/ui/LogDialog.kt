package jp.pocketmagi.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** A message plus a scrollable log, with a button that copies everything for a bug report. */
@Composable
fun LogDialog(title: String, message: String, log: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("閉じる") } },
        dismissButton = {
            TextButton(onClick = {
                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("PersonalMAGI log", "$title\n$message\n\n$log"))
                Toast.makeText(context, "ログをコピーしました", Toast.LENGTH_SHORT).show()
            }) { Text("ログをコピー") }
        },
        title = { Text(title, style = MaterialTheme.typography.titleMedium) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                if (message.isNotBlank()) Text(message, style = MaterialTheme.typography.bodyMedium)
                if (log.isNotBlank()) {
                    Spacer(Modifier.height(10.dp))
                    Text(log, fontFamily = FontFamily.Monospace, fontSize = 10.sp, lineHeight = 13.sp, color = MagiColors.TextDim)
                }
            }
        },
        containerColor = MagiColors.Panel,
    )
}
