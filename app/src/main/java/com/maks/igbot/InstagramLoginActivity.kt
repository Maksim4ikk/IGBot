package com.maks.igbot

import com.maks.igbot.data.BotEngine



import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebSettings
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import kotlin.coroutines.resume

/**
 * Екран входу в Instagram через WebView.
 * kind = "main" (основний акаунт) або "scraper" (технічний).
 *
 * Після успішного входу:
 *  1. нікнейм дізнається сам WebView (fetch зсередини сторінки Instagram);
 *  2. cookies + нікнейм передаються в Python (BotEngine.registerAccount),
 *     який просто зберігає акаунт без жодних запитів до Instagram (без 429).
 */
class InstagramLoginActivity : ComponentActivity() {

    companion object {
        const val EXTRA_KIND = "kind"
        const val RESULT_USERNAME = "username"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val kind = intent.getStringExtra(EXTRA_KIND) ?: "main"

        setContent {
            val colors = darkColorScheme(
                primary = Color(0xFFE1306C),
                secondary = Color(0xFF833AB4),
                background = Color(0xFF0F0F14),
                surface = Color(0xFF1C1C26),
                onPrimary = Color.White,
                onBackground = Color.White,
                onSurface = Color.White
            )
            MaterialTheme(colorScheme = colors) {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    LoginScreen(
                        kind = kind,
                        onClose = {
                            setResult(RESULT_CANCELED)
                            finish()
                        },
                        onDone = { username ->
                            setResult(RESULT_OK, Intent().putExtra(RESULT_USERNAME, username))
                            finish()
                        }
                    )
                }
            }
        }
    }
}

private val KEEP_COOKIES = setOf("sessionid", "ds_user_id", "mid", "ig_did", "datr", "ig_nrcb")

private fun readInstagramCookies(): Map<String, String> {
    val raw = CookieManager.getInstance().getCookie("https://www.instagram.com") ?: return emptyMap()
    val map = mutableMapOf<String, String>()
    for (part in raw.split(";")) {
        val i = part.indexOf('=')
        if (i > 0) map[part.substring(0, i).trim()] = part.substring(i + 1).trim()
    }
    return map
}

private suspend fun clearWebData() = suspendCancellableCoroutine<Unit> { cont ->
    val cm = CookieManager.getInstance()
    WebStorage.getInstance().deleteAllData()
    cm.removeAllCookies {
        cm.flush()
        if (cont.isActive) cont.resume(Unit)
    }
}

/**
 * Міст JavaScript -> Kotlin. Клас публічний, а метод має @JavascriptInterface,
 * інакше WebView не побачить його.
 */
class UsernameBridge {
    @Volatile
    var pending: CompletableDeferred<String>? = null

    @JavascriptInterface
    fun onResult(value: String) {
        pending?.complete(value)
    }
}

/** Просить сам WebView отримати нікнейм (запит іде як від справжнього браузера). */
private suspend fun fetchUsernameInWebView(webView: WebView, bridge: UsernameBridge, uid: String): String {
    val safeUid = uid.filter { it.isDigit() }
    if (safeUid.isEmpty()) return ""

    repeat(3) {
        val deferred = CompletableDeferred<String>()
        bridge.pending = deferred
        withContext(Dispatchers.Main) {
            val js = """
                (function() {
                  fetch('/api/v1/users/$safeUid/info/', {
                    credentials: 'include',
                    headers: {'X-IG-App-ID': '936619743392459', 'X-Requested-With': 'XMLHttpRequest'}
                  })
                  .then(function(r) { return r.json(); })
                  .then(function(d) {
                    AndroidBridge.onResult((d && d.user && d.user.username) ? d.user.username : '');
                  })
                  .catch(function() { AndroidBridge.onResult(''); });
                })();
            """.trimIndent()
            webView.evaluateJavascript(js, null)
        }
        val name = withTimeoutOrNull(15000) { deferred.await() } ?: ""
        if (name.isNotBlank()) return name
        delay(2500)
    }
    return ""
}

@OptIn(ExperimentalMaterial3Api::class)
@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun LoginScreen(kind: String, onClose: () -> Unit, onDone: (String) -> Unit) {
    val context = LocalContext.current

    var ready by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var attempt by remember { mutableIntStateOf(0) }

    var webViewRef by remember { mutableStateOf<WebView?>(null) }
    val bridge = remember { UsernameBridge() }

    // Звичайний мобільний Chrome UA (без "wv"), щоб Instagram не мав зайвих підозр.
    // Цей самий UA зберігається в налаштуваннях, бо sessionid прив'язаний до User-Agent.
    val userAgent = remember {
        WebSettings.getDefaultUserAgent(context)
            .replace("; wv", "")
            .replace("Version/4.0 ", "")
    }

    // Чистимо старі cookies, щоб не «підхопити» попередній акаунт
    LaunchedEffect(attempt) {
        clearWebData()
        ready = true
    }

    // Чекаємо, поки з'явиться sessionid — це означає, що вхід завершено
    LaunchedEffect(ready) {
        if (!ready) return@LaunchedEffect
        while (true) {
            val c = readInstagramCookies()
            if (c.containsKey("sessionid") && c.containsKey("ds_user_id")) break
            delay(1000)
        }
        delay(3000) // даємо Instagram дописати решту cookies

        val all = readInstagramCookies()
        val cookies = all.filterKeys { it in KEEP_COOKIES }
        val uid = all["ds_user_id"].orEmpty()

        // Нікнейм беремо з WebView, поки він ще на екрані
        val wv = webViewRef
        val username = if (wv != null) fetchUsernameInWebView(wv, bridge, uid) else ""

        saving = true
        try {
            val json = JSONObject()
            cookies.forEach { (k, v) -> json.put(k, v) }
            val res = withContext(Dispatchers.IO) {
                BotEngine.registerAccount(context, kind, json.toString(), userAgent, username)
            }
            onDone(res.optString("username", ""))
        } catch (e: Exception) {
            error = e.message ?: "Невідома помилка"
            saving = false
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(if (kind == "main") "Вхід: основний акаунт" else "Вхід: технічний акаунт")
                },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(Icons.Default.Close, contentDescription = "Закрити")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFF16161E))
            )
        }
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            when {
                error != null -> {
                    Column(
                        modifier = Modifier.fillMaxSize().padding(24.dp),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text("Не вдалося зберегти акаунт", fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(8.dp))
                        Text(error ?: "", color = Color(0xFFEF5350))
                        Spacer(Modifier.height(16.dp))
                        Button(onClick = {
                            error = null
                            ready = false
                            attempt++
                        }) { Text("Спробувати ще раз") }
                    }
                }

                saving -> {
                    Column(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        CircularProgressIndicator()
                        Spacer(Modifier.height(16.dp))
                        Text("Зберігаю акаунт...")
                    }
                }

                ready -> {
                    AndroidView(
                        modifier = Modifier.fillMaxSize(),
                        factory = { ctx ->
                            WebView(ctx).apply {
                                layoutParams = ViewGroup.LayoutParams(
                                    ViewGroup.LayoutParams.MATCH_PARENT,
                                    ViewGroup.LayoutParams.MATCH_PARENT
                                )
                                settings.javaScriptEnabled = true
                                settings.domStorageEnabled = true
                                settings.userAgentString = userAgent
                                addJavascriptInterface(bridge, "AndroidBridge")
                                webViewRef = this
                                CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                                webViewClient = WebViewClient()
                                loadUrl("https://www.instagram.com/accounts/login/")
                            }
                        },
                        onRelease = {
                            webViewRef = null
                            it.destroy()
                        }
                    )
                }

                else -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }
            }
        }
    }
}