package info.plateaukao.ohmybias.keyboard

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import info.plateaukao.ohmybias.android.Prefs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/// 浮動鍵盤層：鋪滿整個 IME 視窗（透明、本身不吃觸控 — service 以 onComputeInsets 把可觸區
/// 縮到卡片本身，其餘觸控穿透到 app），承載一張可拖曳、四角可縮放的「卡片」：
/// 候選列＋鍵盤本體（由 service 建好後 attach 進來）＋底部拖曳把手。
/// 幾何（位置／大小）以 dp 存進 Prefs.floatingRectDp，下次浮出時還原並夾進當時的視窗範圍
/// （轉向、換裝置都不會跑到畫面外）。
@SuppressLint("ViewConstructor")
class FloatingKeyboardLayer(
    context: Context,
    private val barHeight: Int,
    /// 貼底模式的鍵盤本體高度 — 預設卡片高度以此為基準
    private val dockedBodyHeight: Int,
) : FrameLayout(context) {

    companion object {
        const val DRAG_BAR_DP = 22f
        const val CORNER_DP = 28f
        const val CARD_RADIUS_DP = 14f
        const val MIN_WIDTH_DP = 240f
        const val MIN_BODY_DP = 140f
        const val CONTENT_INSET_DP = 6f
    }

    /// 縮放放手後（尺寸定案）— service 依新寬度重算鍵面字級並重建鍵面
    var onResizeEnd: (() -> Unit)? = null

    private val density = context.resources.displayMetrics.density
    private fun dp(v: Float): Int = (v * density).roundToInt()

    /// 卡片（圓角、陰影、裁切）— 子視圖：content（面板＋拖曳把手）＋四角把手
    val card = FrameLayout(context)
    private val content = android.widget.LinearLayout(context)
    private val dragBar = DragBar(context)
    private val corners = listOf(
        CornerHandle(context, left = true, top = true),
        CornerHandle(context, left = false, top = true),
        CornerHandle(context, left = true, top = false),
        CornerHandle(context, left = false, top = false),
    )

    /// 卡片矩形（本層座標，px）
    private val rect = Rect()

    /// 角落提示弧目前是否顯示 — 平時藏著，拖曳（把手或角落）時亮起、放手 3 秒後消失；
    /// 角落的觸控範圍不受影響，只是不畫提示
    private var hintsVisible = false
    private val hideHintsRunnable = Runnable { setHintsVisible(false) }

    private fun setHintsVisible(v: Boolean) {
        if (hintsVisible == v) return
        hintsVisible = v
        for (c in corners) c.invalidate()
    }

    /// 拖曳開始：亮起並取消倒數
    private fun showHints() {
        removeCallbacks(hideHintsRunnable)
        setHintsVisible(true)
    }

    /// 拖曳放手：3 秒後熄滅
    private fun scheduleHideHints() {
        removeCallbacks(hideHintsRunnable)
        postDelayed(hideHintsRunnable, 3000)
    }

    init {
        clipChildren = false
        clipToPadding = false

        // 底色與邊框分開：邊框畫在 foreground（蓋在子視圖上）— 拖曳把手貼齊卡片底緣、
        // onDraw 又整片填色，畫在 background 的底邊框會被它蓋掉而看不見
        val bg = GradientDrawable()
        bg.setColor(KeyboardTheme.toolbarBackground)
        bg.cornerRadius = dp(CARD_RADIUS_DP).toFloat()
        card.background = bg
        val border = GradientDrawable()
        border.setColor(android.graphics.Color.TRANSPARENT)
        border.cornerRadius = dp(CARD_RADIUS_DP).toFloat()
        border.setStroke(dp(1f), KeyboardTheme.border)
        card.foreground = border
        card.clipToOutline = true
        card.elevation = dp(10f).toFloat()
        // 卡片內的長按氣泡要能凸出鍵外（頂排氣泡伸進候選列區域，仍在卡片內）
        card.clipChildren = false
        card.clipToPadding = false

        content.orientation = android.widget.LinearLayout.VERTICAL
        content.clipChildren = false
        content.clipToPadding = false
        val inset = dp(CONTENT_INSET_DP)
        content.setPadding(inset, inset, inset, 0)
        card.addView(content, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        content.addView(dragBar, android.widget.LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, dp(DRAG_BAR_DP)))

        for (c in corners) {
            val size = dp(CORNER_DP)
            val lp = LayoutParams(size, size)
            lp.gravity = (if (c.left) android.view.Gravity.START else android.view.Gravity.END) or
                (if (c.top) android.view.Gravity.TOP else android.view.Gravity.BOTTOM)
            card.addView(c, lp)
        }
        addView(card, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))

        // 初始幾何：先用偏好（dp → px），沒有就等第一次 layout 依視窗算預設
        Prefs.floatingRectDp?.let { r ->
            rect.set(
                (r[0] * density).roundToInt(), (r[1] * density).roundToInt(),
                ((r[0] + r[2]) * density).roundToInt(), ((r[1] + r[3]) * density).roundToInt(),
            )
        }
    }

    /// 把 service 建好的面板（候選列＋鍵盤本體）放進卡片，位於拖曳把手上方、吃掉剩餘高度
    fun attach(panel: View) {
        content.addView(panel, 0, android.widget.LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
    }

    /// 卡片寬度相對整個視窗寬的比例 — service 據此縮小鍵面字級（窄卡片字不擠）
    val widthRatio: Float
        get() {
            val w = if (rect.width() > 0) rect.width() else defaultWidth(width.takeIf { it > 0 } ?: resources.displayMetrics.widthPixels)
            val total = width.takeIf { it > 0 } ?: resources.displayMetrics.widthPixels
            return if (total > 0) w.toFloat() / total else 1f
        }

    private fun defaultWidth(layerW: Int): Int = min(layerW - dp(40f), dp(420f)).coerceAtLeast(dp(MIN_WIDTH_DP))
    private fun minHeight(): Int = barHeight + dp(MIN_BODY_DP) + dp(DRAG_BAR_DP) + dp(CONTENT_INSET_DP)
    private fun usableBottom(): Int = height - paddingBottom

    /// 第一次有尺寸時決定預設位置：置中、離底 24dp；之後每次量測把矩形夾進視窗
    private fun resolveRect(w: Int, h: Int) {
        if (w <= 0 || h <= 0) return
        val usable = h - paddingBottom
        if (rect.isEmpty) {
            val cw = defaultWidth(w)
            val ch = barHeight + (dockedBodyHeight * 0.9f).roundToInt() + dp(DRAG_BAR_DP) + dp(CONTENT_INSET_DP)
            val left = (w - cw) / 2
            val top = usable - dp(24f) - ch
            rect.set(left, top, left + cw, top + ch)
        }
        val cw = rect.width().coerceIn(min(dp(MIN_WIDTH_DP), w), w)
        val ch = rect.height().coerceIn(min(minHeight(), usable), usable)
        val l = rect.left.coerceIn(0, max(0, w - cw))
        val t = rect.top.coerceIn(0, max(0, usable - ch))
        rect.set(l, t, l + cw, t + ch)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = MeasureSpec.getSize(heightMeasureSpec)
        setMeasuredDimension(w, h)
        resolveRect(w, h)
        card.measure(
            MeasureSpec.makeMeasureSpec(rect.width(), MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(rect.height(), MeasureSpec.EXACTLY),
        )
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        card.layout(rect.left, rect.top, rect.right, rect.bottom)
    }

    /// 卡片目前在視窗座標的矩形（供 onComputeInsets 設可觸區）
    fun cardRectInWindow(out: Rect): Boolean {
        if (card.width == 0) return false
        val loc = IntArray(2)
        card.getLocationInWindow(loc)
        out.set(loc[0], loc[1], loc[0] + card.width, loc[1] + card.height)
        return true
    }

    // MARK: - 拖曳／縮放

    private fun persist() {
        Prefs.floatingRectDp = floatArrayOf(
            rect.left / density, rect.top / density, rect.width() / density, rect.height() / density,
        )
    }

    private fun moveBy(dx: Int, dy: Int, start: Rect) {
        rect.set(start)
        rect.offset(dx, dy)
        resolveRect(width, height)
        requestLayout()
    }

    private fun resize(c: CornerHandle, dx: Int, dy: Int, start: Rect) {
        val minW = min(dp(MIN_WIDTH_DP), width)
        val minH = min(minHeight(), usableBottom())
        var l = start.left; var t = start.top; var r = start.right; var b = start.bottom
        if (c.left) l = (start.left + dx).coerceIn(0, start.right - minW) else r = (start.right + dx).coerceIn(start.left + minW, width)
        if (c.top) t = (start.top + dy).coerceIn(0, start.bottom - minH) else b = (start.bottom + dy).coerceIn(start.top + minH, usableBottom())
        rect.set(l, t, r, b)
        requestLayout()
    }

    /// 底部拖曳把手：畫一條藥丸，拖曳移動整張卡片
    private inner class DragBar(context: Context) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private var downX = 0f; private var downY = 0f
        private val start = Rect()

        init { isClickable = true; contentDescription = "拖曳移動鍵盤" }

        override fun onDraw(canvas: Canvas) {
            // 與鍵盤本體同底色（卡片底色是候選列色，本體色可能不同）
            paint.color = KeyboardTheme.background
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
            paint.color = KeyboardTheme.textSub
            val pw = dp(44f).toFloat(); val ph = dp(4f).toFloat()
            val x = (width - pw) / 2; val y = (height - ph) / 2
            canvas.drawRoundRect(x, y, x + pw, y + ph, ph / 2, ph / 2, paint)
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX; downY = event.rawY; start.set(rect)
                    parent.requestDisallowInterceptTouchEvent(true)
                    showHints()
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    moveBy((event.rawX - downX).roundToInt(), (event.rawY - downY).roundToInt(), start)
                    return true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { persist(); scheduleHideHints(); return true }
            }
            return super.onTouchEvent(event)
        }
    }

    /// 角落把手：把卡片本身的圓角弧「加粗」當作可拖曳的提示（不另畫記號），拖曳改變該角（對角固定）
    private inner class CornerHandle(context: Context, val left: Boolean, val top: Boolean) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
        }
        private val oval = RectF()
        private var downX = 0f; private var downY = 0f
        private val start = Rect()

        init {
            isClickable = true
            contentDescription = "縮放鍵盤（" + (if (top) "上" else "下") + (if (left) "左" else "右") + "角）"
        }

        override fun onDraw(canvas: Canvas) {
            if (!hintsVisible) return
            // 沿著卡片圓角（半徑 CARD_RADIUS_DP）畫一段 90° 的粗弧，貼齊卡片邊緣、蓋在 1dp 邊框上
            val sw = dp(4f).toFloat()
            val r = dp(CARD_RADIUS_DP) - sw / 2
            paint.strokeWidth = sw
            paint.color = KeyboardTheme.border
            val l = if (left) sw / 2 else width - sw / 2 - 2 * r
            val t = if (top) sw / 2 else height - sw / 2 - 2 * r
            oval.set(l, t, l + 2 * r, t + 2 * r)
            val startAngle = when {
                left && top -> 180f
                !left && top -> 270f
                left && !top -> 90f
                else -> 0f
            }
            canvas.drawArc(oval, startAngle, 90f, false, paint)
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX; downY = event.rawY; start.set(rect)
                    parent.requestDisallowInterceptTouchEvent(true)
                    showHints()
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    resize(this, (event.rawX - downX).roundToInt(), (event.rawY - downY).roundToInt(), start)
                    return true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    persist()
                    scheduleHideHints()
                    onResizeEnd?.invoke()
                    return true
                }
            }
            return super.onTouchEvent(event)
        }
    }
}
