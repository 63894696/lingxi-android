package com.lingxi.ime

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.inputmethodservice.InputMethodService
import android.text.InputType
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import androidx.core.content.ContextCompat
import java.io.File

/**
 * 灵犀拼音输入法（Android 端 v2，2026-11-18 重做）。
 *
 * 与旧版的区别：
 *   · 界面换成**常规通用键盘**（26 键 + 候选栏），不再自绘自研布局
 *   · 引擎复用 Rust 那份（与 Windows/Linux 同源），候选/整句/学习行为一致
 *   · 词库打进 APK assets：首启释放到 filesDir，装完即用
 */
class PrisirImeService : InputMethodService(), KeyboardView.Listener {

    private var keyboard: KeyboardView? = null
    private var composing = ""
    private var english = false
    private var shift = false
    /** 英文页 ⇧ 双击锁大写：true 时所有字母持续大写，按字母不复位。 */
    private var shiftLocked = false
    private var lastShiftTapMs = 0L

    /** 复位 shift（换布局、上屏、换行等场合调用）。 */
    private fun resetShift() {
        shift = false
        shiftLocked = false
        lastShiftTapMs = 0L
    }

    /**
     * 当前布局：拼音 26 键 / 拼音 9 键 / 英文 26 键（与 LayoutPref 同步）。
     *
     * 手写面板已下线：自研 ONNX 手写认不准（"识"一直认不出），搜狗 gpen 又拿不到模型
     * （20.20 的手写是云端/内置资源，不落本地文件）—— 集中把打字做扎实。
     */
    private var mode: String = LayoutPref.PY26

    /**
     * 上一次用的拼音布局（26 键 / 9 键）。
     *
     * 「中/英」切到英文再切回来时要回到原来那档拼音布局，
     * 否则从九宫格切到英文后回来会变成 26 键（等于把用户的九宫格设置冲掉）。
     */
    private var pinyinMode: String = LayoutPref.PY26

    /**
     * 当前输入框是不是密码框（密码框强制英文直出，且**不记忆**为英文布局）。
     *
     * 2026-11-25 新增：`english` 以前是三个来源各自赋值（布局 =EN26 / 密码框 / 布局切换键），
     * 谁都可能把它留在旧值。典型故障 —— `onFinishInput` 把 english 清成 false，而
     * `LayoutPref` 仍是 "en"：英文键盘还在显示，字母却被当拼音累积（用户报的
     * "切到 26 键英文打字还出拼音候选，切中文再切回来才好"）。现在 english 一律由
     * `passwordField || layout==EN26` 现算，见 syncLayout()。
     */
    private var passwordField = false

    // ── 候选查询（后台线程 + 缓存）────────────────────────────────────────
    //
    // 2026-11-19 用户反馈"26 键输入反应有点慢"：根因是**每次按键都在主线程同步查库**
    // （引擎一次查询要跑多张表 + 模糊音扩展，几十毫秒），按键事件被阻塞。
    // 现在：主线程只更新首行拼音（用户立刻看到自己敲了什么），候选在后台查完再回填。

    private val queryPool = java.util.concurrent.ThreadPoolExecutor(
        1, 1, 0L, java.util.concurrent.TimeUnit.MILLISECONDS,
        java.util.concurrent.LinkedBlockingQueue()
    )
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

    /** 拼音 → 候选的缓存（LinkedHashMap accessOrder=true，超量就整体清掉）。 */
    private val candCache = LinkedHashMap<String, List<String>>(64, 0.75f, true)

    /** 数字串 → 九宫格结果（读音 + 候选）的缓存，和 candCache 同样的用法。 */
    private val t9Cache = LinkedHashMap<String, Engine.T9Result>(32, 0.75f, true)

    /** 请求序号：只接受最新一次查询的结果（避免旧结果覆盖新结果）。 */
    @Volatile
    private var querySeq = 0

    /** 九宫格：引擎给出的**可能读音**（"mi" / "ni" / "ni hao"…）+ 当前选中的读音。 */
    @Volatile
    private var t9Readings: List<String> = emptyList()
    @Volatile
    private var t9Reading: String = ""

    /** 当前候选的**原始（简体）**文本：学习要写简体，显示层才做繁体投影。 */
    @Volatile
    private var curCands: List<String> = emptyList()

    /**
     * 上次翻译的源是不是"候选词/拼音"（是 → 译文上屏时要连带清掉输入框里未上屏的拼音）。
     *
     * 区分这两种情况很重要：选中文本翻译时**不能**动选区（否则把用户选的内容删了）。
     */
    private var translationFromCandidates = false

    /**
     * 翻译请求序号：用来作废**过期**的翻译结果。
     *
     * 翻译是异步的（几百 ms ~ 几秒），期间用户可能又打了字 → 那份译文对应的
     * 是**上一串拼音**，显示出来就是错的（"nihao 的译文"配 "jisuande 输入"）。
     * 每次发起翻译和每次按键都递增它，回调时对不上就丢弃结果。
     */
    private var translateSeq = 0
    /** 收到过的按键总数（"快速按键自检"用它数"到底收进去几个键"）。 */
    private var keyPressCount: Int = 0

    /** 状态页切换布局后广播过来：正在显示的键盘立刻换布局（不用等下次唤起）。 */
    private val layoutChanged = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            syncLayout()
            refresh()
        }
    }

    override fun onCreate() {
        super.onCreate()
        current = this
        ContextCompat.registerReceiver(
            this, layoutChanged, IntentFilter(ACTION_LAYOUT_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        ContextCompat.registerReceiver(
            this, selfTestReq, IntentFilter(ACTION_SELFTEST),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        // 装完即用：先从 APK assets / 外部目录落位词库，再 load（首启可能多几秒解包）
        try {
            DbLocator.ensureFromAssets(this)
            DbLocator.ensureInstalled(this)
        } catch (t: Throwable) {
            android.util.Log.w("LingxiE", "词库落位: ${t.message}")
        }
        Engine.load(DbLocator.resolve(this).absolutePath)
        // 翻译器初始化：加载离线短语表 + 翻译缓存（幂等；不阻塞启动）。
        try {
            Translator.init(this)
        } catch (t: Throwable) {
        }
        // 词库"预热"：刚打开时数据库页还没进页缓存，第一次查询要几百毫秒（实测 451ms），
        // 用户第一下按键就会感觉到"卡"。这里后台先跑两次典型查询，把热页预热出来。
        queryPool.execute {
            try {
                Engine.query("ni")
                Engine.t9Query("64")
            } catch (t: Throwable) {
            }
        }
        // 开放 API：偏好开启时起 localhost HTTP（与设置页开关共用）
        try {
            OpenApiServer.applyPref(this)
        } catch (t: Throwable) {
        }
    }

    override fun onDestroy() {
        try {
            unregisterReceiver(layoutChanged)
        } catch (t: Throwable) {
            // 已经注销过就算了
        }
        try {
            unregisterReceiver(selfTestReq)
        } catch (t: Throwable) {
        }
        // 不在这里 stop OpenApi：设置页可能仍开着；仅进程退出自然结束。
        current = null
        super.onDestroy()
    }

    companion object {
        /** 状态页切换键盘布局后广播（见 AboutActivity 的切换按钮）。 */
        const val ACTION_LAYOUT_CHANGED = "com.lingxi.ime.action.LAYOUT_CHANGED"

        /** 状态页请求 IME 跑"快速按键自检"。 */
        const val ACTION_SELFTEST = "com.lingxi.ime.action.SELFTEST"

        /** 自检结果（IME → 状态页）。 */
        const val ACTION_SELFTEST_RESULT = "com.lingxi.ime.action.SELFTEST_RESULT"

        /** 当前 IME 服务实例（状态页的"快速按键自检"要用）。 */
        @Volatile
        var current: PrisirImeService? = null
    }

    /** 状态页发来的自检请求 → 在 IME 里跑（键盘视图只有 IME 进程里才有）。 */
    private val selfTestReq = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: android.content.Context?, intent: android.content.Intent?) {
            val interval = intent?.getLongExtra("interval", 90L) ?: 90L
            val count = intent?.getIntExtra("count", 12) ?: 12
            selfTestFastTyping(interval, count) { got, sent ->
                sendBroadcast(
                    android.content.Intent(ACTION_SELFTEST_RESULT)
                        .setPackage(packageName)
                        .putExtra("got", got)
                        .putExtra("sent", sent)
                )
            }
        }
    }

    /**
     * 快速按键自检（2026-11-19 加）：用**真实 MotionEvent** 按固定间隔连发按键，
     * 返回"实际收到的按键数 / 发出的按键数"。
     *
     * 为什么需要它：`adb shell input tap` 每条要 ~460ms（每次起一个 Java 进程），
     * **根本复现不出"快速打字丢键"**；而丢键恰恰只在间隔 < 抬起动画时长(240ms) 时出现。
     *
     * 背景：曾经"按下 B 会 cancel A 的抬起动画 → A 的 onAnimationEnd 清空 B 的 pressedId
     * → B 抬起时按 id 找不到命中 → 这一键被吃掉"，必须靠这个自检守住回归。
     */
    fun selfTestFastTyping(intervalMs: Long, count: Int, onDone: (Int, Int) -> Unit) {
        val kb = keyboard ?: run {
            // 键盘还没显示过 → 自己造一个视图，量好尺寸、画一帧（命中区在 onDraw 里建）
            val v = onCreateInputView() as? KeyboardView
            if (v != null) {
                v.measure(
                    android.view.View.MeasureSpec.makeMeasureSpec(1200, android.view.View.MeasureSpec.EXACTLY),
                    android.view.View.MeasureSpec.makeMeasureSpec(760, android.view.View.MeasureSpec.EXACTLY)
                )
                v.layout(0, 0, 1200, 760)
                v.draw(
                    android.graphics.Canvas(
                        android.graphics.Bitmap.createBitmap(1200, 760, android.graphics.Bitmap.Config.ARGB_8888)
                    )
                )
            }
            v
        }
        if (kb == null || kb.width <= 0) {
            // 键盘视图还没量过尺寸 → 没法算按键坐标
            onDone(0, count)
            return
        }
        val c = kb.debugCharKeyCenter()
        if (c == null) {
            onDone(0, count)
            return
        }
        val startLen = keyPressCount
        val savedComposing = composing
        val base = android.os.SystemClock.uptimeMillis()
        var sent = 0

        fun step() {
            if (sent >= count) {
                // 自检会往输入缓冲里塞字，测完还原
                val got = keyPressCount - startLen
                composing = savedComposing
                refresh()
                onDone(got, count)
                return
            }
            val t = base + sent * intervalMs
            val down = android.view.MotionEvent.obtain(
                t, t, android.view.MotionEvent.ACTION_DOWN, c.first, c.second, 0
            )
            kb.dispatchTouchEvent(down)
            down.recycle()
            val up = android.view.MotionEvent.obtain(
                t, t + 25, android.view.MotionEvent.ACTION_UP, c.first, c.second, 0
            )
            kb.dispatchTouchEvent(up)
            up.recycle()
            sent++
            mainHandler.postDelayed({ step() }, intervalMs)
        }

        mainHandler.post { step() }
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        syncLayout()
        refresh()
    }

    override fun onWindowShown() {
        super.onWindowShown()
        // 用户可能刚在状态页切了布局：窗口一显示就对齐，别等第一个按键
        syncLayout()
        refresh()
    }

    /** 该布局的首页面（拼音字母页 / 九宫格页 / 英文页）。 */
    private fun homePage(): Page = when (mode) {
        LayoutPref.PY9 -> Page.T9
        LayoutPref.EN26 -> Page.LETTERS_EN
        else -> Page.LETTERS
    }

    /**
     * 对齐键盘模式。
     *
     * **必须在处理按键之前调用**：否则切换布局后的第一个字符会按"旧模式"解释
     * （切到 26 键后第一个字母被当成 T9 数字处理 → 缓冲被清空 → 用户感觉"打不出字"）。
     */
    private fun syncLayout() {
        val want = LayoutPref.mode(this)
        var changed = false
        if (want != mode) {
            mode = want
            if (mode != LayoutPref.EN26) pinyinMode = mode
            changed = true
        }
        // 中文/英文解释**每次都按布局现算**，不能只在「布局变了」的分支里赋值：
        // onFinishInput 会把 english 清 false，而 LayoutPref 仍是 "en" →
        // 英文键盘 + 拼音候选（2026-11-25 用户报的 bug）。密码框除外。
        val wantEn = passwordField || mode == LayoutPref.EN26
        if (english != wantEn) {
            english = wantEn
            changed = true
        }
        if (changed) {
            refreshInputView()
            return
        }
        val v = keyboard ?: return
        // 停在别的布局的字母页 → 拉回本布局首页（数字/符号页不动）
        if (v.page == Page.LETTERS || v.page == Page.T9 || v.page == Page.LETTERS_EN) {
            val home = homePage()
            if (v.page != home) v.page = home
        }
    }

    override fun onCreateInputView(): View = buildInputView()

    /**
     * 系统夜间模式/屏幕配置变化时重建键盘视图。
     *
     * 配色 token 在 KeyboardView 构造时读取（R.color.* 会按当前 Configuration 选
     * values 或 values-night），所以切深色后必须重建 view 才能换新色 —— 只 invalidate
     * 没用，Paint 的颜色已经定死了。
     */
    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        if (keyboard != null) {
            refreshInputView()
        }
    }

    private fun buildInputView(): View {
        mode = LayoutPref.mode(this)
        if (mode != LayoutPref.EN26) pinyinMode = mode
        english = passwordField || mode == LayoutPref.EN26
        val v = KeyboardView(this)
        v.listener = this
        v.page = homePage()
        keyboard = v
        return v
    }

    /** 布局切换后整块替换输入视图。 */
    private fun refreshInputView() {
        composing = ""
        resetShift()
        t9Reading = ""
        t9Readings = emptyList()
        keyboard?.resetCandidates()
        setInputView(buildInputView())
    }

    override fun onStartInput(editorInfo: EditorInfo?, restarting: Boolean) {
        super.onStartInput(editorInfo, restarting)
        // 密码框不显示候选（也不学习），避免把密码记进词库
        val type = editorInfo?.inputType ?: 0
        passwordField = (type and InputType.TYPE_TEXT_VARIATION_PASSWORD) != 0 ||
            (type and InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD) != 0 ||
            (type and InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD) != 0
        // 换输入框 = 新的一轮状态：布局/中英一律按偏好重算，别沿用上一个框的残留。
        mode = LayoutPref.mode(this)
        if (mode != LayoutPref.EN26) pinyinMode = mode
        english = passwordField || mode == LayoutPref.EN26
        composing = ""
        resetShift()
        refresh()
    }

    override fun onFinishInput() {
        super.onFinishInput()
        composing = ""
        passwordField = false
        // 只清「本次输入会话」的状态，不能把布局偏好一起抹掉：
        // english 由布局现算，否则会出现「英文键盘但按拼音处理」（2026-11-25 修）。
        mode = LayoutPref.mode(this)
        english = mode == LayoutPref.EN26
        resetShift()
        refresh()
    }

    // ── KeyboardView.Listener ────────────────────────────────────────────────

    override fun onKey(key: KeySpec) {
        // 埋点已清理（2026-11-19）：快速丢键已定位并修复（动画 cancel 时序），不再需要逐键日志。
        keyPressCount++
        // 新输入 → 作废在途的翻译请求（那份译文对应的是上一串拼音，显示出来就是错的）
        translateSeq++
        syncLayout()
        val ic = currentInputConnection ?: return
        val t9 = mode == LayoutPref.PY9
        when (key.type) {
            KeyType.CHAR -> {
                val ch = key.code.ifEmpty { key.label.lowercase() }
                // 仅九宫格主页 2–9 进 T9 缓冲；数字页 0–9 一律直出（与底行 0 一致）
                val onT9Keys = t9 && keyboard?.page == Page.T9
                val isT9Digit = onT9Keys && ch.length == 1 && ch[0] in '2'..'9'
                val isLetter = ch.length == 1 && ch[0] in 'a'..'z'
                when {
                    // 搜狗式分词：写入 `'`，强制音节边界（xi'an → 西安）
                    t9 && ch == "'" -> {
                        if (composing.isNotEmpty() && !composing.endsWith("'")) {
                            composing += "'"
                            t9Reading = ""
                            t9Readings = emptyList()
                            refresh()
                        }
                    }
                    isT9Digit -> {
                        composing += ch
                        t9Reading = ""      // 新输入 → 之前的音节选择作废
                        t9Readings = emptyList()
                        refresh()
                    }
                    // 数字页 / 九宫格底行「0」等：数字直出上屏
                    ch.length == 1 && ch[0] in '0'..'9' -> {
                        if (composing.isNotEmpty()) commitTop()
                        ic.commitText(ch, 1)
                    }
                    english -> {
                        val out = if (shift && isLetter) ch.uppercase() else ch
                        ic.commitText(out, 1)
                        // 单次大写：打一个字母后复位；锁定大写：保持。
                        if (shift && !shiftLocked) { shift = false; refresh() }
                    }
                    isLetter -> {
                        composing += ch
                        t9Reading = ""
                        t9Readings = emptyList()
                        refresh()
                    }
                    else -> {
                        // 符号/数字页按键面原样上屏（半角 `/` `:` 等不能被转成全角，否则 URL 打不出）。
                        // 中文全角只走底行 COMMA/PERIOD，以及 SYM2 上已是全角字形的键。
                        ic.commitText(ch, 1)
                    }
                }
            }

            KeyType.SHIFT -> {
                // 双击（500ms 内两次）锁大写；已锁定时单击解锁；否则单次大写。
                val now = android.os.SystemClock.elapsedRealtime()
                if (shiftLocked) {
                    resetShift()
                } else if (shift && now - lastShiftTapMs < 700L) {
                    shift = true
                    shiftLocked = true
                    lastShiftTapMs = now
                } else {
                    shift = !shift
                    lastShiftTapMs = now
                }
                refresh()
            }

            KeyType.DELETE -> {
                if (composing.isNotEmpty()) {
                    composing = composing.dropLast(1)
                    t9Reading = ""
                    t9Readings = emptyList()
                    refresh()
                } else {
                    ic.deleteSurroundingText(1, 0)
                }
            }

            KeyType.SPACE -> {
                if (composing.isNotEmpty()) {
                    commitTop()
                } else {
                    ic.commitText(" ", 1)
                }
            }

            KeyType.ENTER -> {
                if (composing.isNotEmpty()) commitTop()
                ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
                ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER))
            }

            KeyType.PAGE_LETTERS, KeyType.PAGE_NUMBERS, KeyType.PAGE_SYM1, KeyType.PAGE_SYM2 -> {
                // 切页前把当前组词按"首选候选"上屏（不是把拼音原样吐出去）
                if (composing.isNotEmpty()) commitTop()
                keyboard?.page = when (key.type) {
                    KeyType.PAGE_LETTERS -> homePage()
                    KeyType.PAGE_NUMBERS -> Page.NUMBERS
                    KeyType.PAGE_SYM1 -> Page.SYM1
                    else -> Page.SYM2
                }
            }

            KeyType.LANG -> {
                // 中/英：整页切换（英文是独立的 26 键页，不是"同一个键盘换个解释"）
                if (composing.isNotEmpty()) commitTop()
                val target = if (mode == LayoutPref.EN26) pinyinMode else LayoutPref.EN26
                if (mode != LayoutPref.EN26) pinyinMode = mode
                LayoutPref.setMode(this@PrisirImeService, target)
                mode = target
                english = passwordField || target == LayoutPref.EN26
                resetShift()
                refreshInputView()
                notifyLayoutChanged()
            }

            KeyType.LAYOUT_SWITCH -> {
                // 底行布局键：拼音 26 ↔ 拼音 9
                if (composing.isNotEmpty()) commitTop()
                val target = LayoutPref.switchPinyin(this@PrisirImeService)
                mode = target
                pinyinMode = target
                english = false
                resetShift()
                refreshInputView()
                notifyLayoutChanged()
            }

            KeyType.COMMA, KeyType.PERIOD -> {
                if (composing.isNotEmpty()) commitTop()
                val ch = if (key.type == KeyType.COMMA) "," else "."
                ic.commitText(if (english) ch else KeyboardLayout.cnPunct[ch] ?: ch, 1)
            }
        }
    }

    override fun onCandidate(word: String) {
        val ic = currentInputConnection ?: return
        // 繁体模式下显示的是繁体，学习要写回**简体**原文
        val orig = curCands.firstOrNull { Engine.toScript(it) == word } ?: word
        ic.commitText(convertOut(orig), 1)
        if (mode == LayoutPref.PY9) {
            // 九宫格学习：key 用**该词在词库里的读音**（去空格），"米糕" → "migao"。
            //
            // 为什么不用"用户输入的数字串"：九宫格与 26 键共用同一套学成词 key，
            // 否则同一个词在两个布局里各存一份、学过也互不相认。
            // 为什么不用"用户选中的读音消歧条"：用户**不一定**会去点读音条，
            // 原来的写法是 t9Reading 为空就**根本不学习** → 学成词在九宫格里永远不置顶
            // （2026-11-19 真机实测发现：选词上屏了，下次查询里却没有这个词）。
            // 词库读音是确定的、不依赖用户点了哪条读音，正好当 key。
            val rd = Engine.pinyinOf(orig)?.replace(" ", "")?.replace("'", "")?.takeIf { it.isNotEmpty() }
                ?: t9Reading.filter { it.isLetter() }
            if (rd.isNotEmpty()) {
                Engine.learn(rd, orig)
            }
        } else {
            Engine.learn(composing, orig)
            // 再按**读音**记一条：九宫格是按读音查学成词的（"migao"），
            // 而 26 键记的是用户输入串（"nhjsg"）→ 两套 key 不通，
            // 26 键学过的词切到九宫格就不置顶（用户感知："刚选过的词又不见了"）。
            // 26 键自己查询仍按输入串走 → 原有行为完全不变。
            val rd = Engine.pinyinOf(orig)?.replace(" ", "")?.takeIf { it.isNotEmpty() && it != composing }
            if (rd != null) Engine.learn(rd, orig)
        }
        composing = ""
        t9Reading = ""
        t9Readings = emptyList()
        resetShift()
        refresh()
    }

    /** 翻译结果上屏：直接提交，不进学习通道。 */
    override fun onTranslation(text: String) {
        val ic = currentInputConnection ?: return
        // 译文上屏前：如果翻译源是候选词/拼音，输入框里还留着**未上屏的拼音串**
        // （打 "nihao" 后点「译」→ 译文 "hello" 上屏，输入框会变成 "nihaohello"）。
        // 用 setComposingText("") 清 IME 自己的 composing 区 —— **不能**按 composing.length
        // 逐字删：多音节时 composing 里带空格分隔符（"ni hao"），长度跟输入框里的对不上，
        // 删多了会把用户已上屏的字吃掉。
        if (translationFromCandidates && composing.isNotEmpty()) {
            try {
                ic.setComposingText("", 1)
            } catch (t: Throwable) {
                // 有些输入框不支持设 composing，忽略（最多少留一段拼音）
            }
        }
        ic.commitText(text, 1)
        // 译文已上屏 → **候选栏必须一起清掉**（2026-11-19 用户报的 bug）：
        // 之前这里只 commitText，composing / 候选列表 / 译文行全都留着 ——
        // 用户看到候选栏还在，继续打字时那些残留候选会跟着乱跳。
        composing = ""
        t9Reading = ""
        t9Readings = emptyList()
        curCands = emptyList()
        resetShift()
        translationFromCandidates = false
        keyboard?.resetCandidates()
        refresh()
    }

    // ── 候选栏工具键：繁 / 译 / 语言面板 ──────────────────────────────────

    override fun onTool(action: KeyboardView.ToolAction) {
        when (action) {
            KeyboardView.ToolAction.TOGGLE_TRADITIONAL -> {
                // 二态切换：简体 → 繁体 → 简体（引擎内置 OpenCC 词表）
                val mode = LayoutPref.nextScriptMode(this)
                Engine.setScript(mode)
                refresh()
            }

            KeyboardView.ToolAction.TRANSLATE -> doTranslate()

            KeyboardView.ToolAction.OPEN_LANG_PICKER -> {
                val v = keyboard ?: return
                v.langPicker = !v.langPicker
                v.langItems = LayoutPref.TRANSLATE_LANGS.map { it.first to LayoutPref.shortLang(it.first) }
                v.invalidate()
            }
        }
    }

    /**
     * 九宫格：用户点了某个**读音** → 候选收敛到那个读音（再点一次取消，回到全部读音的合并候选）。
     *
     * 读音来自引擎的音节表精确展开（"64" → mi / ni / oi），
     * 不再靠"从候选反查拼音"那种近似猜法（那是上一版九宫格最大的坑）。
     */
    override fun onReading(reading: String) {
        t9Reading = if (t9Reading == reading) "" else reading
        refresh()
    }

    /** 长按候选词：翻译这个词（而不是只能翻译首选）。 */
    override fun onTranslateWord(word: String) {
        doTranslate(word)
    }

    /** 语言面板里选了目标语言。 */
    override fun onSelectLang(code: String) {
        LayoutPref.setTranslateLang(this, code)
        val v = keyboard ?: return
        v.langPicker = false
        v.translateLangLabel = LayoutPref.shortLang(code)
        v.invalidate()
        doTranslate()
    }

    /**
     * 翻译：源文本优先取**显式传入的词**（长按候选），其次是选中文本，
     * 再次是**输入框里的全部文字**（2026-11-20 新增），最后才是当前首选候选。
     *
     * 为什么把"全文"放在"首选候选"前面（2026-11-20 用户诉求）：
     * 单个候选词脱离上下文，翻译经常词不达意；用户实际要翻的往往是**一整句话**
     * （"我想翻译成英文"翻成 "I want to translate into English" 才有意义，
     * 只翻「我想」就是 "I want"）。所以只要输入框里已经有**上屏的文字**，
     * 点「译」就默认翻全文；还在打拼音（composing 非空）时才退回翻候选。
     */
    private fun doTranslate(source: String? = null) {
        val v = keyboard ?: return
        val ic = currentInputConnection ?: return
        var src = source?.trim().orEmpty()
        // 翻译源是不是"候选词/拼音"？决定译文上屏时要不要连带清掉输入框里未上屏的拼音
        // （选中文本 / 全文翻译时**不能**动输入框，否则把用户已上屏的内容删了）。
        var fromCandidates = source != null
        if (src.isEmpty()) {
            try {
                src = ic.getSelectedText(0)?.toString()?.trim().orEmpty()
            } catch (t: Throwable) {
                // 有些输入框不支持取选区
            }
        }
        // **全文翻译**：没有选区、也没有未上屏拼音时，直接取输入框里的全部文字。
        // composing 非空时**不取全文** —— 那串拼音会出现在 getTextBeforeCursor 里，
        // 翻译 "nihao" 这种拼音毫无意义；此时应该走下面的"翻候选"分支。
        var fromFullText = false
        if (src.isEmpty() && composing.isEmpty()) {
            try {
                val before = ic.getTextBeforeCursor(10000, 0)?.toString().orEmpty()
                val after = ic.getTextAfterCursor(10000, 0)?.toString().orEmpty()
                val all = (before + after).trim()
                if (all.isNotEmpty()) {
                    src = all
                    fromFullText = true
                }
            } catch (t: Throwable) {
                // 输入框不支持取文本，退回候选翻译
            }
        }
        if (src.isEmpty()) {
            src = v.candidates.firstOrNull() ?: composing
            fromCandidates = src.isNotEmpty()
        }
        if (src.isEmpty()) {
            v.statusLine = "先打点字再翻译"
            v.invalidate()
            return
        }
        val lang = LayoutPref.translateLang(this)
        translationFromCandidates = fromCandidates
        val seq = ++translateSeq
        v.statusLine = "翻译中…"
        v.invalidate()
        Translator.translate(src, lang) { res ->
            val k = keyboard ?: return@translate
            // 输入已经变了 → 这份译文是上一串拼音的，丢弃（别显示错的）
            if (seq != translateSeq) return@translate
            // 全文可能很长，状态行只放得下十几个字 —— 截断显示，别把候选栏挤爆
            val shortSrc = if (src.length > 12) src.take(12) + "…" else src
            val scope = if (fromFullText) "（全文）" else ""
            when (res) {
                is Translator.Outcome.Ok -> {
                    k.translations = listOf(res.text)
                    k.statusLine = "$shortSrc → " + LayoutPref.shortLang(lang) + scope +
                        if (res.offline) "（离线词库）" else ""
                }
                is Translator.Outcome.Err -> {
                    k.translations = emptyList()
                    k.statusLine = "翻译失败：" + res.reason
                }
            }
            k.invalidate()
        }
    }

    /** 上屏前的输出转换（中文模式下才转，英文/标点不转；转换由引擎的 OpenCC 表做）。 */
    private fun convertOut(text: String): String {
        if (english || mode == LayoutPref.EN26) return text
        return Engine.toScript(text)
    }

    // ── 内部 ────────────────────────────────────────────────────────────────

    /** 通知状态页：布局变了（状态页按钮文案要跟着变）。 */
    private fun notifyLayoutChanged() {
        sendBroadcast(Intent(ACTION_LAYOUT_CHANGED).setPackage(packageName))
    }

    private fun commitTop() {
        val top = curCands.firstOrNull() ?: composing
        val ic = currentInputConnection ?: return
        ic.commitText(convertOut(top), 1)
        if (top != composing) Engine.learn(composing, top)
        composing = ""
        t9Reading = ""
        t9Readings = emptyList()
        resetShift()
        refresh()
    }

    private fun refresh() {
        val v = keyboard ?: return
        syncScript()
        v.shift = shift
        v.composing = composing
        // 简繁二态状态（候选栏工具键上的字：简/繁）
        v.traditionalState = LayoutPref.scriptLabel(this)
        // 翻译目标语言写在按钮上（用户不用猜下一个是什么语言）
        v.translateLangLabel = LayoutPref.shortLang(LayoutPref.translateLang(this))

        // 译文/状态行只跟"当前这一轮"有关：**每次刷新都清掉**，由翻译回调重新设置。
        // 不清的话会残留上一轮的译文（选完候选后译文还挂着；新输入时还挂着上一个词的译文，
        // 用户会以为那是当前这串拼音的翻译）。
        v.translations = emptyList()
        v.statusLine = ""

        if (composing.isEmpty()) {
            v.candidates = emptyList()
            v.readings = emptyList()
            v.activeReading = ""
            v.pinyinLine = ""
            curCands = emptyList()
            t9Readings = emptyList()
            t9Reading = ""
            v.invalidate()
            return
        }

        // 首行**立刻**反馈用户敲了什么（不等候选查完）
        val isT9 = mode == LayoutPref.PY9
        if (isT9) {
            // 九宫格：数字串 → 读音是**纯内存**展开（引擎音节表，微秒级、不碰数据库），
            // 所以主线程直接算 —— 首行马上显示拼音（"ni"）+ 读音消歧条，
            // 候选继续在后台查。手感和 26 键一样即时（以前这里空等几十~上百毫秒，
            // 用户看着像"按键没反应"）。
            // 保留分词符 `'`，引擎按断点展开（94'26 → xi'an）
            val digits = composing.filter { it.isDigit() || it == '\'' }
            val rd = Engine.t9Readings(digits)
            t9Readings = rd
            v.readings = rd
            v.activeReading = t9Reading
            v.pinyinLine = if (t9Reading.isEmpty()) (rd.firstOrNull() ?: digits) else t9Reading
        } else {
            v.pinyinLine = composing
            v.readings = emptyList()
            v.activeReading = ""
        }
        v.invalidate()

        // 候选放后台查（慢也不挡按键）
        val seq = ++querySeq
        val input = composing
        val reading = t9Reading
        val t9 = isT9
        // 连打时把**排队中**的旧查询全丢掉：只保留最新那一次，
        // 否则队列越积越长，最后一次结果要等前面几次跑完才出（更卡）。
        try {
            queryPool.queue.clear()
        } catch (t: Throwable) {
        }
        queryPool.execute {
            // 连打时前面的查询已经过期 → 直接跳过，别白跑（否则越打越慢）
            if (seq != querySeq) return@execute
            if (t9) {
                // 九宫格：数字串（可含分词 `'`）→ (可能读音, 候选)
                val digits = input.filter { it.isDigit() || it == '\'' }
                val r = if (reading.isEmpty()) {
                    synchronized(t9Cache) { t9Cache[digits] } ?: run {
                        val q = Engine.t9Query(digits)
                        synchronized(t9Cache) {
                            if (t9Cache.size > 64) t9Cache.clear()
                            t9Cache[digits] = q
                        }
                        q
                    }
                } else {
                    val c = Engine.queryReading(reading)
                    if (c.isEmpty()) {
                        // 选中的读音在词库里没词（极少见）→ 取消选中，回退到合并视图
                        t9Reading = ""
                        val fb = Engine.t9Query(digits)
                        Engine.T9Result(fb.readings, fb.words)
                    } else {
                        Engine.T9Result(listOf(reading), c)
                    }
                }
                mainHandler.post {
                    if (seq != querySeq) return@post
                    val k = keyboard ?: return@post
                    curCands = r.words
                    val mode = LayoutPref.scriptMode(this@PrisirImeService)
                    k.candidates = if (mode != LayoutPref.SCRIPT_OFF) r.words.map { Engine.toScript(it) } else r.words
                    val cur = t9Reading          // 用**最新**的选中状态
                    if (cur.isEmpty()) {
                        // 后台结果里的读音按**词频**排（"ni" 在 "mi" 前面），
                        // 首行与读音条都用这个顺序 —— 和候选列表（按词频）一致，
                        // 用户看到的第一行拼音就是候选里排第一的那个字的读音。
                        if (r.readings.isNotEmpty()) {
                            t9Readings = r.readings
                            k.readings = r.readings
                            k.pinyinLine = r.readings.firstOrNull() ?: input
                        }
                    } else {
                        k.readings = t9Readings
                        k.activeReading = cur
                        k.pinyinLine = cur
                    }
                    k.invalidate()
                }
                return@execute
            }
            val cands = queryCandidates(input)
            mainHandler.post {
                if (seq != querySeq) return@post          // 已经有更新的查询了，丢弃
                val k = keyboard ?: return@post
                // 记住**原始（简体）**候选：学习要写简体，繁体只是显示层的投影
                curCands = cands
                // 繁体模式下候选也显示繁体（对齐搜狗「自动繁体候选」：一眼看得见效果）
                val mode = LayoutPref.scriptMode(this@PrisirImeService)
                k.candidates = if (mode != LayoutPref.SCRIPT_OFF) cands.map { Engine.toScript(it) } else cands
                // 首行拼音**保持显示用户敲的字母**（composing），不按首候选反查读音覆盖。
                // 2026-11-20 用户报的 bug：学习/权重让首候选漂移时，首行会被其它词的读音顶掉
                // （打 "nihao" 首行却显示别的词读音）—— 首行就该是用户敲进去的那串字母。
                k.invalidate()
            }
        }
    }

    /** 26 键：查候选（带缓存）。 */
    private fun queryCandidates(input: String): List<String> {
        synchronized(candCache) {
            candCache[input]?.let { return it }
        }
        val r = Engine.query(input)
        synchronized(candCache) {
            if (candCache.size > 200) candCache.clear()
            candCache[input] = r
        }
        return r
    }

    /** 简繁二态与偏好同步（偏好是真相源，引擎是执行者）。 */
    private fun syncScript() {
        val want = LayoutPref.scriptMode(this)
        if (Engine.script() != want) Engine.setScript(want)
    }
}
