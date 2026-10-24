package com.maks.igbot.ui.dashboard

import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.maks.igbot.BotForegroundService
import com.maks.igbot.data.BotEngine
import com.maks.igbot.ui.accounts.requestIgnoreBatteryOptimizations
import com.maks.igbot.ui.components.ScreenHeader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import com.maks.igbot.ui.theme.BrandAccent
import com.maks.igbot.ui.theme.BrandPrimaryDark

// ════════════════════════════════════════════════════════════════════
// ВКЛАДКА 1: ДАШБОРД
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
    onUnlock: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        ScreenHeader("Огляд", "Контроль активності та стану системи")

        // Картка стану процесів та кнопок Старт/Стоп
        Card(
            colors = CardDefaults.cardColors(containerColor = BrandPrimaryDark),
            shape = RoundedCornerShape(28.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("АВТОМАТИЗАЦІЯ", fontWeight = FontWeight.Bold, fontSize = 11.sp,
                            letterSpacing = 1.4.sp, color = Color(0xFFADB7AE))
                        Spacer(Modifier.height(5.dp))
                        Text("Основний воркер", fontWeight = FontWeight.Bold, fontSize = 20.sp, color = Color.White)
                        Text(
                            if (workerRunning) "Працює у фоні · прогрів ×$warmup" else "Зараз зупинений",
                            color = if (workerRunning) BrandAccent else Color(0xFFADB7AE),
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
                                CoroutineScope(Dispatchers.IO).launch { BotEngine.stopWorker(context) }
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = if (workerRunning) Color(0xFFB84A49) else BrandAccent,
                            contentColor = if (workerRunning) Color.White else BrandPrimaryDark),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Icon(
                            if (workerRunning) Icons.Default.Stop else Icons.Default.PlayArrow,
                            contentDescription = null
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(if (workerRunning) "Стоп" else "Старт")
                    }
                }

                if (scraperRunning) {
                    HorizontalDivider(color = Color(0xFF35423A))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Парсер · $scraperTask", fontSize = 13.sp, color = BrandAccent)
                        TextButton(onClick = {
                            CoroutineScope(Dispatchers.IO).launch { BotEngine.stopScraper(context) }
                        }) {
                            Text("Зупинити", color = Color(0xFFFF9D91))
                        }
                    }
                }
            }
        }

        // Картки черги в базі даних
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatBox("В черзі", ready.toString(), Color(0xFF4F72BE), Modifier.weight(1f))
            StatBox("Оброблено", interacted.toString(), Color(0xFF588958), Modifier.weight(1f))
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatBox("Відсіяно", skipped.toString(), Color(0xFFB17A36), Modifier.weight(1f))
            StatBox("Помилки", errors.toString(), Color(0xFFB84A49), Modifier.weight(1f))
        }

        // Прогрес лімітів за сьогодні та за годину
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            shape = RoundedCornerShape(26.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Ліміти активності", fontWeight = FontWeight.Bold, fontSize = 17.sp)

                LimitProgressRow("❤️ Лайки сьогодні", likesToday, likesDayMax, "За годину: $likesHr/$likesHrMax")
                LimitProgressRow("👁 Сторіс сьогодні", storiesToday, storiesDayMax, "За годину: $storiesHr/$storiesHrMax")
            }
        }

        // Кнопки обслуговування (Unlock + Батарея)
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(
                onClick = onUnlock,
                modifier = Modifier.weight(1f)
            ) {
                Icon(Icons.Default.LockOpen, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Unlock / Помилки", fontSize = 12.sp)
            }
            OutlinedButton(
                onClick = { requestIgnoreBatteryOptimizations(context) },
                modifier = Modifier.weight(1f)
            ) {
                Icon(Icons.Default.BatteryChargingFull, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Фон без сну", fontSize = 12.sp)
            }
        }

        Spacer(Modifier.height(12.dp))
    }
}

@Composable
fun StatBox(label: String, value: String, accent: Color, modifier: Modifier = Modifier) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            shape = RoundedCornerShape(22.dp),
        modifier = modifier
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(label.uppercase(), fontSize = 10.sp, letterSpacing = 0.65.sp,
                fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
            Text(value, fontSize = 30.sp, fontWeight = FontWeight.ExtraBold, color = MaterialTheme.colorScheme.onSurface)
            Spacer(Modifier.height(8.dp))
            androidx.compose.foundation.layout.Box(Modifier.width(28.dp).height(3.dp)
                .then(Modifier), contentAlignment = Alignment.CenterStart) {
                androidx.compose.foundation.layout.Box(Modifier.fillMaxSize().background(accent, RoundedCornerShape(2.dp)))
            }
        }
    }
}

@Composable
fun LimitProgressRow(title: String, current: Int, max: Int, sub: String) {
    val progress = if (max > 0) (current.toFloat() / max.toFloat()).coerceIn(0f, 1f) else 0f
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("$title: $current / $max", fontSize = 13.sp, fontWeight = FontWeight.Medium)
            Text(sub, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        LinearProgressIndicator(
            progress = { progress },
            modifier = Modifier
                .fillMaxWidth()
                .height(8.dp),
            color = MaterialTheme.colorScheme.primary,
            trackColor = MaterialTheme.colorScheme.surfaceVariant,
        )
    }
}

