package com.maks.igbot.web

import android.annotation.SuppressLint
import android.content.Context
import android.view.View
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import org.json.JSONTokener
import kotlin.coroutines.resume

/**
 * Один прихований WebView для всіх дій на instagram.com: відкриття сторінок, куки потрібного акаунта,
 * виконання JavaScript. Сторінки відкриваються як у звичайному браузері, тому Instagram не блокує їх
 * так, як запити з Python.
 *
 * WebView спільний для двох задач, тому mutex серіалізує їхні переходи й скрипти.
 */
internal object WebSession {
    const val BASE = "https://www.instagram.com"
    private const val PAGE_TIMEOUT_MS = 30_000L

    @Volatile
    var appContext: Context? = null

    val mutex = Mutex()

    @Volatile
    private var webView: WebView? = null

    @Volatile
    private var pageLoaded = CompletableDeferred<Unit>()

    @Volatile
    private var currentUa = ""

    /** Opens a page with the requested cookies and can be interrupted by its owning task. */
    suspend fun open(
        url: String,
        ua: String,
        cookiesJson: String,
        shouldCancel: () -> Boolean = { false },
    ): WebView? {
        if (shouldCancel()) return null
        val ctx = appContext ?: return null
        syncCookies(cookiesJson)
        if (shouldCancel()) return null
        val wv = ensureWebView(ctx, ua)
        if (shouldCancel()) return null
        pageLoaded = CompletableDeferred()
        withContext(Dispatchers.Main) { wv.loadUrl(url) }
        val loaded = withTimeoutOrNull(PAGE_TIMEOUT_MS) {
            while (!pageLoaded.isCompleted && !shouldCancel()) delay(100)
            pageLoaded.isCompleted
        }
        return if (loaded == true && !shouldCancel()) wv else null
    }

    /** Виконує JavaScript і повертає результат як JSON-текст (рядок у лапках, число, null...). */
    suspend fun evalJs(wv: WebView, script: String): String =
        withContext(Dispatchers.Main) {
            suspendCancellableCoroutine<String> { cont ->
                wv.evaluateJavascript(script) { value ->
                    if (cont.isActive) cont.resume(value ?: "null")
                }
            }
        }

    /** Скрипт, що повертає JSON.stringify(...) — розбирає результат в об'єкт. */
    suspend fun evalJson(wv: WebView, script: String): JSONObject {
        val raw = evalJs(wv, script)
        val s = (JSONTokener(raw).nextValue() as? String) ?: return JSONObject()
        return JSONObject(s)
    }

    /** Polls a page script until it matches the stop condition, times out, or its owner is cancelled. */
    suspend fun poll(
        wv: WebView,
        script: String,
        tries: Int,
        stop: (JSONObject) -> Boolean,
        shouldCancel: () -> Boolean = { false },
    ): JSONObject {
        var st = JSONObject()
        for (i in 1..tries) {
            if (shouldCancel()) break
            delay(1000)
            if (shouldCancel()) break
            st = evalJson(wv, script)
            if (shouldCancel() || stop(st)) break
        }
        return st
    }

    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun ensureWebView(ctx: Context, ua: String): WebView {
        val existing = webView
        if (existing != null) {
            if (ua.isNotBlank() && ua != currentUa) {
                withContext(Dispatchers.Main) { existing.settings.userAgentString = ua }
                currentUa = ua
            }
            return existing
        }
        return withContext(Dispatchers.Main) {
            val wv = WebView(ctx.applicationContext)
            wv.settings.javaScriptEnabled = true
            wv.settings.domStorageEnabled = true
            wv.settings.allowFileAccess = false
            wv.settings.allowContentAccess = false
            @Suppress("DEPRECATION")
            run {
                wv.settings.allowFileAccessFromFileURLs = false
                wv.settings.allowUniversalAccessFromFileURLs = false
            }
            wv.settings.javaScriptCanOpenWindowsAutomatically = false
            wv.settings.setSupportMultipleWindows(false)
            wv.settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
            if (ua.isNotBlank()) wv.settings.userAgentString = ua
            // Невидимому WebView потрібен розмір вікна, інакше сторінка не підвантажує списки при прокрутці.
            // Вікно високе навмисно: Instagram малює сітку постів лише коли вона потрапляє у видиму область,
            // а на профілях із довгим описом чи відкритими рекомендаціями вона починається нижче першого екрана.
            wv.measure(
                View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(4800, View.MeasureSpec.EXACTLY)
            )
            wv.layout(0, 0, 1080, 4800)
            CookieManager.getInstance().setAcceptCookie(true)
            CookieManager.getInstance().setAcceptThirdPartyCookies(wv, true)
            wv.webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(
                    view: WebView?,
                    request: android.webkit.WebResourceRequest
                ): Boolean {
                    val uri = request.url
                    val host = uri.host.orEmpty().lowercase()
                    return uri.scheme != "https" ||
                        (host != "instagram.com" && !host.endsWith(".instagram.com"))
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    pageLoaded.complete(Unit)
                }
            }
            wv.onResume()
            wv.resumeTimers()
            webView = wv
            currentUa = ua
            wv
        }
    }

    /** Підставляє в WebView куки потрібного акаунта (з Python-сесії), якщо там зараз інший. */
    private suspend fun syncCookies(cookiesJson: String) {
        withContext(Dispatchers.Main) {
            val wanted = try {
                JSONObject(cookiesJson)
            } catch (e: Exception) {
                return@withContext
            }
            val wantedUid = wanted.optString("ds_user_id")
            if (wantedUid.isBlank() || wanted.optString("sessionid").isBlank()) return@withContext

            val cm = CookieManager.getInstance()
            val current = cm.getCookie(BASE).orEmpty()
            val currentUid = Regex("ds_user_id=(\\d+)").find(current)?.groupValues?.get(1).orEmpty()
            if (currentUid == wantedUid && current.contains("sessionid=")) return@withContext

            suspendCancellableCoroutine<Unit> { c ->
                cm.removeAllCookies { if (c.isActive) c.resume(Unit) }
            }
            for (k in wanted.keys()) {
                val v = wanted.optString(k)
                if (v.isBlank()) continue
                cm.setCookie(BASE, "$k=$v; Domain=.instagram.com; Path=/; Secure")
            }
            cm.flush()
        }
    }
}
