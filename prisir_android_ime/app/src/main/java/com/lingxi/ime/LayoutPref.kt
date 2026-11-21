package com.lingxi.ime

import android.content.Context

/**
 * 键盘布局选择：拼音 26 键 / 拼音 9 键 / 英文 26 键。
 *
 * 为什么不用"输入法子类型"：实测 EMUI 的 IME 列表会缓存子类型（新增后不刷新，
 * `ime list -a` 里看不到 layout=t9 那条），用户切不过去。搜狗的做法也是**可见的键盘切换入口**，
 * 所以这里用一个显式偏好 + 状态页里的切换按钮 + 键盘底行上的布局切换键（都不藏）。
 */
object LayoutPref {
    private const val FILE = "lingxi_prefs"
    private const val KEY = "layout"      // "26" | "t9" | "en"
    private const val KEY_TRAD = "traditional"      // 旧版：输出繁体？（迁移用）
    private const val KEY_SCRIPT = "script_mode"    // 简繁二态：0=简体 1=繁体
    private const val KEY_TLANG = "translate_lang"  // 翻译目标语言
    private const val KEY_OPEN_API = "open_api_enabled"  // 本机开放 API（默认关）
    private const val KEY_OPEN_API_PORT = "open_api_port"

    const val SCRIPT_OFF = 0
    const val SCRIPT_S2T = 1
    @Deprecated("简繁已简化为二态（0=简 1=繁），旧值 2 读取时归一化为 0")
    const val SCRIPT_T2S = 2

    const val PY26 = "26"
    const val PY9 = "t9"
    const val EN26 = "en"

    fun mode(ctx: Context): String =
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString(KEY, PY26) ?: PY26

    fun setMode(ctx: Context, mode: String) {
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putString(KEY, mode).apply()
    }

    fun isT9(ctx: Context): Boolean = mode(ctx) == PY9
    fun isEn(ctx: Context): Boolean = mode(ctx) == EN26

    /** 循环：拼音 26 → 拼音 9 → 英文 26 → 拼音 26。 */
    fun next(ctx: Context): String {
        val m = when (mode(ctx)) {
            PY26 -> PY9
            PY9 -> EN26
            else -> PY26
        }
        setMode(ctx, m)
        return m
    }

    /** 键盘底行上的布局切换键：只在两种拼音布局之间来回（英文页没有这个键）。 */
    fun switchPinyin(ctx: Context): String {
        val m = if (mode(ctx) == PY9) PY26 else PY9
        setMode(ctx, m)
        return m
    }

    fun label(ctx: Context): String = when (mode(ctx)) {
        PY9 -> "拼音 9 键"
        EN26 -> "英文 26 键"
        else -> "拼音 26 键"
    }

    // ── 输出繁体（本地 OpenCC 词表，不联网）───────────────────────────────

    /**
     * 简繁输出模式：0=简体 1=繁体（数值与引擎 ScriptMode 一致）。
     *
     * 2026-11-20 由三态简化为二态：旧的「繁→简」（2）读取时归一化为简体（0），
     * 写入只产生 0/1。旧版只有 boolean「是否繁体」，首次读取时迁移（true→S2T）。
     */
    fun scriptMode(ctx: Context): Int {
        val p = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        val raw = when {
            p.contains(KEY_SCRIPT) -> p.getInt(KEY_SCRIPT, SCRIPT_OFF)
            p.getBoolean(KEY_TRAD, false) -> SCRIPT_S2T
            else -> SCRIPT_OFF
        }
        return if (raw == SCRIPT_S2T) SCRIPT_S2T else SCRIPT_OFF
    }

    fun setScriptMode(ctx: Context, mode: Int) {
        val v = if (mode == SCRIPT_S2T) SCRIPT_S2T else SCRIPT_OFF
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
            .putInt(KEY_SCRIPT, v)
            .putBoolean(KEY_TRAD, v == SCRIPT_S2T)   // 保持旧字段同步，兼容旧读者
            .apply()
    }

    /** 二态切换：简体 → 繁体 → 简体。工具键每点一次进一态。 */
    fun nextScriptMode(ctx: Context): Int {
        val next = if (scriptMode(ctx) == SCRIPT_S2T) SCRIPT_OFF else SCRIPT_S2T
        setScriptMode(ctx, next)
        return next
    }

    fun scriptLabel(ctx: Context): String = if (scriptMode(ctx) == SCRIPT_S2T) "繁" else "简"

    // 旧接口保留给尚未改造的调用点；true 仅在 S2T 态。
    fun traditional(ctx: Context): Boolean = scriptMode(ctx) == SCRIPT_S2T
    fun setTraditional(ctx: Context, on: Boolean) =
        setScriptMode(ctx, if (on) SCRIPT_S2T else SCRIPT_OFF)

    // ── 翻译目标语言 ──────────────────────────────────────────────────────

    /** 可选目标语言（Google 翻译的 tl 参数）。2026-11-20 扩充到 20 种。 */
    val TRANSLATE_LANGS: List<Pair<String, String>> = listOf(
        "en" to "英语",
        "zh-CN" to "简体中文",
        "zh-TW" to "繁体中文",
        "ja" to "日语",
        "ko" to "韩语",
        "fr" to "法语",
        "de" to "德语",
        "es" to "西班牙语",
        "ru" to "俄语",
        "it" to "意大利语",
        "pt" to "葡萄牙语",
        "vi" to "越南语",
        "th" to "泰语",
        "ar" to "阿拉伯语",
        "nl" to "荷兰语",
        "pl" to "波兰语",
        "tr" to "土耳其语",
        "id" to "印尼语",
        "ms" to "马来语",
        "hi" to "印地语",
    )

    /**
     * 语言短标签（候选栏语言面板用，一个字）。
     *
     * 用短标签而不是全名：面板一行要放下 20 个语言，长名根本排不开。
     * 源语言由 Translator.guessSource 自动识别（CJK → zh，其余 → en），
     * 所以英文翻中文只要目标选「中」即可，不用切"源语言"。
     */
    private val SHORT_LANG = mapOf(
        "en" to "英", "zh-CN" to "中", "zh-TW" to "繁", "ja" to "日", "ko" to "韩",
        "fr" to "法", "de" to "德", "es" to "西", "ru" to "俄", "it" to "意",
        "pt" to "葡", "vi" to "越", "th" to "泰", "ar" to "阿", "nl" to "荷",
        "pl" to "波", "tr" to "土", "id" to "尼", "ms" to "马", "hi" to "印",
    )

    fun shortLang(code: String): String = SHORT_LANG[code] ?: code

    fun translateLang(ctx: Context): String =
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString(KEY_TLANG, "en") ?: "en"

    fun setTranslateLang(ctx: Context, lang: String) {
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putString(KEY_TLANG, lang).apply()
    }

    /** 循环到下一个目标语言（键盘「译」键用）。 */
    fun nextTranslateLang(ctx: Context): String {
        val cur = translateLang(ctx)
        val i = TRANSLATE_LANGS.indexOfFirst { it.first == cur }
        val next = TRANSLATE_LANGS[(if (i < 0) 0 else i + 1) % TRANSLATE_LANGS.size].first
        setTranslateLang(ctx, next)
        return next
    }

    // ── 开放 API（本机 HTTP，默认关）─────────────────────────────────────

    fun openApiEnabled(ctx: Context): Boolean =
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).getBoolean(KEY_OPEN_API, false)

    fun setOpenApiEnabled(ctx: Context, on: Boolean) {
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_OPEN_API, on).apply()
    }

    fun openApiPort(ctx: Context): Int {
        val p = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .getInt(KEY_OPEN_API_PORT, OpenApiServer.DEFAULT_PORT)
        return if (p in 1..65535) p else OpenApiServer.DEFAULT_PORT
    }

    fun setOpenApiPort(ctx: Context, port: Int) {
        val v = if (port in 1..65535) port else OpenApiServer.DEFAULT_PORT
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
            .putInt(KEY_OPEN_API_PORT, v).apply()
    }
}
