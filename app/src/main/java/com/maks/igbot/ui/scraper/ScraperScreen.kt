package com.maks.igbot.ui.scraper

import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.maks.igbot.BotForegroundService
import com.maks.igbot.data.BotEngine
import com.maks.igbot.ui.components.AppCard
import com.maks.igbot.ui.components.InfoNote
import com.maks.igbot.ui.components.ScreenHeader
import com.maks.igbot.ui.components.SectionTitle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// ════════════════════════════════════════════════════════════════════
// ВКЛАДКА 2: ПАРСЕР АУДИТОРІЇ
// ════════════════════════════════════════════════════════════════════
@Composable
fun ScraperScreen(context: Context, scraperRunning: Boolean, scraperTask: String) {
    var sourceType by remember { mutableStateOf("followers") }
    var targetInput by remember { mutableStateOf("") }
    var settingsHint by remember { mutableStateOf("") }

    // Кількість анкет і паузи задаються у вкладці «Ліміти» — тут лише показуємо поточні значення
    LaunchedEffect(Unit) {
        val cfg = withContext(Dispatchers.IO) { BotEngine.getSettingsJson(context) }
        val amount = cfg.optInt("scrape_amount", 50)
        val dMin = cfg.optDouble("scrape_delay_min", 8.0)
        val dMax = cfg.optDouble("scrape_delay_max", 15.0)
        settingsHint = "Збір за запуск: $amount · пауза між анкетами ${dMin.toInt()}–${dMax.toInt()} с. " +
            "Змінити можна у вкладці «Ліміти»."
    }

    val importScope = rememberCoroutineScope()
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            importScope.launch {
                try {
                    val res = withContext(Dispatchers.IO) {
                        val text = context.contentResolver.openInputStream(uri)
                            ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
                            ?: throw IllegalStateException("Не вдалося прочитати файл")
                        BotEngine.importTargets(context, text)
                    }
                    Toast.makeText(
                        context,
                        "Імпортовано: ${res.optInt("added")}, пропущено: ${res.optInt("skipped")} (з ${res.optInt("total")})",
                        Toast.LENGTH_LONG
                    ).show()
                } catch (e: Exception) {
                    Toast.makeText(context, "Помилка імпорту: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        ScreenHeader("Збір аудиторії", "Знайдіть і відфільтруйте потенційних клієнтів")

        AppCard {
            SectionTitle("Нове завдання", "🔎")

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = sourceType == "followers",
                    onClick = { sourceType = "followers" },
                    label = { Text("Підписники") }
                )
                FilterChip(
                    selected = sourceType == "likers",
                    onClick = { sourceType = "likers" },
                    label = { Text("Лайкери поста") }
                )
                FilterChip(
                    selected = sourceType == "hashtag",
                    onClick = { sourceType = "hashtag" },
                    label = { Text("Хештег") }
                )
            }

            Text(
                when (sourceType) {
                    "followers" -> "Бере підписників акаунта-донора, відсіює приватні та магазини, знаходить свіжий пост."
                    "likers" -> "Бере людей, які лайкнули пост, відсіює приватні та магазини, знаходить свіжий пост."
                    else -> "Відкриває сторінку хештегу, дізнається авторів постів і лайкатиме саме ці пости."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            OutlinedTextField(
                value = targetInput,
                onValueChange = { targetInput = it },
                label = {
                    Text(
                        when (sourceType) {
                            "followers" -> "Нікнейм донора (наприклад: kyiv_coffee)"
                            "likers" -> "Посилання на пост (https://www.instagram.com/p/...)"
                            else -> "Хештег без # (наприклад: київкава)"
                        }
                    )
                },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            if (settingsHint.isNotEmpty()) InfoNote(settingsHint, emoji = "⚙️")

            Button(
                onClick = {
                    if (targetInput.isBlank()) {
                        Toast.makeText(context, "Введіть ціль для парсингу!", Toast.LENGTH_SHORT).show()
                        return@Button
                    }
                    // amount не передаємо: Python бере кількість із налаштувань («Ліміти»)
                    val intent = Intent(context, BotForegroundService::class.java).apply {
                        action = "START_SCRAPER"
                        putExtra("source_type", sourceType)
                        putExtra("target", targetInput.trim())
                    }
                    ContextCompat.startForegroundService(context, intent)
                    Toast.makeText(context, "Парсер запущено! Прогрес — на вкладках «Огляд» і «Події».", Toast.LENGTH_SHORT).show()
                },
                enabled = !scraperRunning,
                modifier = Modifier.fillMaxWidth().height(52.dp)
            ) {
                Icon(Icons.Default.Search, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(if (scraperRunning) "Парсер вже працює ($scraperTask)" else "Запустити збір бази")
            }

            if (scraperRunning) {
                OutlinedButton(
                    onClick = { CoroutineScope(Dispatchers.IO).launch { BotEngine.stopScraper(context) } },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Зупинити поточний парсинг", color = MaterialTheme.colorScheme.error)
                }
            }
        }

        AppCard {
            SectionTitle("Імпорт черги з файлу", "📂")
            Text(
                "Якщо ви зібрали чергу на комп'ютері (команда export), завантажте файл targets.json.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            OutlinedButton(
                onClick = { importLauncher.launch(arrayOf("*/*")) },
                modifier = Modifier.fillMaxWidth().height(50.dp)
            ) {
                Text("Імпортувати чергу з JSON")
            }
        }
        Spacer(Modifier.height(12.dp))
    }
}
