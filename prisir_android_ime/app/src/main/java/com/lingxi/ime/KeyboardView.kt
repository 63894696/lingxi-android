package com.lingxi.ime

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import androidx.core.content.ContextCompat

/**
 * 键盘视图：候选栏 + 按键区，全自绘。
 *
 * 候选栏分区保持两行（拼音+工具 / 候选）；主键盘区 2026-11-20 按主流参考重排：
 *   · 26 键底行：`123 符 ， 中/英 空格 。 九 回车`；字母键右上角数字角标
 *   · 九宫：3×3 + 右功能列 + 底行含 `0`；字母组大写 ABC/DEF
 *   · 数字页：拨号盘 3×4；键距/圆角对齐搜狗量测（hGap≈3dp、radius≈10dp）
 *   · 按压动画、命中区稳定 id、简繁/译工具键逻辑不变
 */
class KeyboardView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    /** 候选栏工具键。 */
    enum class ToolAction { TOGGLE_TRADITIONAL, LOOKUP, TRANSLATE, OPEN_LANG_PICKER }

    interface Listener {
        fun onKey(key: KeySpec)

        fun onCandidate(word: String)

        /** 翻译结果上屏（不走学习通道）。 */
        fun onTranslation(text: String)

        /** 工具键（繁/查/译/语言面板）。 */
        fun onTool(action: ToolAction)

        /** 九宫格：用户选了某个读音（用来消歧、把候选收敛过去）。 */
        fun onReading(reading: String)

        /** 长按候选词：查词典释义（翻译请点「译」）。 */
        fun onLookupWord(word: String)

        /** 语言面板里选了目标语言。 */
        fun onSelectLang(code: String)
    }

    var listener: Listener? = null

    var page: Page = Page.LETTERS
        set(v) {
            field = v
            hitsDirty = true
            invalidate()
        }

    var shift: Boolean = false
        set(v) {
            field = v
            invalidate()
        }

    var composing: String = ""
        set(v) {
            field = v
            invalidate()
        }

    /**
     * 候选栏**第一行**显示的拼音串。
     *
     * 26 键 = 刚敲的字母；九宫格 = 从数字串解析出的拼音（搜狗也是这么显示的，
     * 用户看的是拼音而不是 644 这种内部编码）。
     */
    var pinyinLine: String = ""
        set(v) {
            field = v
            invalidate()
        }

    /** 翻译结果（显示在候选行最前面，带淡蓝底，点一下直接上屏）。 */
    var translations: List<String> = emptyList()
        set(v) {
            field = v
            hitsDirty = true
            invalidate()
        }

    /** 简繁二态当前状态文字（工具键上显示：简/繁）。 */
    var traditionalState: String = "简"
        set(v) {
            field = v
            invalidate()
        }

    /** 当前翻译目标语言短码（工具键「译·英」上显示）。 */
    var translateLangLabel: String = "英"
        set(v) {
            field = v
            invalidate()
        }

    /**
     * 九宫格的**读音消歧区**（对齐搜狗：给出这串数字可能的读音，点一下候选就收敛过去）。
     *
     * 2026-11-19 第四轮重做：读音不再靠"从候选反查拼音"猜，
     * 而是由引擎用标准音节表把数字串**精确展开**得到（"64" → mi / ni / oi）。
     * 最多显示 5 个。
     */
    var readings: List<String> = emptyList()
        set(v) {
            field = v
            hitsDirty = true
            invalidate()
        }

    /** 当前选中的读音（空 = 还没选，候选是所有读音的合并结果）。 */
    var activeReading: String = ""
        set(v) {
            field = v
            hitsDirty = true
            invalidate()
        }

    /** 语言选择面板是否展开（长按「译」打开）。展开时第二行变成语言列表。 */
    var langPicker: Boolean = false
        set(v) {
            field = v
            hitsDirty = true
            invalidate()
        }

    /** 语言面板里的语言项（短标签 + 代码）。 */
    var langItems: List<Pair<String, String>> = emptyList()
        set(v) {
            field = v
            hitsDirty = true
            invalidate()
        }

    /** 第一行中间的临时提示（翻译中 / 失败原因 / 目标语言）。 */
    var statusLine: String = ""
        set(v) {
            field = v
            invalidate()
        }

    var candidates: List<String> = emptyList()
        set(v) {
            field = v
            candPage = 0
            hitsDirty = true
            invalidate()
        }

    private var candPage = 0
    private val candPerPage = 9

    private val dp = resources.displayMetrics.density
    private fun d(v: Float) = v * dp

    /**
     * 候选栏**两行**（对齐搜狗的显示方式）：
     *   第一行 = 拼音串（用户能读懂的字母）+ 右侧工具键
     *   第二行 = 候选字
     *
     * 2026-11-19 第四轮（用户反馈"简/译按钮太小，小屏手机按不到"）：
     *   第一行由 30dp 提到 **42dp**，工具键视觉 60×34dp（小屏 54×34dp），
     *   并且**命中区与视觉分离**：命中区在视觉基础上外扩 3dp(横)/2dp(竖)，
     *   保证手指点得中（原来视觉 52×26dp，竖向只有 26dp，远低于可点下限）。
     */
    private val pyH = d(42f)
    private val candH = d(46f)
    private val barH = pyH + candH
    // 主键盘几何对齐搜狗量测：水平间隙约 0.45% 屏宽 ≈ 2–3dp，垂直约 4–6dp；
    // 圆角 10dp（字母键），功能键仍用胶囊半高。
    private val pad = d(5f)
    private val hGap = d(3f)
    private val vGap = d(6f)
    private val radius = d(10f)

    // ── 视觉 token ─────────────────────────────────────────────────────────
    private val bgPaint = Paint().apply { color = ContextCompat.getColor(context, R.color.kb_bg) }
    private val keyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.kb_key)
    }
    private val funcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.kb_key_func)
    }
    private val pressedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.kb_key_pressed)
    }
    private val pressedFuncPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.kb_key_pressed_func)
    }
    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.kb_shadow)
    }
    private val barPaint = Paint().apply { color = ContextCompat.getColor(context, R.color.cand_bg) }
    private val sepPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.kb_sep)
        strokeWidth = d(1f)
    }
    private val toolBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.kb_tool_bg)
    }
    private val translateBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.kb_translate_bg)
    }

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.kb_text)
        textSize = d(20f)
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }
    private val funcTextPaint = Paint(textPaint).apply {
        color = ContextCompat.getColor(context, R.color.kb_text_func)
        textSize = d(15f)
        typeface = Typeface.create("sans-serif", Typeface.NORMAL)
    }
    private val candPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.kb_text)
        textSize = d(19f)
        textAlign = Paint.Align.LEFT
        typeface = Typeface.create("sans-serif", Typeface.NORMAL)
    }
    private val composingPaint = Paint(candPaint).apply {
        color = ContextCompat.getColor(context, R.color.kb_accent)
        textSize = d(16f)
    }
    /** 拼音串胶囊底（设计稿 #E2E9F6 淡蓝带）。 */
    private val pinyinCapsulePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.kb_pinyin_capsule)
    }
    /** 首选候选：加粗深色（对齐搜狗"第一候选突出"的层级）。 */
    private val cand0Paint = Paint(candPaint).apply {
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }
    private val statusPaint = Paint(candPaint).apply {
        color = ContextCompat.getColor(context, R.color.kb_text_func)
        textSize = d(13f)
        textAlign = Paint.Align.RIGHT
    }
    private val toolPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.kb_text_func)
        textSize = d(17f)
        textAlign = Paint.Align.CENTER
    }
    /** 「译·英」比「简/繁」多一个字，字号小一号才塞得进 54dp 的小屏按钮。 */
    private val toolLangPaint = Paint(toolPaint).apply {
        textSize = d(15f)
    }
    private val toolHiPaint = Paint(toolPaint).apply {
        color = ContextCompat.getColor(context, R.color.kb_accent)
    }
    /** 「译·英」的高亮态（字号跟 toolLangPaint 一致，别被 17dp 撑爆按钮）。 */
    private val toolLangHiPaint = Paint(toolLangPaint).apply {
        color = ContextCompat.getColor(context, R.color.kb_accent)
    }
    // 九宫格：数字小、字母组大（主流拇指键盘层级）
    private val t9DigitPaint = Paint(textPaint).apply {
        color = ContextCompat.getColor(context, R.color.kb_text_func)
        textSize = d(13f)
        typeface = Typeface.create("sans-serif", Typeface.NORMAL)
    }
    private val t9LetterPaint = Paint(textPaint).apply {
        color = ContextCompat.getColor(context, R.color.kb_text)
        textSize = d(16f)
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }
    /** 26 键字母右上角数字角标（主流「键上角标」视觉）。 */
    private val cornerHintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.kb_text_func)
        textSize = d(10f)
        textAlign = Paint.Align.RIGHT
        typeface = Typeface.create("sans-serif", Typeface.NORMAL)
    }
    private val hintPaint = Paint(candPaint).apply {
        color = ContextCompat.getColor(context, R.color.kb_text_func)
        textSize = d(13f)
    }
    // 音节区（九宫格消歧）
    private val readingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.kb_text)
        textSize = d(15f)
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }
    private val readingOnPaint = Paint(readingPaint).apply {
        color = ContextCompat.getColor(context, R.color.kb_accent)
    }
    private val accentFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.kb_accent_fill)
    }

    private class Hit(
        val rect: RectF,
        val key: KeySpec? = null,
        val candidate: String? = null,
        val pageDelta: Int = 0,
        val tool: ToolAction? = null,
        /** 翻译结果项（点击上屏，但走的是候选通道）。 */
        val isTranslation: Boolean = false,
        /** 音节项（九宫格消歧）。 */
        val reading: String? = null,
        /** 语言项（语言面板）。 */
        val langCode: String? = null,
        /**
         * 稳定身份串。
         *
         * **不能用对象引用或下标来记"按下了哪个键"**：候选是异步查回来的，
         * 候选到达会重建命中区列表 → 下标漂移 → 松手时提交了**另一个键**
         * （表现为"点候选却打出字母"，实测中招）。所以按下时记身份串，抬起时按身份重找。
         */
        val id: String = "",
    )

    private var hits: List<Hit> = emptyList()
    private var hitsDirty = true

    /**
     * 视图在屏幕上的原点（调试用）。
     * 键盘弹出/收起时视图位置会变 → 命中区的**屏幕坐标**跟着变，
     * 调试输出必须同步更新（否则自动化按坐标点键会点偏）。
     */
    private val lastOrigin = intArrayOf(0, 0)

    /** 命中区日志的节流时间戳（调试用）：每 120ms 最多打一条，防止 logcat 缓冲区被撑爆。 */
    private var lastHitLogAt = 0L

    /**
     * 屏幕原点是否已被**触摸事件**校准过（2026-11-20）。
     *
     * getLocationOnScreen 在输入视图窗口做位置动画期间会返回过渡态的值，
     * 校准过一次之后就以事件反推的值为准（事件给的原点永远是当下的真值）。
     */
    private var originTrusted = false

    /** 调试构建（debuggable）才输出命中区日志，release 包不刷屏。 */
    private fun debuggable(): Boolean =
        try {
            context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0
        } catch (t: Throwable) {
            false
        }

    // ── 按压动画 ───────────────────────────────────────────────────────────
    /** 按下的是哪个命中项（用稳定 id，不用下标——候选异步到达会让下标漂移）。 */
    private var pressedId: String = ""
    /** 按压代次：动画回调用它判断"自己是不是最新一次按压"。 */
    private var pressGen: Int = 0
    private var pressScale = 1f
    private var pressAnim: ValueAnimator? = null
    private val handler = Handler(Looper.getMainLooper())
    private var repeatDelete: Runnable? = null
    private var longPressTool: Runnable? = null

    // ── 候选栏左右滑动翻页 ─────────────────────────────────────────────────
    /** 手势起点是否落在候选栏第二行（y ∈ [pyH, barH]）；只有这里才响应横滑翻页。 */
    private var swipeFromCandidates = false
    private var swipeStartX = 0f
    /** 本次手势是否已用横滑翻过页（翻过就吞掉 UP，避免顺带把候选上屏）。 */
    private var swipeConsumed = false

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        // 4 行键区：行高略抬到 56dp，配合更紧的 vGap，整体仍约屏高 1/3
        val h = (barH + d(56f) * 4 + vGap * 5 + pad * 2).toInt()
        setMeasuredDimension(w, h)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        hitsDirty = true
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        // 刚挂上窗口时屏幕位置还没定 → 命中区坐标是旧的，重建一次
        hitsDirty = true
        invalidate()
    }

    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        super.onWindowFocusChanged(hasWindowFocus)
        // 输入视图窗口移动/聚焦变化 → 屏幕坐标会变，重建并重打日志
        hitsDirty = true
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bgPaint)
        val bar = RectF(0f, 0f, width.toFloat(), barH)
        canvas.drawRect(bar, barPaint)
        // 候选栏与键区之间一条分隔线（白条和灰底直接贴一起会显得糊在一起）
        canvas.drawLine(0f, barH, width.toFloat(), barH, sepPaint)

        // 视图在屏幕上的位置变了（键盘弹出/收起、窗口移动）→ 命中区的**屏幕坐标**跟着变，
        // 调试输出必须跟着更新（否则自动化按坐标点键会点偏）。
        // 命中判定本身不受影响（rect 是视图坐标，event 也是视图坐标）。
        if (debuggable() && !originTrusted) {
            val loc = IntArray(2)
            try {
                getLocationOnScreen(loc)
                if (loc[0] != lastOrigin[0] || loc[1] != lastOrigin[1]) {
                    lastOrigin[0] = loc[0]
                    lastOrigin[1] = loc[1]
                    hitsDirty = true
                }
            } catch (t: Throwable) {
            }
        }

        // 命中区只在"真的变了"时重建（之前每帧 new Hit，按下高亮瞬间失效 → 按压没动静）
        var rebuilt = false
        if (hitsDirty || hits.isEmpty()) {
            hits = buildHits()
            hitsDirty = false
            rebuilt = true
        }

        // 调试构建下把命中区连同**屏幕绝对坐标**打出来：
        // 自绘键盘没有 accessibility 节点，uiautomator 看不到，
        // 自动化测试要按坐标点键时只能靠这份输出标定（目测截图会偏 35~40px）。
        //
        // **每帧都打**（2026-11-19 修）：原来只在"命中区重建时"打，可视图被父容器移动时
        // 并不一定触发 onDraw → 日志里的 origin 会**过期**，自动化按旧坐标点会打到隔壁键
        // （实测九宫格的 6 键被点成 9 键，"64426" 变 "94426"）。
        //
        // 但每帧打会把 logcat 缓冲区撑爆（自动化脚本全量读取要几百 ms → 期间位置又变了），
        // 所以**节流 120ms**，位置/命中区变化时立即打（保证最新坐标可取）。
        if (debuggable()) {
            val now = android.os.SystemClock.uptimeMillis()
            if (rebuilt || now - lastHitLogAt > 120) {
                lastHitLogAt = now
                try {
                    android.util.Log.d(
                        "LingxiK",
                        "HITS origin=(${lastOrigin[0]},${lastOrigin[1]}) " +
                            hits.joinToString(" ") {
                                "${it.id}@${it.rect.left.toInt()},${it.rect.top.toInt()}," +
                                    "${it.rect.right.toInt()},${it.rect.bottom.toInt()}"
                            }
                    )
                } catch (t: Throwable) {
                }
            }
        }

        drawPinyinLine(canvas)
        drawCandidateRow(canvas)
        drawKeys(canvas)
    }

    // ── 候选栏第一行：拼音 + 工具键 ────────────────────────────────────────

    /** 工具键个数：简/繁 · 查 · 译。 */
    private val toolCount = 3

    /** 工具键视觉宽度：三键时略收窄，小屏不低于 46dp。 */
    private fun toolW() = if (width < d(360f)) d(46f) else d(52f)

    /** 三个工具键占的总宽（含间隙）。 */
    private fun toolAreaWidth() = toolW() * toolCount + d(6f) * (toolCount - 1)

    /** 工具键的**视觉**矩形（第一行右侧，垂直居中；index 0=最左）。 */
    private fun toolRect(index: Int): RectF {
        val w = toolW()
        val gap = d(6f)
        val right = width - d(8f)
        val left = right - w * (toolCount - index) - gap * (toolCount - 1 - index)
        val h = d(34f)
        return RectF(left, (pyH - h) / 2f, left + w, (pyH + h) / 2f)
    }

    /**
     * 工具键的**命中区**：比视觉外扩一圈（横 3dp / 竖 2dp），手指不用抠着点。
     *
     * 外扩量刚好等于按钮间隙（6dp/2 = 3dp），两个按钮的命中区互不重叠。
     * 原实现命中区 == 视觉区，竖向只有 26dp，小屏上根本点不准。
     */
    private fun toolHitRect(index: Int): RectF {
        val r = toolRect(index)
        return RectF(r.left - d(3f), r.top - d(2f), r.right + d(3f), r.bottom + d(2f))
    }

    /**
     * 音节/拼音候选区的排版（绘制与命中区共用）。
     *
     * 位置：拼音/数字串右侧，最多 5 个；选中的那个用蓝底标出来。
     * 九宫格里这里放的是**可能的拼音**（mi / ni / oi…），点一下候选就收敛到那个读音。
     */
    private fun layoutReadings(): List<Pair<String, RectF>> {
        val out = ArrayList<Pair<String, RectF>>(5)
        // 只有一个读音时不用消歧条（首行已经写着它了，重复一个 chip 很啰嗦）
        if (readings.size <= 1 && activeReading.isEmpty()) return out
        if (readings.isEmpty()) return out
        // 拼音胶囊占位：左 6dp + 字宽 + 右 20dp，chip 从胶囊右缘 + 8dp 开始排
        var x = if (pinyinLine.isEmpty()) d(10f)
            else d(6f) + composingPaint.measureText(pinyinLine) + d(20f) + d(8f)
        val h = pyH - d(10f)
        val limit = width - toolAreaWidth() - d(10f)
        for (s in readings.take(5)) {
            val tw = readingPaint.measureText(s)
            val r = RectF(x, (pyH - h) / 2f, x + tw + d(14f), (pyH + h) / 2f)
            out.add(s to r)
            x = r.right + d(6f)
            if (x > limit) break
        }
        return out
    }

    /** 第一行：拼音串（淡蓝胶囊底）+ 音节区 + 右侧工具键。 */
    private fun drawPinyinLine(canvas: Canvas) {
        val cy = pyH / 2f - (composingPaint.descent() + composingPaint.ascent()) / 2f
        if (pinyinLine.isNotEmpty()) {
            // 2026-11-20 设计稿对齐：拼音串放在淡蓝圆角胶囊里（设计稿 y≈224-304 的
            // #E2E9F6 高亮带），一眼看出"正在输入"，比细下划线显眼得多。
            val uw = composingPaint.measureText(pinyinLine)
            val capH = pyH - d(10f)
            val capR = RectF(d(6f), (pyH - capH) / 2f, d(6f) + uw + d(20f), (pyH + capH) / 2f)
            canvas.drawRoundRect(capR, capH / 2f, capH / 2f, pinyinCapsulePaint)
            canvas.drawText(pinyinLine, d(16f), cy, composingPaint)
        }

        // 音节区（九宫格消歧用）
        for ((s, r) in layoutReadings()) {
            val active = s == activeReading
            val rp = r.height() / 2f
            canvas.drawRoundRect(r, rp, rp, if (active) accentFillPaint else toolBgPaint)
            val p = if (active) readingOnPaint else readingPaint
            canvas.drawText(
                s,
                r.left + d(6f),
                r.centerY() - (p.descent() + p.ascent()) / 2f,
                p
            )
        }

        // 状态提示（翻译中 / 失败原因 / 目标语言），画在工具键左边
        if (statusLine.isNotEmpty()) {
            val right = width - d(12f) - toolAreaWidth()
            canvas.drawText(statusLine, right, cy, statusPaint)
        }

        // 简/繁 · 查 · 译·XX（长按「译」开语言面板；长按候选 = 查释义）
        drawTool(canvas, toolRect(0), traditionalState, traditionalState == "繁", "tool:trad", toolPaint, toolHiPaint)
        drawTool(canvas, toolRect(1), "查", false, "tool:lookup", toolPaint, toolHiPaint)
        drawTool(canvas, toolRect(2), "译·" + translateLangLabel, false, "tool:trans", toolLangPaint, toolLangHiPaint)
    }

    private fun drawTool(
        canvas: Canvas,
        r: RectF,
        label: String,
        active: Boolean,
        id: String,
        paint: Paint,
        hiPaint: Paint,
    ) {
        val down = id == pressedId
        val rp = r.height() / 2f
        canvas.drawRoundRect(r, rp, rp, if (down) pressedPaint else toolBgPaint)
        val p = if (active || down) hiPaint else paint
        val cy = r.centerY() - (p.descent() + p.ascent()) / 2f
        canvas.drawText(label, r.centerX(), cy, p)
    }

    // ── 候选栏第二行：翻译结果 + 候选字 + 翻页 ──────────────────────────────

    /** 翻译结果的排版（绘制与命中区共用，避免两处算出不同位置）。 */
    private fun layoutTranslations(): List<Pair<String, RectF>> {
        val out = ArrayList<Pair<String, RectF>>()
        var x = d(10f)
        for (t in translations) {
            val tw = candPaint.measureText(t)
            if (x + tw > width - d(56f)) break
            val r = RectF(x - d(6f), pyH + d(6f), x + tw + d(6f), barH - d(6f))
            out.add(t to r)
            x += tw + d(22f)
        }
        return out
    }

    /** 语言面板的排版（绘制与命中区共用）。20 种语言用单字短标签 + 紧凑间距一行放下。 */
    private fun layoutLangItems(): List<Pair<String, RectF>> {
        val out = ArrayList<Pair<String, RectF>>(langItems.size)
        var x = d(6f)
        for ((code, label) in langItems) {
            val tw = readingPaint.measureText(label)
            if (x + tw + d(10f) > width - d(6f)) break
            out.add(code to RectF(x, pyH + d(5f), x + tw + d(10f), barH - d(5f)))
            x += tw + d(10f)
        }
        return out
    }

    private fun drawCandidateRow(canvas: Canvas) {
        var x = d(10f)
        val cy = pyH + candH / 2f - (candPaint.descent() + candPaint.ascent()) / 2f

        // 语言面板（长按「译」展开）：第二行变成语言列表，当前语言高亮
        if (langPicker) {
            val cur = translateLangLabel
            for ((code, r) in layoutLangItems()) {
                val label = langItems.firstOrNull { it.first == code }?.second ?: code
                val active = label == cur
                canvas.drawRoundRect(r, d(6f), d(6f), if (active) accentFillPaint else toolBgPaint)
                val p = if (active) readingOnPaint else readingPaint
                canvas.drawText(
                    label,
                    r.left + d(5f),
                    r.centerY() - (p.descent() + p.ascent()) / 2f,
                    p
                )
            }
            return
        }

        // 翻译结果（淡蓝底，点一下上屏）
        for ((t, r) in layoutTranslations()) {
            canvas.drawRoundRect(r, d(6f), d(6f), translateBgPaint)
            canvas.drawText(t, r.left + d(6f), cy, candPaint)
            x = r.right + d(12f)
        }
        // 译文出现时**隐藏中文候选**：避免用户去点英文时误触旁边的中文直接上屏
        // （命中区也同步不收 cand:*，见 buildHits）。点译文上屏后会恢复。
        if (translations.isNotEmpty()) return

        val start = candPage * candPerPage
        val slice = candidates.drop(start).take(candPerPage)
        if (slice.isEmpty() && translations.isEmpty()) {
            val hint = if (Engine.loaded) "" else "词库未就绪（请重装完整 APK）"
            if (hint.isNotEmpty()) canvas.drawText(hint, x, cy, hintPaint)
            return
        }
        for ((i, w) in slice.withIndex()) {
            val p = if (i == 0) cand0Paint else candPaint
            val tw = p.measureText(w)
            if (x + tw > width - d(56f)) break
            canvas.drawText(w, x, cy, p)
            x += tw + d(22f)
        }
        if (candidates.size > candPerPage) {
            val p = Paint(candPaint).apply { textAlign = Paint.Align.RIGHT }
            val right = width - d(10f)
            if (start + candPerPage < candidates.size) canvas.drawText("›", right, cy, p)
            if (candPage > 0) canvas.drawText("‹", right - d(22f), cy, p)
        }
    }

    // ── 键区绘制（阴影层 + 键面层 + 文字层）────────────────────────────────

    private fun pressScaleFor(hit: Hit): Float = if (hit.id == pressedId) pressScale else 1f

    /**
     * 键的圆角半径：字母键用统一大圆角；功能键/空格/回车/退格用**胶囊形**
     * （圆角 = 半高，横向药丸）。胶囊键是 2025 极简键盘的标志性形态，
     * 一眼就能和"方块按键"的旧版区分开。
     */
    private fun keyRadius(hit: Hit): Float {
        val key = hit.key ?: return radius
        return if (key.type != KeyType.CHAR) hit.rect.height() / 2f else radius
    }

    private fun drawKeys(canvas: Canvas) {
        // 第一遍：键面下方的柔和阴影（按下时随缩放一起收掉 → 有"沉下去"的立体感）
        for (hit in hits) {
            val key = hit.key ?: continue
            val s = pressScaleFor(hit)
            val dy = d(3f) * (1f - s)
            val r = hit.rect
            val rp = keyRadius(hit)
            canvas.drawRoundRect(
                RectF(r.left, r.top + dy, r.right, r.bottom + dy), rp, rp, shadowPaint
            )
        }
        // 第二遍：键面 + 文字
        for (hit in hits) {
            val key = hit.key ?: continue
            val s = pressScaleFor(hit)
            canvas.save()
            if (s != 1f) canvas.scale(s, s, hit.rect.centerX(), hit.rect.centerY())
            val down = hit.id == pressedId
            val paint = when {
                down && key.type == KeyType.CHAR -> pressedPaint
                down -> pressedFuncPaint
                // ⇪ 锁定时淡蓝底（一眼看出大写状态）
                key.type == KeyType.SHIFT && shift -> accentFillPaint
                key.type != KeyType.CHAR -> funcPaint
                else -> keyPaint
            }
            val rp = keyRadius(hit)
            canvas.drawRoundRect(hit.rect, rp, rp, paint)
            drawKeyText(canvas, hit.rect, key)
            canvas.restore()
        }
    }

    private fun drawKeyText(canvas: Canvas, rect: RectF, key: KeySpec) {
        val label = when {
            key.type == KeyType.SHIFT && shift -> "⇪"
            key.type == KeyType.CHAR && shift && key.label.length == 1 &&
                key.label[0] in 'a'..'z' -> key.label.uppercase()
            else -> key.label
        }
        // 功能键（⏎ ⌫ 符 123 …）统一用灰色小字
        if (key.type != KeyType.CHAR) {
            // 空格：中文页品牌名；英文页保持 "space"
            val shown = when {
                key.type == KeyType.SPACE && page != Page.LETTERS_EN -> "灵犀拼音"
                else -> label
            }
            // ⇪ 锁定时用强调蓝字
            val p = if (key.type == KeyType.SHIFT && shift) toolHiPaint else funcTextPaint
            val cy = rect.centerY() - (p.descent() + p.ascent()) / 2f
            canvas.drawText(shown, rect.centerX(), cy, p)
            return
        }
        // 九宫格：数字小在上，字母组大在下（ABC/DEF…）
        if (page == Page.T9 && key.sub.isNotEmpty()) {
            canvas.drawText(label, rect.centerX(), rect.centerY() - d(6f), t9DigitPaint)
            canvas.drawText(key.sub, rect.centerX(), rect.centerY() + d(12f), t9LetterPaint)
            return
        }
        if (page == Page.T9) {
            // 「1」「0」等无字母组：大号数字居中
            val cy = rect.centerY() - (t9LetterPaint.descent() + t9LetterPaint.ascent()) / 2f
            canvas.drawText(label, rect.centerX(), cy, t9LetterPaint)
            return
        }
        // 26 键：主字母居中 + 右上角数字角标
        val cy = rect.centerY() - (textPaint.descent() + textPaint.ascent()) / 2f
        canvas.drawText(label, rect.centerX(), cy, textPaint)
        if (key.sub.isNotEmpty()) {
            canvas.drawText(
                key.sub,
                rect.right - d(5f),
                rect.top + d(12f),
                cornerHintPaint
            )
        }
    }

    // ── 命中区 ─────────────────────────────────────────────────────────────

    private fun buildHits(): List<Hit> {
        val out = ArrayList<Hit>(96)

        // 候选栏工具键（第一行右侧）：命中区比视觉大一圈，手指好按
        out.add(Hit(toolHitRect(0), tool = ToolAction.TOGGLE_TRADITIONAL, id = "tool:trad"))
        out.add(Hit(toolHitRect(1), tool = ToolAction.LOOKUP, id = "tool:lookup"))
        out.add(Hit(toolHitRect(2), tool = ToolAction.TRANSLATE, id = "tool:trans"))

        // 音节/拼音候选区（九宫格消歧）：命中区上下各外扩 2dp
        for ((s, r) in layoutReadings()) {
            out.add(
                Hit(
                    RectF(r.left, r.top - d(2f), r.right, r.bottom + d(2f)),
                    reading = s, id = "syl:" + s
                )
            )
        }

        // 语言面板（展开时第二行变成语言列表；**键区照常可用**，只是不显示候选）
        if (langPicker) {
            for ((code, r) in layoutLangItems()) out.add(Hit(r, langCode = code, id = "lang:" + code))
        } else {
            // 翻译结果
            for ((t, r) in layoutTranslations()) {
                out.add(Hit(r, candidate = t, isTranslation = true, id = "trans:" + t))
            }

            // **翻译期间只给译文命中区，中文候选不可点**（2026-11-20 用户报的 bug）：
            // 原来译文和中文候选并排显示，用户长按候选翻译后去点左边的英文，
            // 手指稍偏就点到紧挨着的中文候选 → 中文直接上屏（"我还没选译文呢"）。
            // 语义应该是：译文出来后**等用户明确点了译文**才上屏。
            if (translations.isNotEmpty()) return out

            // 第二行：候选字 + 翻页
            var x = d(10f)
            for ((_, r) in layoutTranslations()) x = r.right + d(12f)
            val rowTop = pyH
            val start = candPage * candPerPage
            val slice = candidates.drop(start).take(candPerPage)
            for ((i, w) in slice.withIndex()) {
                val p = if (i == 0) cand0Paint else candPaint
                val tw = p.measureText(w)
                if (x + tw > width - d(56f)) break
                out.add(Hit(RectF(x - d(4f), rowTop, x + tw + d(4f), barH), candidate = w, id = "cand:" + w))
                x += tw + d(22f)
            }
            if (candidates.size > candPerPage) {
                val right = width - d(6f)
                if (start + candPerPage < candidates.size) {
                    out.add(Hit(RectF(right - d(26f), rowTop, right, barH), pageDelta = 1, id = "page:+"))
                }
                if (candPage > 0) {
                    out.add(Hit(RectF(right - d(52f), rowTop, right - d(28f), barH), pageDelta = -1, id = "page:-"))
                }
            }
        }

        // 键区
        val rows = KeyboardLayout.rowsFor(page)
        val top = barH + pad
        val availH = height.toFloat() - top - pad - vGap * (rows.size - 1)
        val rowH = availH / rows.size
        val availW = width.toFloat() - pad * 2
        for ((ri, row) in rows.withIndex()) {
            val totalWeight = row.sumOf { it.weight.toDouble() }.toFloat()
            val gaps = hGap * (row.size - 1)
            val unit = (availW - gaps) / totalWeight
            var cx = pad
            val y0 = top + ri * (rowH + vGap)
            for (key in row) {
                val kw = unit * key.weight
                out.add(
                    Hit(
                        RectF(cx, y0, cx + kw, y0 + rowH), key = key,
                        id = "key:" + key.label + ":" + key.type.name + ":" + key.code
                    )
                )
                cx += kw + hGap
            }
        }
        return out
    }

    // ── 触摸 + 按压动画 ────────────────────────────────────────────────────

    /** 自检用：找一个普通字符键的中心坐标（视图坐标）。 */
    fun debugCharKeyCenter(): Pair<Float, Float>? {
        val h = hits.firstOrNull { it.key != null && it.key.type == KeyType.CHAR } ?: return null
        return Pair(h.rect.centerX(), h.rect.centerY())
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                // **用事件反推真实的屏幕原点**（raw - view 就是事件坐标系里的原点）。
                //
                // 为什么必须这么做（2026-11-20 踩坑）：getLocationOnScreen 在输入视图
                // 窗口做位置动画期间会返回**过渡态**的值（如 1158，而真实位置是 1074），
                // 而且视图被移动**不一定触发 onDraw** → 命中区日志里的 origin 会过期。
                // 自动化脚本按过期 origin 算坐标 → 整体偏 84px → 6 键被点成 9 键
                // （"64426" 变 "94426"），看起来像"按键映射错了"，其实是坐标错了。
                // 触摸事件给的原点永远是当下的真值 → 用它校正，日志里的 origin 就不会再错。
                val ox = (event.rawX - event.x).toInt()
                val oy = (event.rawY - event.y).toInt()
                if (ox != lastOrigin[0] || oy != lastOrigin[1]) {
                    lastOrigin[0] = ox
                    lastOrigin[1] = oy
                    hitsDirty = true
                    invalidate() // 重打一次命中区日志，让脚本读到校正后的 origin
                }
                originTrusted = true
                val idx = hits.indexOfFirst { it.rect.contains(event.x, event.y) }
                // 调试：打印事件的**屏幕坐标**与命中的键 —— 自动化点键偏位时，
                // 一眼就能看出是"坐标算错"还是"命中区与实际位置不一致"。
                if (debuggable()) {
                    try {
                        android.util.Log.d(
                            "LingxiK",
                            "TOUCH raw=(${event.rawX.toInt()},${event.rawY.toInt()}) " +
                                "view=(${event.x.toInt()},${event.y.toInt()}) " +
                                "origin=(${lastOrigin[0]},${lastOrigin[1]}) " +
                                "hit=${if (idx >= 0) hits[idx].id else "NONE"}"
                        )
                    } catch (t: Throwable) {
                    }
                }
                // 候选栏横滑翻页：记下起点。第二行 y ∈ [pyH, barH] 才响应，
                // 第一行拼音/工具键和键区不参与（避免打字时误触翻页）。
                swipeFromCandidates = event.y >= pyH && event.y <= barH && translations.isEmpty() && !langPicker
                swipeStartX = event.x
                swipeConsumed = false
                if (idx < 0) return true
                // 每次按下都从"没触发过长按"开始（防御：上次手势若被系统取消，
                // 标志可能残留 → 这次普通点击被误吞）。
                longPressToolFired = false
                pressedId = hits[idx].id
                animatePress(0.93f)
                val hit = hits[idx]
                // 退格长按连删（通用习惯）
                if (hit.key?.type == KeyType.DELETE) {
                    handler.postDelayed({ startRepeatDelete() }, 350)
                }
                // 「译」长按 = 打开语言面板；长按候选 = 查词典释义
                when {
                    hit.tool == ToolAction.TRANSLATE -> {
                        longPressTool = Runnable {
                            longPressToolFired = true
                            listener?.onTool(ToolAction.OPEN_LANG_PICKER)
                        }
                        handler.postDelayed(longPressTool!!, 450)
                    }
                    hit.candidate != null && !hit.isTranslation -> {
                        longPressTool = Runnable {
                            longPressToolFired = true
                            listener?.onLookupWord(hit.candidate!!)
                        }
                        handler.postDelayed(longPressTool!!, 450)
                    }
                }
            }

            MotionEvent.ACTION_MOVE -> {
                // 候选栏横滑翻页：左滑下一页 / 右滑上一页。一次手势只翻一页（翻后置 consumed）。
                if (swipeFromCandidates && !swipeConsumed && candidates.size > candPerPage) {
                    val dx = event.x - swipeStartX
                    val threshold = d(36f)
                    if (dx <= -threshold) {
                        val maxPage = (candidates.size - 1) / candPerPage
                        if (candPage < maxPage) {
                            candPage++
                            hitsDirty = true
                            invalidate()
                        }
                        swipeConsumed = true
                        cancelPending()
                        releasePress()
                        return true
                    } else if (dx >= threshold) {
                        if (candPage > 0) {
                            candPage--
                            hitsDirty = true
                            invalidate()
                        }
                        swipeConsumed = true
                        cancelPending()
                        releasePress()
                        return true
                    }
                }
                if (pressedId.isEmpty()) return true
                val hit = hits.firstOrNull { it.id == pressedId } ?: return true
                // 手指在按键上微微滑动是常态（快速打字更明显），
                // 滑出一点就取消 = "按了没反应"。给 10dp 容差（Android 的 touch slop 量级）。
                val slop = d(10f)
                val r = hit.rect
                val out = event.x < r.left - slop || event.x > r.right + slop ||
                        event.y < r.top - slop || event.y > r.bottom + slop
                if (out) {
                    cancelPending()
                    releasePress()
                }
            }

            MotionEvent.ACTION_UP -> {
                // 按**身份**重新找（候选可能异步换掉了，不能用下标）
                val hit = hits.firstOrNull { it.id == pressedId }
                val deleting = repeatDelete != null
                val toolLong = longPressToolFired
                cancelPending()
                releasePress()
                if (hit == null) return true
                when {
                    swipeConsumed -> Unit                  // 本次手势已用于横滑翻页，不上屏候选
                    deleting -> Unit                       // 退格长按已连删，吞掉这次 UP
                    toolLong -> Unit                       // 长按已触发（翻译/语言面板）
                    hit.reading != null -> listener?.onReading(hit.reading)
                    hit.langCode != null -> listener?.onSelectLang(hit.langCode)
                    hit.candidate != null ->
                        if (hit.isTranslation) listener?.onTranslation(hit.candidate)
                        else listener?.onCandidate(hit.candidate)
                    hit.pageDelta != 0 -> {
                        candPage = (candPage + hit.pageDelta).coerceAtLeast(0)
                        hitsDirty = true
                        invalidate()
                    }
                    hit.tool != null -> listener?.onTool(hit.tool)
                    hit.key != null -> listener?.onKey(hit.key)
                }
            }

            MotionEvent.ACTION_CANCEL -> {
                cancelPending()
                releasePress()
            }
        }
        return true
    }

    /** 按下：缩到 0.93（快）；抬起：带 overshoot 回弹到 1.0（慢一点，才看得出"弹回来"）。 */
    private fun animatePress(target: Float) {
        // 代次标记：动画结束回调只在"我还是最新那次按压"时才允许改状态。
        // 2026-11-19 修「快速打字丢键」：按下 B 会 cancel 掉 A 的抬起动画，
        // 而 cancel 同样会触发 onAnimationEnd → 老动画把 B 刚设的 pressedId 清空 →
    // B 抬起时按 id 找不到命中 → 这一键被吃掉（必须间隔 >240ms 才正常）。
    // ⚠️ 顺序关键：**先换代再 cancel**。cancel 会同步触发老动画的 onAnimationEnd，
    //    如果那时 pressGen 还是老的，老动画就会误以为自己仍是最新 → 照样清空 pressedId。
    val gen = ++pressGen
    pressAnim?.cancel()
    pressAnim = ValueAnimator.ofFloat(pressScale, target).apply {
            duration = if (target < 1f) 90 else 240
            interpolator = if (target < 1f) DecelerateInterpolator() else OvershootInterpolator(1.4f)
            addUpdateListener {
                pressScale = it.animatedValue as Float
                invalidate()
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    if (gen != pressGen) return          // 已被更新的按压取代 → 不碰状态
                    if (target >= 1f) {
                        pressedId = ""
                        pressScale = 1f
                        invalidate()
                    }
                }
            })
            start()
        }
    }

    private fun releasePress() {
        if (pressedId.isEmpty()) return
        animatePress(1f)
    }

    /** 清理定时器。 */
    private fun cancelPending() {
        handler.removeCallbacksAndMessages(null)
        repeatDelete = null
        longPressTool = null
        longPressToolFired = false
    }

    private var longPressToolFired = false

    private fun startRepeatDelete() {
        val r = object : Runnable {
            override fun run() {
                listener?.onKey(KeySpec("⌫", KeyType.DELETE))
                handler.postDelayed(this, 55)
            }
        }
        repeatDelete = r
        handler.postDelayed(r, 0)
    }

    fun resetCandidates() {
        composing = ""
        candidates = emptyList()
        translations = emptyList()
        statusLine = ""
        candPage = 0
        hitsDirty = true
    }
}
