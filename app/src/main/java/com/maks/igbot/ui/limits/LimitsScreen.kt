package com.maks.igbot.ui.limits

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.maks.igbot.data.BotEngine
import com.maks.igbot.ui.components.ScreenHeader
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
    var watchStories by remember { mutableStateOf(true) }
    var skipBusiness by remember { mutableStateOf(true) }

    var likesPerHour by remember { mutableStateOf("18") }
    var likesPerDay by remember { mutableStateOf("120") }
    var storiesPerHour by remember { mutableStateOf("50") }
    var storiesPerDay by remember { mutableStateOf("500") }
    var startHour by remember { mutableStateOf("9") }
    var endHour by remember { mutableStateOf("23") }

    LaunchedEffect(Unit) {
        val cfg = withContext(Dispatchers.IO) { BotEngine.getSettingsJson(context) }
        likeLastPost = cfg.optBoolean("like_last_post", true)
        watchStories = cfg.optBoolean("watch_stories", true)
        skipBusiness = cfg.optBoolean("skip_business", true)
        likesPerHour = cfg.optInt("likes_per_hour", 18).toString()
        likesPerDay = cfg.optInt("likes_per_day", 120).toString()
        storiesPerHour = cfg.optInt("stories_per_hour", 50).toString()
        storiesPerDay = cfg.optInt("stories_per_day", 500).toString()
        startHour = cfg.optInt("active_start_hour", 9).toString()
        endHour = cfg.optInt("active_end_hour", 23).toString()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        ScreenHeader("Ліміти та режим", "Налаштуйте дії, частоту й активний час")

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("❤️ Ставити лайк на останній пост")
            Switch(checked = likeLastPost, onCheckedChange = { likeLastPost = it })
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("👁 Переглядати сторіс користувача")
            Switch(checked = watchStories, onCheckedChange = { watchStories = it })
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("🏪 Пропускати бізнес-акаунти")
            Switch(checked = skipBusiness, onCheckedChange = { skipBusiness = it })
        }

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedTextField(
                value = likesPerHour,
                onValueChange = { likesPerHour = it.filter { c -> c.isDigit() } },
                label = { Text("Лайків / год") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.weight(1f)
            )
            OutlinedTextField(
                value = likesPerDay,
                onValueChange = { likesPerDay = it.filter { c -> c.isDigit() } },
                label = { Text("Лайків / день") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.weight(1f)
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedTextField(
                value = storiesPerHour,
                onValueChange = { storiesPerHour = it.filter { c -> c.isDigit() } },
                label = { Text("Сторіс / год") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.weight(1f)
            )
            OutlinedTextField(
                value = storiesPerDay,
                onValueChange = { storiesPerDay = it.filter { c -> c.isDigit() } },
                label = { Text("Сторіс / день") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.weight(1f)
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedTextField(
                value = startHour,
                onValueChange = { startHour = it.filter { c -> c.isDigit() } },
                label = { Text("Старт з (год, 0-23)") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.weight(1f)
            )
            OutlinedTextField(
                value = endHour,
                onValueChange = { endHour = it.filter { c -> c.isDigit() } },
                label = { Text("Сон з (год, 1-24)") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.weight(1f)
            )
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
                    BotEngine.saveSettingsJson(context, cfg)
                }
                Toast.makeText(context, "Ліміти збережено!", Toast.LENGTH_SHORT).show()
            },
            modifier = Modifier.fillMaxWidth().height(50.dp)
        ) {
            Icon(Icons.Default.Save, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("Зберегти налаштування")
        }
        Spacer(Modifier.height(16.dp))
    }
}

