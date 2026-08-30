package info.plateaukao.ohmybias

import android.app.Activity
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Build
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import info.plateaukao.ohmybias.android.Prefs
import info.plateaukao.ohmybias.keyboard.ToolbarItems
import info.plateaukao.ohmybias.shared.SkinSettings

/// 自訂工具列 — 設定頁「主題」下的獨立畫面，操作模型同鍵盤外觀編輯器（ohmybias-skin）
/// 的工具列面板：上方固定 10 格，點一格選取，再從下方按鈕表點要放的按鈕（選取自動
/// 前進到下一格）。每次指定立即寫入 Prefs.toolbarButtons（覆寫皮膚的 toolbarButtons），
/// 鍵盤同 process 立即重建；「還原預設工具列」清掉覆寫、回到皮膚定義。
class ToolbarSettingsActivity : Activity() {

    private lateinit var strip: LinearLayout
    private lateinit var grid: LinearLayout
    private lateinit var status: TextView
    private lateinit var resetButton: Button

    /// 10 格內容（不足補 0 佔位）
    private val slots = IntArray(ToolbarItems.SLOT_COUNT)
    private var selected = 0

    private fun dp(v: Float): Int = (v * resources.displayMetrics.density).toInt()

    private fun themeColor(attr: Int): Int {
        val tv = TypedValue()
        theme.resolveAttribute(attr, tv, true)
        return if (tv.resourceId != 0) resources.getColor(tv.resourceId, theme) else tv.data
    }

    private val accent by lazy { themeColor(android.R.attr.colorAccent) }
    private val primary by lazy { themeColor(android.R.attr.textColorPrimary) }
    private val secondary by lazy { themeColor(android.R.attr.textColorSecondary) }
    private fun alpha(color: Int, a: Int): Int = (color and 0xFFFFFF) or (a shl 24)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "自訂工具列"
        loadSlots()

        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setPadding(dp(16f), dp(8f), dp(16f), dp(24f))

        root.addView(sectionTitle("工具列（10 格）"))
        root.addView(footnote("點一格，再從下方選要放的按鈕。空白佔位＝留空格。改動立即生效。"))

        strip = LinearLayout(this)
        strip.orientation = LinearLayout.HORIZONTAL
        root.addView(strip, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56f)).apply {
            topMargin = dp(8f)
        })
        status = footnote("")
        root.addView(status)

        grid = LinearLayout(this)
        grid.orientation = LinearLayout.VERTICAL
        root.addView(grid, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(16f)
        })

        resetButton = compactButton("還原預設工具列") {
            Prefs.toolbarButtons = null
            loadSlots()
            selected = 0
            refresh()
        }
        root.addView(resetButton, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(24f)
        })

        val scroll = ScrollView(this)
        scroll.addView(root)
        if (Build.VERSION.SDK_INT >= 30) {
            scroll.setOnApplyWindowInsetsListener { v, insets ->
                val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
                WindowInsets.CONSUMED
            }
        }
        setContentView(scroll)
        buildGrid()
        refresh()
    }

    // MARK: - 狀態

    private fun loadSlots() {
        val cur = SkinSettings.shared.toolbarButtons
        for (i in slots.indices) slots[i] = cur.getOrElse(i) { ToolbarItems.PLACEHOLDER }
    }

    private fun assign(id: Int) {
        slots[selected] = id
        Prefs.toolbarButtons = slots.toList()
        selected = minOf(selected + 1, ToolbarItems.SLOT_COUNT - 1)
        refresh()
    }

    private fun refresh() {
        val custom = Prefs.toolbarButtons != null
        status.text = if (custom) "目前：自訂" else "目前：跟隨主題「${SkinSettings.shared.skinName}」"
        resetButton.visibility = if (custom) View.VISIBLE else View.GONE
        rebuildStrip()
    }

    // MARK: - 10 格

    private fun rebuildStrip() {
        strip.removeAllViews()
        for (i in slots.indices) {
            val on = i == selected
            val cell = FrameLayout(this)
            val supported = slots[i] == ToolbarItems.PLACEHOLDER || ToolbarItems.item(slots[i]) != null
            val bg = GradientDrawable().apply {
                cornerRadius = dp(8f).toFloat()
                setColor(if (on) alpha(accent, 0x33) else Color.TRANSPARENT)
                setStroke(dp(if (on) 2f else 1f), if (on) accent else alpha(secondary, 0x66))
            }
            cell.background = bg
            cell.isClickable = true
            cell.contentDescription = "第 ${i + 1} 格：${ToolbarItems.label(slots[i])}"
            addGlyph(cell, slots[i], if (supported) primary else alpha(secondary, 0x55), dp(22f))
            cell.setOnClickListener { selected = i; rebuildStrip() }
            strip.addView(cell, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply {
                if (i > 0) marginStart = dp(4f)
            })
        }
    }

    /// 把格內圖樣加進 parent 並置中：有圖示用固定 iconBox 大小的 ImageView（tint）、
    /// 文字鍵用 TextView 撐滿；佔位顯示「·」
    private fun addGlyph(parent: FrameLayout, id: Int, color: Int, iconBox: Int) {
        val item = ToolbarItems.item(id)
        if (item != null && item.iconRes != 0) {
            val iv = ImageView(this)
            iv.setImageResource(item.iconRes)
            iv.imageTintList = ColorStateList.valueOf(color)
            iv.scaleType = ImageView.ScaleType.FIT_CENTER
            parent.addView(iv, FrameLayout.LayoutParams(iconBox, iconBox, Gravity.CENTER))
            return
        }
        val t = TextView(this)
        t.text = item?.text ?: "·"
        t.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 19f)
        t.setTextColor(color)
        t.gravity = Gravity.CENTER
        parent.addView(t, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }

    // MARK: - 按鈕表（4 欄）

    private fun buildGrid() {
        grid.removeAllViews()
        val columns = 4
        val ids = ToolbarItems.selectable
        var row: LinearLayout? = null
        for ((n, id) in ids.withIndex()) {
            if (n % columns == 0) {
                row = LinearLayout(this).also { r ->
                    r.orientation = LinearLayout.HORIZONTAL
                    grid.addView(r, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                        if (n > 0) topMargin = dp(8f)
                    })
                }
            }
            row!!.addView(optionCell(id), LinearLayout.LayoutParams(0, dp(68f), 1f).apply {
                if (n % columns > 0) marginStart = dp(8f)
            })
        }
        // 最後一列補空位，讓每格等寬
        val rest = (columns - ids.size % columns) % columns
        repeat(rest) { row!!.addView(View(this), LinearLayout.LayoutParams(0, dp(68f), 1f).apply { marginStart = dp(8f) }) }
    }

    private fun optionCell(id: Int): View {
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.gravity = Gravity.CENTER
        val outline = GradientDrawable().apply {
            cornerRadius = dp(8f).toFloat()
            setColor(Color.TRANSPARENT)
            setStroke(dp(1f), alpha(secondary, 0x66))
        }
        val mask = GradientDrawable().apply { setColor(Color.WHITE); cornerRadius = dp(8f).toFloat() }
        box.background = RippleDrawable(ColorStateList.valueOf(alpha(accent, 0x33)), outline, mask)
        box.isClickable = true
        box.contentDescription = ToolbarItems.label(id)

        val glyphBox = FrameLayout(this)
        addGlyph(glyphBox, id, primary, dp(26f))
        box.addView(glyphBox, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(34f)))

        val label = TextView(this)
        label.text = ToolbarItems.label(id)
        label.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 11f)
        label.setTextColor(secondary)
        label.gravity = Gravity.CENTER
        label.maxLines = 1
        box.addView(label, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(2f)
        })
        box.setOnClickListener { assign(id) }
        return box
    }

    // MARK: - UI helpers（同 MainActivity 風格）

    private fun sectionTitle(text: String): TextView {
        val t = TextView(this)
        t.text = text
        t.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 17f)
        t.typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        t.setTextColor(accent)
        t.setPadding(0, dp(22f), 0, dp(6f))
        return t
    }

    private fun footnote(text: String): TextView {
        val t = TextView(this)
        t.text = text
        t.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13f)
        t.setTextColor(secondary)
        t.setPadding(0, dp(2f), 0, dp(2f))
        return t
    }

    private fun compactButton(title: String, onClick: () -> Unit): Button {
        val radius = dp(8f).toFloat()
        val outline = GradientDrawable().apply {
            setColor(Color.TRANSPARENT)
            cornerRadius = radius
            setStroke(dp(1f), alpha(accent, 0x99))
        }
        val mask = GradientDrawable().apply { setColor(Color.WHITE); cornerRadius = radius }
        val ripple = RippleDrawable(ColorStateList.valueOf(alpha(accent, 0x33)), outline, mask)
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
