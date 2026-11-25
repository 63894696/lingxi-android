package com.lingxi.ime

import android.app.Activity
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.KeyEvent
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

/**
 * 灵犀造词（Android）—— 对齐 Win quick_add_window / Linux prisir_quick_add。
 *
 * 词条 → 自动拆简拼（可手改）→ 指定候选位 → 写 user_word（全拼主行+简拼别名）。
 */
class QuickAddActivity : Activity() {

    private lateinit var eWord: EditText
    private lateinit var eCode: EditText
    private lateinit var ePos: EditText
    private lateinit var chkMore: CheckBox
    private lateinit var status: TextView
    private var codeAuto = true
    private var setting = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "灵犀造词"

        if (!Engine.loaded) {
            val db = DbLocator.resolve(this)
            if (db.exists()) Engine.load(db.absolutePath)
        }

        fun label(t: String) = TextView(this).apply {
            text = t
            textSize = 15f
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 12, 0)
        }

        eWord = EditText(this).apply {
            hint = "输入或粘贴中文词条"
            setSingleLine()
            addTextChangedListener(simpleWatcher { if (!setting) maybeAutofill() })
        }
        eCode = EditText(this).apply {
            hint = "编码"
            setSingleLine()
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
            addTextChangedListener(simpleWatcher {
                if (!setting) codeAuto = false
            })
        }
        ePos = EditText(this).apply {
            setText("1")
            setSingleLine()
            inputType = InputType.TYPE_CLASS_NUMBER
            setEms(3)
        }
        chkMore = CheckBox(this).apply { text = "继续造词" }
        status = TextView(this).apply {
            textSize = 13f
            setPadding(0, 8, 0, 8)
        }

        val rowWord = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(label("词条"), LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(eWord, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        val rowCode = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(label("编码"), LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(eCode, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(TextView(this@QuickAddActivity).apply {
                text = "自动拆简拼，可手改"
                textSize = 12f
            })
        }
        val rowPos = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(label("位置"), LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(ePos, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(TextView(this@QuickAddActivity).apply {
                text = "候选第几位(1 起)"
                textSize = 12f
                setPadding(8, 0, 8, 0)
            })
            addView(chkMore)
        }
        val rowBtn = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            addView(Button(this@QuickAddActivity).apply {
                text = "取消"
                setOnClickListener { finish() }
            })
            addView(Button(this@QuickAddActivity).apply {
                text = "保存"
                setOnClickListener { onSave() }
            })
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 24, 24, 24)
            addView(rowWord)
            addView(rowCode)
            addView(rowPos)
            addView(status)
            addView(TextView(this@QuickAddActivity).apply {
                text = "Enter 保存 · 返回键退出"
                textSize = 12f
            })
            addView(rowBtn)
        }
        setContentView(root)

        // 剪贴板中文预填
        val clip = (getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager)
            ?.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString()?.trim().orEmpty()
        if (clip.isNotEmpty() && clip.length <= 30 && clip.any { it in '\u4e00'..'\u9fff' }) {
            setting = true
            eWord.setText(clip)
            setting = false
            maybeAutofill()
        }
        eWord.requestFocus()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_ENTER) {
            onSave()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun maybeAutofill() {
        if (!codeAuto) return
        val word = eWord.text.toString().trim()
        if (word.isEmpty() || !Engine.loaded) return
        val code = Engine.autoCode(word) ?: return
        if (code != eCode.text.toString()) {
            setting = true
            eCode.setText(code)
            setting = false
        }
    }

    private fun onSave() {
        if (!Engine.loaded) {
            status.text = "引擎未就绪"
            return
        }
        val word = eWord.text.toString().trim()
        val given = eCode.text.toString().filter { it.isLetter() }.lowercase()
        val pos = ePos.text.toString().toLongOrNull() ?: 1L
        try {
            val code = Engine.addWordAuto(word, given.ifEmpty { null }, pos)
            status.text = "已加入：$word → $code（候选第 ${pos.coerceIn(1, 50)} 位）"
            Toast.makeText(this, "已加入 $word", Toast.LENGTH_SHORT).show()
            if (chkMore.isChecked) {
                setting = true
                eWord.setText("")
                eCode.setText("")
                setting = false
                codeAuto = true
                eWord.requestFocus()
            } else {
                finish()
            }
        } catch (t: Throwable) {
            status.text = t.message ?: "保存失败"
        }
    }

    private fun simpleWatcher(onChange: () -> Unit) = object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        override fun afterTextChanged(s: Editable?) { onChange() }
    }
}
