package com.lingxi.ime

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 轻量词典查询（主动「查」才联网）。
 *
 * 对齐 [Translator] 的缓存 + 断网检测 + 多端点竞速模式：
 * 1. 落盘缓存（查过的词二次 0 延迟）
 * 2. FreeDictionaryAPI（Wiktionary）/ dictionaryapi.dev 并行，谁先成功用谁
 * 3. 仅取词性 + 前两条释义，压成候选栏可显示的短摘要
 *
 * 数据来源署名：Wiktionary（CC BY-SA）via FreeDictionaryAPI / dictionaryapi.dev。
 */
object Dictionary {

    private val main = Handler(Looper.getMainLooper())
    private const val CONNECT_TIMEOUT = 2500
    private const val READ_TIMEOUT = 4000
    private const val TOTAL_TIMEOUT = 6000L
    private const val CACHE_MAX = 800
    private const val PREF_CACHE = "lingxi_dict_cache"

    sealed class Outcome {
        data class Ok(val summary: String, val detail: String, val sourceUrl: String) : Outcome()
        data class Err(val reason: String) : Outcome()
    }

    private var appCtx: Context? = null
    private var initialized = false

    private val cache = object : LinkedHashMap<String, String>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean =
            size > CACHE_MAX
    }

    fun init(ctx: Context) {
        synchronized(this) {
            if (initialized) return
            initialized = true
            appCtx = ctx.applicationContext
        }
        loadCache(appCtx!!)
    }

    fun lookup(word: String, callback: (Outcome) -> Unit) {
        val w = word.trim().trim(',', '.', '!', '?', ':', ';', '"', '\'', '“', '”', '‘', '’')
        if (w.isEmpty()) {
            main.post { callback(Outcome.Err("没有可查询的词")) }
            return
        }
        // 短语过长不适合当词典条目（整句请用「译」）
        if (w.length > 48 || w.count { it.isWhitespace() } > 3) {
            main.post { callback(Outcome.Err("请选单个词再查（整句用「译」）")) }
            return
        }
        val lang = guessLang(w)
        synchronized(cache) {
            cache[cacheKey(lang, w)]?.let { raw ->
                parseCached(raw)?.let { hit ->
                    main.post { callback(hit) }
                    return
                }
            }
        }
        if (!hasNetwork()) {
            main.post { callback(Outcome.Err("当前无网络，且本地没有缓存")) }
            return
        }
        race(w, lang, callback)
    }

    private fun race(word: String, lang: String, callback: (Outcome) -> Unit) {
        val done = AtomicBoolean(false)
        val jobs = listOf(
            { freedictionary(word, lang) },
            { dictionaryApiDev(word, lang) },
        )
        for (job in jobs) {
            Thread {
                val r = try {
                    job()
                } catch (t: Throwable) {
                    null
                }
                if (r != null && done.compareAndSet(false, true)) {
                    putCache(lang, word, r)
                    main.post { callback(r) }
                }
            }.start()
        }
        main.postDelayed({
            if (done.compareAndSet(false, true)) {
                callback(
                    Outcome.Err(
                        if (lang == "zh") "暂无该词释义（中文词典离线包后续提供）"
                        else "词典服务不可用或未收录"
                    )
                )
            }
        }, TOTAL_TIMEOUT)
    }

    /** FreeDictionaryAPI：Wiktionary 结构化数据。 */
    private fun freedictionary(word: String, lang: String): Outcome.Ok? {
        val url = "https://freedictionaryapi.com/api/entries/" +
            URLEncoder.encode(lang, "UTF-8") + "/" +
            URLEncoder.encode(word.lowercase(), "UTF-8")
        val body = get(url) ?: return null
        val root = JSONObject(body)
        val entries = root.optJSONArray("entries") ?: return null
        if (entries.length() == 0) return null
        val parts = ArrayList<String>()
        val details = ArrayList<String>()
        for (i in 0 until entries.length()) {
            val e = entries.optJSONObject(i) ?: continue
            val pos = e.optString("partOfSpeech").ifBlank { "?" }
            val senses = e.optJSONArray("senses") ?: continue
            val defs = ArrayList<String>()
            for (j in 0 until minOf(2, senses.length())) {
                val d = senses.optJSONObject(j)?.optString("definition").orEmpty().trim()
                if (d.isNotEmpty()) defs.add(d)
            }
            if (defs.isEmpty()) continue
            parts.add("$pos. ${defs[0]}")
            details.add("[$pos] " + defs.joinToString("；"))
            if (parts.size >= 2) break
        }
        if (parts.isEmpty()) return null
        val src = root.optJSONObject("source")?.optString("url").orEmpty()
        return Outcome.Ok(
            summary = shorten(parts.joinToString(" · "), 56),
            detail = details.joinToString("\n"),
            sourceUrl = src
        )
    }

    /** dictionaryapi.dev：英文为主；非 en 时也试 en 作回退。 */
    private fun dictionaryApiDev(word: String, lang: String): Outcome.Ok? {
        val code = if (lang == "en") "en" else return null
        val url = "https://api.dictionaryapi.dev/api/v2/entries/$code/" +
            URLEncoder.encode(word.lowercase(), "UTF-8")
        val body = get(url) ?: return null
        if (body.startsWith("{")) {
            // {"title":"No Definitions Found",...}
            return null
        }
        val arr = JSONArray(body)
        if (arr.length() == 0) return null
        val first = arr.optJSONObject(0) ?: return null
        val meanings = first.optJSONArray("meanings") ?: return null
        val parts = ArrayList<String>()
        val details = ArrayList<String>()
        for (i in 0 until meanings.length()) {
            val m = meanings.optJSONObject(i) ?: continue
            val pos = m.optString("partOfSpeech").ifBlank { "?" }
            val defsArr = m.optJSONArray("definitions") ?: continue
            val defs = ArrayList<String>()
            for (j in 0 until minOf(2, defsArr.length())) {
                val d = defsArr.optJSONObject(j)?.optString("definition").orEmpty().trim()
                if (d.isNotEmpty()) defs.add(d)
            }
            if (defs.isEmpty()) continue
            parts.add("$pos. ${defs[0]}")
            details.add("[$pos] " + defs.joinToString("；"))
            if (parts.size >= 2) break
        }
        if (parts.isEmpty()) return null
        return Outcome.Ok(
            summary = shorten(parts.joinToString(" · "), 56),
            detail = details.joinToString("\n"),
            sourceUrl = "https://en.wiktionary.org/wiki/" + URLEncoder.encode(word, "UTF-8")
        )
    }

    private fun guessLang(text: String): String {
        val cjk = text.any { it.code in 0x4E00..0x9FFF }
        return if (cjk) "zh" else "en"
    }

    private fun shorten(s: String, max: Int): String =
        if (s.length <= max) s else s.take(max - 1) + "…"

    private fun get(url: String): String? {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = CONNECT_TIMEOUT
                readTimeout = READ_TIMEOUT
                setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android) LingxiIME/2.0")
                setRequestProperty("Accept", "application/json")
            }
            if (conn.responseCode !in 200..299) return null
            conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } catch (t: Throwable) {
            null
        } finally {
            try {
                conn?.disconnect()
            } catch (t: Throwable) {
            }
        }
    }

    private fun hasNetwork(): Boolean {
        val ctx = appCtx ?: return true
        return try {
            val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                ?: return true
            cm.allNetworks.any { n ->
                val c = cm.getNetworkCapabilities(n)
                c != null && c.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            }
        } catch (t: Throwable) {
            true
        }
    }

    private fun cacheKey(lang: String, word: String): String = "$lang\u0001${word.lowercase()}"

    private fun putCache(lang: String, word: String, ok: Outcome.Ok) {
        val ctx = appCtx ?: return
        val payload = JSONObject()
            .put("summary", ok.summary)
            .put("detail", ok.detail)
            .put("sourceUrl", ok.sourceUrl)
            .toString()
        synchronized(cache) { cache[cacheKey(lang, word)] = payload }
        try {
            val obj = JSONObject()
            synchronized(cache) { cache.forEach { (k, v) -> obj.put(k, v) } }
            ctx.getSharedPreferences(PREF_CACHE, Context.MODE_PRIVATE)
                .edit().putString("data", obj.toString()).apply()
        } catch (t: Throwable) {
        }
    }

    private fun parseCached(raw: String): Outcome.Ok? = try {
        val o = JSONObject(raw)
        val summary = o.optString("summary")
        if (summary.isBlank()) null
        else Outcome.Ok(summary, o.optString("detail"), o.optString("sourceUrl"))
    } catch (t: Throwable) {
        null
    }

    private fun loadCache(ctx: Context) {
        try {
            val raw = ctx.getSharedPreferences(PREF_CACHE, Context.MODE_PRIVATE)
                .getString("data", "") ?: ""
            if (raw.isEmpty()) return
            val obj = JSONObject(raw)
            synchronized(cache) {
                cache.clear()
                obj.keys().forEach { k -> cache[k] = obj.optString(k) }
            }
        } catch (t: Throwable) {
        }
    }
}
