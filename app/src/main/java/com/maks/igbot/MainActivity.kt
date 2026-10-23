package com.maks.igbot

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

class MainActivity : ComponentActivity() {

    private val notifPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        setContent {
            val darkColors = darkColorScheme(
                primary = Color(0xFFE1306C),     // Фірмовий рожево-малиновий Instagram
                secondary = Color(0xFF833AB4),   // Фіолетовий акцент
                background = Color(0xFF0F0F14),  // Глибокий темний фон
                surface = Color(0xFF1C1C26),     // Фон карток
                onPrimary = Color.White,
                onBackground = Color.White,
                onSurface = Color.White
            )

            MaterialTheme(colorScheme = darkColors) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    IGBotMainApp()
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IGBotMainApp() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var selectedTab by remember { mutableIntStateOf(0) }

    // Стан дашборду (оновлюється кожні 2 секунди)
    var workerRunning by remember { mutableStateOf(false) }
    var scraperRunning by remember { mutableStateOf(false) }
    var scraperTask by remember { mutableStateOf("") }
    var readyCount by remember { mutableIntStateOf(0) }
    var interactedCount by remember { mutableIntStateOf(0) }
    var skippedCount by remember { mutableIntStateOf(0) }
    var errorCount by remember { mutableIntStateOf(0) }
    var warmup by remember { mutableDoubleStateOf(1.0) }

    var likesToday by remember { mutableIntStateOf(0) }
    var likesDayMax by remember { mutableIntStateOf(120) }
    var likesHr by remember { mutableIntStateOf(0) }
    var likesHrMax by remember { mutableIntStateOf(18) }

    var storiesToday by remember { mutableIntStateOf(0) }
    var storiesDayMax by remember { mutableIntStateOf(500) }
    var storiesHr by remember { mutableIntStateOf(0) }
    var storiesHrMax by remember { mutableIntStateOf(50) }

    var logs by remember { mutableStateOf(listOf<String>()) }

    // Фонове опитування Python-ядра кожні 2 секунди
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            BotEngine.ensureInit(context)
        }
        while (true) {
            try {
                val dash = withContext(Dispatchers.IO) { BotEngine.getDashboardJson(context) }
                workerRunning = dash.optBoolean("worker_running", false)
                scraperRunning = dash.optBoolean("scraper_running", false)
                scraperTask = dash.optString("scraper_task", "")
                readyCount = dash.optInt("ready", 0)
                interactedCount = dash.optInt("interacted", 0)
                skippedCount = dash.optInt("skipped", 0)
                errorCount = dash.optInt("errors", 0)
                warmup = dash.optDouble("warmup", 1.0)

                likesToday = dash.optInt("likes_today", 0)
                likesDayMax = dash.optInt("likes_day_max", 120)
                likesHr = dash.optInt("likes_hr", 0)
                likesHrMax = dash.optInt("likes_hr_max", 18)

                storiesToday = dash.optInt("stories_today", 0)
                storiesDayMax = dash.optInt("stories_day_max", 500)
                storiesHr = dash.optInt("stories_hr", 0)
                storiesHrMax = dash.optInt("stories_hr_max", 50)

                val arr = dash.optJSONArray("logs")
                if (arr != null) {
                    val list = mutableListOf<String>()
                    for (i in 0 until arr.length()) {
                        list.add(arr.getString(i))
                    }
                    logs = list.reversed()
                }
            } catch (_: Exception) {
            }
            delay(2000)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.AutoAwesome,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(Modifier.width(10.dp))
                        Text("IGBot Studio", fontWeight = FontWeight.Bold)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color(0xFF16161E)
                )
            )
        },
        bottomBar = {
            NavigationBar(containerColor = Color(0xFF16161E)) {
                NavigationBarItem(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    icon = { Icon(Icons.Default.Dashboard, contentDescription = null) },
                    label = { Text("Дашборд") }
                )
                NavigationBarItem(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    icon = { Icon(Icons.Default.PersonSearch, contentDescription = null) },
                    label = { Text("Парсер") }
                )
                NavigationBarItem(
                    selected = selectedTab == 2,
                    onClick = { selectedTab = 2 },
                    icon = { Icon(Icons.Default.Tune, contentDescription = null) },
                    label = { Text("Ліміти") }
                )
                NavigationBarItem(
                    selected = selectedTab == 3,
                    onClick = { selectedTab = 3 },
                    icon = { Icon(Icons.Default.ManageAccounts, contentDescription = null) },
                    label = { Text("Акаунти") }
                )
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp)
        ) {
            when (selectedTab) {
                0 -> DashboardTab(
                    context = context,
                    workerRunning = workerRunning,
                    scraperRunning = scraperRunning,
                    scraperTask = scraperTask,
                    ready = readyCount,
                    interacted = interactedCount,
                    skipped = skippedCount,
                    errors = errorCount,
                    warmup = warmup,
                    likesToday = likesToday,
                    likesDayMax = likesDayMax,
                    likesHr = likesHr,
                    likesHrMax = likesHrMax,
                    storiesToday = storiesToday,
                    storiesDayMax = storiesDayMax,
                    storiesHr = storiesHr,
                    storiesHrMax = storiesHrMax,
                    logs = logs,
                    onUnlock = {
                        scope.launch(Dispatchers.IO) {
                            BotEngine.unlockAll(context)
                        }
                        Toast.makeText(context, "Блокування знято, помилки повернуто в чергу!", Toast.LENGTH_SHORT).show()
                    }
                )
                1 -> ScraperTab(
                    context = context,
                    scraperRunning = scraperRunning,
                    scraperTask = scraperTask
                )
                2 -> LimitsTab(context = context)
                3 -> AccountsTab(context = context)
            }
        }
    }
}

// ════════════════════════════════════════════════════════════════════
// ВКЛАДКА 1: ДАШБОРД ТА ЖИВИЙ ЛОГ
// ════════════════════════════════════════════════════════════════════
@Composable
fun DashboardTab(
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
    logs: List<String>,
    onUnlock: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Spacer(Modifier.height(4.dp))

        // Картка стану процесів та кнопок Старт/Стоп
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text("Основний Воркер (24/7)", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                        Text(
                            if (workerRunning) "🟢 Працює у фоні (прогрів ×$warmup)" else "⚪️ Зупинений",
                            color = if (workerRunning) Color(0xFF4CAF50) else Color.Gray,
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
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (workerRunning) Color(0xFFD32F2F) else Color(0xFF2E7D32)
                        )
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
                    HorizontalDivider(color = Color(0xFF2E2E3E))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("🔍 Парсер: $scraperTask", fontSize = 13.sp, color = Color(0xFF64B5F6))
                        TextButton(onClick = {
                            CoroutineScope(Dispatchers.IO).launch { BotEngine.stopScraper(context) }
                        }) {
                            Text("Зупинити", color = Color(0xFFEF5350))
                        }
                    }
                }
            }
        }

        // Картки черги в базі даних
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatBox("🎯 В черзі (ready)", ready.toString(), Color(0xFF42A5F5), Modifier.weight(1f))
            StatBox("✅ Оброблено", interacted.toString(), Color(0xFF66BB6A), Modifier.weight(1f))
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatBox("🛡 Відсіяно фільтром", skipped.toString(), Color(0xFFFFA726), Modifier.weight(1f))
            StatBox("⚠️ Помилки", errors.toString(), Color(0xFFEF5350), Modifier.weight(1f))
        }

        // Прогрес лімітів за сьогодні та за годину
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("📈 Виконання лімітів", fontWeight = FontWeight.Bold, fontSize = 15.sp)

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

        // Живий лог
        Card(
            colors = CardDefaults.cardColors(containerColor = Color(0xFF121218)),
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier
                .fillMaxWidth()
                .height(220.dp)
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text("📜 Живий журнал подій (останні дії зверху):", fontSize = 12.sp, color = Color.Gray)
                Spacer(Modifier.height(6.dp))
                if (logs.isEmpty()) {
                    Text("Лог порожній. Запустіть парсер або воркер.", fontSize = 12.sp, color = Color.DarkGray)
                } else {
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        items(logs) { line ->
                            Text(
                                text = line,
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace,
                                color = Color(0xFFD0D0E0)
                            )
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(12.dp))
    }
}

@Composable
fun StatBox(label: String, value: String, accent: Color, modifier: Modifier = Modifier) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(14.dp),
        modifier = modifier
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(label, fontSize = 12.sp, color = Color.Gray)
            Spacer(Modifier.height(4.dp))
            Text(value, fontSize = 22.sp, fontWeight = FontWeight.ExtraBold, color = accent)
        }
    }
}

@Composable
fun LimitProgressRow(title: String, current: Int, max: Int, sub: String) {
    val progress = if (max > 0) (current.toFloat() / max.toFloat()).coerceIn(0f, 1f) else 0f
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("$title: $current / $max", fontSize = 13.sp, fontWeight = FontWeight.Medium)
            Text(sub, fontSize = 12.sp, color = Color.Gray)
        }
        LinearProgressIndicator(
            progress = { progress },
            modifier = Modifier
                .fillMaxWidth()
                .height(8.dp),
            color = MaterialTheme.colorScheme.primary,
            trackColor = Color(0xFF2C2C3A),
        )
    }
}

// ════════════════════════════════════════════════════════════════════
// ВКЛАДКА 2: ПАРСЕР АУДИТОРІЇ
// ════════════════════════════════════════════════════════════════════
@Composable
fun ScraperTab(context: Context, scraperRunning: Boolean, scraperTask: String) {
    var sourceType by remember { mutableStateOf("followers") }
    var targetInput by remember { mutableStateOf("") }
    var amountInput by remember { mutableStateOf("100") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Spacer(Modifier.height(4.dp))
        Text("🔍 Збір та фільтрація цільової бази", fontWeight = FontWeight.Bold, fontSize = 18.sp)
        Text(
            "Технічний акаунт збере анкети, автоматично відсіє закриті сторінки, магазини та порожні профілі і покладе чистих людей у чергу.",
            fontSize = 13.sp,
            color = Color.Gray
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
                Text("Зупинити поточний парсинг", color = Color(0xFFEF5350))
            }
        }
    }
}

// ════════════════════════════════════════════════════════════════════
// ВКЛАДКА 3: НАЛАШТУВАННЯ ЛІМІТІВ ТА РЕЖИМУ
// ════════════════════════════════════════════════════════════════════
@Composable
fun LimitsTab(context: Context) {
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
        Spacer(Modifier.height(4.dp))
        Text("⚙️ Керування швидкістю та діями", fontWeight = FontWeight.Bold, fontSize = 18.sp)

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

// ════════════════════════════════════════════════════════════════════
// ВКЛАДКА 4: АКАУНТИ (вхід через Instagram)
// ════════════════════════════════════════════════════════════════════
@Composable
fun AccountsTab(context: Context) {
    val scope = rememberCoroutineScope()

    var mainUser by remember { mutableStateOf("") }
    var mainLogged by remember { mutableStateOf(false) }
    var mainProxy by remember { mutableStateOf("") }

    var scraperUser by remember { mutableStateOf("") }
    var scraperLogged by remember { mutableStateOf(false) }
    var scraperProxy by remember { mutableStateOf("") }

    suspend fun loadAccounts() {
        val cfg = withContext(Dispatchers.IO) { BotEngine.getSettingsJson(context) }
        mainUser = cfg.optString("main_username", "")
        mainLogged = mainUser.isNotBlank() && cfg.optString("main_sessionid", "").isNotBlank()
        mainProxy = cfg.optString("main_proxy", "")

        scraperUser = cfg.optString("scraper_username", "")
        scraperLogged = scraperUser.isNotBlank() && cfg.optString("scraper_sessionid", "").isNotBlank()
        scraperProxy = cfg.optString("scraper_proxy", "")
    }

    fun saveProxies() {
        val cfg = BotEngine.getSettingsJson(context)
        cfg.put("main_proxy", mainProxy.trim())
        cfg.put("scraper_proxy", scraperProxy.trim())
        BotEngine.saveSettingsJson(context, cfg)
    }

    LaunchedEffect(Unit) { loadAccounts() }

    val loginLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val u = result.data?.getStringExtra(InstagramLoginActivity.RESULT_USERNAME).orEmpty()
            Toast.makeText(context, "Акаунт @$u збережено!", Toast.LENGTH_SHORT).show()
        }
        scope.launch { loadAccounts() }
    }

    fun startLogin(kind: String) {
        scope.launch {
            withContext(Dispatchers.IO) { saveProxies() }
            loginLauncher.launch(
                Intent(context, InstagramLoginActivity::class.java)
                    .putExtra(InstagramLoginActivity.EXTRA_KIND, kind)
            )
        }
    }

    fun logout(kind: String) {
        scope.launch {
            withContext(Dispatchers.IO) { BotEngine.logoutAccount(context, kind) }
            loadAccounts()
            Toast.makeText(context, "Вихід виконано", Toast.LENGTH_SHORT).show()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Spacer(Modifier.height(4.dp))

        AccountCard(
            title = "👤 Основний акаунт (Виконавець)",
            username = mainUser,
            loggedIn = mainLogged,
            proxy = mainProxy,
            onProxyChange = { mainProxy = it },
            onLogin = { startLogin("main") },
            onLogout = { logout("main") }
        )

        AccountCard(
            title = "🔍 Технічний акаунт (Парсер бази)",
            username = scraperUser,
            loggedIn = scraperLogged,
            proxy = scraperProxy,
            onProxyChange = { scraperProxy = it },
            onLogin = { startLogin("scraper") },
            onLogout = { logout("scraper") }
        )

        OutlinedButton(
            onClick = {
                scope.launch(Dispatchers.IO) { saveProxies() }
                Toast.makeText(context, "Проксі збережено!", Toast.LENGTH_SHORT).show()
            },
            modifier = Modifier.fillMaxWidth().height(48.dp)
        ) {
            Icon(Icons.Default.Save, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("Зберегти проксі")
        }
        Spacer(Modifier.height(20.dp))
    }
}

@Composable
fun AccountCard(
    title: String,
    username: String,
    loggedIn: Boolean,
    proxy: String,
    onProxyChange: (String) -> Unit,
    onLogin: () -> Unit,
    onLogout: () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, fontWeight = FontWeight.Bold, fontSize = 16.sp)

            Text(
                if (loggedIn) "🟢 Увійшли як @$username" else "⚪️ Не авторизовано",
                color = if (loggedIn) Color(0xFF4CAF50) else Color.Gray,
                fontSize = 14.sp
            )

            Button(onClick = onLogin, modifier = Modifier.fillMaxWidth().height(48.dp)) {
                Icon(Icons.Default.Login, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(if (loggedIn) "Змінити акаунт" else "Увійти через Instagram")
            }

            if (loggedIn) {
                TextButton(onClick = onLogout, modifier = Modifier.fillMaxWidth()) {
                    Text("Вийти з акаунта", color = Color(0xFFEF5350))
                }
            }

            OutlinedTextField(
                value = proxy,
                onValueChange = onProxyChange,
                label = { Text("Проксі (http://user:pass@ip:port — необов'язково)") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
        }
    }
}

fun requestIgnoreBatteryOptimizations(context: Context) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        if (!pm.isIgnoringBatteryOptimizations(context.packageName)) {
            try {
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:${context.packageName}")
                }
                context.startActivity(intent)
            } catch (_: Exception) {
            }
        } else {
            Toast.makeText(context, "Економію батареї вже вимкнено для IGBot!", Toast.LENGTH_SHORT).show()
        }
    }
}