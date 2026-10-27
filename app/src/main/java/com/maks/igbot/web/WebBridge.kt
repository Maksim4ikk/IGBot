package com.maks.igbot.web

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject

/**
 * Єдина точка, яку викликає Python (igbot/webbridge.py): дії на сторінках Instagram через WebView.
 * Кожен метод блокує потік Python до завершення (це нормально — він фоновий) і повертає JSON
 * {"status": ..., "detail": ..., + дані}. Статус "login" означає, що WebView не авторизований.
 */
object WebBridge {
    private val CODE_RE = Regex("[A-Za-z0-9_-]{8,15}")
    private val USERNAME_RE = Regex("[A-Za-z0-9._]{1,30}")

    fun init(context: Context) {
        WebSession.appContext = context.applicationContext
    }

    /** Лайк поста за кодом. liked | already_liked | no_button | not_confirmed | login | timeout | error */
    @JvmStatic
    fun like(code: String, userAgent: String, cookiesJson: String): String =
        guarded { doLike(code, userAgent, cookiesJson) }

    /** Коди постів зі сторінки профілю. ok(+codes) | private | no_posts | not_found | unknown | login | timeout */
    @JvmStatic
    fun latestPost(username: String, userAgent: String, cookiesJson: String): String =
        guarded { doLatestPosts(username, userAgent, cookiesJson) }

    /** ID акаунта за нікнеймом. ok(+id) | not_found | unknown | login | timeout */
    @JvmStatic
    fun profileId(username: String, userAgent: String, cookiesJson: String): String =
        guarded { doProfileId(username, userAgent, cookiesJson) }

    /** Пости зі сторінки хештегу (з прокруткою). ok(+codes) | unknown | login | timeout */
    @JvmStatic
    fun hashtagPosts(tag: String, amount: Int, userAgent: String, cookiesJson: String): String =
        guarded { doHashtag(tag, amount, userAgent, cookiesJson) }

    /** Автор поста. ok(+username, ids) | unknown | login | timeout */
    @JvmStatic
    fun postAuthor(code: String, userAgent: String, cookiesJson: String): String =
        guarded { doPostAuthor(code, userAgent, cookiesJson) }

    // ── реалізація ──────────────────────────────────────────────────
    private fun guarded(block: suspend () -> String): String =
        try {
            runBlocking(Dispatchers.Default) { WebSession.mutex.withLock { block() } }
        } catch (e: Exception) {
            result("error", "${e::class.simpleName}: ${e.message}")
        }

    private fun result(status: String, detail: String = "", vararg extra: Pair<String, Any?>): String {
        val o = JSONObject().put("status", status).put("detail", detail)
        for ((k, v) in extra) o.put(k, v)
        return o.toString()
    }

    private fun pageDetail(st: JSONObject): String =
        "title=${st.optString("title")} path=${st.optString("path")} text=${st.optString("text")}"

    private suspend fun doLike(code: String, ua: String, cookiesJson: String): String {
        if (!CODE_RE.matches(code)) return result("error", "невірний код поста: $code")
        val wv = WebSession.open("${WebSession.BASE}/p/$code/", ua, cookiesJson)
            ?: return result("timeout", "сторінка поста не завантажилась за 30 с")

        // чекаємо, поки сторінка "оживе" і з'явиться панель дій під постом
        var st = WebSession.poll(wv, WebScripts.STATE_JS, 15) { it.optBoolean("login") || it.optBoolean("found") }
        if (st.optBoolean("login")) return result("login", st.optString("title"))
        if (!st.optBoolean("found")) {
            return result("no_button", pageDetail(st) + " labels=${st.optString("labels")}")
        }
        if (st.optBoolean("liked")) return result("already_liked")

        WebSession.evalJs(wv, WebScripts.CLICK_JS)
        st = WebSession.poll(wv, WebScripts.STATE_JS, 6) { it.optBoolean("liked") }
        return if (st.optBoolean("liked")) {
            result("liked")
        } else {
            result("not_confirmed", "label=${st.optString("label")} path=${st.optString("path")} text=${st.optString("text")}")
        }
    }

    private suspend fun doLatestPosts(username: String, ua: String, cookiesJson: String): String {
        val uname = username.trim().trimStart('@')
        if (!USERNAME_RE.matches(uname)) return result("error", "невірний нікнейм: $uname")
        val wv = WebSession.open("${WebSession.BASE}/${Uri.encode(uname)}/", ua, cookiesJson)
            ?: return result("timeout", "профіль не завантажився за 30 с")
        val st = WebSession.poll(wv, WebScripts.PROFILE_JS, 14) { it.optString("status", "wait") != "wait" }
        val status = st.optString("status", "wait").let { if (it == "wait") "unknown" else it }
        return result(status, pageDetail(st), "codes" to st.optString("codes"))
    }

    private suspend fun doProfileId(username: String, ua: String, cookiesJson: String): String {
        val uname = username.trim().trimStart('@')
        if (!USERNAME_RE.matches(uname)) return result("error", "невірний нікнейм: $uname")
        val wv = WebSession.open("${WebSession.BASE}/${Uri.encode(uname)}/", ua, cookiesJson)
            ?: return result("timeout", "сторінка профілю не завантажилась за 30 с")
        val js = WebScripts.PROFILE_ID_JS.replace("__UNAME__", JSONObject.quote(uname))
        val st = WebSession.poll(wv, js, 14) { it.optString("status", "wait") != "wait" }
        val status = st.optString("status", "wait").let { if (it == "wait") "unknown" else it }
        return result(status, pageDetail(st), "id" to st.optString("id"))
    }

    private suspend fun doHashtag(tag: String, amount: Int, ua: String, cookiesJson: String): String {
        val t = tag.trim().trimStart('#')
        if (t.isEmpty() || t.length > 100 || t.any { it.isWhitespace() || it == '/' || it == '?' || it == '#' }) {
            return result("error", "невірний хештег: $tag")
        }
        val wv = WebSession.open("${WebSession.BASE}/explore/tags/${Uri.encode(t)}/", ua, cookiesJson)
            ?: return result("timeout", "сторінка хештегу не завантажилась за 30 с")

        val want = amount.coerceIn(1, 200)
        var st = JSONObject()
        var best = ""
        var bestCount = 0
        var stagnant = 0
        for (round in 1..14) {
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
        if (st.optString("status") == "login") return result("login", st.optString("title"))
        return result(if (best.isNotBlank()) "ok" else "unknown", pageDetail(st), "codes" to best)
    }

    private suspend fun doPostAuthor(code: String, ua: String, cookiesJson: String): String {
        if (!CODE_RE.matches(code)) return result("error", "невірний код поста: $code")
        val wv = WebSession.open("${WebSession.BASE}/p/$code/", ua, cookiesJson)
            ?: return result("timeout", "сторінка поста не завантажилась за 30 с")
        val st = WebSession.poll(wv, WebScripts.POST_AUTHOR_JS, 12) { it.optString("status", "wait") != "wait" }
        val status = st.optString("status", "wait").let { if (it == "wait") "unknown" else it }
        return result(status, pageDetail(st), "username" to st.optString("username"), "ids" to st.optString("ids"))
    }
}
