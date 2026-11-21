package com.lingxi.ime

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.text.SpannableString
import android.text.Spanned
import android.text.method.LinkMovementMethod
import android.text.style.ClickableSpan
import android.view.Gravity
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import androidx.core.content.ContextCompat

/**
 * 灵犀设置页 —— 2026-11-20 视觉改版：卡片式布局
 * （design/settings_design.png 设计稿：白卡片 / 大圆角 / 低饱和 / 蓝色强调 #3060D8）
 *
 * 只回答三个问题：
 *   1. 词库在不在？多大？
 *   2. 引擎加载成功没有？
 *   3. 怎么把输入法切出来？
 * 刻意不做任何"必须配置才能用"的开关：模糊音、学习、精确优先都是引擎默认行为。
 */
class AboutActivity : Activity() {

    private lateinit var layoutBtn: Button
    private lateinit var tradBtn: Button
    private var langSpinner: Spinner? = null

    private val accent: Int by lazy { ContextCompat.getColor(this, R.color.kb_accent) }
    private val textSecondary: Int by lazy { ContextCompat.getColor(this, R.color.text_secondary) }

    /**
     * 键盘侧（底行布局键 / 中英键）改了布局也要刷新这里的按钮文案。
     * 实测踩过的坑：按钮文案只在 onCreate 里算一次，用户在键盘上切了布局回到状态页，
     * 看到的还是旧布局（按钮写"英文 26 键"、实际是九宫格），会误以为键盘坏了。
     */
    private val layoutChanged = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            refreshLayoutLabel()
        }
    }

    // ── 视觉构件 ─────────────────────────────────────────────────────────

    private fun dp(v: Float): Int = (v * resources.displayMetrics.density + 0.5f).toInt()

    /** 白卡片容器：圆角 16dp、描边 1dp、四周留白 */
    private fun card(child: View): LinearLayout {
        val bg = GradientDrawable().apply {
            cornerRadius = dp(16f).toFloat()
            setColor(ContextCompat.getColor(this@AboutActivity, R.color.card_bg))
            setStroke(dp(1f), ContextCompat.getColor(this@AboutActivity, R.color.card_stroke))
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = bg
            setPadding(dp(16f), dp(14f), dp(16f), dp(14f))
            addView(child, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }
    }

    private fun sectionTitle(text: String): TextView = TextView(this).apply {
        this.text = text
        textSize = 13f
        setTextColor(textSecondary)
        typeface = Typeface.DEFAULT_BOLD
        setPadding(0, dp(8f), 0, dp(6f))
    }

    private fun bodyText(text: String, size: Float = 15f, color: Int? = null): TextView =
        TextView(this).apply {
            this.text = text
            textSize = size
            if (color != null) setTextColor(color)
            else setTextColor(ContextCompat.getColor(this@AboutActivity, R.color.text_primary))
            setPadding(0, dp(4f), 0, dp(4f))
            gravity = Gravity.START
        }

    /** 说明卡片：标题 + 正文；正文中的 https 链接可点；可选底部操作按钮。 */
    private fun infoCard(
        title: String,
        body: String,
        actionLabel: String? = null,
        actionUrl: String? = null,
    ): LinearLayout {
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(sectionTitle(title))
            addView(linkifiedBody(body))
            if (actionLabel != null && actionUrl != null) {
                addView(pillButton(actionLabel).apply {
                    setOnClickListener { openUrl(actionUrl) }
                })
            }
        }
        return card(column).apply {
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
            lp.topMargin = dp(10f)
            layoutParams = lp
        }
    }

    private fun linkifiedBody(body: String): TextView {
        val span = SpannableString(body)
        val matcher = Regex("https://\\S+").findAll(body)
        for (m in matcher) {
            val url = m.value.trimEnd('.', ',', ')', '；', '。')
            val start = m.range.first
            val end = start + url.length
            span.setSpan(
                object : ClickableSpan() {
                    override fun onClick(widget: View) {
                        openUrl(url)
                    }
                },
                start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
        }
        return TextView(this).apply {
            text = span
            textSize = 14f
            setTextColor(ContextCompat.getColor(this@AboutActivity, R.color.text_primary))
            setPadding(0, dp(4f), 0, dp(6f))
            gravity = Gravity.START
            movementMethod = LinkMovementMethod.getInstance()
            setLinkTextColor(accent)
        }
    }

    private fun openUrl(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (_: Throwable) {
        }
    }

    /** 药丸按钮：主按钮蓝底白字，次按钮白底描边 */
    private fun pillButton(text: String, primary: Boolean = false): Button = Button(this).apply {
        this.text = text
        textSize = 15f
        isAllCaps = false
        setPadding(dp(16f), 0, dp(16f), 0)
        val bg = GradientDrawable().apply {
            cornerRadius = dp(12f).toFloat()
            if (primary) {
                setColor(accent)
            } else {
                setColor(Color.WHITE)
                setStroke(dp(1f), ContextCompat.getColor(this@AboutActivity, R.color.card_stroke))
            }
        }
        background = bg
        setTextColor(if (primary) Color.WHITE else ContextCompat.getColor(this@AboutActivity, R.color.text_primary))
        stateListAnimator = null
    }

    private fun inputField(hint: String, size: Float = 16f): EditText = EditText(this).apply {
        this.hint = hint
        textSize = size
        setTextColor(ContextCompat.getColor(this@AboutActivity, R.color.text_primary))
        setHintTextColor(textSecondary)
        setPadding(dp(12f), dp(10f), dp(12f), dp(10f))
        background = GradientDrawable().apply {
            cornerRadius = dp(10f).toFloat()
            setColor(ContextCompat.getColor(this@AboutActivity, R.color.page_bg))
        }
    }

    // ── 页面构建 ─────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        // 翻译器也要在这里初始化一次：状态页可以**独立于 IME 服务**运行
        // （用户没打开任何输入框时 IME 服务根本不会被创建），
        // 不初始化的话状态页的翻译测试既没有离线词表也没有缓存。
        try {
            Translator.init(this)
        } catch (t: Throwable) {
        }
        super.onCreate(savedInstanceState)
        ContextCompat.registerReceiver(
            this, layoutChanged, IntentFilter(PrisirImeService.ACTION_LAYOUT_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        val db = DbLocator.resolve(this)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(ContextCompat.getColor(this@AboutActivity, R.color.page_bg))
            setPadding(dp(16f), dp(20f), dp(16f), dp(24f))
        }

        // ── 页头 ─────────────────────────────────────────────────────────
        root.addView(TextView(this).apply {
            text = "灵犀拼音"
            textSize = 26f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(ContextCompat.getColor(this@AboutActivity, R.color.text_primary))
        })
        root.addView(TextView(this).apply {
            text = "输入法设置 · 与 Windows / Linux 同源引擎"
            textSize = 13f
            setTextColor(textSecondary)
            setPadding(0, dp(2f), 0, dp(12f))
        })

        // ── 卡片 1：状态与测试输入 ────────────────────────────────────────
        val engineTv = TextView(this).apply {
            text = "引擎状态：加载中…"
            textSize = 14f
            setPadding(0, dp(4f), 0, dp(4f))
        }
        val probe = inputField("在这里试打拼音（焦点在这里时键盘会弹出）", 17f)
        val statusColumn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(engineTv)
            addView(probe)
        }
        root.addView(card(statusColumn))

        // ── 卡片 2：键盘与文字 ────────────────────────────────────────────
        fun layoutLabel() = "输入方式：" + LayoutPref.label(this@AboutActivity) + "（点按切换）"
        val btn = pillButton(layoutLabel()).apply {
            setOnClickListener {
                LayoutPref.next(this@AboutActivity)
                text = layoutLabel()
                sendBroadcast(
                    Intent(PrisirImeService.ACTION_LAYOUT_CHANGED).setPackage(packageName)
                )
            }
        }
        layoutBtn = btn

        // 简繁二态（本地 OpenCC 词表，不联网）
        fun tradLabel() = "文字转换：" + when (LayoutPref.scriptMode(this@AboutActivity)) {
            LayoutPref.SCRIPT_S2T -> "简体 → 繁体"
            else -> "简体（原样）"
        } + "（点按切换）"
        val trad = pillButton(tradLabel()).apply {
            setOnClickListener {
                // 简体 → 繁体 → 简体
                val mode = LayoutPref.nextScriptMode(this@AboutActivity)
                // 引擎是全局单例，这里切了立刻生效；同时广播让键盘上的简繁键也同步
                Engine.setScript(mode)
                text = tradLabel()
                sendBroadcast(
                    Intent(PrisirImeService.ACTION_LAYOUT_CHANGED).setPackage(packageName)
                )
            }
        }
        tradBtn = trad
        val kbColumn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(sectionTitle("键盘与文字"))
            addView(btn)
            addView(trad)
        }
        root.addView(card(kbColumn))

        // ── 卡片 3：翻译 ───────────────────────────────────────────────────
        val langs = LayoutPref.TRANSLATE_LANGS
        val langSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@AboutActivity,
                android.R.layout.simple_spinner_dropdown_item,
                langs.map { it.second }.toTypedArray(),
            )
            setSelection(
                langs.indexOfFirst { it.first == LayoutPref.translateLang(this@AboutActivity) }
                    .coerceAtLeast(0)
            )
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                    LayoutPref.setTranslateLang(this@AboutActivity, langs[pos].first)
                    sendBroadcast(
                        Intent(PrisirImeService.ACTION_LAYOUT_CHANGED).setPackage(packageName)
                    )
                }

                override fun onNothingSelected(p: AdapterView<*>?) {}
            }
        }
        this.langSpinner = langSpinner

        val trInput = inputField("输入中文或英文，测试翻译")
        val trBtn = pillButton("翻译", primary = true)
        val trOut = TextView(this).apply {
            text = "（翻译结果）\n" + Translator.stats()
            textSize = 15f
            setPadding(0, dp(8f), 0, dp(4f))
        }
        trBtn.setOnClickListener {
            val s = trInput.text.toString().trim()
            if (s.isEmpty()) return@setOnClickListener
            trOut.text = "翻译中…"
            val lang = LayoutPref.translateLang(this@AboutActivity)
            Translator.translate(s, lang) { r ->
                trOut.text = when (r) {
                    is Translator.Outcome.Ok ->
                        r.text + if (r.offline) "（离线词库命中）" else ""
                    is Translator.Outcome.Err -> "翻译失败：" + r.reason
                }
            }
        }
        val trColumn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(sectionTitle("翻译"))
            addView(bodyText("键盘「译」键默认使用此处目标语言。不使用翻译时，输入法可完全离线运行。", 12f, textSecondary))
            addView(langSpinner)
            addView(trInput)
            addView(trBtn)
            addView(trOut)
        }
        root.addView(card(trColumn))

        // ── 卡片 4：个人词库 ───────────────────────────────────────────────
        // 用剪贴板做中转：不依赖文件权限、不依赖 SD 卡，用户贴到微信/备忘录即可留存。
        val clip = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        val exportBtn = pillButton("导出个人词库（复制到剪贴板）")
        val importBtn = pillButton("导入个人词库（从剪贴板）")
        val personalMsg = TextView(this).apply {
            text = Translator.stats()
            textSize = 13f
            setTextColor(textSecondary)
            setPadding(0, dp(6f), 0, dp(2f))
        }
        exportBtn.setOnClickListener {
            val json = Translator.exportPersonal()
            clip.setPrimaryClip(android.content.ClipData.newPlainText("lingxi_personal_dict", json))
            personalMsg.text = "已复制 ${json.length} 字符到剪贴板，可粘贴保存作为备份"
        }
        importBtn.setOnClickListener {
            val raw = clip.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString().orEmpty()
            val n = Translator.importPersonal(raw)
            personalMsg.text = if (n > 0) "已导入/合并 $n 条 · ${Translator.stats()}"
                               else "剪贴板中无可导入词库，或格式不正确"
        }
        val pdColumn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(sectionTitle("个人词库"))
            addView(bodyText("翻译过程中积累的词条保存在本机；可通过剪贴板导入或导出。", 12f, textSecondary))
            addView(exportBtn)
            addView(importBtn)
            addView(personalMsg)
        }
        root.addView(card(pdColumn))

        // ── 卡片 5：开放 API（本机 HTTP，默认关；对齐 Windows 口径）──
        fun openApiLabel() = if (LayoutPref.openApiEnabled(this@AboutActivity)) {
            val port = LayoutPref.openApiPort(this@AboutActivity)
            val live = if (OpenApiServer.isRunning()) "已监听" else "已开启 · 待监听"
            "开放 API：$live · 127.0.0.1:$port（点按关闭）"
        } else {
            "开放 API：已关闭（点按启用，供 Agent 接入）"
        }
        val openApiHint = TextView(this).apply {
            text = AboutCopy.OPENAPI_HINT_OFF
            textSize = 12f
            setTextColor(textSecondary)
            setPadding(0, dp(4f), 0, dp(2f))
        }
        val openApiBtn = pillButton(openApiLabel()).apply {
            setOnClickListener {
                val next = !LayoutPref.openApiEnabled(this@AboutActivity)
                LayoutPref.setOpenApiEnabled(this@AboutActivity, next)
                if (next && LayoutPref.openApiPort(this@AboutActivity) <= 0) {
                    LayoutPref.setOpenApiPort(this@AboutActivity, OpenApiServer.DEFAULT_PORT)
                }
                OpenApiServer.applyPref(this@AboutActivity)
                text = openApiLabel()
                openApiHint.text = if (next) {
                    AboutCopy.openApiHintOn(
                        LayoutPref.openApiPort(this@AboutActivity),
                        OpenApiServer.isRunning(),
                    )
                } else {
                    AboutCopy.OPENAPI_HINT_OFF
                }
            }
        }
        // 若进入页时已开启，回填开启态提示
        if (LayoutPref.openApiEnabled(this)) {
            openApiHint.text = AboutCopy.openApiHintOn(
                LayoutPref.openApiPort(this),
                OpenApiServer.isRunning(),
            )
        }
        val apiColumn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(sectionTitle("开放 API"))
            addView(openApiBtn)
            addView(openApiHint)
        }
        root.addView(card(apiColumn))

        // ── 卡片 6：诊断 ───────────────────────────────────────────────────
        val wordBtn = pillButton("词库管理").apply {
            setOnClickListener {
                startActivity(Intent(this@AboutActivity, WordActivity::class.java))
            }
        }
        // 快速按键自检（抓"快速打字丢键"这类回归）
        // adb input tap 每次 ~460ms，复现不出"间隔 < 抬起动画时长"的丢键场景，
        // 所以这里用真实 MotionEvent 按 90ms 间隔连发 12 次，看收进去几个。
        val selfTestBtn = pillButton("快速按键自检（90ms×12）").apply {
            setOnClickListener {
                text = "自检中…"
                sendBroadcast(
                    Intent(PrisirImeService.ACTION_SELFTEST)
                        .setPackage(packageName)
                        .putExtra("interval", 90L)
                        .putExtra("count", 12)
                )
            }
        }
        val selfTestResult = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                val got = intent?.getIntExtra("got", 0) ?: 0
                val sent = intent?.getIntExtra("sent", 0) ?: 0
                selfTestBtn.text = if (got >= sent) "快速按键自检：$got/$sent ✓"
                else "快速按键自检：$got/$sent ✗ 丢了 ${sent - got} 键"
            }
        }
        ContextCompat.registerReceiver(
            this, selfTestResult,
            IntentFilter(PrisirImeService.ACTION_SELFTEST_RESULT),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        val diagColumn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(sectionTitle("诊断与维护"))
            addView(wordBtn)
            addView(selfTestBtn)
        }
        root.addView(card(diagColumn))

        // ── 卡片 7+：使用说明 / 关于 / 隐私政策 / 使用条款 / 问题反馈 ─────
        root.addView(infoCard(AboutCopy.HELP_TITLE, AboutCopy.HELP_BODY))
        root.addView(
            infoCard(
                AboutCopy.ABOUT_TITLE,
                AboutCopy.ABOUT_BODY,
                actionLabel = "打开官网",
                actionUrl = AboutCopy.WEBSITE_URL,
            )
        )
        root.addView(infoCard(AboutCopy.PRIVACY_TITLE, AboutCopy.PRIVACY_BODY))
        root.addView(infoCard(AboutCopy.TERMS_TITLE, AboutCopy.TERMS_BODY))
        root.addView(infoCard(AboutCopy.OPENAPI_TITLE, AboutCopy.OPENAPI_BODY))
        root.addView(
            infoCard(
                AboutCopy.FEEDBACK_TITLE,
                AboutCopy.FEEDBACK_BODY,
                actionLabel = "打开论坛",
                actionUrl = AboutCopy.FORUM_URL,
            )
        )

        setContentView(ScrollView(this).apply { addView(root) })

        // 设置页也能独立起开放 API（IME 服务未绑定时同样可用）
        OpenApiServer.applyPref(this)

        // 后台加载词库（不阻塞 UI），完成后回填状态行
        Thread {
            val ok = db.exists() && Engine.load(db.absolutePath)
            // 引擎诊断：同一输入在 mmap 路径与 SQLite 路径下的耗时 + 首屏候选对比。
            // 这是"安卓走内存映射路线"的验收依据 —— 候选必须逐条一致，且 mmap 要更快。
            if (ok) {
                android.util.Log.i("LingxiE", "引擎诊断 mmap=" + Engine.usingMmap)
                for (probe in listOf("n", "ni", "nihao", "shi", "zhongguo", "s")) {
                    android.util.Log.i("LingxiE", "PROFILE " + Engine.queryProfile(probe))
                }
            }
            runOnUiThread {
                engineTv.text = "引擎状态：" + if (ok) "已加载" else "未加载（词库缺失或损坏）"
            }
        }.start()
    }

    override fun onResume() {
        super.onResume()
        refreshLayoutLabel()
    }

    override fun onDestroy() {
        try {
            unregisterReceiver(layoutChanged)
        } catch (t: Throwable) {
            // 已经注销过就算了
        }
        super.onDestroy()
    }

    /** 按钮文案永远跟当前布局一致（键盘侧改了布局也会广播过来）。 */
    private fun refreshLayoutLabel() {
        if (!::layoutBtn.isInitialized) return
        layoutBtn.text = "输入方式：" + LayoutPref.label(this) + "（点按切换）"
        if (::tradBtn.isInitialized) {
            tradBtn.text = "文字转换：" + when (LayoutPref.scriptMode(this)) {
                LayoutPref.SCRIPT_S2T -> "简体 → 繁体"
                else -> "简体（原样）"
            } + "（点按切换）"
        }
        // 语言下拉也要跟着实际值走（否则显示"越南语"、实际是英语）
        langSpinner?.let { sp ->
            val langs = LayoutPref.TRANSLATE_LANGS
            val idx = langs.indexOfFirst { it.first == LayoutPref.translateLang(this) }
            if (idx >= 0 && sp.selectedItemPosition != idx) sp.setSelection(idx)
        }
    }
}
