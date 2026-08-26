package info.plateaukao.ohmybias.keyboard

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.max

/// 實體鍵盤「游標旁浮動」模式的容器：鋪滿整個 IME 視窗（透明、本身不吃觸控 ——
/// service 以 onComputeInsets 把可觸區縮到氣泡本身，其餘觸控穿透到 app）。
/// 氣泡依游標錨點（CursorAnchorInfo）擺在游標正下方、放不下改放上方；沒有錨點
/// （部分 WebView／自繪欄位不回報）退回貼齊底部置中。toast 與氣泡相反方向擺放，互不遮蓋。
/// 底列模式也用這個容器承載 toast（鍵盤本體收起後原本的 toast 位置不存在）。
@SuppressLint("ViewConstructor")
class FloatingCandidateHost(context: Context) : FrameLayout(context) {

    val bubble = FloatingCandidateView(context)
    val toast = TextView(context)

    private val density = context.resources.displayMetrics.density
    private fun dp(v: Float): Int = (v * density).toInt()

    /// 游標錨點（本視圖座標）；null = 未知 → 退回底部置中
    private var anchor: RectF? = null

    init {
        clipChildren = false
        clipToPadding = false
        bubble.visibility = View.GONE
        addView(bubble, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))

        toast.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 20f)
        toast.setTextColor(Color.WHITE)
        val bg = GradientDrawable()
        bg.setColor(0xBF000000.toInt())
        bg.cornerRadius = dp(10f).toFloat()
        toast.background = bg
        toast.gravity = Gravity.CENTER
        toast.setPadding(dp(12f), dp(6f), dp(12f), dp(6f))
        toast.visibility = View.GONE
        addView(toast, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
    }

    fun setAnchor(r: RectF?) {
        val cur = anchor
        if (r == null && cur == null) return
        if (r != null && cur != null && r == cur) return
        anchor = if (r == null) null else RectF(r)
        requestLayout()
    }

    val hasAnchor: Boolean get() = anchor != null

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        super.onLayout(changed, l, t, r, b)  // 先照 FrameLayout 量好尺寸（放在左上），再依錨點移動
        place(bubble, preferBelow = true)
        place(toast, preferBelow = false)
    }

    private fun place(v: View, preferBelow: Boolean) {
        if (v.visibility != View.VISIBLE) return
        val w = v.measuredWidth
        val h = v.measuredHeight
        val gap = dp(6f)
        val usableBottom = height - paddingBottom
        val a = anchor
        val x: Int
        val y: Int
        if (a == null) {
            // 無錨點：貼底置中（toast 疊在氣泡上方）
            x = max(0, (width - w) / 2)
            val base = usableBottom - dp(8f)
            y = if (preferBelow) base - h
                else base - h - (if (bubble.visibility == View.VISIBLE) bubble.measuredHeight + gap else 0)
        } else {
            x = a.left.toInt().coerceIn(0, max(0, width - w))
            val below = a.bottom.toInt() + gap
            val above = a.top.toInt() - gap - h
            y = if (preferBelow) {
                if (below + h <= usableBottom) below else above.coerceAtLeast(0)
            } else {
                // 放不到游標上方（游標貼齊螢幕頂）改放下方：below 已是下方擺法的頂 y，
                // 不再加 h（加了會多推一個自身高度、被夾到底）
                if (above >= 0) above else below.coerceAtMost(usableBottom - h)
            }
        }
        v.layout(x, y, x + w, y + h)
    }

    /// 氣泡目前在視窗座標的矩形（供 onComputeInsets 設可觸區）；未顯示回 false
    fun bubbleRectInWindow(out: Rect): Boolean {
        if (bubble.visibility != View.VISIBLE || bubble.width == 0) return false
        val loc = IntArray(2)
        bubble.getLocationInWindow(loc)
        out.set(loc[0], loc[1], loc[0] + bubble.width, loc[1] + bubble.height)
        return true
    }
}

/// 浮動組字／候選氣泡：左側組字碼、右側候選（多於一個時帶數字前綴 —— 實體鍵盤靠數字鍵選字）。
/// 內容為空時整顆隱藏。寬度上限為容器的 85%，超出可橫向捲動。
@SuppressLint("ViewConstructor")
class FloatingCandidateView(context: Context) : HorizontalScrollView(context) {

    var onSelect: ((Int) -> Unit)? = null
    var onCommitComposing: (() -> Unit)? = null
    var onDismissSuggestions: (() -> Unit)? = null

    private val density = context.resources.displayMetrics.density
    private fun dp(v: Float): Int = (v * density).toInt()

    private val stack = LinearLayout(context)
    private val composingLabel = TextView(context)
    private var lastComposing = ""
    private var lastCandidates: List<String> = emptyList()
    private var lastSuggestions = false

    init {
        isHorizontalScrollBarEnabled = false
        val bg = GradientDrawable()
        bg.setColor(KeyboardTheme.toolbarBackground)
        bg.cornerRadius = dp(8f).toFloat()
        bg.setStroke(dp(KeyboardTheme.borderWidth).coerceAtLeast(1), KeyboardTheme.border)
        background = bg
        elevation = dp(4f).toFloat()
        setPadding(dp(4f), 0, dp(4f), 0)

        stack.orientation = LinearLayout.HORIZONTAL
        stack.gravity = Gravity.CENTER_VERTICAL
        stack.minimumHeight = dp(40f)
        addView(stack, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))

        composingLabel.typeface = Typeface.MONOSPACE
        composingLabel.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15f)
        composingLabel.setTextColor(KeyboardTheme.textSub)
        composingLabel.gravity = Gravity.CENTER_VERTICAL
        composingLabel.setPadding(dp(8f), 0, dp(8f), 0)
        composingLabel.isClickable = true
        composingLabel.setOnClickListener { onCommitComposing?.invoke() }
        stack.addView(composingLabel, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.MATCH_PARENT))
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        // 容器給的是 AT_MOST 整個寬度 — 壓到 85%，留邊讓氣泡看起來是浮在游標旁而非橫幅
        val cap = (MeasureSpec.getSize(widthMeasureSpec) * 0.85f).toInt()
        val spec = if (cap > 0) MeasureSpec.makeMeasureSpec(cap, MeasureSpec.AT_MOST) else widthMeasureSpec
        super.onMeasure(spec, heightMeasureSpec)
    }

    private fun obtainView(index: Int): TextView {
        // index 0 = 組字標籤；候選格從 1 起
        while (stack.childCount <= index) {
            val v = TextView(context)
            v.gravity = Gravity.CENTER
            v.isClickable = true
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            lp.gravity = Gravity.CENTER_VERTICAL
            stack.addView(v, lp)
        }
        val v = stack.getChildAt(index) as TextView
        v.visibility = View.VISIBLE
        return v
    }

    fun setContent(composing: String, candidates: List<String>, suggestions: Boolean) {
        if (composing == lastComposing && candidates == lastCandidates && suggestions == lastSuggestions) return
        lastComposing = composing
        lastCandidates = ArrayList(candidates)
        lastSuggestions = suggestions

        if (composing.isEmpty() && candidates.isEmpty()) {
            visibility = View.GONE
            return
        }
        visibility = View.VISIBLE
        scrollTo(0, 0)
        composingLabel.text = composing
        composingLabel.visibility = if (composing.isEmpty()) View.GONE else View.VISIBLE

        var slot = 1
        if (suggestions && candidates.isNotEmpty()) {
            val x = obtainView(slot); slot += 1
            x.text = "✕"
            x.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16f)
            x.setTextColor(KeyboardTheme.textSub)
            x.setPadding(dp(8f), dp(4f), dp(8f), dp(4f))
            x.background = null
            x.contentDescription = "清除聯想"
            x.setOnClickListener { onDismissSuggestions?.invoke() }
        }
        val showIndex = candidates.size > 1 && !suggestions
        for ((i, c) in candidates.withIndex()) {
            val b = obtainView(slot); slot += 1
            b.text = if (showIndex && i < 10) "${(i + 1) % 10} $c" else c
            b.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 20f)
            b.setPadding(dp(8f), dp(4f), dp(8f), dp(4f))
            b.contentDescription = null
            if (i == 0 && !suggestions && candidates.size > 1) {
                b.setTextColor(KeyboardTheme.candidateSelectedText)
                val bg = GradientDrawable()
                bg.setColor(KeyboardTheme.candidateSelectedBackground)
                bg.cornerRadius = dp(6f).toFloat()
                bg.setStroke(dp(KeyboardTheme.borderWidth).coerceAtLeast(1), KeyboardTheme.border)
                b.background = bg
            } else {
                b.setTextColor(if (suggestions) 0xFF2F7CF6.toInt() else KeyboardTheme.candidateText)
                b.background = null
            }
            val idx = i
            b.setOnClickListener { onSelect?.invoke(idx) }
        }
        for (j in slot until stack.childCount) stack.getChildAt(j).visibility = View.GONE
        requestLayout()
    }
}
