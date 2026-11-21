package com.lingxi.ime

import android.app.Activity
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast

/**
 * 词库管理（Android）—— 灵犀在搜狗式键盘之外**唯一多加的功能**。
 *
 * 能力全部来自引擎（与 Windows 词库窗同一套 C ABI / JNI）：
 *   · 搜索主库（前缀匹配，1ms 级）· 查看权重
 *   · 屏蔽（黑名单，永不出现）/ 置顶（白名单 +10M）/ 解除
 *   · 加入词库（只需输入中文，拼音由引擎反推）· 删除（只读安装下退化为用户层屏蔽）
 *
 * 设计原则：**不做需要用户配置才能用的开关**；每个动作都立即可见（列表标记 + 计数）。
 */
class WordActivity : Activity() {

    private lateinit var search: EditText
    private lateinit var list: ListView
    private lateinit var status: TextView
    private lateinit var adapter: ArrayAdapter<String>

    private var rows: List<Engine.Row> = emptyList()
    private var selected = -1
    private var black: List<String> = emptyList()
    private var white: List<String> = emptyList()
    private var mine: List<String> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "灵犀词库管理"

        search = EditText(this).apply {
            hint = "搜词或拼音（前缀匹配）"
            setSingleLine()
        }
        val searchBtn = Button(this).apply {
            text = "搜索"
            setOnClickListener { reload() }
        }
        status = TextView(this).apply {
            textSize = 13f
            setPadding(8, 6, 8, 6)
        }
        adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, ArrayList())
        list = ListView(this).apply {
            adapter = this@WordActivity.adapter
            setOnItemClickListener { _, _, pos, _ ->
                selected = pos
                status.text = buildStatus(rows.getOrNull(pos))
            }
        }

        fun btn(text: String, action: () -> Unit) = Button(this).apply {
            this.text = text
            setOnClickListener { action() }
        }

        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(btn("屏蔽") { mark(black = true) })
            addView(btn("置顶") { mark(black = false) })
            addView(btn("解除") { unmark() })
            addView(btn("加入") { addWord() })
            addView(btn("删除") { deleteWord() })
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(search, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(searchBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(status, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(list, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            addView(actions, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        setContentView(root)

        if (!Engine.loaded) {
            val db = DbLocator.resolve(this)
            if (db.exists()) Engine.load(db.absolutePath)
        }
        reload()
    }

    private fun reload() {
        val term = search.text.toString().trim()
        rows = Engine.dictRows(term, 100, 0)
        black = Engine.nameList(true)
        white = Engine.nameList(false)
        mine = Engine.userRows().map { it.value }
        adapter.clear()
        rows.forEach { r ->
            val tags = buildString {
                if (black.contains(r.value)) append("[禁] ")
                if (white.contains(r.value)) append("[顶] ")
                if (mine.contains(r.value)) append("[自] ")
            }
            adapter.add("$tags${r.value}   ${r.key}   w=${r.weight}")
        }
        adapter.notifyDataSetChanged()
        selected = -1
        status.text = "「${if (term.isEmpty()) "全库" else term}」共 ${Engine.dictCount(term)} 条" +
            " · 屏蔽 ${black.size} · 置顶 ${white.size} · 自加 ${mine.size}"
    }

    private fun buildStatus(row: Engine.Row?): String {
        if (row == null) return "未选中"
        val flags = buildList {
            if (black.contains(row.value)) add("已屏蔽")
            if (white.contains(row.value)) add("已置顶")
            if (mine.contains(row.value)) add("自加词")
        }.joinToString(" / ").ifEmpty { "普通词" }
        return "「${row.value}」${row.key} w=${row.weight} · $flags"
    }

    private fun current(): Engine.Row? = rows.getOrNull(selected)

    private fun mark(black: Boolean) {
        val row = current() ?: return toast("先在列表里选一个词")
        val ok = if (black) {
            Engine.nameAdd(true, row.value)
        } else {
            // 置顶前先解除屏蔽，否则加了权重也不会出现
            Engine.nameRemove(true, row.value)
            Engine.nameAdd(false, row.value)
        }
        toast(if (ok) (if (black) "已屏蔽「${row.value}」" else "已置顶「${row.value}」") else "操作失败")
        reload()
    }

    private fun unmark() {
        val row = current() ?: return toast("先在列表里选一个词")
        val a = Engine.nameRemove(true, row.value)
        val b = Engine.nameRemove(false, row.value)
        toast(if (a || b) "已解除「${row.value}」" else "本来就不在名单里")
        reload()
    }

    /** 加入词库：只需要输入中文，拼音由引擎逐字反推（`nativePinyinFor`）。 */
    private fun addWord() {
        val row = current()
        val word = row?.value ?: search.text.toString().trim()
        if (word.isEmpty()) return toast("先输入一个中文词")
        val pinyin = Engine.pinyinFor(word) ?: return toast("拼音算不出来（含非汉字？）")
        val ok = Engine.userAdd(pinyin, word)
        toast(if (ok) "已加入「$word」($pinyin)" else "加入失败")
        reload()
    }

    private fun deleteWord() {
        val row = current() ?: return toast("先在列表里选一个词")
        val pinyin = row.key.ifEmpty { Engine.pinyinFor(row.value) ?: "" }
        if (pinyin.isEmpty()) return toast("拿不到拼音，删不了")
        val ok = Engine.mainDelete(pinyin, row.value)
        // 只读安装下引擎会退化成"用户层屏蔽"，这里顺手把学习词一起清掉
        Engine.userRemove(pinyin, row.value)
        toast(if (ok) "已删除「${row.value}」（只读安装下等价于屏蔽，可点「加入」恢复）" else "删除失败")
        reload()
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }
}
