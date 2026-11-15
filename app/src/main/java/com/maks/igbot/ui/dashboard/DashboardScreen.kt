package com.maks.igbot.ui.dashboard

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.maks.igbot.BotForegroundService
import com.maks.igbot.data.BotEngine
import com.maks.igbot.ui.accounts.requestIgnoreBatteryOptimizations
import com.maks.igbot.ui.components.AppCard
import com.maks.igbot.ui.components.ConfirmDialog
import com.maks.igbot.ui.components.LimitRow
import com.maks.igbot.ui.components.ScreenHeader
import com.maks.igbot.ui.components.SectionTitle
import com.maks.igbot.ui.components.StatTile
import com.maks.igbot.ui.components.StatusPill
import com.maks.igbot.ui.logs.parseLogLine
import com.maks.igbot.ui.theme.AppError
import com.maks.igbot.ui.theme.AppErrorSoft
import com.maks.igbot.ui.theme.AppInfo
import com.maks.igbot.ui.theme.AppInfoSoft
import com.maks.igbot.ui.theme.AppSuccess
import com.maks.igbot.ui.theme.AppSuccessSoft
import com.maks.igbot.ui.theme.AppTextMuted
import com.maks.igbot.ui.theme.AppWarning
import com.maks.igbot.ui.theme.AppWarningSoft
import com.maks.igbot.ui.theme.BrandAccent
import com.maks.igbot.ui.theme.BrandPrimaryDark
import com.maks.igbot.ui.theme.DarkDivider
import com.maks.igbot.ui.theme.OnDarkMuted
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Яку плитку збираються очистити (ключі збігаються з Python: ready | interacted | skipped | errors). */
private data class ClearRequest(val kind: String, val title: String, val text: String, val button: String)

// ════════════════════════════════════════════════════════════════════
// ВКЛАДКА 1: ОГЛЯД
// ════════════════════════════════════════════════════════════════════
@Composable
fun DashboardScreen(
    context: Context,
    workerRunning: Boolean,
    scraperRunning: Boolean,
    scraperTask: String,
    ready: Int,
    interacted: Int,
    skipped: Int,
    errors: Int,
    warmup: Double,
    likesToday: Int,
    likesDayMax: Int,
    likesHr: Int,
    likesHrMax: Int,
    storiesToday: Int,
    storiesDayMax: Int,
    storiesHr: Int,
    storiesHrMax: Int,
    lastEvent: String,
    onClearCounter: (String) -> Unit,
    onUnlock: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var pending by remember { mutableStateOf<ClearRequest?>(null) }

    pending?.let { req ->
        ConfirmDialog(
            title = req.title,
            text = req.text,
            confirmLabel = req.button,
            onConfirm = { onClearCounter(req.kind); pending = null },
            onDismiss = { pending = null }
        )
    }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        ScreenHeader("Огляд", "Контроль активності та стану системи")

        // ── Стан і керування ────────────────────────────────────────
        Card(
            colors = CardDefaults.cardColors(containerColor = BrandPrimaryDark),
            shape = RoundedCornerShape(28.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    StatusPill(
                        text = if (workerRunning) "Воркер працює" else "Воркер зупинений",
                        color = if (workerRunning) BrandAccent else OnDarkMuted,
                        pulsing = workerRunning
                    )
                    if (scraperRunning) {
                        StatusPill(text = "Парсер", color = Color(0xFF8FB4FF), pulsing = true)
                    }
                }

                Column {
                    Text("Основний воркер", fontWeight = FontWeight.Bold, fontSize = 22.sp, color = Color.White)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        if (workerRunning) "Лайкає у фоні · прогрів ×$warmup" else "Натисніть «Старт», щоб почати роботу",
                        color = OnDarkMuted,
                        fontSize = 13.sp
                    )
                }

                Button(
                    onClick = {
                        if (!workerRunning) {
                            val intent = Intent(context, BotForegroundService::class.java).apply {
                                action = "START_WORKER"
                            }
                            ContextCompat.startForegroundService(context, intent)
                        } else {
                            scope.launch { withContext(Dispatchers.IO) { BotEngine.stopWorker(context) } }
                        }
                    },
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (workerRunning) Color(0xFFB84A49) else BrandAccent,
                        contentColor = if (workerRunning) Color.White else BrandPrimaryDark
                    ),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Icon(if (workerRunning) Icons.Default.Stop else Icons.Default.PlayArrow, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(if (workerRunning) "Зупинити" else "Старт", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                }

                if (scraperRunning) {
                    HorizontalDivider(color = DarkDivider)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "Парсер · $scraperTask",
                            fontSize = 13.sp,
                            color = Color(0xFF8FB4FF),
                            modifier = Modifier.weight(1f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        TextButton(onClick = {
                            scope.launch { withContext(Dispatchers.IO) { BotEngine.stopScraper(context) } }
                        }) {
                            Text("Зупинити", color = Color(0xFFFF9D91))
                        }
                    }
                }

                val last = remember(lastEvent) { if (lastEvent.isBlank()) "" else parseLogLine(lastEvent).message }
                if (last.isNotBlank()) {
                    HorizontalDivider(color = DarkDivider)
                    Text(
                        "Остання подія: $last",
                        fontSize = 12.sp,
                        color = OnDarkMuted,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }

        // ── Черга та результати (кошик очищає значення) ─────────────
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatTile(
                "В черзі", ready, "📥", AppInfo, AppInfoSoft,
                onClear = {
                    pending = ClearRequest(
                        "ready", "Очистити чергу?",
                        "Буде видалено $ready анкет, що чекають на лайк. Цю дію не можна скасувати.", "Очистити"
                    )
                },
                modifier = Modifier.weight(1f)
            )
            StatTile(
                "Оброблено", interacted, "✅", AppSuccess, AppSuccessSoft,
                onClear = {
                    pending = ClearRequest(
                        "interacted", "Скинути «Оброблено»?",
                        "Лічильник стане 0. Історію ми збережемо, тому бот не лайкатиме тих самих людей повторно.",
                        "Скинути"
                    )
                },
                modifier = Modifier.weight(1f)
            )
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatTile(
                "Відсіяно", skipped, "🚫", AppWarning, AppWarningSoft,
                onClear = {
                    pending = ClearRequest(
                        "skipped", "Скинути «Відсіяно»?",
                        "Лічильник стане 0. Відсіяних (приватні, без постів, магазини) бот пам'ятатиме й не перевірятиме повторно.",
                        "Скинути"
                    )
                },
                modifier = Modifier.weight(1f)
            )
            StatTile(
                "Помилки", errors, "⚠️", AppError, AppErrorSoft,
                onClear = {
                    pending = ClearRequest(
                        "errors", "Видалити помилки?",
                        "Буде видалено $errors записів із помилками. Щоб спробувати їх ще раз, натисніть «Unlock / Помилки».",
                        "Видалити"
                    )
                },
                modifier = Modifier.weight(1f)
            )
        }

        // ── Ліміти ──────────────────────────────────────────────────
        AppCard {
            SectionTitle("Ліміти активності", "📊")
            LimitRow("❤️ Лайки сьогодні", likesToday, likesDayMax, "За годину: $likesHr / $likesHrMax")
            LimitRow("👁 Сторіс сьогодні", storiesToday, storiesDayMax, "За годину: $storiesHr / $storiesHrMax")
        }

        // ── Обслуговування ──────────────────────────────────────────
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(onClick = onUnlock, modifier = Modifier.weight(1f)) {
                Icon(Icons.Default.LockOpen, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Unlock / Помилки", fontSize = 12.sp)
            }
            OutlinedButton(onClick = { requestIgnoreBatteryOptimizations(context) }, modifier = Modifier.weight(1f)) {
                Icon(Icons.Default.BatteryChargingFull, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Фон без сну", fontSize = 12.sp)
            }
        }
        Spacer(Modifier.height(12.dp))
    }
}
