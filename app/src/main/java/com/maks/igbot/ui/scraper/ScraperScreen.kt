package com.maks.igbot.ui.scraper

import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.maks.igbot.BotForegroundService
import com.maks.igbot.data.BotEngine
import com.maks.igbot.ui.components.ScreenHeader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

// ════════════════════════════════════════════════════════════════════
// ВКЛАДКА 2: ПАРСЕР АУДИТОРІЇ
// ════════════════════════════════════════════════════════════════════
@Composable
fun ScraperScreen(context: Context, scraperRunning: Boolean, scraperTask: String) {
    var sourceType by remember { mutableStateOf("followers") }
    var targetInput by remember { mutableStateOf("") }
    var amountInput by remember { mutableStateOf("100") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        ScreenHeader("Збір аудиторії", "Знайдіть і відфільтруйте потенційних клієнтів")
        Text(
            "Оберіть джерело, задайте ціль і кількість профілів для перевірки.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        // Вибір типу джерела
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

        if (sourceType != "likers") {
            OutlinedTextField(
                value = amountInput,
                onValueChange = { amountInput = it.filter { ch -> ch.isDigit() } },
                label = { Text("Скільки кандидатів перевірити (напр. 100)") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
        }

        Button(
            onClick = {
                if (targetInput.isBlank()) {
                    Toast.makeText(context, "Введіть ціль для парсингу!", Toast.LENGTH_SHORT).show()
                    return@Button
                }
                val amt = amountInput.toIntOrNull() ?: 100
                val intent = Intent(context, BotForegroundService::class.java).apply {
                    action = "START_SCRAPER"
                    putExtra("source_type", sourceType)
                    putExtra("target", targetInput.trim())
                    putExtra("amount", amt)
                }
                ContextCompat.startForegroundService(context, intent)
                Toast.makeText(context, "Парсер запущено! Дивіться прогрес на Дашборді.", Toast.LENGTH_SHORT).show()
            },
            enabled = !scraperRunning,
            modifier = Modifier.fillMaxWidth().height(50.dp)
        ) {
            Icon(Icons.Default.Search, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text(if (scraperRunning) "Парсер вже працює ($scraperTask)" else "Запустити збір бази")
        }

        if (scraperRunning) {
            OutlinedButton(
                onClick = {
                    CoroutineScope(Dispatchers.IO).launch { BotEngine.stopScraper(context) }
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Зупинити поточний парсинг", color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

