package com.maks.igbot.data

import android.content.Context
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import org.json.JSONObject

object BotEngine {
    private var initialized = false

    @Synchronized
    fun ensureInit(context: Context) {
        if (!Python.isStarted()) {
            Python.start(AndroidPlatform(context.applicationContext))
        }
        if (!initialized) {
            val py = Python.getInstance()
            val mod = py.getModule("igbot_engine")
            mod.callAttr("init_android_env", context.filesDir.absolutePath)
            initialized = true
        }
    }

    private fun module(context: Context) =
        ensureInit(context).let { Python.getInstance().getModule("igbot_engine") }

    fun startWorker(context: Context) {
        module(context).callAttr("start_worker_thread")
    }

    fun stopWorker(context: Context) {
        module(context).callAttr("stop_worker_thread")
    }

    fun startScraper(context: Context, sourceType: String, target: String, amount: Int) {
        module(context).callAttr("start_scraper_thread", sourceType, target, amount)
    }

    fun stopScraper(context: Context) {
        module(context).callAttr("stop_scraper_thread")
    }

    fun getDashboardJson(context: Context): JSONObject {
        val raw = module(context).callAttr("get_dashboard_json").toString()
        return JSONObject(raw)
    }

    fun getSettingsJson(context: Context): JSONObject {
        val raw = module(context).callAttr("get_settings_json").toString()
        return JSONObject(raw)
    }

    fun saveSettingsJson(context: Context, json: JSONObject) {
        module(context).callAttr("save_settings_json", json.toString())
    }

    fun unlockAll(context: Context) {
        module(context).callAttr("unlock_all")
    }

    /** Зберегти акаунт після входу через WebView. kind = "main" | "scraper" */
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
}
