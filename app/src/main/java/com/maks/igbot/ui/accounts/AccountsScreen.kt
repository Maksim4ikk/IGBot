package com.maks.igbot.ui.accounts

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.maks.igbot.InstagramLoginActivity
import com.maks.igbot.data.BotEngine
import com.maks.igbot.ui.components.AccountCard
import com.maks.igbot.ui.components.ScreenHeader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// ════════════════════════════════════════════════════════════════════
// ВКЛАДКА 4: АКАУНТИ (вхід через Instagram)
// ════════════════════════════════════════════════════════════════════
@Composable
fun AccountsScreen(context: Context) {
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
        ScreenHeader("Акаунти", "Підключення, проксі та сесії")

        AccountCard(
            title = "Основний акаунт · виконавець",
            username = mainUser,
            loggedIn = mainLogged,
            proxy = mainProxy,
            onProxyChange = { mainProxy = it },
            onLogin = { startLogin("main") },
            onLogout = { logout("main") }
        )

        AccountCard(
            title = "Акаунт парсера",
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
