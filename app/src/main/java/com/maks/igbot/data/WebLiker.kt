package com.maks.igbot.data

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
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

    // Сторінка профілю: читаємо посилання на пости з сітки (як це бачить людина)
    private val PROFILE_JS = """
        (function () {
          var txt = ((document.body && document.body.innerText) || '').replace(/\s+/g, ' ');
          var low = txt.toLowerCase();
          var res = {status: 'wait', codes: '', title: (document.title || '').slice(0, 60),
                     path: location.pathname.slice(0, 40), text: txt.slice(0, 100)};
          if (location.pathname.indexOf('/accounts/login') === 0 || document.querySelector('input[name="username"]')) {
            res.status = 'login';
            return JSON.stringify(res);
          }
          var root = document.querySelector('main') || document;
          var as = root.querySelectorAll('a[href*="/p/"], a[href*="/reel/"]');
          var codes = [];
          for (var i = 0; i < as.length && codes.length < 12; i++) {
            var m = /\/(?:p|reel)\/([A-Za-z0-9_-]{8,15})\//.exec(as[i].getAttribute('href') || '');
            if (m && codes.indexOf(m[1]) < 0) codes.push(m[1]);
          }
          if (codes.length) {
            res.status = 'ok';
            res.codes = codes.join(',');
            return JSON.stringify(res);
          }
          function has(list) {
            for (var j = 0; j < list.length; j++) { if (low.indexOf(list[j]) >= 0) return true; }
            return false;
          }
          if (has(['this account is private', 'цей акаунт приватний', 'це приватний акаунт', 'это закрытый аккаунт',
                   'dieses konto ist privat', 'esta cuenta es privada', 'ce compte est privé',
                   'questo account è privato', 'esta conta é privada', 'bu hesap gizli', 'to konto jest prywatne'])) {
            res.status = 'private';
          } else if (has(["isn't available", 'isn’t available', 'page not found', 'сторінку не знайдено', 'сторінка недоступна',
                          'страница не найдена', 'страница недоступна', 'seite nicht gefunden', 'página no disponible',
                          'page introuvable', 'pagina non trovata', 'página não encontrada', 'sayfa bulunamadı',
                          'nie znaleziono strony'])) {
            res.status = 'not_found';
          } else if (has(['no posts yet', 'ще немає дописів', 'ще немає публікацій', 'немає публікацій',
                          'пока нет публикаций', 'публикаций пока нет', 'noch keine beiträge',
                          'aún no hay publicaciones', 'pas encore de publications', 'ancora nessun post',
                          'ainda não há publicações', 'henüz gönderi yok', 'brak postów'])) {
            res.status = 'no_posts';
          }
          return JSON.stringify(res);
        })();
    """.trimIndent()

    // ID профілю: беремо з коду сторінки профілю (числа поруч з "username":"<нік>" та "profile_id")
    private val PROFILE_ID_JS = """
        (function () {
          var txt = ((document.body && document.body.innerText) || '').replace(/\s+/g, ' ');
          var low = txt.toLowerCase();
          var res = {status: 'wait', id: '', title: (document.title || '').slice(0, 60),
                     path: location.pathname.slice(0, 40), text: txt.slice(0, 100)};
          if (location.pathname.indexOf('/accounts/login') === 0 || document.querySelector('input[name="username"]')) {
            res.status = 'login';
            return JSON.stringify(res);
          }
          var uname = __UNAME__;
          var html = document.documentElement.innerHTML;
          var counts = {};
          function add(v, w) { if (v && v.length >= 5 && v.length <= 14) counts[v] = (counts[v] || 0) + w; }
          var re = new RegExp('"username":"' + uname.replace(/[.]/g, '\\.') + '"', 'gi');
          var m, n = 0;
          while ((m = re.exec(html)) && n < 12) {
            n++;
            var win = html.slice(Math.max(0, m.index - 400), m.index + 400);
            var r2 = /"(?:pk|id|user_id|profile_id|owner_id)":"(\d{5,14})"/g, m2;
            while ((m2 = r2.exec(win))) add(m2[1], 1);
          }
          var r3 = /"profile_id":"(\d{5,14})"/g, m3;
          while ((m3 = r3.exec(html))) add(m3[1], 2);
          var best = '', bestN = 0;
          for (var k in counts) { if (counts[k] > bestN) { bestN = counts[k]; best = k; } }
          if (best) {
            res.status = 'ok';
            res.id = best;
            return JSON.stringify(res);
          }
          function has(list) {
            for (var j = 0; j < list.length; j++) { if (low.indexOf(list[j]) >= 0) return true; }
            return false;
          }
          if (has(["isn't available", 'isn’t available', 'page not found', 'сторінку не знайдено', 'сторінка недоступна',
                   'страница не найдена', 'страница недоступна', 'seite nicht gefunden', 'página no disponible',
                   'page introuvable', 'pagina non trovata', 'página não encontrada', 'sayfa bulunamadı',
                   'nie znaleziono strony'])) {
            res.status = 'not_found';
          }
          return JSON.stringify(res);
        })();
    """.trimIndent()

    // Сторінка хештегу: збираємо посилання на пости
    private val HASHTAG_JS = """
        (function () {
          var txt = ((document.body && document.body.innerText) || '').replace(/\s+/g, ' ');
          var res = {status: 'wait', codes: '', count: 0, title: (document.title || '').slice(0, 60),
                     path: location.pathname.slice(0, 50), text: txt.slice(0, 100)};
          if (location.pathname.indexOf('/accounts/login') === 0 || document.querySelector('input[name="username"]')) {
            res.status = 'login';
            return JSON.stringify(res);
          }
          var as = document.querySelectorAll('a[href*="/p/"], a[href*="/reel/"]');
          var codes = [];
          for (var i = 0; i < as.length; i++) {
            var m = /\/(?:p|reel)\/([A-Za-z0-9_-]{8,15})\//.exec(as[i].getAttribute('href') || '');
            if (m && codes.indexOf(m[1]) < 0) codes.push(m[1]);
          }
          res.count = codes.length;
          res.codes = codes.join(',');
          if (codes.length) res.status = 'ok';
          return JSON.stringify(res);
        })();
    """.trimIndent()

    // Сторінка поста: автор (нікнейм) та числові ID поруч із його нікнеймом у коді сторінки
    private val POST_AUTHOR_JS = """
        (function () {
          var txt = ((document.body && document.body.innerText) || '').replace(/\s+/g, ' ');
          var res = {status: 'wait', username: '', ids: '', title: (document.title || '').slice(0, 60),
                     path: location.pathname.slice(0, 40), text: txt.slice(0, 100)};
          if (location.pathname.indexOf('/accounts/login') === 0 || document.querySelector('input[name="username"]')) {
            res.status = 'login';
            return JSON.stringify(res);
          }
          var SKIP = ['explore','reels','reel','direct','accounts','p','stories','notifications','about','legal',
                      'privacy','terms','directory','developers','api','web','static','emails','challenge','session',
                      'login','nametag','settings','create','ads','blog','help','tv','lite','topics','locations',
                      'tags','archive','saved','activity','threads','meta','download','oauth'];
          var scope = document.querySelector('article');
          if (!scope) return JSON.stringify(res);
          var found = '';
          var groups = [scope.querySelectorAll('header a[href]'), scope.querySelectorAll('a[href]')];
          for (var g = 0; g < groups.length && !found; g++) {
            for (var i = 0; i < groups[g].length && !found; i++) {
              var m = /^\/([A-Za-z0-9._]{1,30})\/$/.exec(groups[g][i].getAttribute('href') || '');
              if (m && SKIP.indexOf(m[1].toLowerCase()) < 0) found = m[1];
            }
          }
          if (!found) return JSON.stringify(res);
          res.username = found;
          res.status = 'ok';
          var html = document.documentElement.innerHTML;
          var re = new RegExp('"username":"' + found.replace(/[.]/g, '\\.') + '"', 'gi');
          var ids = [], mm, n = 0;
          while ((mm = re.exec(html)) && n < 8) {
            n++;
            var win = html.slice(Math.max(0, mm.index - 400), mm.index + 400);
            var r2 = /"(?:pk|id|user_id|profile_id|owner_id)":"(\d{5,14})"/g, m2;
            while ((m2 = r2.exec(win))) { if (ids.indexOf(m2[1]) < 0) ids.push(m2[1]); }
          }
          res.ids = ids.join(',');
          return JSON.stringify(res);
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

    /**
     * Остання публікація профілю: відкриває сторінку профілю у WebView й читає посилання на пости.
     * Повертає JSON {"status": ok|private|not_found|no_posts|login|unknown|timeout|error,
     *                "codes": "code1,code2,...", "detail": "..."}.
     */
    @JvmStatic
    fun latestPost(username: String, userAgent: String, cookiesJson: String): String =
        try {
            runBlocking(Dispatchers.Default) {
                mutex.withLock { doLatest(username, userAgent, cookiesJson) }
            }
        } catch (e: Exception) {
            result("error", "${e::class.simpleName}: ${e.message}")
        }

    /** ID акаунта за нікнеймом (зі сторінки профілю). status: ok(+id) | not_found | login | unknown | timeout | error */
    @JvmStatic
    fun profileId(username: String, userAgent: String, cookiesJson: String): String =
        guarded { doProfileId(username, userAgent, cookiesJson) }

    /** Пости зі сторінки хештегу (з прокруткою). status: ok(+codes) | login | unknown | timeout | error */
    @JvmStatic
    fun hashtagPosts(tag: String, amount: Int, userAgent: String, cookiesJson: String): String =
        guarded { doHashtag(tag, amount, userAgent, cookiesJson) }

    /** Автор поста за його кодом. status: ok(+username, ids) | login | unknown | timeout | error */
    @JvmStatic
    fun postAuthor(code: String, userAgent: String, cookiesJson: String): String =
        guarded { doPostAuthor(code, userAgent, cookiesJson) }

    private fun guarded(block: suspend () -> String): String =
        try {
            runBlocking(Dispatchers.Default) { mutex.withLock { block() } }
        } catch (e: Exception) {
            result("error", "${e::class.simpleName}: ${e.message}")
        }

    private suspend fun openPage(url: String, ua: String, cookiesJson: String): WebView? {
        val ctx = appContext ?: return null
        syncCookies(cookiesJson)
        val wv = ensureWebView(ctx, ua)
        pageLoaded = CompletableDeferred()
        withContext(Dispatchers.Main) { wv.loadUrl(url) }
        val loaded = withTimeoutOrNull(30_000) { pageLoaded.await() }
        return if (loaded == null) null else wv
    }

    private fun pageDetail(st: JSONObject): String =
        "title=${st.optString("title")} path=${st.optString("path")} text=${st.optString("text")}"

    private suspend fun doProfileId(username: String, ua: String, cookiesJson: String): String {
        val uname = username.trim().trimStart('@')
        if (!Regex("[A-Za-z0-9._]{1,30}").matches(uname)) return result("error", "невірний нікнейм: $uname")
        val wv = openPage("$BASE/${Uri.encode(uname)}/", ua, cookiesJson)
            ?: return result("timeout", "сторінка профілю не завантажилась за 30 с")
        val js = PROFILE_ID_JS.replace("__UNAME__", JSONObject.quote(uname))
        var st = JSONObject()
        for (i in 1..14) {
            delay(1000)
            st = evalJson(wv, js)
            if (st.optString("status", "wait") != "wait") break
        }
        var status = st.optString("status", "wait")
        if (status == "wait") status = "unknown"
        return JSONObject().put("status", status).put("id", st.optString("id"))
            .put("detail", pageDetail(st)).toString()
    }

    private suspend fun doHashtag(tag: String, amount: Int, ua: String, cookiesJson: String): String {
        val t = tag.trim().trimStart('#')
        if (t.isEmpty() || t.length > 100 || t.any { it.isWhitespace() || it == '/' || it == '?' || it == '#' }) {
            return result("error", "невірний хештег: $tag")
        }
        val wv = openPage("$BASE/explore/tags/${Uri.encode(t)}/", ua, cookiesJson)
            ?: return result("timeout", "сторінка хештегу не завантажилась за 30 с")
        val want = amount.coerceIn(1, 200)
        var st = JSONObject()
        var best = ""
        var bestCount = 0
        var stagnant = 0
        for (round in 1..14) {
            delay(if (round == 1) 3000 else 2000)
            st = evalJson(wv, HASHTAG_JS)
            if (st.optString("status") == "login") break
            val cnt = st.optInt("count", 0)
            if (cnt > bestCount) {
                bestCount = cnt
                best = st.optString("codes")
                stagnant = 0
            } else if (bestCount > 0) {
                stagnant++
            }
            if (bestCount >= want || stagnant >= 3) break
            withContext(Dispatchers.Main) { evalJs(wv, "window.scrollTo(0, document.body.scrollHeight); 'ok'") }
        }
        if (st.optString("status") == "login") return result("login", st.optString("title"))
        val status = if (best.isNotBlank()) "ok" else "unknown"
        return JSONObject().put("status", status).put("codes", best).put("detail", pageDetail(st)).toString()
    }

    private suspend fun doPostAuthor(code: String, ua: String, cookiesJson: String): String {
        if (!Regex("[A-Za-z0-9_-]{8,15}").matches(code)) return result("error", "невірний код поста: $code")
        val wv = openPage("$BASE/p/$code/", ua, cookiesJson)
            ?: return result("timeout", "сторінка поста не завантажилась за 30 с")
        var st = JSONObject()
        for (i in 1..12) {
            delay(1000)
            st = evalJson(wv, POST_AUTHOR_JS)
            if (st.optString("status", "wait") != "wait") break
        }
        var status = st.optString("status", "wait")
        if (status == "wait") status = "unknown"
        return JSONObject().put("status", status).put("username", st.optString("username"))
            .put("ids", st.optString("ids")).put("detail", pageDetail(st)).toString()
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

    private suspend fun doLatest(username: String, ua: String, cookiesJson: String): String {
        val ctx = appContext ?: return result("error", "appContext не ініціалізовано")
        syncCookies(cookiesJson)
        val wv = ensureWebView(ctx, ua)

        pageLoaded = CompletableDeferred()
        withContext(Dispatchers.Main) { wv.loadUrl("$BASE/${Uri.encode(username)}/") }
        val loaded = withTimeoutOrNull(30_000) { pageLoaded.await() }
        if (loaded == null) return result("timeout", "профіль не завантажився за 30 с")

        var st = JSONObject()
        for (i in 1..14) {
            delay(1000)
            st = evalJson(wv, PROFILE_JS)
            if (st.optString("status", "wait") != "wait") break
        }
        var status = st.optString("status", "wait")
        if (status == "wait") status = "unknown"
        return JSONObject()
            .put("status", status)
            .put("codes", st.optString("codes"))
            .put("detail", "title=${st.optString("title")} path=${st.optString("path")} text=${st.optString("text")}")
            .toString()
    }

    private suspend fun evalJs(wv: WebView, script: String): String =
        suspendCancellableCoroutine { cont ->
            wv.evaluateJavascript(script) { value ->
                if (cont.isActive) cont.resume(value ?: "null")
            }
        }

    private suspend fun evalJson(wv: WebView, script: String): JSONObject {
        val raw = withContext(Dispatchers.Main) { evalJs(wv, script) }
        val s = (JSONTokener(raw).nextValue() as? String) ?: return JSONObject()
        return JSONObject(s)
    }

    private suspend fun state(wv: WebView): JSONObject = evalJson(wv, STATE_JS)

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
