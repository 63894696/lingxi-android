package com.lingxi.ime

/**
 * 键盘布局（纯数据）。2026-11-20：主键盘区对齐主流安卓拼音输入法。
 *
 * 几何与底行比例仍参考搜狗 20.20（`py_26.ini` / `py_9`）；键位编排参考
 * 青简 / 墨奇 / 百度常见形态：
 *   · 26 键底行：`123 / 符 / ， / 中英 / 空格 / 。 / 回车`，布局切换键标「九」
 *   · 九宫：3×3 + 右侧功能列，底行含 `0`，字母组 abc/def… 大字、数字小字
 *   · 数字页：拨号盘 3×4（非横排 1–0），与九键视觉对齐、切换不跳
 *   · 符号页 1：英文半角（Gboard ?123）+ 常用 `、` `…`，保证 `http://` 可直接输入
 *   · 符号页 2：中文书名号/引号 + 数学/货币杂项（无 SYM1 成对全角）
 *
 * 候选栏分区不在这里，由 [KeyboardView] 负责。
 */
enum class Page { LETTERS, T9, LETTERS_EN, NUMBERS, SYM1, SYM2 }

enum class KeyType {
    CHAR, SHIFT, DELETE, ENTER, SPACE, LANG,
    PAGE_LETTERS, PAGE_NUMBERS, PAGE_SYM1, PAGE_SYM2,
    COMMA, PERIOD,
    /** 底行布局切换键：拼音 26 ↔ 拼音 9。 */
    LAYOUT_SWITCH,
}

data class KeySpec(
    val label: String,
    val type: KeyType,
    val weight: Float = 1f,
    /** 实际提交的字符（CHAR 用；空则用 label） */
    val code: String = "",
    /** 副标：九宫格第二行字母组（ABC / DEF …）；26 键不用。 */
    val sub: String = "",
)

object KeyboardLayout {

    private fun ch(c: String, weight: Float = 1f, sub: String = "") =
        KeySpec(c, KeyType.CHAR, weight, c, sub)

    /** 26 键拼音页：QWERTY + 主流中文底行（含，。与「九」切换）。 */
    val letters: List<List<KeySpec>> = listOf(
        listOf("q", "w", "e", "r", "t", "y", "u", "i", "o", "p").map { ch(it) },
        listOf("a", "s", "d", "f", "g", "h", "j", "k", "l").map { ch(it) },
        listOf(
            KeySpec("⇧", KeyType.SHIFT, 1.5f),
            ch("z"), ch("x"), ch("c"), ch("v"), ch("b"), ch("n"), ch("m"),
            KeySpec("⌫", KeyType.DELETE, 1.5f),
        ),
        // 权重近似搜狗底行，并插入「，。」与布局键「九」（青简/墨奇常见形态）
        listOf(
            KeySpec("123", KeyType.PAGE_NUMBERS, 0.10f),
            KeySpec("符", KeyType.PAGE_SYM1, 0.09f),
            KeySpec("，", KeyType.COMMA, 0.09f),
            KeySpec("中/英", KeyType.LANG, 0.11f),
            KeySpec("空格", KeyType.SPACE, 0.32f),
            KeySpec("。", KeyType.PERIOD, 0.09f),
            KeySpec("九", KeyType.LAYOUT_SWITCH, 0.09f),
            KeySpec("回车", KeyType.ENTER, 0.11f),
        ),
    )

    /** 英文 26 键：底行用半角标点，无九宫切换。 */
    val lettersEn: List<List<KeySpec>> = listOf(
        listOf("q", "w", "e", "r", "t", "y", "u", "i", "o", "p").map { ch(it) },
        listOf("a", "s", "d", "f", "g", "h", "j", "k", "l").map { ch(it) },
        listOf(
            KeySpec("⇧", KeyType.SHIFT, 1.5f),
            ch("z"), ch("x"), ch("c"), ch("v"), ch("b"), ch("n"), ch("m"),
            KeySpec("⌫", KeyType.DELETE, 1.5f),
        ),
        listOf(
            KeySpec("123", KeyType.PAGE_NUMBERS, 0.12f),
            KeySpec("!#", KeyType.PAGE_SYM1, 0.10f),
            KeySpec(",", KeyType.COMMA, 0.10f),
            KeySpec("英/中", KeyType.LANG, 0.12f),
            KeySpec("space", KeyType.SPACE, 0.34f),
            KeySpec(".", KeyType.PERIOD, 0.10f),
            KeySpec("return", KeyType.ENTER, 0.12f),
        ),
    )

    /**
     * 数字页：拨号盘 3×4（主流九键数字形态），右侧/底行放常用运算符与返回。
     * 与九宫主区同为 4 列，切换时键位视觉更稳。
     */
    val numbers: List<List<KeySpec>> = listOf(
        listOf(
            ch("1"), ch("2"), ch("3"),
            ch("-", 1f),
        ),
        listOf(
            ch("4"), ch("5"), ch("6"),
            ch(".", 1f),
        ),
        listOf(
            ch("7"), ch("8"), ch("9"),
            KeySpec("⌫", KeyType.DELETE, 1f),
        ),
        listOf(
            KeySpec("ABC", KeyType.PAGE_LETTERS, 1f),
            KeySpec("符", KeyType.PAGE_SYM1, 1f),
            ch("0", 1.2f),
            KeySpec("空格", KeyType.SPACE, 1.5f),
            KeySpec("回车", KeyType.ENTER, 1.3f),
        ),
    )

    /**
     * 符号页 1：英文半角（对齐 Gboard ?123 符号区）。
     *
     * 必须能直接打出 URL / 路径 / 代码常用符：`/` `:` `.` `@` 等。
     * 中文全角标点挪到 SYM2「更多」。
     */
    val sym1: List<List<KeySpec>> = listOf(
        // Gboard 第一屏符号行：@#$%&-+()/
        listOf("@", "#", "$", "%", "&", "-", "+", "(", ")", "/").map { ch(it) },
        listOf("*", "\"", "'", ":", ";", "!", "?", "\\", "_").map { ch(it) },
        // `,` `.`/`。` 在字母底行/数字页已有；本页放更常用的顿号、省略号。
        listOf(
            ch("、"), ch("…"), ch("~"), ch("`"), ch("|"), ch("^"), ch("="),
            ch("<"), ch(">"),
            KeySpec("⌫", KeyType.DELETE, 1.5f),
        ),
        listOf(
            KeySpec("ABC", KeyType.PAGE_LETTERS, 0.12f),
            KeySpec("123", KeyType.PAGE_NUMBERS, 0.12f),
            KeySpec("空格", KeyType.SPACE, 0.40f),
            KeySpec("更多", KeyType.PAGE_SYM2, 0.14f),
            KeySpec("回车", KeyType.ENTER, 0.22f),
        ),
    )

    /**
     * 符号页 2：中文独有标点 + Gboard 二屏杂项（无 SYM1 半角成对重复）。
     *
     * 已去掉与 SYM1 成对的全角：，。；：？！（）～—“”‘’
     * `、` `…` 已上移 SYM1；此处补不常用符 ※ ✓。
     */
    val sym2: List<List<KeySpec>> = listOf(
        // 中文独有书名号/引号 + 不常用补位
        listOf("※", "✓", "《", "》", "【", "】", "「", "」", "『", "』").map { ch(it) },
        // Gboard 二屏：括号 + 数学/项目符
        listOf("[", "]", "{", "}", "°", "•", "√", "π", "÷", "×").map { ch(it) },
        listOf(
            ch("·"), ch("￥"), ch("€"), ch("£"), ch("±"), ch("≠"), ch("≈"), ch("∞"), ch("©"),
            KeySpec("⌫", KeyType.DELETE, 1.5f),
        ),
        listOf(
            KeySpec("ABC", KeyType.PAGE_LETTERS, 0.12f),
            KeySpec("123", KeyType.PAGE_NUMBERS, 0.12f),
            KeySpec("空格", KeyType.SPACE, 0.40f),
            KeySpec("返回", KeyType.PAGE_SYM1, 0.14f),
            KeySpec("回车", KeyType.ENTER, 0.22f),
        ),
    )

    /**
     * 九宫格（T9）：对齐搜狗「拼音九键显示分词」。
     *
     * ```
     *  分词  ABC  DEF   ⌫
     *  GHI   JKL  MNO   符
     *  PQRS  TUV  WXYZ  回车
     *  123  中/英  26   0   空格
     * ```
     *
     * · 分词键写入 `'`，强制音节边界（xi'an → 西安，而非 xian → 先）
     * · 2–9 只显示字母组（数字标无上屏能力，已去掉）；code 仍为数字进引擎
     * · `0` 仍直出数字；其它数字走「123」页
     */
    val t9: List<List<KeySpec>> = listOf(
        listOf(
            KeySpec("分词", KeyType.CHAR, 1f, "'", ""),
            KeySpec("ABC", KeyType.CHAR, 1f, "2", "ABC"),
            KeySpec("DEF", KeyType.CHAR, 1f, "3", "DEF"),
            KeySpec("⌫", KeyType.DELETE, 1f),
        ),
        listOf(
            KeySpec("GHI", KeyType.CHAR, 1f, "4", "GHI"),
            KeySpec("JKL", KeyType.CHAR, 1f, "5", "JKL"),
            KeySpec("MNO", KeyType.CHAR, 1f, "6", "MNO"),
            KeySpec("符", KeyType.PAGE_SYM1, 1f),
        ),
        listOf(
            KeySpec("PQRS", KeyType.CHAR, 1f, "7", "PQRS"),
            KeySpec("TUV", KeyType.CHAR, 1f, "8", "TUV"),
            KeySpec("WXYZ", KeyType.CHAR, 1f, "9", "WXYZ"),
            KeySpec("回车", KeyType.ENTER, 1f),
        ),
        listOf(
            KeySpec("123", KeyType.PAGE_NUMBERS, 0.95f),
            KeySpec("中/英", KeyType.LANG, 1f),
            KeySpec("26", KeyType.LAYOUT_SWITCH, 0.9f),
            KeySpec("0", KeyType.CHAR, 0.95f, "0", ""),
            KeySpec("空格", KeyType.SPACE, 2.2f),
        ),
    )

    fun rowsFor(page: Page): List<List<KeySpec>> = when (page) {
        Page.LETTERS -> letters
        Page.T9 -> t9
        Page.LETTERS_EN -> lettersEn
        Page.NUMBERS -> numbers
        Page.SYM1 -> sym1
        Page.SYM2 -> sym2
    }

    /** 中文标点（英文模式用半角）。 */
    val cnPunct = mapOf(
        "," to "，", "." to "。", "?" to "？", "!" to "！", ":" to "：", ";" to "；",
        "(" to "（", ")" to "）", "'" to "’", "\"" to "“",
    )
}
