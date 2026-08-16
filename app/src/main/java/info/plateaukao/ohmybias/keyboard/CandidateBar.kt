package info.plateaukao.ohmybias.keyboard

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import info.plateaukao.ohmybias.shared.SkinSettings

/// 候選字列：左側 composing 碼、右側水平捲動候選字／聯想詞。
/// 空閒時顯示目前輸入法模式＋sweetlime 工具列（組字/候選出現時自動隱藏）。
@SuppressLint("ViewConstructor")
class CandidateBar(context: Context) : FrameLayout(context) {

    companion object {
        const val BAR_HEIGHT_DP = 46f
    }

    var onSelect: ((Int) -> Unit)? = null
    /// 工具列按鈕動作（路由至 OhMyBiasImeService.handleKey）
    var onToolbarKey: ((KeyAction) -> Unit)? = null
    /// ✕ 關閉聯想列（不輸出任何字、回到工具列）
    var onDismissSuggestions: (() -> Unit)? = null

    private val density = context.resources.displayMetrics.density
    private fun dp(v: Float): Int = (v * density).toInt()

    private val composingLabel = TextView(context)
    private val scrollView = HorizontalScrollView(context)
    private val stack = LinearLayout(context)
    private val toolbarStack = LinearLayout(context)
    private var languageButton: TextView? = null

    /// 工具列項目：由 cskin 的 toolbarButtons 按鈕 ID 對應而來
    private class ToolbarItem(
        val text: String,
        val label: String,
        val action: KeyAction,
        val isLanguage: Boolean = false,
        /// 非 0 = 改用圖示（ImageView，tint 跟著 toolbarColor）而非文字字樣
        val iconRes: Int = 0,
    )

    /// 按鈕 ID → 動作（ID 定義同 sweetlime SkinSettings.TB_*；iOS 版因 extension API 缺失
    /// 略過的編輯類動作，Android 依原始定義實作）。null = 不可實作 → 空白佔位。
    /// （0 佔位符、6 剪貼本（無剪貼簿歷史 API，sweetlime 亦略過）、18-25/31 Hamster 專屬 → 空；
    /// 32 起是本家自訂 ID，同步定義於皮膚設計器 ohmybias-skin `data.js` TOOLBAR_ITEMS）
    private fun item(forButtonID: Int): ToolbarItem? = when (forButtonID) {
        1 -> ToolbarItem("設", "設定", KeyAction.OpenSettings)
        // U+2228 邏輯或 — 以數學軸垂直置中，與 ←/→ 同基準；
        // 原 U+2304 ⌄ 箭頭符貼著字面上緣畫，在列裡看起來偏高不置中
        2 -> ToolbarItem("∨", "收折鍵盤", KeyAction.DismissKeyboard)
        3 -> ToolbarItem("米", "中英切換", KeyAction.ToggleLanguage, isLanguage = true)
        4 -> ToolbarItem("簡", "簡繁切換", KeyAction.ToggleSimpTrad)
        5 -> ToolbarItem("♥︎", "常用語", KeyAction.ToggleToolbarPage(KeyboardView.PageKind.PHRASES))
        7 -> ToolbarItem("符", "符號面板", KeyAction.ToggleToolbarPage(KeyboardView.PageKind.SYMBOL_PANEL))
        8 -> ToolbarItem("☺︎", "Emoji", KeyAction.ToggleToolbarPage(KeyboardView.PageKind.EMOJI))
        9 -> {
            val page = if (SkinSettings.shared.keyboardLayout == "row") KeyboardView.PageKind.NUMBERS
                       else KeyboardView.PageKind.NUMERIC9
            ToolbarItem("123", "數字鍵盤", KeyAction.ToggleToolbarPage(page))
        }
        10 -> ToolbarItem("全", "全選", KeyAction.SelectAll)
        11 -> ToolbarItem("複", "複製", KeyAction.Copy)
        12 -> ToolbarItem("剪", "剪下", KeyAction.Cut)
        13 -> ToolbarItem("貼", "貼上", KeyAction.PasteClipboard)
        14 -> ToolbarItem("↶", "復原", KeyAction.Undo)
        15 -> ToolbarItem("↷", "重做", KeyAction.Redo)
        16 -> ToolbarItem("←", "游標左移", KeyAction.CursorLeft)
        17 -> ToolbarItem("→", "游標右移", KeyAction.CursorRight)
        26 -> ToolbarItem("顏", "顏文字", KeyAction.ToggleToolbarPage(KeyboardView.PageKind.KAOMOJIS))
        27 -> ToolbarItem("ㄅ", "注音查碼", KeyAction.EnterZhuyin)
        29 -> ToolbarItem("123", "九宮格數字", KeyAction.ToggleToolbarPage(KeyboardView.PageKind.NUMERIC9))
        30 -> ToolbarItem("符", "符號面板", KeyAction.ToggleToolbarPage(KeyboardView.PageKind.SYMBOL_PANEL))
        // 32 = 本家自訂（Hamster 用到 31 為止）：語音輸入 — 切到系統語音輸入法，iOS 版無此能力。
        // 麥克風沒有黑白的 Unicode 字（🎤/🎙 加 VS15 在 Android 一律走彩色 emoji 字型、
        // 不吃 toolbarColor），改用系統內建圖示 ic_btn_speak_now 上色。
        32 -> ToolbarItem("", "語音輸入", KeyAction.VoiceInput, iconRes = android.R.drawable.ic_btn_speak_now)
        else -> null
    }

    /// 語言鍵顯示目前輸入法：嘸蝦米 →「米」、英文 →「英」
    fun setEnglishMode(isEnglish: Boolean) {
        languageButton?.text = if (isEnglish) "英" else "米"
    }

    /// 上次顯示內容 — 相同就完全不動（IME 每個按鍵/每次切輸入框都會重設候選列）
    private var lastCandidates: List<String> = emptyList()
    private var lastSuggestions = false
    /// stack 目前作用中的子視圖數（多餘的隱藏備用，取代每鍵擊 removeAllViews + new TextView）
    private var activeStackViews = 0

    init {
        setBackgroundColor(KeyboardTheme.toolbarBackground)

        composingLabel.typeface = Typeface.MONOSPACE
        composingLabel.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15f)
        composingLabel.setTextColor(KeyboardTheme.textSub)
        addView(composingLabel, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            leftMargin = dp(10f)
        })

        scrollView.isHorizontalScrollBarEnabled = false
        stack.orientation = LinearLayout.HORIZONTAL
        stack.gravity = Gravity.CENTER_VERTICAL
        scrollView.addView(stack, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT))
        addView(scrollView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT).apply {
            leftMargin = dp(64f)
        })

        toolbarStack.orientation = LinearLayout.HORIZONTAL
        addView(toolbarStack, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT).apply {
            leftMargin = dp(8f); rightMargin = dp(8f)
        })
        // 照 cskin toolbarButtons 序列建構；做不到的按鈕 ID（含 0 佔位符）留空格
        for (id in SkinSettings.shared.toolbarButtons) {
            val item = item(forButtonID = id)
            if (item == null) {
                toolbarStack.addView(View(context), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f))
                continue
            }
            if (item.iconRes != 0) {
                val iv = ImageView(context)
                iv.setImageResource(item.iconRes)
                iv.imageTintList = android.content.res.ColorStateList.valueOf(KeyboardTheme.toolbarColor)
                iv.scaleType = ImageView.ScaleType.FIT_CENTER
                // 6dp 內縮 — 量過：圖示畫出來 50px 高，旁邊 19sp 的字樣 47px，看起來同一級
                val ip = dp(6f)
                iv.setPadding(ip, ip, ip, ip)
                iv.contentDescription = item.label
                iv.isClickable = true
                iv.setOnClickListener { onToolbarKey?.invoke(item.action) }
                toolbarStack.addView(iv, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f))
                continue
            }
            val b = TextView(context)
            b.text = item.text
            b.setTextSize(TypedValue.COMPLEX_UNIT_DIP, if (item.text.length > 1 && item.text != "123") 19f else 19f)
            b.setTextColor(KeyboardTheme.toolbarColor)
            b.gravity = Gravity.CENTER
            b.contentDescription = item.label
            b.isClickable = true
            b.setOnClickListener { onToolbarKey?.invoke(item.action) }
            toolbarStack.addView(b, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f))
            if (item.isLanguage) {
                languageButton = b
                // 長按米/英 → 系統輸入法選單（隱藏 🌐 鍵後仍可換輸入法）
                b.setOnLongClickListener { onToolbarKey?.invoke(KeyAction.ShowImePicker); true }
            }
        }
        applyComposingMargin()
        updateToolbarVisibility()
    }

    /// 空閒（無組字、無候選）才顯示工具列；候選捲動區與其互斥
    private fun updateToolbarVisibility() {
        val idle = composingLabel.text.isNullOrEmpty() && activeStackViews == 0
        toolbarStack.visibility = if (idle) View.VISIBLE else View.GONE
        scrollView.visibility = if (idle) View.GONE else View.VISIBLE
    }

    /// 對應 iOS 約束：候選捲動區起點 = composing 標籤右緣 + 8dp（標籤變長時跟著推移）
    private fun applyComposingMargin() {
        composingLabel.measure(
            View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED,
        )
        val m = dp(10f) + composingLabel.measuredWidth + dp(8f)
        (scrollView.layoutParams as? LayoutParams)?.let { lp ->
            if (lp.leftMargin != m) {
                lp.leftMargin = m
                scrollView.layoutParams = lp
            }
        }
    }

    fun setComposing(text: String) {
        if (composingLabel.text?.toString() == text) return  // 未變就不量測/不重排
        composingLabel.text = text
        applyComposingMargin()
        updateToolbarVisibility()
    }

    /// 從 stack 取第 index 個 TextView 重用，不夠才建（取代每鍵擊 removeAllViews + new）
    private fun obtainStackView(index: Int): TextView {
        while (stack.childCount <= index) {
            val v = TextView(context)
            v.gravity = Gravity.CENTER
            v.isClickable = true
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            lp.gravity = Gravity.CENTER_VERTICAL
            lp.marginEnd = dp(4f)
            stack.addView(v, lp)
        }
        val v = stack.getChildAt(index) as TextView
        v.visibility = View.VISIBLE
        return v
    }

    fun setCandidates(candidates: List<String>, suggestions: Boolean) {
        // 內容未變就完全不動 — refreshIdleBar 常以空列表重複呼叫
        if (suggestions == lastSuggestions && candidates == lastCandidates) return
        lastCandidates = ArrayList(candidates)
        lastSuggestions = suggestions

        scrollView.scrollTo(0, 0)
        var slot = 0

        // 聯想列開頭放 ✕ — 不需要聯想時一鍵關閉（不影響已輸出文字）
        if (suggestions && candidates.isNotEmpty()) {
            val x = obtainStackView(slot); slot += 1
            x.text = "✕"
            x.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16f)
            x.setTextColor(KeyboardTheme.textSub)
            x.setPadding(dp(10f), dp(4f), dp(10f), dp(4f))
            x.background = null
            x.contentDescription = "清除聯想"
            x.setOnClickListener { onDismissSuggestions?.invoke() }
        }

        // 候選 1–2 個時不顯示數字前綴（與 macOS 版一致的精簡顯示）
        val showIndex = candidates.size > 2 && !suggestions
        for ((i, c) in candidates.withIndex()) {
            val b = obtainStackView(slot); slot += 1
            b.text = if (showIndex && i < 9) "${i + 1} $c" else c
            b.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 20f)
            b.setTextColor(
                if (suggestions) 0xFF2F7CF6.toInt()
                else KeyboardTheme.candidateText
            )
            b.setPadding(dp(9f), dp(4f), dp(9f), dp(4f))
            b.contentDescription = null
            if (i == 0 && !suggestions && candidates.size > 2) {
                b.setTextColor(KeyboardTheme.candidateSelectedText)
                val bg = GradientDrawable()
                bg.setColor(KeyboardTheme.candidateSelectedBackground)
                bg.cornerRadius = dp(6f).toFloat()
                bg.setStroke(dp(KeyboardTheme.borderWidth).coerceAtLeast(1), KeyboardTheme.border)
                b.background = bg
            } else {
                b.background = null
            }
            val idx = i
            b.setOnClickListener { onSelect?.invoke(idx) }
        }

        activeStackViews = slot
        for (j in slot until stack.childCount) stack.getChildAt(j).visibility = View.GONE
        updateToolbarVisibility()
    }
}
