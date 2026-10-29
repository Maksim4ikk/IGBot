package com.maks.igbot.ui.limits

import android.content.Context
import android.widget.Toast
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
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
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
import com.maks.igbot.data.BotEngine
import com.maks.igbot.ui.components.AppCard
import com.maks.igbot.ui.components.InfoNote
import com.maks.igbot.ui.components.NumField
import com.maks.igbot.ui.components.ScreenHeader
import com.maks.igbot.ui.components.SectionTitle
import com.maks.igbot.ui.components.SettingSwitch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// ════════════════════════════════════════════════════════════════════
// ВКЛАДКА 3: НАЛАШТУВАННЯ ЛІМІТІВ ТА РЕЖИМУ
// ════════════════════════════════════════════════════════════════════
@Composable
fun LimitsScreen(context: Context) {
    val scope = rememberCoroutineScope()
    var likeLastPost by remember { mutableStateOf(true) }
    var watchStories by remember { mutableStateOf(false) }
    var skipBusiness by remember { mutableStateOf(true) }

    var likesPerHour by remember { mutableStateOf("18") }
    var likesPerDay by remember { mutableStateOf("120") }
    var storiesPerHour by remember { mutableStateOf("50") }
    var storiesPerDay by remember { mutableStateOf("500") }
    var startHour by remember { mutableStateOf("9") }
    var endHour by remember { mutableStateOf("23") }

    var scrapeAmount by remember { mutableStateOf("50") }
    var scrapeDelayMin by remember { mutableStateOf("8") }
    var scrapeDelayMax by remember { mutableStateOf("15") }
    var breakEveryMin by remember { mutableStateOf("35") }
    var breakEveryMax by remember { mutableStateOf("50") }
    var breakMinutesMin by remember { mutableStateOf("2") }
    var breakMinutesMax by remember { mutableStateOf("4") }

    LaunchedEffect(Unit) {
        val cfg = withContext(Dispatchers.IO) { BotEngine.getSettingsJson(context) }
        likeLastPost = cfg.optBoolean("like_last_post", true)
        watchStories = cfg.optBoolean("watch_stories", false)
        skipBusiness = cfg.optBoolean("skip_business", true)
        likesPerHour = cfg.optInt("likes_per_hour", 18).toString()
        likesPerDay = cfg.optInt("likes_per_day", 120).toString()
        storiesPerHour = cfg.optInt("stories_per_hour", 50).toString()
        storiesPerDay = cfg.optInt("stories_per_day", 500).toString()
        startHour = cfg.optInt("active_start_hour", 9).toString()
        endHour = cfg.optInt("active_end_hour", 23).toString()
        scrapeAmount = cfg.optInt("scrape_amount", 50).toString()
        scrapeDelayMin = fmtNum(cfg.optDouble("scrape_delay_min", 8.0))
        scrapeDelayMax = fmtNum(cfg.optDouble("scrape_delay_max", 15.0))
        breakEveryMin = cfg.optInt("scrape_break_every_min", 35).toString()
        breakEveryMax = cfg.optInt("scrape_break_every_max", 50).toString()
        breakMinutesMin = fmtNum(cfg.optDouble("scrape_break_minutes_min", 2.0))
        breakMinutesMax = fmtNum(cfg.optDouble("scrape_break_minutes_max", 4.0))
    }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        ScreenHeader("Ліміти та режим", "Що робить бот, як часто і коли")

        AppCard {
            SectionTitle("Дії бота", "🎛️")
            SettingSwitch(
                title = "Лайкати останній пост",
                subtitle = "Відкриває пост і ставить «подобається»",
                checked = likeLastPost, onChange = { likeLastPost = it }, emoji = "❤️"
            )
            SettingSwitch(
                title = "Переглядати сторіс",
                subtitle = "Ненадійно: Instagram часто блокує цей запит",
                checked = watchStories, onChange = { watchStories = it }, emoji = "👁"
            )
            SettingSwitch(
                title = "Пропускати бізнес-акаунти",
                subtitle = "Магазини та сторінки компаній відсіюються",
                checked = skipBusiness, onChange = { skipBusiness = it }, emoji = "🏪"
            )
        }

        AppCard {
            SectionTitle("Частота дій", "⏱️")
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                NumField(likesPerHour, { likesPerHour = it }, "Лайків / год", Modifier.weight(1f))
                NumField(likesPerDay, { likesPerDay = it }, "Лайків / день", Modifier.weight(1f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                NumField(storiesPerHour, { storiesPerHour = it }, "Сторіс / год", Modifier.weight(1f))
                NumField(storiesPerDay, { storiesPerDay = it }, "Сторіс / день", Modifier.weight(1f))
            }
            InfoNote("Перші дні ліміти автоматично менші (прогрів нового акаунта).")
        }

        AppCard {
            SectionTitle("Робочі години", "🌙")
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                NumField(startHour, { startHour = it }, "Старт з (0–23)", Modifier.weight(1f))
                NumField(endHour, { endHour = it }, "Сон з (1–24)", Modifier.weight(1f))
            }
        }

        AppCard {
            SectionTitle("Парсер аудиторії", "🔎")
            NumField(scrapeAmount, { scrapeAmount = it }, "Скільки анкет збирати за запуск", Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                NumField(scrapeDelayMin, { scrapeDelayMin = it }, "Пауза від (с)", Modifier.weight(1f), decimal = true)
                NumField(scrapeDelayMax, { scrapeDelayMax = it }, "до (с)", Modifier.weight(1f), decimal = true)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                NumField(breakEveryMin, { breakEveryMin = it }, "Перерва кожні, анкет", Modifier.weight(1f))
                NumField(breakEveryMax, { breakEveryMax = it }, "до (анкет)", Modifier.weight(1f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                NumField(breakMinutesMin, { breakMinutesMin = it }, "Перерва від (хв)", Modifier.weight(1f), decimal = true)
                NumField(breakMinutesMax, { breakMinutesMax = it }, "до (хв)", Modifier.weight(1f), decimal = true)
            }
            InfoNote("Повільніший збір безпечніший для технічного акаунта. Зміни діють з наступного запуску парсера.")
        }

        Button(
            onClick = {
                scope.launch(Dispatchers.IO) {
                    val cfg = BotEngine.getSettingsJson(context)
                    cfg.put("like_last_post", likeLastPost)
                    cfg.put("watch_stories", watchStories)
                    cfg.put("skip_business", skipBusiness)
                    cfg.put("likes_per_hour", likesPerHour.toIntOrNull() ?: 18)
                    cfg.put("likes_per_day", likesPerDay.toIntOrNull() ?: 120)
                    cfg.put("stories_per_hour", storiesPerHour.toIntOrNull() ?: 50)
                    cfg.put("stories_per_day", storiesPerDay.toIntOrNull() ?: 500)
                    cfg.put("active_start_hour", (startHour.toIntOrNull() ?: 9).coerceIn(0, 22))
                    cfg.put("active_end_hour", (endHour.toIntOrNull() ?: 23).coerceIn(1, 24))

                    cfg.put("scrape_amount", (scrapeAmount.toIntOrNull() ?: 50).coerceIn(1, 5000))
                    val dMin = parseNum(scrapeDelayMin, 8.0).coerceAtLeast(1.0)
                    val dMax = parseNum(scrapeDelayMax, 15.0).coerceAtLeast(dMin)
                    cfg.put("scrape_delay_min", dMin)
                    cfg.put("scrape_delay_max", dMax)
                    val bMin = (breakEveryMin.toIntOrNull() ?: 35).coerceAtLeast(1)
                    val bMax = (breakEveryMax.toIntOrNull() ?: 50).coerceAtLeast(bMin)
                    cfg.put("scrape_break_every_min", bMin)
                    cfg.put("scrape_break_every_max", bMax)
                    val mMin = parseNum(breakMinutesMin, 2.0).coerceAtLeast(0.0)
                    val mMax = parseNum(breakMinutesMax, 4.0).coerceAtLeast(mMin)
                    cfg.put("scrape_break_minutes_min", mMin)
                    cfg.put("scrape_break_minutes_max", mMax)
                    BotEngine.saveSettingsJson(context, cfg)
                }
                Toast.makeText(context, "Налаштування збережено!", Toast.LENGTH_SHORT).show()
            },
            modifier = Modifier.fillMaxWidth().height(52.dp)
        ) {
            Icon(Icons.Default.Save, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("Зберегти налаштування")
        }
        Spacer(Modifier.height(16.dp))
    }
}

private fun fmtNum(d: Double): String = if (d % 1.0 == 0.0) d.toInt().toString() else d.toString()

private fun parseNum(s: String, default: Double): Double = s.replace(',', '.').toDoubleOrNull() ?: default
