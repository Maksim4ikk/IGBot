package com.maks.igbot.data

import android.content.Context
import android.util.Log
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import com.maks.igbot.web.WebBridge
import org.json.JSONArray
import org.json.JSONObject

/**
 * Єдиний шлюз між Kotlin-інтерфейсом і Python-ядром (Chaquopy).
 * Усі виклики блокуючі — викликайте їх з Dispatchers.IO.
 */
object BotEngine {
    private var initialized = false

    @Synchronized
    fun ensureInit(context: Context) {
        WebBridge.init(context)
        if (!Python.isStarted()) {
            Python.start(AndroidPlatform(context.applicationContext))
        }
        if (!initialized) {
            val mod = Python.getInstance().getModule("igbot_engine")
            mod.callAttr("init_android_env", context.filesDir.absolutePath)
            initialized = true
        }
    }

    private fun module(context: Context) =
        ensureInit(context).let { Python.getInstance().getModule("igbot_engine") }

    // ── воркер і парсер ─────────────────────────────────────────────
    fun startWorker(context: Context) {
        module(context).callAttr("start_worker_thread")
    }

    fun stopWorker(context: Context) {
        module(context).callAttr("stop_worker_thread")
    }

    /** amount = 0 — кількість береться з налаштувань («Ліміти» → «Парсер аудиторії»). */
    fun startScraper(context: Context, sourceType: String, target: String, amount: Int = 0) {
        module(context).callAttr("start_scraper_thread", sourceType, target, amount)
    }

    fun stopScraper(context: Context) {
        module(context).callAttr("stop_scraper_thread")
    }

    // ── стан і журнал ───────────────────────────────────────────────
    fun getDashboardJson(context: Context): JSONObject =
        JSONObject(module(context).callAttr("get_dashboard_json").toString())

    /** Рядки журналу «ГГ:ХХ:СС|рівень|текст», від старих до нових. */
    fun getLogs(context: Context, limit: Int = 300): List<String> {
        val arr = JSONArray(module(context).callAttr("get_logs_json", limit).toString())
        return List(arr.length()) { arr.getString(it) }
    }

    fun clearLogs(context: Context) {
        module(context).callAttr("clear_logs")
    }

    fun logExternal(context: Context, msg: String) {
        try {
            ensureInit(context)
            Python.getInstance().getModule("logging")
                .callAttr("getLogger", "igbot")
                .callAttr("info", msg)
        } catch (e: Exception) {
            Log.e("BotEngine", "logExternal: ", e)
        }
    }

    /** Очистити плитку «Огляду»: ready | interacted | skipped | errors. Повертає, скільки очищено. */
    fun clearCounter(context: Context, kind: String): Int {
        val raw = module(context).callAttr("clear_counter", kind).toString()
        return JSONObject(raw).optInt("cleared", 0)
    }

    // ── налаштування ────────────────────────────────────────────────
    fun getSettingsJson(context: Context): JSONObject =
        JSONObject(module(context).callAttr("get_settings_json").toString())

    fun saveSettingsJson(context: Context, json: JSONObject) {
        module(context).callAttr("save_settings_json", json.toString())
    }

    fun unlockAll(context: Context) {
        module(context).callAttr("unlock_all")
    }

    // ── акаунти ─────────────────────────────────────────────────────
    /** Зберегти акаунт після входу через WebView. kind = "main" | "scraper" */
    fun registerAccount(
        context: Context,
        kind: String,
        cookiesJson: String,
        userAgent: String,
        usernameHint: String
    ): JSONObject {
        val raw = module(context)
            .callAttr("register_account", kind, cookiesJson, userAgent, usernameHint)
            .toString()
        return JSONObject(raw)
    }

    /** Вийти з акаунта (очистити дані та файл сесії) */
    fun logoutAccount(context: Context, kind: String) {
        module(context).callAttr("logout_account", kind)
    }

    /** Імпорт черги з JSON-файлу (команда export на ПК). Повертає {added, skipped, total}. */
    fun importTargets(context: Context, rawJson: String): JSONObject =
        JSONObject(module(context).callAttr("import_targets_json", rawJson).toString())

    // ── мій профіль: підписники / підписки ──────────────────────────
    /** Останній збережений знімок або null, якщо ще не завантажували. */
    fun friendsCached(context: Context): JSONObject? {
        val raw = module(context).callAttr("friends_get_cached").toString()
        return if (raw.isBlank()) null else JSONObject(raw)
    }

    /** Повне оновлення (кілька хвилин на великих акаунтах). {ok, error?} */
    fun friendsRefresh(context: Context): JSONObject =
        JSONObject(module(context).callAttr("friends_refresh").toString())

    fun friendsProgress(context: Context): JSONObject =
        JSONObject(module(context).callAttr("friends_progress").toString())

    /** action = "follow" | "unfollow". status: ok | requested | blocked | rate_limited | login | error */
    fun friendsAction(context: Context, action: String, pk: Long, username: String): JSONObject =
        JSONObject(module(context).callAttr("friends_action", action, pk, username).toString())
}
