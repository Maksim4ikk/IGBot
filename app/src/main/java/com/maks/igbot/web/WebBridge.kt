package com.maks.igbot.web

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Єдина точка, яку викликає Python (igbot/webbridge.py): дії на сторінках Instagram через WebView.
 * Кожен метод блокує потік Python до завершення (це нормально — він фоновий) і повертає JSON
 * {"status": ..., "detail": ..., + дані}. Статус "login" означає, що WebView не авторизований.
 */
object WebBridge {
    private val CODE_RE = Regex("[A-Za-z0-9_-]{8,15}")
    private val USERNAME_RE = Regex("[A-Za-z0-9._]{1,30}")
    private const val MAX_LIST = 5000

    /** Cancellation generation is scoped to the worker or scraper, never to the shared WebView. */
    private val cancellationGenerations = ConcurrentHashMap<String, AtomicLong>()

    @Volatile
    private var activeTaskId: String? = null

    @Volatile
    private var activeTaskGeneration = 0L

    private fun generation(taskId: String): AtomicLong =
        cancellationGenerations.computeIfAbsent(taskId) { AtomicLong(0L) }

    private val cancelled: Boolean
        get() {
            val taskId = activeTaskId ?: return false
            return generation(taskId).get() != activeTaskGeneration
        }

    @JvmStatic
    fun cancel(taskId: String) {
        if (taskId.isNotBlank()) generation(taskId).incrementAndGet()
    }

    fun init(context: Context) {
        WebSession.appContext = context.applicationContext
    }

    /** Лайк поста за кодом. liked | already_liked | no_button | not_confirmed | login | timeout | error */
    @JvmStatic
    fun like(code: String, userAgent: String, cookiesJson: String, taskId: String): String =
        guarded(taskId) { doLike(code, userAgent, cookiesJson) }

    /** Коди постів зі сторінки профілю. ok(+codes) | private | no_posts | not_found | unknown | login | timeout */
    @JvmStatic
    fun latestPost(username: String, userAgent: String, cookiesJson: String, taskId: String): String =
        guarded(taskId) { doLatestPosts(username, userAgent, cookiesJson) }

    /** ID акаунта за нікнеймом. ok(+id) | not_found | unknown | login | timeout */
    @JvmStatic
    fun profileId(username: String, userAgent: String, cookiesJson: String, taskId: String): String =
        guarded(taskId) { doProfileId(username, userAgent, cookiesJson) }

    /** Пости зі сторінки хештегу (з прокруткою). ok(+codes) | unknown | login | timeout */
    @JvmStatic
    fun hashtagPosts(tag: String, amount: Int, userAgent: String, cookiesJson: String, taskId: String): String =
        guarded(taskId) { doHashtag(tag, amount, userAgent, cookiesJson) }

    /** Автор поста. ok(+username, ids) | unknown | login | timeout */
    @JvmStatic
    fun postAuthor(code: String, userAgent: String, cookiesJson: String, taskId: String): String =
        guarded(taskId) { doPostAuthor(code, userAgent, cookiesJson) }

    /** Повний список тих, хто лайкнув пост (прокрутка екрана «Вподобання»). ok(+names) | unknown | login | timeout */
    @JvmStatic
    fun postLikers(code: String, amount: Int, userAgent: String, cookiesJson: String, taskId: String): String =
        guarded(taskId) { doPostLikers(code, amount, userAgent, cookiesJson) }

    /** Автори коментарів поста (прокрутка сторінки коментарів). ok(+names) | unknown | login | timeout */
    @JvmStatic
    fun postCommenters(code: String, amount: Int, userAgent: String, cookiesJson: String, taskId: String): String =
        guarded(taskId) { doPostCommenters(code, amount, userAgent, cookiesJson) }

    // ── реалізація ──────────────────────────────────────────────────
    private fun guarded(taskId: String, block: suspend () -> String): String =
        try {
            val taskGeneration = generation(taskId).get()
            runBlocking(Dispatchers.Default) {
                WebSession.mutex.withLock {
                    if (generation(taskId).get() != taskGeneration) {
                        return@withLock result("cancelled", "Зупинено користувачем")
                    }
                    activeTaskId = taskId
                    activeTaskGeneration = taskGeneration
                    try {
                        block()
                    } finally {
                        activeTaskId = null
                    }
                }
            }
        } catch (e: Exception) {
            result("error", "${e::class.simpleName}: ${e.message}")
        }

    private fun cancelledResult(): String = result("cancelled", "Зупинено користувачем")

    private fun result(status: String, detail: String = "", vararg extra: Pair<String, Any?>): String {
        val o = JSONObject().put("status", status).put("detail", clean(detail))
        for ((k, v) in extra) o.put(k, if (v is String) clean(v) else v)
        return o.toString()
    }

    /**
     * Прибирає «осиротілі» сурогати (наприклад емодзі, обрізане посередині при скороченні тексту сторінки):
     * Python не може декодувати такий рядок і падає з помилкою UTF-16.
     */
    private fun clean(s: String): String {
        val sb = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (Character.isHighSurrogate(c)) {
                if (i + 1 < s.length && Character.isLowSurrogate(s[i + 1])) {
                    sb.append(c).append(s[i + 1])
                    i += 2
                } else {
                    i++
                }
            } else if (Character.isLowSurrogate(c)) {
                i++
            } else {
                sb.append(c)
                i++
            }
        }
        return sb.toString()
    }

    private fun pageDetail(st: JSONObject): String =
        "title=${st.optString("title")} path=${st.optString("path")} text=${st.optString("text")}"

    private suspend fun doLike(code: String, ua: String, cookiesJson: String): String {
        if (!CODE_RE.matches(code)) return result("error", "невірний код поста: $code")
        val wv = WebSession.open("${WebSession.BASE}/p/$code/", ua, cookiesJson) { cancelled }
            ?: return if (cancelled) cancelledResult() else result("timeout", "сторінка поста не завантажилась за 30 с")

        // чекаємо, поки сторінка "оживе" і з'явиться панель дій під постом
        var st = WebSession.poll(wv, WebScripts.STATE_JS, 15, { it.optBoolean("login") || it.optBoolean("found") }) { cancelled }
        if (cancelled) return cancelledResult()
        if (st.optBoolean("login")) return result("login", st.optString("title"))
        if (!st.optBoolean("found")) {
            return result("no_button", pageDetail(st) + " labels=${st.optString("labels")}")
        }
        if (st.optBoolean("liked")) return result("already_liked")

        if (cancelled) return cancelledResult()
        WebSession.evalJs(wv, WebScripts.CLICK_JS)
        st = WebSession.poll(wv, WebScripts.STATE_JS, 6, { it.optBoolean("liked") }) { cancelled }
        if (cancelled) return cancelledResult()
        return if (st.optBoolean("liked")) {
            result("liked")
        } else {
            result("not_confirmed", "label=${st.optString("label")} path=${st.optString("path")} text=${st.optString("text")}")
        }
    }

    private suspend fun doLatestPosts(username: String, ua: String, cookiesJson: String): String {
        val uname = username.trim().trimStart('@')
        if (!USERNAME_RE.matches(uname)) return result("error", "невірний нікнейм: $uname")
        val wv = WebSession.open("${WebSession.BASE}/${Uri.encode(uname)}/", ua, cookiesJson) { cancelled }
            ?: return if (cancelled) cancelledResult() else result("timeout", "профіль не завантажився за 30 с")
        var st = JSONObject()
        for (i in 1..30) {
            if (cancelled) return cancelledResult()
            delay(1000)
            st = WebSession.evalJson(wv, WebScripts.PROFILE_JS)
            if (st.optString("status", "wait") != "wait") break
            // Даємо повільній сторінці час відрендерити сітку та кілька разів запускаємо lazy loading.
            when (i) {
                3, 8, 13, 18, 23, 28 ->
                    WebSession.evalJs(wv, "window.scrollTo(0, document.body.scrollHeight); 'ok'")
                5, 10, 15, 20, 25 ->
                    WebSession.evalJs(wv, "window.scrollTo(0, 0); 'ok'")
            }
        }
        if (cancelled) return cancelledResult()
        val status = st.optString("status", "wait").let { if (it == "wait") "unknown" else it }
        if (status == "unknown") {
            // для діагностики: що під заголовком профілю та які кнопки є внизу сторінки
            val btns = WebSession.evalJson(wv, WebScripts.TAIL_JS).optString("tail")
            return result(
                status,
                "path=${st.optString("path")} tail=${st.optString("tail")} ${st.optString("diag")} btns=$btns",
                "codes" to ""
            )
        }
        return result(status, pageDetail(st), "codes" to st.optString("codes"))
    }

    private suspend fun doProfileId(username: String, ua: String, cookiesJson: String): String {
        val uname = username.trim().trimStart('@')
        if (!USERNAME_RE.matches(uname)) return result("error", "невірний нікнейм: $uname")
        val wv = WebSession.open("${WebSession.BASE}/${Uri.encode(uname)}/", ua, cookiesJson) { cancelled }
            ?: return if (cancelled) cancelledResult() else result("timeout", "сторінка профілю не завантажилась за 30 с")
        val js = WebScripts.PROFILE_ID_JS.replace("__UNAME__", JSONObject.quote(uname))
        val st = WebSession.poll(wv, js, 14, { it.optString("status", "wait") != "wait" }) { cancelled }
        if (cancelled) return cancelledResult()
        val status = st.optString("status", "wait").let { if (it == "wait") "unknown" else it }
        return result(status, pageDetail(st), "id" to st.optString("id"))
    }

    private suspend fun doHashtag(tag: String, amount: Int, ua: String, cookiesJson: String): String {
        val t = tag.trim().trimStart('#')
        if (t.isEmpty() || t.length > 100 || t.any { it.isWhitespace() || it == '/' || it == '?' || it == '#' }) {
            return result("error", "невірний хештег: $tag")
        }
        val wv = WebSession.open("${WebSession.BASE}/explore/tags/${Uri.encode(t)}/", ua, cookiesJson) { cancelled }
            ?: return if (cancelled) cancelledResult() else result("timeout", "сторінка хештегу не завантажилась за 30 с")

        val want = amount.coerceIn(1, 1000)
        var st = JSONObject()
        var best = ""
        var bestCount = 0
        var stagnant = 0
        for (round in 1..80) {
            if (cancelled) break
            delay(if (round == 1) 3000 else 2000)
            st = WebSession.evalJson(wv, WebScripts.HASHTAG_JS)
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
            WebSession.evalJs(wv, "window.scrollTo(0, document.body.scrollHeight); 'ok'")
        }
        if (cancelled) return cancelledResult()
        if (st.optString("status") == "login") return result("login", st.optString("title"))
        return result(if (best.isNotBlank()) "ok" else "unknown", pageDetail(st), "codes" to best)
    }

    private suspend fun doPostLikers(code: String, amount: Int, ua: String, cookiesJson: String): String {
        if (!CODE_RE.matches(code)) return result("error", "невірний код поста: $code")
        return scrollCollect("${WebSession.BASE}/p/$code/liked_by/", WebScripts.LIKERS_JS, amount, ua, cookiesJson, "Вподобання")
    }

    private suspend fun doPostCommenters(code: String, amount: Int, ua: String, cookiesJson: String): String {
        if (!CODE_RE.matches(code)) return result("error", "невірний код поста: $code")
        return scrollCollect("${WebSession.BASE}/p/$code/comments/", WebScripts.COMMENTERS_JS, amount, ua, cookiesJson, "Коментарі")
    }

    /**
     * Відкриває сторінку зі списком людей, прокручує її та збирає нікнейми скриптом (він повертає names/count).
     * Зупиняється, коли набрано потрібну кількість, список перестав зростати або натиснули «Зупинити».
     */
    private suspend fun scrollCollect(
        url: String, script: String, amount: Int, ua: String, cookiesJson: String, what: String
    ): String {
        val wv = WebSession.open(url, ua, cookiesJson) { cancelled }
            ?: return if (cancelled) cancelledResult() else result("timeout", "сторінка «$what» не завантажилась за 30 с")

        val want = amount.coerceIn(1, MAX_LIST)
        var st = JSONObject()
        var best = ""
        var bestCount = 0
        var stagnant = 0
        var rounds = 0
        while (rounds < 600 && !cancelled) {
            rounds++
            delay(if (rounds == 1) 3000 else 1500)
            st = WebSession.evalJson(wv, script)
            if (st.optString("status") == "login") break
            val cnt = st.optInt("count", 0)
            if (cnt > bestCount) {
                bestCount = cnt
                best = st.optString("names")
                stagnant = 0
            } else {
                stagnant++
            }
            // список перестав зростати (кінець або сторінка не підвантажує) — зупиняємось
            if (bestCount >= want || stagnant >= 8) break
            WebSession.evalJs(wv, WebScripts.SCROLL_JS)
        }
        if (cancelled) return cancelledResult()
        if (st.optString("status") == "login") return result("login", st.optString("title"))
        // якщо список закінчився раніше за потрібне — додаємо підписи нижніх кнопок для діагностики
        val tail = if (bestCount < want) WebSession.evalJson(wv, WebScripts.TAIL_JS).optString("tail") else ""
        return result(
            if (best.isNotBlank()) "ok" else "unknown",
            pageDetail(st) + " rounds=$rounds found=$bestCount" + (if (tail.isNotEmpty()) " tail=$tail" else ""),
            "names" to best
        )
    }

    private suspend fun doPostAuthor(code: String, ua: String, cookiesJson: String): String {
        if (!CODE_RE.matches(code)) return result("error", "невірний код поста: $code")
        val wv = WebSession.open("${WebSession.BASE}/p/$code/", ua, cookiesJson) { cancelled }
            ?: return if (cancelled) cancelledResult() else result("timeout", "сторінка поста не завантажилась за 30 с")
        val st = WebSession.poll(wv, WebScripts.POST_AUTHOR_JS, 12, { it.optString("status", "wait") != "wait" }) { cancelled }
        if (cancelled) return cancelledResult()
        val status = st.optString("status", "wait").let { if (it == "wait") "unknown" else it }
        return result(status, pageDetail(st), "username" to st.optString("username"), "ids" to st.optString("ids"))
    }
}
