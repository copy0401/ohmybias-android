package info.plateaukao.ohmybias.keyboard

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/// 分類面板 — sweetlime 符號/Emoji/顏文字鍵盤：左欄分類、右欄格狀內容、底列返回/退格。
/// 顏色由 cskin 的 panel 色鍵控制；未設定時跟隨系統深淺色。
@SuppressLint("ViewConstructor")
class CollectionPanelView(
    context: Context,
    private val sections: List<Pair<String, List<String>>>,
    private val itemFontSize: Float,
    private val showSettings: Boolean = false,
) : FrameLayout(context) {

    var onInsert: ((String) -> Unit)? = null
    var onBack: (() -> Unit)? = null
    var onBackspace: (() -> Unit)? = null
    var onSettings: (() -> Unit)? = null

    private val density = context.resources.displayMetrics.density
    private fun dp(v: Float): Int = (v * density).toInt()

    private var currentSection = 0
    private val categoryButtons = mutableListOf<TextView>()
    private val contentScroll = ScrollView(context)
    private val flow = FlowLayout(context)

    init {
        setBackgroundColor(KeyboardTheme.panelRightBackground)

        // 左欄分類
        val categoryScroll = ScrollView(context)
        categoryScroll.isVerticalScrollBarEnabled = false
        categoryScroll.setBackgroundColor(KeyboardTheme.panelLeftBackground)
        val categoryStack = LinearLayout(context)
        categoryStack.orientation = LinearLayout.VERTICAL
        categoryStack.setPadding(dp(4f), dp(4f), dp(4f), dp(48f))
        categoryScroll.addView(categoryStack, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        addView(categoryScroll, LayoutParams(dp(64f), LayoutParams.MATCH_PARENT))

        for ((i, section) in sections.withIndex()) {
            val b = TextView(context)
            b.text = section.first
            b.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15f)
            b.gravity = Gravity.CENTER
            b.isClickable = true
            b.setOnClickListener {
                currentSection = i
                highlightCategory()
                reloadContent()
            }
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(34f))
            lp.bottomMargin = dp(2f)
            categoryStack.addView(b, lp)
            categoryButtons.add(b)
        }

        // 常用語面板：分類下方的「設定」— 開設定頁的常用語編輯對話框
        if (showSettings) {
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(34f))
            lp.topMargin = dp(6f)
            categoryStack.addView(bottomButton("設定") { onSettings?.invoke() }, lp)
        }

        // 右欄內容格
        contentScroll.isVerticalScrollBarEnabled = true
        flow.setPadding(dp(6f), dp(6f), dp(6f), dp(48f))  // 底部留空避免被浮動退格鍵遮住
        contentScroll.addView(flow, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        addView(contentScroll, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT).apply {
            leftMargin = dp(64f)
        })

        // 底列：返回＋退格
        addView(bottomButton("返回") { onBack?.invoke() }, LayoutParams(dp(56f), dp(40f)).apply {
            gravity = Gravity.BOTTOM or Gravity.START
            leftMargin = dp(4f); bottomMargin = dp(4f)
        })
        addView(bottomButton("⌫") { onBackspace?.invoke() }, LayoutParams(dp(56f), dp(40f)).apply {
            gravity = Gravity.BOTTOM or Gravity.END
            rightMargin = dp(4f); bottomMargin = dp(4f)
        })

        highlightCategory()
        reloadContent()
    }

    private fun bottomButton(title: String, onClick: () -> Unit): TextView {
        val b = TextView(context)
        b.text = title
        b.setTextSize(TypedValue.COMPLEX_UNIT_DIP, KeyboardTheme.systemFontSize + 2)
        b.setTextColor(KeyboardTheme.textSystem)
        b.gravity = Gravity.CENTER
        val bg = GradientDrawable()
        bg.setColor(KeyboardTheme.keySystem)
        bg.cornerRadius = KeyboardTheme.cornerRadius * density
        bg.setStroke(dp(KeyboardTheme.borderWidth).coerceAtLeast(1), KeyboardTheme.systemBorder)
        b.background = bg
        b.isClickable = true
        b.setOnClickListener { onClick() }
        return b
    }

    private fun highlightCategory() {
        for ((i, b) in categoryButtons.withIndex()) {
            val selected = i == currentSection
            if (selected) {
                val bg = GradientDrawable()
                bg.setColor(KeyboardTheme.panelCategoryHighlight)
                bg.cornerRadius = dp(6f).toFloat()
                b.background = bg
                b.setTextColor(KeyboardTheme.panelCategorySelectedText)
            } else {
                b.background = null
                b.setTextColor(KeyboardTheme.panelText)
            }
        }
    }

    private fun reloadContent() {
        flow.removeAllViews()
        contentScroll.scrollTo(0, 0)
        for (item in sections[currentSection].second) {
            val cell = TextView(context)
            cell.text = item
            cell.setTextSize(TypedValue.COMPLEX_UNIT_DIP, itemFontSize)
            cell.setTextColor(KeyboardTheme.panelText)
            cell.gravity = Gravity.CENTER
            cell.setPadding(dp(8f), dp(4f), dp(8f), dp(4f))
            cell.minWidth = dp(40f)
            cell.minHeight = dp(40f)
            cell.isClickable = true
            cell.setOnClickListener { onInsert?.invoke(item) }
            flow.addView(cell)
        }
        flow.requestLayout()
    }

    /// 簡易流式排版（對應 UICollectionViewFlowLayout 自動尺寸）
    private class FlowLayout(context: Context) : ViewGroup(context) {
        private val spacing = (4 * context.resources.displayMetrics.density).toInt()

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val w = MeasureSpec.getSize(widthMeasureSpec)
            val innerW = w - paddingLeft - paddingRight
            var x = 0; var y = 0; var rowH = 0
            for (i in 0 until childCount) {
                val c = getChildAt(i)
                c.measure(MeasureSpec.makeMeasureSpec(innerW, MeasureSpec.AT_MOST), MeasureSpec.UNSPECIFIED)
                if (x > 0 && x + c.measuredWidth > innerW) {
                    x = 0; y += rowH + spacing; rowH = 0
                }
                x += c.measuredWidth + spacing
                if (c.measuredHeight > rowH) rowH = c.measuredHeight
            }
            val h = y + rowH + paddingTop + paddingBottom
            setMeasuredDimension(w, h)
        }

        override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
            val innerW = width - paddingLeft - paddingRight
            var x = 0; var y = 0; var rowH = 0
            for (i in 0 until childCount) {
                val c = getChildAt(i)
                if (x > 0 && x + c.measuredWidth > innerW) {
                    x = 0; y += rowH + spacing; rowH = 0
                }
                val left = paddingLeft + x
                val top = paddingTop + y
                c.layout(left, top, left + c.measuredWidth, top + c.measuredHeight)
                x += c.measuredWidth + spacing
                if (c.measuredHeight > rowH) rowH = c.measuredHeight
            }
        }
    }
}
