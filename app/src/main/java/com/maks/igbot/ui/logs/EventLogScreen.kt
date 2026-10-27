package com.maks.igbot.ui.logs

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.maks.igbot.ui.components.AppCard
import com.maks.igbot.ui.components.ConfirmDialog
import com.maks.igbot.ui.components.ScreenHeader
import com.maks.igbot.ui.theme.AppError
import com.maks.igbot.ui.theme.AppInfo
import com.maks.igbot.ui.theme.AppSuccess
import com.maks.igbot.ui.theme.AppWarning

private enum class LogFilter(val label: String) {
    ALL("Усі"), LIKES("Лайки"), PARSER("Парсер"), PROBLEMS("Проблеми")
}

private fun LogEntry.matches(filter: LogFilter): Boolean = when (filter) {
    LogFilter.ALL -> true
    LogFilter.LIKES -> kind == LogKind.LIKE
    LogFilter.PARSER -> kind == LogKind.PARSER
    LogFilter.PROBLEMS -> kind == LogKind.WARNING || kind == LogKind.ERROR
}

@Composable
private fun kindColor(kind: LogKind): Color = when (kind) {
    LogKind.ERROR -> AppError
    LogKind.WARNING -> AppWarning
    LogKind.LIKE -> AppSuccess
    LogKind.PARSER -> AppInfo
    LogKind.WORKER -> MaterialTheme.colorScheme.onSurface
    LogKind.INFO -> MaterialTheme.colorScheme.onSurfaceVariant
}

/** logs — від нових до старих. onClear — очистити журнал у Python. */
@Composable
fun EventLogScreen(logs: List<String>, onClear: () -> Unit) {
    val context = LocalContext.current
    var filter by remember { mutableStateOf(LogFilter.ALL) }
    var confirmClear by remember { mutableStateOf(false) }

    val entries = remember(logs) { logs.map { parseLogLine(it) } }
    val shown = remember(entries, filter) { entries.filter { it.matches(filter) } }

    if (confirmClear) {
        ConfirmDialog(
            title = "Очистити журнал?",
            text = "Усі ${logs.size} записів буде видалено. Статистика й черга не постраждають.",
            confirmLabel = "Очистити",
            onConfirm = { confirmClear = false; onClear() },
            onDismiss = { confirmClear = false }
        )
    }

    Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ScreenHeader("Журнал подій", "Що зараз роблять парсер і виконавець")

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                modifier = Modifier.weight(1f).horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                LogFilter.values().forEach { f ->
                    FilterChip(selected = filter == f, onClick = { filter = f }, label = { Text(f.label) })
                }
            }
            IconButton(onClick = { copyLogs(context, entries.reversed()) }, enabled = entries.isNotEmpty()) {
                Icon(Icons.Default.ContentCopy, contentDescription = "Скопіювати журнал")
            }
            IconButton(onClick = { confirmClear = true }, enabled = entries.isNotEmpty()) {
                Icon(Icons.Default.DeleteSweep, contentDescription = "Очистити журнал")
            }
        }

        AppCard(modifier = Modifier.weight(1f)) {
            if (shown.isEmpty()) {
                Column(
                    modifier = Modifier.fillMaxSize().padding(bottom = 24.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        if (entries.isEmpty()) "Журнал поки порожній" else "Немає записів для цього фільтра",
                        fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold
                    )
                    Spacer(Modifier.size(6.dp))
                    Text(
                        "Події з’являться тут після запуску парсера або виконавця.",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(shown) { e -> LogRow(e) }
                }
            }
        }
    }
}

@Composable
private fun LogRow(e: LogEntry) {
    val color = kindColor(e.kind)
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Text(
            e.time,
            modifier = Modifier.width(56.dp),
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Box(modifier = Modifier.padding(top = 5.dp, end = 8.dp).size(7.dp).background(color, CircleShape))
        Text(e.message, modifier = Modifier.weight(1f), fontSize = 13.sp, lineHeight = 18.sp, color = color)
    }
}

/** Копіює журнал у буфер обміну (від старих до нових) — зручно надсилати розробнику замість скріншотів. */
private fun copyLogs(context: Context, chronological: List<LogEntry>) {
    val text = chronological.joinToString("\n") { "${it.time} ${it.level} ${it.message}" }
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText("IGBot журнал", text))
    Toast.makeText(context, "Скопійовано рядків: ${chronological.size}", Toast.LENGTH_SHORT).show()
}
