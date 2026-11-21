package com.lingxi.ime

import org.json.JSONArray

/**
 * Rust 引擎（libprisir_ime.so）的 JNI 桥。
 *
 * 与 Windows TSF / Linux fcitx5 用的是**同一份引擎**（prisir_ime crate），
 * 所以三端候选、整句、学习行为一致（见 installer/smoke_matrix.py 的 15 条共用用例）。
 *
 * 类名/方法名必须与 prisir_ime/src/jni.rs 的导出严格一致：
 *   Java_com_lingxi_ime_Engine_nativeLoad / nativeQuery / nativeSmartSentence / nativeLearn / nativeFree
 */
object Engine {

    private var handle: Long = 0L

    /**
     * 引擎内部只有一个 SQLite 连接，**不能多线程同时用**。
     *
     * 现在候选查询放在后台线程（不然按键会卡），而"学习"还在主线程，
     * 两边同时进同一个连接会出事 → 统一用这把锁串行化。
     */
    private val lock = Any()

    val loaded: Boolean get() = handle != 0L

    private external fun nativeLoad(dbPath: String, buildIndex: Boolean): Long
    private external fun nativeQuery(handle: Long, input: String): String
    private external fun nativeQueryT9(handle: Long, digits: String): String
    private external fun nativeSmartSentence(handle: Long, input: String): String
    private external fun nativeLearn(handle: Long, input: String, word: String)
    private external fun nativeFree(handle: Long)

    // ── 词库管理（与 Windows 词库窗同一套引擎能力）────────────────────────
    private external fun nativeUserList(handle: Long): String
    private external fun nativeUserAdd(handle: Long, pinyin: String, word: String): Boolean
    private external fun nativeUserRemove(handle: Long, pinyin: String, word: String): Int
    private external fun nativeDictSearchPage(handle: Long, term: String, limit: Int, offset: Int): String
    private external fun nativeDictCount(handle: Long, term: String): Long
    private external fun nativeNameList(handle: Long, black: Boolean): String
    private external fun nativeNameAdd(handle: Long, black: Boolean, word: String, reason: String): Int
    private external fun nativeNameRemove(handle: Long, black: Boolean, word: String): Int
    private external fun nativeMainUpsert(handle: Long, pinyin: String, word: String, weight: Long): Int
    private external fun nativeMainDelete(handle: Long, pinyin: String, word: String): Int
    private external fun nativePinyinFor(handle: Long, word: String): String?

    // ── 简繁输出模式（P2，引擎自带 OpenCC 词表）─────────────────────────
    private external fun nativeSetScript(handle: Long, mode: Int): Int
    private external fun nativeScript(handle: Long): Int
    private external fun nativeToScript(handle: Long, text: String): String

    // ── mmap 内存映射索引（Android 专用）────────────────────────────────
    // 词库旁边有 <db>.midx 就用：映射即用、零 SQL 解析、不依赖 SQLite 页缓存。
    // 没有就继续走 SQLite（功能不受影响，只是慢一点）。
    private external fun nativeLoadMmap(handle: Long, dbPath: String): Int

    /** 是否已经用上 mmap 内存索引。 */
    @Volatile
    var usingMmap: Boolean = false
        private set

    // ── 九宫格（T9）：数字串 → 读音 + 候选；以及"选定读音"后的候选 ────────
    private external fun nativeT9Query(handle: Long, digits: String): String
    private external fun nativeT9Readings(handle: Long, digits: String): String
    private external fun nativeQueryReading(handle: Long, reading: String): String

    // ── 26 键：输入 → 读音解析（简拼可发现性）──────────────────────────────
    private external fun nativeResolvePinyin(handle: Long, input: String): String?
    private external fun nativePinyinOf(handle: Long, word: String): String?

    // ── 诊断:同一输入在 mmap 与 SQLite 两条路径下的耗时/候选对比(JSON)──────
    private external fun nativeQueryProfile(handle: Long, input: String): String

    const val SCRIPT_OFF = 0
    const val SCRIPT_S2T = 1
    @Deprecated("简繁已简化为二态（0=简 1=繁），旧值 2 不再由上层产生")
    const val SCRIPT_T2S = 2

    /** 九宫格查询结果：可能的读音（"ni" / "ni hao"）+ 这些读音下的候选词。 */
    data class T9Result(val readings: List<String>, val words: List<String>)

    /**
     * 九宫格：数字串 → 读音 + 候选。
     *
     * 读音来自引擎的音节表精确展开（"64" → mi / ni / oi），
     * 候选是这些读音在词库里的命中（干净，不再是"整个前缀范围"的噪音）。
     */
    fun t9Query(digits: String): T9Result {
        if (handle == 0L || digits.isEmpty()) return T9Result(emptyList(), emptyList())
        val json = synchronized(lock) { nativeT9Query(handle, digits) }
        return try {
            val o = org.json.JSONObject(json)
            val readings = o.optJSONArray("pinyins")?.let { arr ->
                (0 until arr.length()).mapNotNull { i -> arr.optString(i).takeIf { it.isNotEmpty() } }
            } ?: emptyList()
            val words = o.optJSONArray("words")?.let { arr ->
                (0 until arr.length()).mapNotNull { i ->
                    arr.optJSONObject(i)?.optString("word")?.takeIf { it.isNotEmpty() }
                }
            } ?: emptyList()
            T9Result(readings, words)
        } catch (t: Throwable) {
            T9Result(emptyList(), emptyList())
        }
    }

    /** 九宫格：用户选定某个读音后，把候选收敛到那个读音。 */
    fun queryReading(reading: String): List<String> {
        if (handle == 0L || reading.isEmpty()) return emptyList()
        return synchronized(lock) { parse(nativeQueryReading(handle, reading)) }
    }

    /**
     * **26 键**：把输入解析成读音（音节用空格分隔），解析不出来返回 null。
     *
     * 用途：26 键打简拼（"nhjsg" → 「你好旧时光」）本来就能一次出多字词，
     * 但第一行只显示用户敲的字母 → 用户不知道这串字母被解读成了 5 个音节，
     * 能力等于不存在。把读音显示出来，这个能力才**可被发现**。
     *
     * 引擎侧（Rust）：全拼走音节表切分（"nihao" → "ni hao"）；
     * 简拼走 `phrase.jp` 精确命中 → 取词库里的全拼 key 再切分。
     */
    fun resolvePinyin(input: String): String? {
        if (handle == 0L || input.isEmpty()) return null
        val json = synchronized(lock) { nativeResolvePinyin(handle, input) } ?: return null
        return try {
            val o = org.json.JSONObject(json)
            o.optString("pinyin").takeIf { it.isNotEmpty() }
        } catch (t: Throwable) {
            null
        }
    }

    /**
     * **26 键**：由候选词反查读音（"你好" → "ni hao"），解析不出来返回 null。
     *
     * 界面用它显示第一行的读音 —— 必须按**词**查而不是按**输入**查：
     * 候选排序（精确命中优先 + 多字优先 + 权重）与 `phrase.jp` 的纯权重排序
     * 对同一串输入经常给出不同的首选，按词查才能保证"读音 = 首候选的读音"。
     */
    fun pinyinOf(word: String): String? {
        if (handle == 0L || word.isEmpty()) return null
        return synchronized(lock) { nativePinyinOf(handle, word) }
    }

    /**
     * 诊断：同一输入在 mmap 路径与 SQLite 路径下的耗时/首屏候选对比（JSON 字符串）。
     *
     * 用途：Android 端把候选查询搬到 mmap 索引上，必须证明"结果与 SQLite 一致"，
     * 否则三端候选不同、用户会发现"同样的词在手机和电脑不一样"。
     * 引擎侧会临时摘掉/装回内存索引各查一次 —— 这是**诊断**接口，不在打字路径里调用。
     */
    fun queryProfile(input: String): String =
        if (handle == 0L) "{}" else synchronized(lock) { nativeQueryProfile(handle, input) }

    /**
     * 九宫格：数字串 → 读音（**不查库**，引擎里纯内存展开，微秒级）。
     *
     * 主线程按键后**立刻**调它，首行马上显示拼音（"ni"）而不是数字串，
     * 候选还在后台查 —— 这样九宫格的手感和 26 键一样即时。
     */
    fun t9Readings(digits: String): List<String> {
        if (handle == 0L || digits.isEmpty()) return emptyList()
        val json = synchronized(lock) { nativeT9Readings(handle, digits) }
        return try {
            val arr = org.json.JSONArray(json)
            (0 until arr.length()).mapNotNull { i -> arr.optString(i).takeIf { it.isNotEmpty() } }
        } catch (t: Throwable) {
            emptyList()
        }
    }

    /** 设置简繁模式（0=简体 1=繁体），返回设置后的模式。 */
    fun setScript(mode: Int): Int = if (handle == 0L) 0 else nativeSetScript(handle, mode)

    /** 当前简繁模式。 */
    fun script(): Int = if (handle == 0L) 0 else nativeScript(handle)

    /** 按当前模式转换文本（原文案原样返回）。 */
    fun toScript(text: String): String =
        if (handle == 0L || text.isEmpty()) text else nativeToScript(handle, text)

    data class Row(val key: String, val value: String, val weight: Long)

    fun userRows(): List<Row> = objects(nativeUserList(handle))
    fun dictRows(term: String, limit: Int = 50, offset: Int = 0): List<Row> =
        objects(nativeDictSearchPage(handle, term, limit, offset))
    fun dictCount(term: String): Long = if (handle == 0L) 0 else nativeDictCount(handle, term)
    fun nameList(black: Boolean): List<String> =
        objects(nativeNameList(handle, black)).map { it.value }
    fun nameAdd(black: Boolean, word: String): Boolean =
        handle != 0L && nativeNameAdd(handle, black, word, if (black) "用户屏蔽" else "用户置顶") == 1
    fun nameRemove(black: Boolean, word: String): Boolean =
        handle != 0L && nativeNameRemove(handle, black, word) > 0
    fun userAdd(pinyin: String, word: String): Boolean =
        handle != 0L && nativeUserAdd(handle, pinyin, word)
    fun userRemove(pinyin: String, word: String): Boolean =
        handle != 0L && nativeUserRemove(handle, pinyin, word) > 0
    fun mainUpsert(pinyin: String, word: String, weight: Long): Boolean =
        handle != 0L && nativeMainUpsert(handle, pinyin, word, weight) == 1
    fun mainDelete(pinyin: String, word: String): Boolean =
        handle != 0L && nativeMainDelete(handle, pinyin, word) > 0
    fun pinyinFor(word: String): String? =
        if (handle == 0L) null else synchronized(lock) { nativePinyinFor(handle, word) }

    /** 把 [{"key":..,"value":..,"weight":..}] 解成 Row。 */
    private fun objects(json: String?): List<Row> {
        if (json.isNullOrBlank()) return emptyList()
        return try {
            val arr = JSONArray(json)
            buildList {
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    add(Row(o.optString("key"), o.optString("value"), o.optLong("weight")))
                }
            }
        } catch (t: Throwable) {
            emptyList()
        }
    }



    init {
        System.loadLibrary("prisir_ime")
    }

    /** 打开词库。词库不在 APK 里（302MB），由 adb push 到 filesDir。 */
    fun load(dbPath: String): Boolean {
        if (handle != 0L) return true
        handle = try {
            nativeLoad(dbPath, false)
        } catch (t: Throwable) {
            0L
        }
        if (handle == 0L) return false
        // 词库旁边有 <db>.midx 就用内存映射索引（查询 2~9ms，且不依赖 SQLite 页缓存）；
        // 没有/指纹对不上就走 SQLite（照常能用）。
        usingMmap = try {
            nativeLoadMmap(handle, dbPath) == 1
        } catch (t: Throwable) {
            false
        }
        android.util.Log.i("LingxiE", "引擎已加载，mmap 索引：$usingMmap")
        return true
    }

    /** 候选（最多 50 条，UI 自己分页）。 */
    fun query(input: String): List<String> {
        if (handle == 0L || input.isEmpty()) return emptyList()
        return synchronized(lock) { parse(nativeQuery(handle, input)) }
    }

    /** 九宫格候选（数字串 2-9）。 */
    fun queryT9(digits: String): List<String> {
        if (handle == 0L || digits.isEmpty()) return emptyList()
        return synchronized(lock) { parse(nativeQueryT9(handle, digits)) }
    }

    /** 整句（多词整句，空格上屏用）。 */
    fun sentence(input: String): String? {
        if (handle == 0L || input.length < 2) return null
        return parse(nativeSmartSentence(handle, input)).firstOrNull()
    }

    /** 选词后学习（频率靠前就靠它）。 */
    fun learn(input: String, word: String) {
        if (handle == 0L || input.isEmpty() || word.isEmpty()) return
        try {
            synchronized(lock) { nativeLearn(handle, input, word) }
        } catch (t: Throwable) {
            // 学习失败不影响打字
        }
    }

    fun close() {
        if (handle != 0L) {
            try {
                nativeFree(handle)
            } catch (t: Throwable) {
            }
            handle = 0L
        }
    }

    private fun parse(json: String?): List<String> {
        if (json.isNullOrBlank()) return emptyList()
        return try {
            val arr = JSONArray(json)
            buildList {
                for (i in 0 until arr.length()) {
                    val o = arr.opt(i)
                    val w = when (o) {
                        is String -> o
                        else -> (o as? org.json.JSONObject)?.optString("word")
                            ?.ifEmpty { null }
                            ?: (o as? org.json.JSONObject)?.optString("value")
                            ?: (o as? org.json.JSONObject)?.optString("sentence")
                            ?: ""
                    }
                    if (w.isNotEmpty()) add(w)
                }
            }
        } catch (t: Throwable) {
            emptyList()
        }
    }
}
