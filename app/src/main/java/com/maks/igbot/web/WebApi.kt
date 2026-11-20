package com.maks.igbot.web

import android.webkit.CookieManager
import android.webkit.WebView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.json.JSONTokener

/**
 * Запит до Instagram зсередини спільного WebView — рівно те, що робить сайт у браузері
 * (свої куки, свій TLS-відбиток). Викликається з Python (igbot/friends.py):
 *   jclass("com.maks.igbot.web.WebApi").request(method, path, body, userAgent, cookiesJson)
 *
 * Повертає JSON {"status": HTTP-код | -1 помилка JS/мережі | -2 таймаут | -3 заборонено, "body": "...", "error": "..."}.
 * Дозволені лише GET/POST на /api/v1/..., тому цим мостом не можна відкрити довільну адресу.
 * Спільний mutex WebSession серіалізує виклики з воркером і парсером.
 */
object WebApi {
    private const val ALLOWED_PREFIX = "/api/v1/"
    private const val POLL_STEP_MS = 400L
    private const val POLL_TRIES = 75 // ≈ 30 с

    @Volatile
    private var lastWv: WebView? = null

    @JvmStatic
    fun request(method: String, path: String, body: String, userAgent: String, cookiesJson: String): String =
        runBlocking(Dispatchers.IO) { doRequest(method, path, body, userAgent, cookiesJson) }

    private suspend fun doRequest(
        method: String,
        path: String,
        body: String,
        userAgent: String,
        cookiesJson: String
    ): String {
        val m = method.uppercase()
        if (m != "GET" && m != "POST") return reply(-3, "", "метод не підтримується")
        if (!path.startsWith(ALLOWED_PREFIX) || path.contains("..") || path.any { it == '\n' || it == '\r' }) {
            return reply(-3, "", "шлях не дозволено")
        }
        val wantedUid = try {
            JSONObject(cookiesJson).optString("ds_user_id")
        } catch (e: Exception) {
            ""
        }

        return WebSession.mutex.withLock {
            var wv = lastWv
            if (wv == null || !onInstagram(wv, wantedUid)) {
                wv = WebSession.open(WebSession.BASE + "/", userAgent, cookiesJson)
                    ?: return@withLock reply(-2, "", "сторінка Instagram не завантажилась за 30 с")
                lastWv = wv
                delay(2500) // даємо сторінці виставити csrftoken та ініціалізуватись
            }
            execute(wv, m, path, body)
        }
    }

    /** Чи WebView уже на instagram.com, не на екрані входу і з куками саме потрібного акаунта. */
    private suspend fun onInstagram(wv: WebView, wantedUid: String): Boolean =
        withContext(Dispatchers.Main) {
            val url = wv.url.orEmpty()
            if (!url.startsWith(WebSession.BASE) || url.contains("/accounts/login")) return@withContext false
            val cookie = CookieManager.getInstance().getCookie(WebSession.BASE).orEmpty()
            if (!cookie.contains("sessionid=")) return@withContext false
            val currentUid = Regex("ds_user_id=(\\d+)").find(cookie)?.groupValues?.get(1).orEmpty()
            wantedUid.isBlank() || currentUid == wantedUid
        }

    private suspend fun execute(wv: WebView, method: String, path: String, body: String): String {
        val key = "__igb_" + System.nanoTime()
        val js = """
            (function () {
              window.$key = null;
              var m = document.cookie.match(/(?:^|; )csrftoken=([^;]+)/);
              var headers = {
                'X-IG-App-ID': '936619743392459',
                'X-ASBD-ID': '129477',
                'X-IG-WWW-Claim': '0',
                'X-Requested-With': 'XMLHttpRequest',
                'X-CSRFToken': m ? m[1] : ''
              };
              var opts = {method: ${JSONObject.quote(method)}, credentials: 'include', headers: headers};
              if (opts.method === 'POST') {
                headers['Content-Type'] = 'application/x-www-form-urlencoded';
                opts.body = ${JSONObject.quote(body)};
              }
              fetch(${JSONObject.quote(path)}, opts)
                .then(function (r) { return r.text().then(function (t) { return {status: r.status, body: t}; }); })
                .then(function (o) { window.$key = JSON.stringify(o); })
                .catch(function (e) { window.$key = JSON.stringify({status: -1, body: '', error: String(e)}); });
              return 'started';
            })();
        """.trimIndent()

        WebSession.evalJs(wv, js)
        repeat(POLL_TRIES) {
            delay(POLL_STEP_MS)
            val raw = WebSession.evalJs(wv, "window.$key")
            val result = JSONTokener(raw).nextValue() as? String
            if (result != null) {
                WebSession.evalJs(wv, "delete window.$key")
                return result
            }
        }
        return reply(-2, "", "таймаут запиту (30 с)")
    }

    private fun reply(status: Int, body: String, error: String): String =
        JSONObject().put("status", status).put("body", body).put("error", error).toString()
}
