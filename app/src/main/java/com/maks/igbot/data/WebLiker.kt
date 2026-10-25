package com.maks.igbot.data

import android.annotation.SuppressLint
import android.content.Context
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import org.json.JSONTokener
import kotlin.coroutines.resume

/**
 * Лайк поста "як людина": відкриває сторінку поста у прихованому WebView і натискає кнопку лайка.
 * Не залежить від прихованих ендпоінтів/doc_id Instagram (вони постійно змінюються).
 * Викликається з Python: jclass("com.maks.igbot.data.WebLiker").like(url, userAgent, cookiesJson)
 * Повертає JSON {"status": ..., "detail": ...}.
 * status: liked | already_liked | login_required | no_button | not_confirmed | timeout | error
 */
object WebLiker {
    @Volatile
    var appContext: Context? = null

    private const val BASE = "https://www.instagram.com"
    private val mutex = Mutex()

    @Volatile
    private var webView: WebView? = null

    @Volatile
    private var pageLoaded = CompletableDeferred<Unit>()

    @Volatile
    private var currentUa = ""

    // Спільна частина JS: шукаємо іконку лайка саме в пості (а не логотип чи меню навігації).
    private val FIND_JS = """
        var LIKE = ['like','подобається','нравится','мне нравится','me gusta','gefällt mir','j’aime',"j'aime",'mi piace','curtir','beğen','lubię to','to se mi líbí','tetszik'];
        var UNLIKE = ['unlike','не подобається','не нравится','ya no me gusta','gefällt mir nicht mehr','je n’aime plus',"je n'aime plus",'non mi piace più','descurtir','beğenmekten vazgeç','przestań lubić','už se mi nelíbí','mégsem tetszik'];
        function norm(s) { return (s || '').toLowerCase().trim(); }
        function findIcon() {
          var scope = document.querySelector('article') || document.querySelector('main') || document.body;
          var all = Array.prototype.slice.call(scope.querySelectorAll('svg[aria-label]'));
          var svgs = all.filter(function (s) {
            return !s.closest('nav, header') && norm(s.getAttribute('aria-label')) !== 'instagram';
          });
          var labels = svgs.map(function (s) { return s.getAttribute('aria-label'); });
          var i, l;
          for (i = 0; i < svgs.length; i++) {
            l = norm(svgs[i].getAttribute('aria-label'));
            if (LIKE.indexOf(l) >= 0 || UNLIKE.indexOf(l) >= 0) return {icon: svgs[i], labels: labels};
          }
          // структурний резерв: перша іконка в блоці дій (section, де є щонайменше 3 іконки)
          for (i = 0; i < svgs.length; i++) {
            var sec = svgs[i].closest('section');
            if (!sec) continue;
            var inSec = svgs.filter(function (s) { return sec.contains(s); });
            if (inSec.length >= 3) return {icon: inSec[0], labels: labels};
          }
          return {icon: null, labels: labels};
        }
    """.trimIndent()

    private val STATE_JS = """
        (function () {
          $FIND_JS
          var txt = ((document.body && document.body.innerText) || '').replace(/\s+/g, ' ').slice(0, 100);
          var res = {login: false, found: false, liked: false, label: '', labels: '',
                     title: (document.title || '').slice(0, 60), path: location.pathname.slice(0, 40), text: txt};
          if (location.pathname.indexOf('/accounts/login') === 0 || document.querySelector('input[name="username"]')) {
            res.login = true;
            return JSON.stringify(res);
          }
          function rgb(c) {
            if (!c) return null;
            var m = /rgba?\((\d+),\s*(\d+),\s*(\d+)/.exec(c);
            if (m) return [+m[1], +m[2], +m[3]];
            m = /^#([0-9a-f]{6})${'$'}/i.exec(String(c).trim());
            if (m) return [parseInt(m[1].slice(0, 2), 16), parseInt(m[1].slice(2, 4), 16), parseInt(m[1].slice(4, 6), 16)];
            return null;
          }
          function red(c) { var v = rgb(c); return !!v && v[0] > 200 && v[1] < 110 && v[2] < 120; }
          var f = findIcon();
          res.labels = f.labels.slice(0, 10).join('|');
          if (!f.icon) return JSON.stringify(res);
          res.found = true;
          res.label = f.icon.getAttribute('aria-label') || '';
          var cs = getComputedStyle(f.icon);
          res.liked = UNLIKE.indexOf(norm(res.label)) >= 0 || red(f.icon.getAttribute('fill')) || red(cs.fill) || red(cs.color);
          return JSON.stringify(res);
        })();
    """.trimIndent()

    private val CLICK_JS = """
        (function () {
          $FIND_JS
          var f = findIcon();
          if (!f.icon) return 'none';
          var b = f.icon.closest('[role="button"], button') || f.icon.parentElement;
          b.click();
          return 'clicked';
        })();
    """.trimIndent()

    /** Точка входу для Python (блокує потік воркера до завершення, це нормально). */
    @JvmStatic
    fun like(url: String, userAgent: String, cookiesJson: String): String =
        try {
            runBlocking(Dispatchers.Default) {
                mutex.withLock { doLike(url, userAgent, cookiesJson) }
            }
        } catch (e: Exception) {
            result("error", "${e::class.simpleName}: ${e.message}")
        }

    private fun result(status: String, detail: String = ""): String =
        JSONObject().put("status", status).put("detail", detail).toString()

    private suspend fun doLike(url: String, ua: String, cookiesJson: String): String {
        val ctx = appContext ?: return result("error", "appContext не ініціалізовано")
        syncCookies(cookiesJson)
        val wv = ensureWebView(ctx, ua)

        pageLoaded = CompletableDeferred()
        withContext(Dispatchers.Main) { wv.loadUrl(url) }
        val loaded = withTimeoutOrNull(30_000) { pageLoaded.await() }
        if (loaded == null) return result("timeout", "сторінка не завантажилась за 30 с")

        // чекаємо, поки сторінка "оживе" і з'явиться панель дій під постом
        var st = JSONObject()
        for (i in 1..15) {
            delay(1000)
            st = state(wv)
            if (st.optBoolean("login") || st.optBoolean("found")) break
        }
        if (st.optBoolean("login")) return result("login_required", st.optString("title"))
        if (!st.optBoolean("found")) {
            return result(
                "no_button",
                "title=${st.optString("title")} path=${st.optString("path")} " +
                    "labels=${st.optString("labels")} text=${st.optString("text")}"
            )
        }
        if (st.optBoolean("liked")) return result("already_liked")

        withContext(Dispatchers.Main) { evalJs(wv, CLICK_JS) }
        for (i in 1..6) {
            delay(1000)
            st = state(wv)
            if (st.optBoolean("liked")) return result("liked")
        }
        return result(
            "not_confirmed",
            "label=${st.optString("label")} path=${st.optString("path")} text=${st.optString("text")}"
        )
    }

    private suspend fun evalJs(wv: WebView, script: String): String =
        suspendCancellableCoroutine { cont ->
            wv.evaluateJavascript(script) { value ->
                if (cont.isActive) cont.resume(value ?: "null")
            }
        }

    private suspend fun state(wv: WebView): JSONObject {
        val raw = withContext(Dispatchers.Main) { evalJs(wv, STATE_JS) }
        val s = (JSONTokener(raw).nextValue() as? String) ?: return JSONObject()
        return JSONObject(s)
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
            if (ua.isNotBlank()) wv.settings.userAgentString = ua
            CookieManager.getInstance().setAcceptCookie(true)
            CookieManager.getInstance().setAcceptThirdPartyCookies(wv, true)
            wv.webViewClient = object : WebViewClient() {
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
