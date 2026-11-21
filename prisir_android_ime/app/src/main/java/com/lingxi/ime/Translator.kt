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
 * 翻译（中文 ↔ 多语言）：**多端点并行竞速** + 断网检测 + 翻译缓存 + 离线短语表。
 *
 * ## 为什么不能"一个端点打到底"
 * 翻译是**强依赖网络**的功能，而用户会出国、进弱网、换运营商 —— 单端点必然出现
 * "某个端点从某天起就再也连不上，而用户完全无感（只看到一句'翻译失败'）"。
 * 这里做三层兜底：
 *
 * 1. **缓存**（内存 LRU + 落盘）：翻译过的东西第二次 0 延迟；断网/出境也能用。
 *    （用户常翻的是**重复的词**，这条的实际命中率最高）
 * 2. **离线短语表**（assets）：常用表达**完全离线**可译，不依赖任何网络。
 * 3. **多端点并行竞速**：Google gtx / Mozhi（Google 前端代理）/ MyMemory **同时**打，
 *    **谁先成功用谁** —— Google 与 Mozhi 视为优质端（互相抢先即可挡住 MyMemory 脏 TM），
 *    MyMemory 仅作兜底。不需要"先探测哪个能用"。
 *
 * ## 为什么是"并行竞速"而不是"顺序 fallback"
 * 顺序 fallback 的延迟是"前面几个端点依次超时"**之和**（国内 Google 不通 = 先白等 3~6 秒，
 * 用户盯着转圈）。竞速的延迟是"**最快那个端点**"的延迟，与端点数量无关。
 *
 * ## 端点覆盖
 * | 端点 | 免 key | 国内可用 | 出境可用 | 备注 |
 * |---|---|---|---|---|
 * | Google gtx | ✓ | ✗（常被墙/不稳） | ✓ | 质量最好 |
 * | Mozhi (engine=google) | ✓ | 视实例 | ✓ | 公共前端代理 Google；替代已失效的 Libre 公共实例 |
 * | MyMemory | ✓ | ✓ | ✓ | 有每日配额；简中偶发脏 TM，作兜底 |
 */
object Translator {

    private val main = Handler(Looper.getMainLooper())

    /** 单端点超时：连接 2.5s / 读 4s。比原来(6/8)短 —— 端点不通时别让用户干等。 */
    private const val CONNECT_TIMEOUT = 2500
    private const val READ_TIMEOUT = 4000

    /** 总超时：所有端点都还没结果就放弃（不能无限等）。 */
    private const val TOTAL_TIMEOUT = 6000L

    /**
     * 个人词库容量（原"翻译缓存"升级而来）。
     *
     * 用户翻过的每一条都长期保留（不再是翻完就可能被挤掉的临时缓存），
     * 并支持导出/导入 —— 换机、重装、出境前把常用表达带在身上。
     * 词库体积不再是约束（用户明确："词库大小不重要，只要覆盖各场景"），
     * 所以从 500 提到 5000，落盘 JSON 也就几百 KB。
     */
    private const val CACHE_MAX = 5000

    private const val PREF_CACHE = "lingxi_tr_cache"
    private const val OFFLINE_ASSET = "offline_dict.txt"

    /**
     * Mozhi 公共实例（Google 引擎前端，免 key）。
     * 2026-11 实测：旧 LibreTranslate 公共实例（astian / zillyhuhn）已 DNS/证书失效；
     * Lingva 多数实例 403/超时；`mozhi.pussthecat.org` 对 en→zh-CN/zh-TW 可用且质量对齐 Google。
     * 多实例轮询：单个社区实例会挂，竞速下无害。
     */
    private val MOZHI_INSTANCES = listOf(
        "https://mozhi.pussthecat.org",
        "https://mozhi.ducks.party",
        "https://mozhi.adminforge.de",
    )

    /** 翻译结果。 */
    sealed class Outcome {
        /** [offline]=true 表示来自**离线短语表**（没走网络，质量有限但确实离线可用）。 */
        data class Ok(val text: String, val offline: Boolean) : Outcome()

        data class Err(val reason: String) : Outcome()
    }

    private var appCtx: Context? = null
    private var initialized = false

    /** 翻译缓存：内存 LRU（访问序）+ 落盘（SharedPreferences/JSON）。 */
    private val cache = object : LinkedHashMap<String, String>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean =
            size > CACHE_MAX
    }

    /** 离线短语表：key = "src>tgt"，value = 该语向的短语表。 */
    private val offline: HashMap<String, Map<String, String>> = HashMap()

    /** 初始化（加载离线词表 + 缓存）。幂等，可在 IME 启动时调一次。 */
    fun init(ctx: Context) {
        synchronized(this) {
            if (initialized) return
            initialized = true
            appCtx = ctx.applicationContext
        }
        loadOffline(appCtx!!)
        loadCache(appCtx!!)
    }

    /** 诊断：离线词库条数 + 个人词库条数（状态页显示，方便确认离线能力是否就位）。 */
    fun stats(): String {
        val offlineCount = synchronized(offline) { offline.values.sumOf { it.size } }
        val cached = synchronized(cache) { cache.size }
        return "离线词库 $offlineCount 条 · 个人词库 $cached 条"
    }

    /**
     * 导出个人词库为 JSON 字符串（换机/备份用）。
     * key = "目标语言\u0001原文"，value = 译文。
     */
    fun exportPersonal(): String = synchronized(cache) {
        val obj = JSONObject()
        cache.forEach { (k, v) -> obj.put(k, v) }
        obj.toString()
    }

    /**
     * 导入个人词库（与 [exportPersonal] 配对）。返回实际导入条数。
     * 合并而不是覆盖 —— 换机后翻译过的旧词和本机现有词都保留。
     */
    fun importPersonal(raw: String): Int {
        if (raw.isBlank()) return 0
        return try {
            val obj = JSONObject(raw)
            val ctx = appCtx ?: return 0
            synchronized(cache) {
                obj.keys().forEach { k -> cache[k] = obj.optString(k) }
            }
            // 立刻落盘，别等下一次翻译
            ctx.getSharedPreferences(PREF_CACHE, Context.MODE_PRIVATE)
                .edit().putString("data", synchronized(cache) {
                    val o = JSONObject(); cache.forEach { (k, v) -> o.put(k, v) }; o.toString()
                }).apply()
            synchronized(cache) { cache.size }
        } catch (t: Throwable) {
            0
        }
    }

    /**
     * 翻译。回调在主线程。
     *
     * 顺序：缓存 → 离线短语表 → （有网才）多端点并行竞速。
     * 无网且离线表没有 → 直接失败（**不白等 9 秒**）。
     */
    fun translate(text: String, target: String, callback: (Outcome) -> Unit) {
        val t = text.trim()
        if (t.isEmpty()) {
            main.post { callback(Outcome.Err("没有可翻译的内容")) }
            return
        }
        // 0) 源语言与目标语言同为英文：无需翻译（否则端点会返回
        //    "PLEASE SELECT TWO DISTINCT LANGUAGES" 这类错误文本）。直接回原文。
        if (guessSource(t) == "en" && (target == "en" || target == "en-US" || target == "en-GB")) {
            main.post { callback(Outcome.Ok(t, false)) }
            return
        }
        // 1) 缓存命中（翻译过的东西，第二次 0 延迟，断网也能用）
        synchronized(cache) {
            cache[cacheKey(t, target)]?.let { hit ->
                main.post { callback(Outcome.Ok(hit, false)) }
                return
            }
        }
        // 2) 离线短语表命中（完全不需要网络）
        offlineLookup(t, target)?.let { hit ->
            main.post { callback(Outcome.Ok(hit, true)) }
            return
        }
        // 3) 没网就别白等：直接失败（离线表已在上一步试过）
        if (!hasNetwork()) {
            main.post { callback(Outcome.Err("当前无网络，离线词库也没有这个词")) }
            return
        }
        // 4) 多端点并行竞速
        race(t, target, callback)
    }

    // ── 并行竞速 ─────────────────────────────────────────────────────────────

    private fun race(text: String, target: String, callback: (Outcome) -> Unit) {
        val src = guessSource(text)
        val done = AtomicBoolean(false)
        val googleDone = AtomicBoolean(false)

        fun deliver(result: String, fromGoogle: Boolean) {
            if (fromGoogle) {
                googleDone.set(true)
                if (done.compareAndSet(false, true)) {
                    putCache(text, target, result)
                    main.post { callback(Outcome.Ok(result, false)) }
                }
            } else {
                // 非 Google 端点：只要 Google 还没抢先返回，就接受它（国内场景 Google 会超时）
                if (!googleDone.get() && done.compareAndSet(false, true)) {
                    putCache(text, target, result)
                    main.post { callback(Outcome.Ok(result, false)) }
                }
            }
        }

        // preferred=true：Google 质量档（含 Mozhi 代理）。任一先到即挡住 MyMemory 脏结果。
        val jobs = listOf(
            Triple(true, "google", { google(text, target) }),
            Triple(true, "mozhi-1", { mozhi(text, src, target, MOZHI_INSTANCES[0]) }),
            Triple(true, "mozhi-2", { mozhi(text, src, target, MOZHI_INSTANCES[1]) }),
            Triple(false, "mymemory", { mymemory(text, src, target) }),
        )
        for ((isGoogle, epName, job) in jobs) {
            Thread {
                val r = try {
                    job()
                } catch (t: Throwable) {
                    null
                }
                if (!r.isNullOrBlank()) {
                    logEndpoint(epName, text, r)
                    deliver(r, isGoogle)
                }
            }.start()
        }
        // 总超时兜底（每个端点自己也有超时，这里防"全部线程都卡住"的极端情况）
        main.postDelayed({
            if (done.compareAndSet(false, true)) {
                callback(Outcome.Err("翻译服务不可用（网络不通或超时）"))
            }
        }, TOTAL_TIMEOUT)
    }

    /** 诊断：哪个端点赢了这次翻译（调试构建）。判断"当前环境该用哪个端点"就靠它。 */
    private fun logEndpoint(ep: String, text: String, result: String) {
        val ctx = appCtx ?: return
        try {
            if (ctx.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0) {
                android.util.Log.d("LingxiT", "translate '$text' via $ep -> '$result'")
            }
        } catch (t: Throwable) {
        }
    }

    // ── 端点实现 ─────────────────────────────────────────────────────────────

    /** Google 翻译的 gtx 端点（免 key；质量最好，出境可用）。 */
    private fun google(text: String, target: String): String? {
        val url = "https://translate.googleapis.com/translate_a/single?client=gtx&sl=auto&tl=" +
            URLEncoder.encode(target, "UTF-8") + "&dt=t&q=" + URLEncoder.encode(text, "UTF-8")
        val body = get(url) ?: return null
        val arr = JSONArray(body)
        val segs = arr.optJSONArray(0) ?: return null
        val sb = StringBuilder()
        for (i in 0 until segs.length()) {
            val seg = segs.optJSONArray(i) ?: continue
            sb.append(seg.optString(0))
        }
        return decodeEntities(sb.toString())
    }

    /** MyMemory（免 key；国内可直连；有每日配额，超额会返回错误文本 → 识别为失败）。 */
    private fun mymemory(text: String, src: String, target: String): String? {
        val url = "https://api.mymemory.translated.net/get?q=" + URLEncoder.encode(text, "UTF-8") +
            "&langpair=" + URLEncoder.encode("$src|$target", "UTF-8")
        val body = get(url) ?: return null
        val obj = JSONObject(body)
        val data = obj.optJSONObject("responseData") ?: return null
        val t = data.optString("translatedText")
        if (t.isBlank() || t.contains("MYMEMORY WARNING", true) ||
            t.contains("INVALID LANGUAGE PAIR", true) ||
            t.contains("QUERY LENGTH LIMIT", true)
        ) return null
        return decodeEntities(t)
    }

    /**
     * Mozhi：社区前端代理，`engine=google` 时质量接近 Google gtx。
     * API：GET `{base}/api/translate?engine=google&from=&to=&text=`
     * 注意：源语言不能传裸 `zh`（会 500），需映射为 `zh-CN`。
     */
    private fun mozhi(text: String, src: String, target: String, base: String): String? {
        val from = mozhiLang(src)
        val to = mozhiLang(target)
        val url = base.trimEnd('/') + "/api/translate?engine=google" +
            "&from=" + URLEncoder.encode(from, "UTF-8") +
            "&to=" + URLEncoder.encode(to, "UTF-8") +
            "&text=" + URLEncoder.encode(text, "UTF-8")
        val body = get(url) ?: return null
        val obj = JSONObject(body)
        val t = obj.optString("translated-text")
        if (t.isBlank()) return null
        return decodeEntities(t)
    }

    /** Mozhi 语码：应用内 `zh` → `zh-CN`；其余与 Google tl 一致（含 zh-CN / zh-TW）。 */
    private fun mozhiLang(code: String): String = when (code) {
        "zh" -> "zh-CN"
        else -> code
    }

    private fun get(url: String): String? = request(url, null)

    private fun request(url: String, body: String?): String? {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = if (body == null) "GET" else "POST"
                connectTimeout = CONNECT_TIMEOUT
                readTimeout = READ_TIMEOUT
                setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android) LingxiIME/2.0")
                if (body != null) {
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json; charset=utf-8")
                }
            }
            if (body != null) {
                conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
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

    // ── 网络检测 ─────────────────────────────────────────────────────────────

    /**
     * 当前有没有可用网络。
     *
     * **不能只看 activeNetwork**：飞行模式下某些机型仍会返回那个"已断开"的网络对象，
     * 它的 capabilities 里还带着 INTERNET 能力 → 误判成"有网" → 干等 9 秒才失败
     * （实测华为 JDN2 就是这样）。改看 allNetworks —— 它只包含**已连接**的网络，
     * 飞行模式下是空的。
     */
    private fun hasNetwork(): Boolean {
        val ctx = appCtx ?: return true   // 拿不到上下文就当有网（宁可多等也别误杀）
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

    // ── 缓存 ─────────────────────────────────────────────────────────────────

    private fun cacheKey(text: String, target: String): String = "$target\u0001$text"

    private fun putCache(text: String, target: String, value: String) {
        val ctx = appCtx ?: return
        synchronized(cache) { cache[cacheKey(text, target)] = value }
        // 落盘（JSON 存，译文里的换行/制表符由 JSON 转义，不会有分隔符冲突）
        try {
            val obj = JSONObject()
            synchronized(cache) {
                cache.forEach { (k, v) -> obj.put(k, v) }
            }
            ctx.getSharedPreferences(PREF_CACHE, Context.MODE_PRIVATE)
                .edit().putString("data", obj.toString()).apply()
        } catch (t: Throwable) {
            // 缓存写失败不影响翻译
        }
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
            // 缓存坏了就当没有
        }
    }

    // ── 离线短语表 ───────────────────────────────────────────────────────────

    /**
     * 离线短语表查找。
     *
     * 表格式（assets/offline_dict.txt）：
     * ```
     * #! src=zh tgt=en
     * 你好<TAB>hello
     * ```
     * 查不到精确短语就返回 null（**不做逐词硬拼** —— 拼出来的句子质量太差，
     * 宁可明确告诉用户"离线词库没有这个词"，也不给一个似是而非的译文）。
     */
    private fun offlineLookup(text: String, target: String): String? {
        val src = guessSource(text)
        val key = "$src>$target"
        // 简体中文目标（zh-CN）复用已有的 `en>zh` 离线表 —— 2026-11-20 语言面板
        // 加了 zh-CN，但离线表只有 zh 语向；不别名的话英文翻中文就永远走网络。
        val fallback = if (target == "zh-CN") "$src>zh" else "auto>$target"
        val table = offline[key] ?: offline[fallback] ?: return null
        return table[text] ?: table[text.trim()]
    }

    private fun loadOffline(ctx: Context) {
        try {
            ctx.assets.open(OFFLINE_ASSET).bufferedReader(Charsets.UTF_8).useLines { lines ->
                var curKey = "zh>en"
                val table = HashMap<String, MutableMap<String, String>>()
                lines.forEach { raw ->
                    val line = raw.trim()
                    if (line.isEmpty()) return@forEach
                    if (line.startsWith("#!")) {
                        val h = line.removePrefix("#!").trim()
                        val src = Regex("src=(\\S+)").find(h)?.groupValues?.get(1) ?: "zh"
                        val tgt = Regex("tgt=(\\S+)").find(h)?.groupValues?.get(1) ?: "en"
                        curKey = "$src>$tgt"
                        table.getOrPut(curKey) { HashMap() }
                        return@forEach
                    }
                    if (line.startsWith("#")) return@forEach
                    val tab = line.indexOf('\t')
                    if (tab <= 0) return@forEach
                    val k = line.substring(0, tab).trim()
                    val v = line.substring(tab + 1).trim()
                    if (k.isEmpty() || v.isEmpty()) return@forEach
                    table.getOrPut(curKey) { HashMap() }[k] = v
                }
                synchronized(offline) {
                    offline.clear()
                    offline.putAll(table)
                }
            }
        } catch (t: Throwable) {
            // 没有 assets 或格式不对 → 没有离线表（云翻译仍可用）
        }
    }

    // ── 工具 ─────────────────────────────────────────────────────────────────

    /**
     * 源语言猜测：含中日韩统一表意文字/假名 → 中文，否则按英文。
     *
     * 用 **ISO 639-1 两字母代码**（"zh"/"en"），离线短语表的语向键（"zh>en"）
     * 和 MyMemory 的 langpair 都用同一套 —— 三处不一致的话离线表永远命中不了。
     */
    private fun guessSource(text: String): String {
        val cjk = text.any { it.code in 0x4E00..0x9FFF }
        val kana = text.any { it.code in 0x3040..0x30FF }
        return if (cjk || kana) "zh" else "en"
    }

    /** 翻译接口偶尔会返回 HTML 实体（&quot; &amp; &#39; …）。 */
    private fun decodeEntities(s: String): String = s
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .replace("&apos;", "'")
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&nbsp;", " ")
}
