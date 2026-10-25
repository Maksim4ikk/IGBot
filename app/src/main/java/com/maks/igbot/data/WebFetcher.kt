package com.maks.igbot.data

import android.annotation.SuppressLint
import android.content.Context
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/** Результат одного запиту, виконаного всередині WebView. status: -1 = помилка JS/мережі, -2 = таймаут. */
class FetchResult(val status: Int, val body: String, val error: String = "")

/** Міст JavaScript -> Kotlin (клас публічний, метод з @JavascriptInterface). */
class FetchBridge {
    val pending = ConcurrentHashMap<String, CompletableDeferred<String>>()

    @JavascriptInterface
    fun onResult(id: String, json: String) {
        pending.remove(id)?.complete(json)
    }
}

/**
 * Виконує запити до Instagram у справжньому WebView (Chrome-рушій, свої куки).
 * Поки що використовується лише для діагностики: runTest().
 */
object WebFetcher {
    private val bridge = FetchBridge()
    private val mutex = Mutex()

    // Власний scope: тест не залежить від екрана (при перемиканні вкладок екран знищується)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val running = AtomicBoolean(false)

    @Volatile
    private var webView: WebView? = null

    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun ensureWebView(context: Context, userAgent: String) {
        if (webView != null) return
        val loaded = CompletableDeferred<Unit>()
        logPy(context, "[Міст] Завантажую instagram.com у WebView...")
        withContext(Dispatchers.Main) {
            val wv = WebView(context.applicationContext)
            wv.settings.javaScriptEnabled = true
            wv.settings.domStorageEnabled = true
            wv.settings.userAgentString = userAgent
            wv.addJavascriptInterface(bridge, "AndroidFetch")
            CookieManager.getInstance().setAcceptCookie(true)
            CookieManager.getInstance().setAcceptThirdPartyCookies(wv, true)
            wv.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    loaded.complete(Unit)
                }
            }
            webView = wv
            wv.loadUrl("https://www.instagram.com/")
        }
        val ok = withTimeoutOrNull(30_000) { loaded.await() }
        if (ok == null) {
            release()
            throw IllegalStateException("Сторінка Instagram не завантажилась за 30 с")
        }
        logPy(context, "[Міст] Сторінку завантажено, роблю запити...")
        delay(3000) // даємо сторінці виставити csrftoken та ініціалізуватись
    }

    suspend fun release() {
        withContext(NonCancellable + Dispatchers.Main) {
            webView?.destroy()
            webView = null
        }
    }

    /** GET-запит до Instagram зсередини WebView. path — відносний, напр. "/api/v1/users/web_profile_info/?username=x" */
    suspend fun fetch(context: Context, userAgent: String, path: String): FetchResult =
        mutex.withLock {
            ensureWebView(context, userAgent)
            val wv = webView ?: throw IllegalStateException("WebView не створено")

            val id = UUID.randomUUID().toString()
            val deferred = CompletableDeferred<String>()
            bridge.pending[id] = deferred

            val js = """
                (function() {
                  var id = ${JSONObject.quote(id)};
                  var m = document.cookie.match(/(?:^|; )csrftoken=([^;]+)/);
                  fetch(${JSONObject.quote(path)}, {
                    credentials: 'include',
                    headers: {
                      'X-IG-App-ID': '936619743392459',
                      'X-ASBD-ID': '129477',
                      'X-IG-WWW-Claim': '0',
                      'X-Requested-With': 'XMLHttpRequest',
                      'X-CSRFToken': m ? m[1] : ''
                    }
                  })
                  .then(function(r) { return r.text().then(function(t) { return {status: r.status, body: t}; }); })
                  .then(function(o) { AndroidFetch.onResult(id, JSON.stringify(o)); })
                  .catch(function(e) { AndroidFetch.onResult(id, JSON.stringify({status: -1, body: '', error: String(e)})); });
                })();
            """.trimIndent()

            withContext(Dispatchers.Main) { wv.evaluateJavascript(js, null) }

            val raw = withTimeoutOrNull(30_000) { deferred.await() }
            bridge.pending.remove(id)
            if (raw == null) {
                FetchResult(-2, "", "timeout")
            } else {
                val o = JSONObject(raw)
                FetchResult(o.optInt("status", -1), o.optString("body"), o.optString("error"))
            }
        }

    private suspend fun logPy(context: Context, msg: String) {
        withContext(Dispatchers.IO) { BotEngine.logExternal(context, msg) }
    }

    /** Запуск тесту в окремому scope (безпечно викликати з UI). */
    fun startTest(context: Context) {
        if (!running.compareAndSet(false, true)) return
        val app = context.applicationContext
        scope.launch {
            try {
                runTest(app)
            } catch (e: Exception) {
                try {
                    logPy(app, "[Міст] помилка тесту: ${e::class.simpleName}: ${e.message}")
                } catch (_: Exception) {
                }
            } finally {
                running.set(false)
            }
        }
    }

    /**
     * Діагностика: 3 запити (з паузою 5 с) із WebView, результат — у вкладку «Події».
     * Використовує куки останнього входу (вони лишаються в CookieManager).
     */
    suspend fun runTest(context: Context) {
        val cfg = withContext(Dispatchers.IO) { BotEngine.getSettingsJson(context) }
        val ua = cfg.optString("web_user_agent", "").ifBlank {
            WebSettings.getDefaultUserAgent(context).replace("; wv", "").replace("Version/4.0 ", "")
        }

        val cookieRaw = CookieManager.getInstance().getCookie("https://www.instagram.com").orEmpty()
        val hasSession = cookieRaw.contains("sessionid=")
        val uid = Regex("ds_user_id=(\\d+)").find(cookieRaw)?.groupValues?.get(1).orEmpty()
        logPy(context, "[Міст] Старт тесту. sessionid в WebView: ${if (hasSession) "є" else "НЕМАЄ"}, uid=$uid")
        if (!hasSession) {
            logPy(context, "[Міст] Спершу увійдіть в акаунт через вкладку «Акаунти» (куки лишаються після входу).")
            return
        }

        val tests = listOf(
            "web_profile_info" to "/api/v1/users/web_profile_info/?username=instagram",
            "users/info" to "/api/v1/users/$uid/info/",
            "feed/user" to "/api/v1/feed/user/instagram/username/?count=2",
        )
        try {
            for ((name, path) in tests) {
                val r = fetch(context, ua, path)
                val snippet = r.body.take(140).replace("\n", " ")
                logPy(context, "[Міст] $name -> HTTP ${r.status} ${r.error} | довжина=${r.body.length} | $snippet")
                delay(5000)
            }
            logPy(context, "[Міст] Тест завершено.")
        } finally {
            release()
        }
    }
}
