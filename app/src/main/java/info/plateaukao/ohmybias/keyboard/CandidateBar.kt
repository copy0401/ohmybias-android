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
    )

    /// Hamster 按鈕 ID → 本鍵盤可實作的項目；null = 不可實作 → 空白佔位。
    /// （4 簡繁、6 剪貼本、10-12/14-15 編輯、18-25/31 Hamster 專屬 → 空）
    private fun item(forButtonID: Int): ToolbarItem? = when (forButtonID) {
        1 -> ToolbarItem("設", "設定", KeyAction.OpenSettings)
        2 -> ToolbarItem("⌄", "收折鍵盤", KeyAction.DismissKeyboard)
        3 -> ToolbarItem("米", "中英切換", KeyAction.ToggleLanguage, isLanguage = true)
        5 -> ToolbarItem("♥︎", "常用語", KeyAction.ToggleToolbarPage(KeyboardView.PageKind.PHRASES))
        7 -> ToolbarItem("符", "符號面板", KeyAction.ToggleToolbarPage(KeyboardView.PageKind.SYMBOL_PANEL))
        8 -> ToolbarItem("☺︎", "Emoji", KeyAction.ToggleToolbarPage(KeyboardView.PageKind.EMOJI))
        9 -> {
            val page = if (SkinSettings.shared.keyboardLayout == "row") KeyboardView.PageKind.NUMBERS
                       else KeyboardView.PageKind.NUMERIC9
            ToolbarItem("123", "數字鍵盤", KeyAction.ToggleToolbarPage(page))
        }
        // 全選在 IME 無可靠 API — 依使用者決定，此位置固定放 ♥ 常用語
        10 -> ToolbarItem("♥︎", "常用語", KeyAction.ToggleToolbarPage(KeyboardView.PageKind.PHRASES))
        13 -> ToolbarItem("貼", "貼上", KeyAction.PasteClipboard)
        16 -> ToolbarItem("←", "游標左移", KeyAction.CursorLeft)
        17 -> ToolbarItem("→", "游標右移", KeyAction.CursorRight)
        26 -> ToolbarItem("顏", "顏文字", KeyAction.ToggleToolbarPage(KeyboardView.PageKind.KAOMOJIS))
        27 -> ToolbarItem("ㄅ", "注音查碼", KeyAction.EnterZhuyin)
        29 -> ToolbarItem("123", "九宮格數字", KeyAction.ToggleToolbarPage(KeyboardView.PageKind.NUMERIC9))
        30 -> ToolbarItem("符", "符號面板", KeyAction.ToggleToolbarPage(KeyboardView.PageKind.SYMBOL_PANEL))
        else -> null
    }

    /// 語言鍵顯示目前輸入法：嘸蝦米 →「米」、英文 →「英」
    fun setEnglishMode(isEnglish: Boolean) {
        languageButton?.text = if (isEnglish) "英" else "米"
    }

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
            val b = TextView(context)
            b.text = item.text
            b.setTextSize(TypedValue.COMPLEX_UNIT_DIP, if (item.text.length > 1 && item.text != "123") 19f else 19f)
            b.setTextColor(KeyboardTheme.toolbarColor)
            b.gravity = Gravity.CENTER
            b.contentDescription = item.label
            b.isClickable = true
            b.setOnClickListener { onToolbarKey?.invoke(item.action) }
            toolbarStack.addView(b, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f))
            if (item.isLanguage) languageButton = b
        }
        updateToolbarVisibility()
    }

    /// 空閒（無組字、無候選）才顯示工具列；候選捲動區與其互斥
    private fun updateToolbarVisibility() {
        val idle = composingLabel.text.isNullOrEmpty() && stack.childCount == 0
        toolbarStack.visibility = if (idle) View.VISIBLE else View.GONE
        scrollView.visibility = if (idle) View.GONE else View.VISIBLE
    }

    fun setComposing(text: String) {
        composingLabel.text = text
        updateToolbarVisibility()
    }

    fun setCandidates(candidates: List<String>, suggestions: Boolean) {
        stack.removeAllViews()
        scrollView.scrollTo(0, 0)

        // 候選 1–2 個時不顯示數字前綴（與 macOS 版一致的精簡顯示）
        val showIndex = candidates.size > 2 && !suggestions
        for ((i, c) in candidates.withIndex()) {
            val b = TextView(context)
            b.text = if (showIndex && i < 9) "${i + 1} $c" else c
            b.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 20f)
            b.setTextColor(
                if (suggestions) 0xFF2F7CF6.toInt()
                else KeyboardTheme.candidateText
            )
            b.gravity = Gravity.CENTER
            b.setPadding(dp(9f), dp(4f), dp(9f), dp(4f))
            if (i == 0 && !suggestions && candidates.size > 2) {
                b.setTextColor(KeyboardTheme.candidateSelectedText)
                val bg = GradientDrawable()
                bg.setColor(KeyboardTheme.candidateSelectedBackground)
                bg.cornerRadius = dp(6f).toFloat()
                bg.setStroke(dp(KeyboardTheme.borderWidth).coerceAtLeast(1), KeyboardTheme.border)
                b.background = bg
            }
            b.isClickable = true
            val idx = i
            b.setOnClickListener { onSelect?.invoke(idx) }
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            lp.gravity = Gravity.CENTER_VERTICAL
            lp.marginEnd = dp(4f)
            stack.addView(b, lp)
        }
        updateToolbarVisibility()
    }
}
