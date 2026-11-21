package com.maks.igbot.web

import android.net.Uri
import android.webkit.WebView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import org.json.JSONTokener

/**
 * Підписка / відписка на сторінці профілю в спільному WebView: відкриває https://www.instagram.com/<нік>/,
 * натискає кнопки й перевіряє результат — так само, як працює лайк (WebBridge.like).
 * Запити /api/v1 зсередини WebView (fetch) Instagram віддавав як HTML / 404, тому ними не користуємось.
 *
 * Викликається з Python (igbot/friends.py):
 *   jclass("com.maks.igbot.web.WebApi").friendAction(action, username, userAgent, cookiesJson)
 *
 * Повертає JSON {"status": ..., "detail": ...}.
 * status: ok | requested | blocked | login | not_found | no_button | not_confirmed | timeout | error
 * Спільний mutex WebSession серіалізує дію з воркером і парсером.
 */
object WebApi {
    private val USERNAME_RE = Regex("[A-Za-z0-9._]{1,30}")

    /** action = "follow" | "unfollow". Блокує потік Python до завершення (кілька секунд). */
    @JvmStatic
    fun friendAction(action: String, username: String, userAgent: String, cookiesJson: String): String =
        try {
            runBlocking(Dispatchers.IO) {
                WebSession.mutex.withLock { doFriendAction(action, username, userAgent, cookiesJson) }
            }
        } catch (e: Exception) {
            reply("error", "${e::class.simpleName}: ${e.message}")
        }

    private suspend fun doFriendAction(action: String, username: String, ua: String, cookiesJson: String): String {
        if (action != "follow" && action != "unfollow") return reply("error", "невідома дія: $action")
        val unfollow = action == "unfollow"
        val uname = username.trim().trimStart('@')
        if (!USERNAME_RE.matches(uname)) return reply("error", "невірний нікнейм: $uname")

        val wv = WebSession.open("${WebSession.BASE}/${Uri.encode(uname)}/", ua, cookiesJson)
            ?: return reply("timeout", "профіль не завантажився за 30 с")

        // 1) чекаємо, поки в шапці профілю з'явиться кнопка підписки
        var st = WebSession.poll(
            wv, FriendScripts.STATE_JS, 15,
            { it.optBoolean("login") || it.optBoolean("missing") || it.optString("state") != "none" }
        )
        if (st.optBoolean("login")) return reply("login", st.optString("title"))
        if (st.optBoolean("missing")) return reply("not_found", "акаунт недоступний: ${detail(st)}")
        val before = st.optString("state")
        if (before == "none") return reply("no_button", detail(st))

        // 2) уже в потрібному стані — нічого не натискаємо
        if (unfollow && before == "follow") return reply("ok", "already")
        if (!unfollow && before == "following") return reply("ok", "already")
        if (!unfollow && before == "requested") return reply("requested", "already")

        // 3) головна кнопка шапки
        if (clickJs(wv, FriendScripts.CLICK_MAIN_JS) != "clicked") return reply("no_button", detail(st))

        // 4) відписка: у меню та (для приватних акаунтів) у діалозі підтвердження натискаємо «Відписатись»
        if (unfollow) {
            for (round in 1..3) {
                delay(1300)
                if (clickJs(wv, FriendScripts.CLICK_UNFOLLOW_JS) != "clicked") break
                delay(1200)
                st = WebSession.evalJson(wv, FriendScripts.STATE_JS)
                if (st.optString("state") == "follow") break
            }
        }

        // 5) перевіряємо, що кнопка змінила стан
        val wanted = if (unfollow) setOf("follow") else setOf("following", "requested")
        if (st.optString("state") !in wanted) {
            st = WebSession.poll(wv, FriendScripts.STATE_JS, 8, { it.optString("state") in wanted })
        }
        val after = st.optString("state")
        return when {
            !unfollow && after == "requested" -> reply("requested", "")
            after in wanted -> reply("ok", "")
            st.optBoolean("blocked") -> reply("blocked", detail(st))
            else -> reply("not_confirmed", "було=$before стало=$after ${detail(st)}")
        }
    }

    private suspend fun clickJs(wv: WebView, script: String): String =
        (JSONTokener(WebSession.evalJs(wv, script)).nextValue() as? String).orEmpty()

    private fun detail(st: JSONObject): String =
        "title=${st.optString("title")} path=${st.optString("path")} labels=${st.optString("labels")} " +
            "dialog=${st.optString("dialog")} text=${st.optString("text")}"

    private fun reply(status: String, detail: String): String =
        JSONObject().put("status", status).put("detail", clean(detail)).toString()

    /** Прибирає «осиротілі» сурогати (напр. емодзі, обрізаний посередині), щоб Python не впав на UnicodeError. */
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
}
