package com.maks.igbot.ui

import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.maks.igbot.BotForegroundService
import com.maks.igbot.data.BotEngine
import com.maks.igbot.ui.accounts.AccountsScreen
import com.maks.igbot.ui.components.StatusPill
import com.maks.igbot.ui.dashboard.DashboardScreen
import com.maks.igbot.ui.logs.EventLogScreen
import com.maks.igbot.ui.limits.LimitsScreen
import com.maks.igbot.ui.scraper.ScraperScreen
import com.maks.igbot.ui.theme.AppBackground
import com.maks.igbot.ui.theme.AppInfo
import com.maks.igbot.ui.theme.AppSuccess
import com.maks.igbot.ui.theme.AppTextMuted
import com.maks.igbot.ui.theme.BrandAccent
import com.maks.igbot.ui.theme.BrandPrimaryDark
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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

    var lastEvent by remember { mutableStateOf("") }
    var logs by remember { mutableStateOf(listOf<String>()) }  // від нових до старих; оновлюється лише на вкладці «Події»

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

                lastEvent = dash.optString("last_event", "")
            } catch (_: Exception) {
            }
            delay(2000)
        }
    }

    // Журнал підвантажуємо лише поки відкрита вкладка «Події» — без зайвого трафіку до Python
    LaunchedEffect(selectedTab) {
        if (selectedTab != 2) return@LaunchedEffect
        while (true) {
            try {
                logs = withContext(Dispatchers.IO) { BotEngine.getLogs(context, 300) }.reversed()
            } catch (_: Exception) {
            }
            delay(2000)
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Surface(
                            shape = RoundedCornerShape(14.dp),
                            color = BrandAccent,
                            modifier = Modifier.size(40.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(Icons.Default.AutoAwesome, contentDescription = null,
                                    tint = BrandPrimaryDark)
                            }
                        }
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text("IGBot Studio", style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold)
                            Text("Керування автоматизацією", style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                },
                actions = {
                    // Загальний індикатор стану видно на будь-якій вкладці
                    val (pillText, pillColor) = when {
                        workerRunning -> "Працює" to AppSuccess
                        scraperRunning -> "Збір бази" to AppInfo
                        else -> "Очікує" to AppTextMuted
                    }
                    StatusPill(pillText, pillColor, pulsing = workerRunning || scraperRunning)
                    Spacer(Modifier.width(12.dp))
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = AppBackground,
                    titleContentColor = MaterialTheme.colorScheme.onSurface
                ),
                windowInsets = WindowInsets.statusBars
            )
        },
        bottomBar = {
            NavigationBar(
                containerColor = BrandPrimaryDark,
                tonalElevation = 0.dp
            ) {
                NavigationBarItem(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    icon = { Icon(Icons.Default.Dashboard, contentDescription = null) },
                    label = { Text("Огляд") },
                    colors = darkNavColors()
                )
                NavigationBarItem(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    icon = { Icon(Icons.Default.PersonSearch, contentDescription = null) },
                    label = { Text("Парсер") },
                    colors = darkNavColors()
                )
                NavigationBarItem(
                    selected = selectedTab == 2,
                    onClick = { selectedTab = 2 },
                    icon = { Icon(Icons.Default.ReceiptLong, contentDescription = null) },
                    label = { Text("Події") },
                    colors = darkNavColors()
                )
                NavigationBarItem(
                    selected = selectedTab == 3,
                    onClick = { selectedTab = 3 },
                    icon = { Icon(Icons.Default.Tune, contentDescription = null) },
                    label = { Text("Ліміти") },
                    colors = darkNavColors()
                )
                NavigationBarItem(
                    selected = selectedTab == 4,
                    onClick = { selectedTab = 4 },
                    icon = { Icon(Icons.Default.ManageAccounts, contentDescription = null) },
                    label = { Text("Акаунти") },
                    colors = darkNavColors()
                )
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(AppBackground)
                .padding(horizontal = 18.dp)
        ) {
            when (selectedTab) {
                0 -> DashboardScreen(
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
                    lastEvent = lastEvent,
                    onClearCounter = { kind ->
                        scope.launch {
                            val cleared = withContext(Dispatchers.IO) { BotEngine.clearCounter(context, kind) }
                            Toast.makeText(context, "Очищено: $cleared", Toast.LENGTH_SHORT).show()
                        }
                    },
                    onUnlock = {
                        scope.launch(Dispatchers.IO) {
                            BotEngine.unlockAll(context)
                        }
                        Toast.makeText(context, "Блокування знято, помилки повернуто в чергу!", Toast.LENGTH_SHORT).show()
                    }
                )
                1 -> ScraperScreen(
                    context = context,
                    scraperRunning = scraperRunning,
                    scraperTask = scraperTask
                )
                2 -> EventLogScreen(
                    logs = logs,
                    onClear = {
                        scope.launch {
                            withContext(Dispatchers.IO) { BotEngine.clearLogs(context) }
                            logs = emptyList()
                        }
                    }
                )
                3 -> LimitsScreen(context = context)
                4 -> AccountsScreen(context = context)
            }
        }
    }
}

@Composable
private fun darkNavColors() = NavigationBarItemDefaults.colors(
    selectedIconColor = BrandPrimaryDark,
    selectedTextColor = Color.White,
    unselectedIconColor = Color(0xFFAAB2AC),
    unselectedTextColor = Color(0xFFAAB2AC),
    indicatorColor = BrandAccent
)

