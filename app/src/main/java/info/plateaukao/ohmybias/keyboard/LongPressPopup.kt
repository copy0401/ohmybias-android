package info.plateaukao.ohmybias.keyboard

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.View

/// 長按彈出選單 — sweetlime 氣泡樣式：外殼淺底圓角、選中項反白。
/// 水平滑動選擇、放開送出。以自繪 View 實作（加在鍵盤根視圖上）。
class LongPressPopup(
    context: Context,
    private val options: List<LongPressData.Option>,
    defaultIndex: Int,
) : View(context) {

    companion object {
        const val ITEM_HEIGHT_DP = 44f
    }

    private val density = context.resources.displayMetrics.density
    private val wide = options.any { it.label.length > 1 }
    private val itemWidthPx = (if (wide) 52f else 40f) * density
    val popupWidth = (itemWidthPx * options.size + 8 * density).toInt()
    val popupHeight = (ITEM_HEIGHT_DP * density).toInt()

    var selectedIndex: Int = defaultIndex
        private set

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
    }

    init {
        elevation = 8 * density
    }

    /// 依觸點（本視圖座標的 x）更新選中項
    fun updateSelection(forX: Float) {
        val idx = ((forX - 4 * density) / itemWidthPx).toInt().coerceIn(0, options.size - 1)
        if (idx != selectedIndex) {
            selectedIndex = idx
            invalidate()
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(popupWidth, popupHeight)
    }

    override fun onDraw(canvas: Canvas) {
        val r = KeyboardTheme.cornerRadius * density
        // 外殼
        paint.style = Paint.Style.FILL
        paint.color = KeyboardTheme.bubbleShellBackground
        canvas.drawRoundRect(0f, 0f, width.toFloat(), height.toFloat(), r, r, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = KeyboardTheme.borderWidth * density
        paint.color = KeyboardTheme.border
        canvas.drawRoundRect(0f, 0f, width.toFloat(), height.toFloat(), r, r, paint)
        // 選項
        paint.style = Paint.Style.FILL
        for ((i, opt) in options.withIndex()) {
            val left = 4 * density + i * itemWidthPx
            val rect = RectF(left, 4 * density, left + itemWidthPx, height - 4 * density)
            val selected = i == selectedIndex
            if (selected) {
                paint.color = KeyboardTheme.bubbleSelectedBackground
                canvas.drawRoundRect(rect, 6 * density, 6 * density, paint)
            }
            textPaint.color = if (selected) KeyboardTheme.bubbleTextSelected else KeyboardTheme.bubbleTextUnselected
            textPaint.textSize = (if (opt.label.length > 1) 15f else 22f) * density
            val y = rect.centerY() - (textPaint.descent() + textPaint.ascent()) / 2
            canvas.drawText(opt.label, rect.centerX(), y, textPaint)
        }
    }
}
