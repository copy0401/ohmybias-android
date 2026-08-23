package info.plateaukao.ohmybias

import android.app.Activity
import android.app.AlertDialog
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Bundle
import android.text.Editable
import android.text.InputFilter
import android.text.InputType
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import info.plateaukao.ohmybias.shared.CINTable
import info.plateaukao.ohmybias.shared.UserPhrases

/// 常用語設定 — 全螢幕編輯器（取代原本的對話框）。
/// 一列一詞：詞 ＋ 自訂組字碼 ＋ 刪除；組字碼即時對照字表：
/// 未被使用 → 打碼直接出本詞；已有候選 → 本詞排在既有候選之後（列出撞到的字）。
/// 組字碼欄用 visiblePassword 型別 — IME 對密碼類欄位走暫時英文直通，打碼才不會被組成中文。
class UserPhrasesActivity : Activity() {

    private lateinit var rows: LinearLayout
    private lateinit var scroll: ScrollView
    private val rowViews = ArrayList<Row>()

    /// 對照用字表 — 只用 lookup（字表本身的候選），不含捷徑
    private val table: CINTable by lazy { CINTable().also { it.reload() } }

    private var original: List<UserPhrases.Entry> = emptyList()

    private fun dp(v: Float): Int = (v * resources.displayMetrics.density).toInt()

    private fun themeColor(attr: Int): Int {
        val tv = TypedValue()
        theme.resolveAttribute(attr, tv, true)
        return if (tv.resourceId != 0) resources.getColor(tv.resourceId, theme) else tv.data
    }

    private val colorOk = 0xFF2E7D32.toInt()
    private val colorWarn = 0xFFEF6C00.toInt()
    private val colorErr = 0xFFC62828.toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "常用語設定"

        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL

        scroll = ScrollView(this)
        scroll.isFillViewport = true
        val body = LinearLayout(this)
        body.orientation = LinearLayout.VERTICAL
        body.setPadding(dp(16f), dp(8f), dp(16f), dp(24f))

        body.addView(footnote(
            "常用語顯示於鍵盤 ♥ 面板；打詞首字也會出現聯想。\n" +
            "設了「組字碼」就能直接用鍵盤打碼叫出，不必開面板。碼已被字表使用時，本詞排在既有候選之後。"
        ))

        rows = LinearLayout(this)
        rows.orientation = LinearLayout.VERTICAL
        body.addView(rows)

        val add = compactButton("＋ 新增常用語") { addRow(UserPhrases.Entry(""), focus = true) }
        val addLp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        addLp.topMargin = dp(12f)
        body.addView(add, addLp)

        scroll.addView(body, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        // 底部列：取消／儲存
        val bar = LinearLayout(this)
        bar.orientation = LinearLayout.HORIZONTAL
        bar.gravity = Gravity.END
        bar.setPadding(dp(16f), dp(8f), dp(16f), dp(8f))
        bar.setBackgroundColor(themeColor(android.R.attr.colorBackground))
        bar.elevation = dp(8f).toFloat()
        val cancel = compactButton("取消") { confirmDiscard() }
        val save = compactButton("儲存") { save() }
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.marginStart = dp(8f)
        bar.addView(cancel, lp)
        bar.addView(save, LinearLayout.LayoutParams(lp))
        root.addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        setContentView(root)

        UserPhrases.shared.reload()
        original = UserPhrases.shared.entries
        for (e in original) addRow(e, focus = false)
        if (original.isEmpty()) addRow(UserPhrases.Entry(""), focus = false)
    }

    // MARK: - 列

    private inner class Row(val container: View, val phrase: EditText, val code: EditText, val status: TextView) {
        fun entry(): UserPhrases.Entry? {
            val p = phrase.text.toString().trim()
            if (p.isEmpty()) return null
            val c = code.text.toString().trim().lowercase().ifEmpty { null }
            return UserPhrases.Entry(p, c)
        }
        /// 組字碼欄有字但不合法
        val codeInvalid: Boolean
            get() = code.text.toString().trim().let { it.isNotEmpty() && !UserPhrases.isValidCode(it) }
    }

    private fun addRow(entry: UserPhrases.Entry, focus: Boolean) {
        val container = LinearLayout(this)
        container.orientation = LinearLayout.VERTICAL
        container.setPadding(0, dp(6f), 0, dp(6f))

        val line = LinearLayout(this)
        line.orientation = LinearLayout.HORIZONTAL
        line.gravity = Gravity.CENTER_VERTICAL

        val phrase = EditText(this)
        phrase.hint = "常用語"
        phrase.setText(entry.phrase)
        phrase.inputType = InputType.TYPE_CLASS_TEXT
        phrase.maxLines = 1
        phrase.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16f)
        line.addView(phrase, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        val code = EditText(this)
        code.hint = "組字碼"
        code.setText(entry.code ?: "")
        // visiblePassword：IME 對密碼類欄位暫時切英文直通；不要自動完成／大寫
        code.inputType = InputType.TYPE_CLASS_TEXT or
            InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD or
            InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        code.typeface = Typeface.MONOSPACE
        code.maxLines = 1
        code.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16f)
        code.filters = arrayOf(InputFilter.LengthFilter(8), InputFilter { src, start, end, _, _, _ ->
            // 一律小寫；不合法字元留著讓狀態列指出來（不吞，使用者才知道打錯了什麼）
            val s = src.subSequence(start, end).toString()
            val lower = s.lowercase()
            if (lower == s) null else lower
        })
        val codeLp = LinearLayout.LayoutParams(dp(104f), ViewGroup.LayoutParams.WRAP_CONTENT)
        codeLp.marginStart = dp(8f)
        line.addView(code, codeLp)

        val del = TextView(this)
        del.text = "✕"
        del.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 18f)
        del.setTextColor(themeColor(android.R.attr.textColorSecondary))
        del.gravity = Gravity.CENTER
        del.contentDescription = "刪除"
        val delLp = LinearLayout.LayoutParams(dp(40f), dp(40f))
        delLp.marginStart = dp(4f)
        line.addView(del, delLp)

        container.addView(line, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val status = TextView(this)
        status.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12f)
        status.setPadding(dp(4f), 0, 0, 0)
        status.visibility = View.GONE
        container.addView(status, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val row = Row(container, phrase, code, status)
        rowViews.add(row)
        rows.addView(container)

        del.setOnClickListener {
            rowViews.remove(row)
            rows.removeView(container)
            refreshAllStatus()  // 同碼提示可能因此消失
        }
        code.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) { refreshAllStatus() }
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })
        refreshStatus(row)

        if (focus) {
            phrase.requestFocus()
            scroll.post { scroll.fullScroll(View.FOCUS_DOWN); phrase.requestFocus() }
        }
    }

    // MARK: - 組字碼狀態

    private fun refreshAllStatus() { for (r in rowViews) refreshStatus(r) }

    private fun refreshStatus(row: Row) {
        val code = row.code.text.toString().trim().lowercase()
        if (code.isEmpty()) { row.status.visibility = View.GONE; return }
        row.status.visibility = View.VISIBLE

        if (!UserPhrases.isValidCode(code)) {
            row.status.setTextColor(colorErr)
            row.status.text = if (code.startsWith(",,")) "✗ ,, 是指令前綴，不能當組字碼"
            else "✗ 只能用 a–z 及 , . ' [ ]"
            return
        }

        // 同碼的其他常用語 — 都會列出（依列表順序）
        val sameCode = rowViews.filter { it !== row && it.code.text.toString().trim().lowercase() == code }
            .mapNotNull { it.phrase.text.toString().trim().ifEmpty { null } }

        val existing = if (table.isEmpty) emptyList() else table.lookup(code)
        val sb = StringBuilder()
        if (existing.isEmpty()) {
            row.status.setTextColor(colorOk)
            sb.append("✓ 未被使用 — 打「").append(code).append("」直接出現本詞")
        } else {
            row.status.setTextColor(colorWarn)
            val shown = existing.take(6).joinToString(" ")
            sb.append("⚠ 已有候選 ").append(shown)
            if (existing.size > 6) sb.append(" …（共 ").append(existing.size).append(" 個）")
            sb.append(" — 本詞排在其後")
        }
        if (sameCode.isNotEmpty()) {
            sb.append("；與「").append(sameCode.joinToString("」「")).append("」同碼，都會列出")
        }
        row.status.text = sb
    }

    // MARK: - 儲存／取消

    private fun currentEntries(): List<UserPhrases.Entry> = rowViews.mapNotNull { it.entry() }

    private fun isDirty(): Boolean = currentEntries() != original

    private fun save() {
        val bad = rowViews.firstOrNull { it.codeInvalid && it.entry() != null }
        if (bad != null) {
            bad.code.requestFocus()
            Toast.makeText(this, "有組字碼不合法，請修正後再儲存", Toast.LENGTH_SHORT).show()
            return
        }
        UserPhrases.shared.save(currentEntries())
        // 鍵盤同 process — 下次進輸入框重載字表快照，捷徑立即可用
        CINTable.bumpGeneration()
        finish()
    }

    private fun confirmDiscard() {
        if (!isDirty()) { finish(); return }
        AlertDialog.Builder(this)
            .setMessage("尚未儲存的變更會遺失")
            .setPositiveButton("放棄變更") { _, _ -> finish() }
            .setNegativeButton("繼續編輯", null)
            .show()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        confirmDiscard()
    }

    // MARK: - UI helpers（同 MainActivity 風格）

    private fun footnote(text: String): TextView {
        val t = TextView(this)
        t.text = text
        t.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13f)
        t.setTextColor(themeColor(android.R.attr.textColorSecondary))
        t.setPadding(0, dp(2f), 0, dp(8f))
        return t
    }

    private fun compactButton(title: String, onClick: () -> Unit): Button {
        val accent = themeColor(android.R.attr.colorAccent)
        val radius = dp(8f).toFloat()
        val outline = GradientDrawable().apply {
            setColor(Color.TRANSPARENT)
            cornerRadius = radius
            setStroke(dp(1f), (accent and 0xFFFFFF) or (0x99 shl 24))
        }
        val mask = GradientDrawable().apply {
            setColor(Color.WHITE)
            cornerRadius = radius
        }
        val ripple = RippleDrawable(ColorStateList.valueOf((accent and 0xFFFFFF) or (0x33 shl 24)), outline, mask)
        val b = Button(this)
        b.text = title
        b.isAllCaps = false
        b.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14f)
        b.setTextColor(accent)
        b.background = ripple
        b.stateListAnimator = null
        b.minWidth = 0; b.minimumWidth = 0
        b.minHeight = 0; b.minimumHeight = dp(40f)
        b.setPadding(dp(14f), 0, dp(14f), 0)
        b.setOnClickListener { onClick() }
        return b
    }
}
