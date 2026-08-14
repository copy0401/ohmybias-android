package info.plateaukao.ohmybias.keyboard

import android.annotation.SuppressLint
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.inputmethodservice.InputMethodService
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.ExtractedTextRequest
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import info.plateaukao.ohmybias.MainActivity
import info.plateaukao.ohmybias.android.Prefs
import info.plateaukao.ohmybias.android.SqliteFreqTracker
import info.plateaukao.ohmybias.shared.ClipboardBridge
import info.plateaukao.ohmybias.shared.InputEngine
import info.plateaukao.ohmybias.shared.InputEngineDelegate
import info.plateaukao.ohmybias.shared.SkinSettings

/// Android IME 主 service — InputEngine 的 Android delegate 實作。
/// 對應 iOS 版 KeyboardViewController：按鍵事件 → InputEngine → delegate 回呼 → UI。
class OhMyBiasImeService : InputMethodService(), InputEngineDelegate {

    private lateinit var engine: InputEngine

    private var rootView: LinearLayout? = null
    private var candidateBar: CandidateBar? = null
    private var keyboardView: KeyboardView? = null
    private var toastLabel: TextView? = null
    private var toastFrame: FrameLayout? = null
    private val handler = Handler(Looper.getMainLooper())
    private var toastHideRunnable: Runnable? = null

    /// 候選列目前顯示的是聯想詞（composing 為空時點選直接送出）
    private var showingSuggestions = false

    private var builtForDark = false

    private val density get() = resources.displayMetrics.density
    private fun dp(v: Float): Int = (v * density).toInt()

    // MARK: - Lifecycle

    override fun onCreate() {
        super.onCreate()
        SkinSettings.shared.reload()
        engine = InputEngine(freqTracker = SqliteFreqTracker())
        engine.delegate = this
        engine.loadTable()
        engine.scheduleBackgroundTasks()
        // 還原上次使用的語言模式（EN/中文）
        engine.setEnglishMode(Prefs.lastEnglishMode)
    }

    override fun onEvaluateFullscreenMode(): Boolean = false

    @SuppressLint("InflateParams")
    override fun onCreateInputView(): View {
        KeyboardTheme.isDark = isDarkMode()
        builtForDark = KeyboardTheme.isDark

        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.clipChildren = false
        root.clipToPadding = false
        root.setBackgroundColor(KeyboardTheme.toolbarBackground)

        val bar = CandidateBar(this)
        root.addView(bar, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(CandidateBar.BAR_HEIGHT_DP)))

        // 鍵盤本體外再包一層 FrameLayout 供 toast 疊加
        val frame = FrameLayout(this)
        frame.clipChildren = false
        frame.clipToPadding = false
        val kv = KeyboardView(this)
        kv.isEnglishMode = engine.isEnglishMode
        frame.addView(kv, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))

        val toast = TextView(this)
        toast.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 20f)
        toast.setTextColor(Color.WHITE)
        val bg = GradientDrawable()
        bg.setColor(0xBF000000.toInt())
        bg.cornerRadius = dp(10f).toFloat()
        toast.background = bg
        toast.gravity = Gravity.CENTER
        toast.setPadding(dp(12f), dp(6f), dp(12f), dp(6f))
        toast.visibility = View.GONE
        frame.addView(toast, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.CENTER
        })

        root.addView(frame, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, keyboardBodyHeight()))

        bar.onSelect = { idx -> didSelectCandidate(idx) }
        bar.onToolbarKey = { action -> handleKey(action) }
        kv.onKey = { action -> handleKey(action) }

        rootView = root
        candidateBar = bar
        keyboardView = kv
        toastLabel = toast
        toastFrame = frame
        refreshIdleBar()
        return root
    }

    private fun keyboardBodyHeight(): Int {
        val landscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        return dp(if (landscape) 180f else 224f)
    }

    private fun isDarkMode(): Boolean =
        (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        // 深淺色或轉向改變時重建整個鍵盤（顏色在建構時解析）
        if (isDarkMode() != builtForDark) {
            setInputView(onCreateInputView())
        }
        keyboardView?.let { kv ->
            kv.needsInputModeSwitchKey = shouldOfferSwitching()
            kv.returnKeyLabel = returnLabel(info)
            kv.reloadKeys()
        }
        rootView?.let { root ->
            (root.getChildAt(1) as? FrameLayout)?.layoutParams?.height = keyboardBodyHeight()
        }
        refreshIdleBar()
    }

    private fun shouldOfferSwitching(): Boolean = try {
        shouldOfferSwitchingToNextInputMethod()
    } catch (e: Exception) {
        true
    }

    /// Enter 鍵依 host app 的 imeOptions 顯示（搜尋/前往/送出…）
    private fun returnLabel(info: EditorInfo?): String {
        return when ((info?.imeOptions ?: 0) and EditorInfo.IME_MASK_ACTION) {
            EditorInfo.IME_ACTION_SEARCH -> "搜尋"
            EditorInfo.IME_ACTION_GO -> "前往"
            EditorInfo.IME_ACTION_SEND -> "送出"
            EditorInfo.IME_ACTION_DONE -> "完成"
            EditorInfo.IME_ACTION_NEXT -> "下一個"
            else -> "⏎"
        }
    }

    // MARK: - Key handling

    private fun haptic() {
        if (Prefs.hapticFeedback) {
            rootView?.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        }
    }

    private fun handleKey(action: KeyAction) {
        haptic()
        when (action) {
            is KeyAction.Letter -> handleLetterKey(action.ch)
            is KeyAction.Space -> handleSpaceKey()
            is KeyAction.Backspace -> handleBackspaceKey()
            is KeyAction.Newline -> handleReturnKey()
            is KeyAction.Symbol -> {
                // 符號頁直接送出（不經引擎組字）
                engine.handleEscape()
                commitToEditor(action.s)
            }
            is KeyAction.ToggleLanguage -> {
                engine.toggleEnglishMode()
                keyboardView?.isEnglishMode = engine.isEnglishMode
                keyboardView?.showPage(KeyboardView.PageKind.LETTERS)  // 從工具列切換時回到字母頁
                Prefs.lastEnglishMode = engine.isEnglishMode
                refreshIdleBar()
            }
            is KeyAction.Page -> keyboardView?.showPage(action.page)
            is KeyAction.ToggleToolbarPage -> keyboardView?.toggleToolbarPage(action.page)
            is KeyAction.Shift -> {
                keyboardView?.let { it.isShifted = !it.isShifted; it.reloadKeys() }
            }
            is KeyAction.ZhuyinSymbol -> engine.handleZhuyinSymbol(action.zy)
            is KeyAction.ZhuyinTone -> engine.handleZhuyinTone(action.tone)
            is KeyAction.ZhuyinExit -> {
                engine.exitZhuyinMode()
                keyboardView?.currentPage = KeyboardView.PageKind.LETTERS
                keyboardView?.reloadKeys()
            }
            is KeyAction.SelectCandidateShortcut -> {
                // 次選/三選上屏（sweetlime n/m 上滑）— 無候選時不動作
                if (engine.currentCandidates.size > action.idx) didSelectCandidate(action.idx)
            }
            is KeyAction.LineStart -> {
                val before = textBeforeCursor()
                val lineLen = before.length - (before.lastIndexOf('\n') + 1)
                if (lineLen > 0) moveCursor(-lineLen)
            }
            is KeyAction.LineEnd -> {
                val after = textAfterCursor()
                val idx = after.indexOf('\n')
                val lineLen = if (idx >= 0) idx else after.length
                if (lineLen > 0) moveCursor(lineLen)
            }
            is KeyAction.PasteClipboard -> {
                engine.handleEscape()
                val text = ClipboardBridge.plainText()
                if (!text.isNullOrEmpty()) {
                    commitToEditor(text)
                } else {
                    showToast("剪貼簿為空", 1.2)
                }
            }
            is KeyAction.Tab -> {
                engine.handleEscape()
                commitToEditor("\t")
            }
            is KeyAction.EnterZhuyin -> {
                engine.switchToMode("zh")
                keyboardView?.showPage(KeyboardView.PageKind.ZHUYIN)
            }
            is KeyAction.CursorLeft -> moveCursor(-1)
            is KeyAction.CursorRight -> moveCursor(1)
            is KeyAction.DismissKeyboard -> requestHideSelf(0)
            is KeyAction.OpenSettings -> {
                val intent = Intent(this, MainActivity::class.java)
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(intent)
            }
            is KeyAction.Globe -> {
                if (!switchToNextIme()) {
                    (getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager)
                        .showInputMethodPicker()
                }
            }
        }
        // ,, 指令（如 ,,ZH）可能切換引擎模式 — 每次按鍵後同步頁面
        syncPageWithEngine()
    }

    private fun switchToNextIme(): Boolean = try {
        switchToNextInputMethod(false)
    } catch (e: Exception) {
        false
    }

    private fun handleLetterKey(ch: String) {
        if (engine.isEnglishMode) {
            val shifted = keyboardView?.isShifted == true
            val out = if (shifted) ch.uppercase() else ch
            if (shifted) {
                keyboardView?.isShifted = false
                keyboardView?.reloadKeys()
            }
            commitToEditor(out)
            return
        }
        if (engine.isPinyinMode) {
            val d = ch.toIntOrNull()
            if (d != null && d in 1..5) engine.handlePinyinTone(d)
            else engine.handlePinyinLetter(ch)
            return
        }
        // VRSF 快選：v/r/s/f 選第 2–5 個候選（碼不可延伸時）
        if (engine.handleVRSF(ch)) return
        engine.handleLetter(ch)
    }

    private fun handleSpaceKey() {
        if (engine.isEnglishMode) { commitToEditor(" "); return }
        if (engine.isPinyinMode) { engine.handlePinyinSpace(); return }
        if (engine.isZhuyinMode) { engine.handleZhuyinSpace(); return }
        if (engine.composing.isEmpty() && !showingSuggestions) {
            commitToEditor(" ")
            return
        }
        if (engine.composing.isEmpty() && showingSuggestions) {
            // 聯想詞顯示中按空白 → 清除聯想、輸出空白
            clearSuggestions()
            commitToEditor(" ")
            return
        }
        engine.handleSpace()
    }

    private fun handleBackspaceKey() {
        if (engine.isEnglishMode) { deleteBackward(); return }
        if (engine.isPinyinMode) { engine.handlePinyinBackspace(); return }
        if (showingSuggestions) clearSuggestions()
        engine.handleBackspace()
    }

    private fun handleReturnKey() {
        if (engine.isPinyinMode) { engine.exitPinyinMode(); return }
        if (engine.composing.isNotEmpty() || engine.isInSpecialMode) {
            engine.handleEnter()
            return
        }
        if (showingSuggestions) clearSuggestions()
        sendEnter()
    }

    private fun didSelectCandidate(index: Int) {
        haptic()
        if (engine.isPinyinMode) { engine.selectPinyinCandidate(index); return }
        engine.selectCandidate(index)
    }

    private fun clearSuggestions() {
        showingSuggestions = false
        engine.clearCandidates()
        refreshIdleBar()
    }

    private fun refreshIdleBar() {
        candidateBar?.setComposing("")
        candidateBar?.setCandidates(emptyList(), suggestions = false)
        candidateBar?.setEnglishMode(engine.isEnglishMode)
    }

    // MARK: - 編輯器操作（對應 textDocumentProxy）

    private fun commitToEditor(text: String) {
        currentInputConnection?.commitText(text, 1)
    }

    private fun deleteBackward() {
        val ic = currentInputConnection ?: return
        ic.deleteSurroundingTextInCodePoints(1, 0)
    }

    private fun sendEnter() {
        val ic = currentInputConnection ?: return
        val ei = currentInputEditorInfo
        val action = (ei?.imeOptions ?: 0) and EditorInfo.IME_MASK_ACTION
        val noEnterAction = ei != null &&
            (ei.imeOptions and EditorInfo.IME_FLAG_NO_ENTER_ACTION) != 0
        if (action != EditorInfo.IME_ACTION_NONE && action != EditorInfo.IME_ACTION_UNSPECIFIED && !noEnterAction) {
            ic.performEditorAction(action)
        } else {
            ic.commitText("\n", 1)
        }
    }

    private fun textBeforeCursor(): String =
        currentInputConnection?.getTextBeforeCursor(1000, 0)?.toString() ?: ""

    private fun textAfterCursor(): String =
        currentInputConnection?.getTextAfterCursor(1000, 0)?.toString() ?: ""

    /// 游標相對移動（Java char 單位）
    private fun moveCursor(offset: Int) {
        val ic = currentInputConnection ?: return
        val extracted = ic.getExtractedText(ExtractedTextRequest(), 0) ?: return
        val pos = (extracted.selectionStart + offset).coerceIn(0, extracted.text?.length ?: 0)
        ic.setSelection(pos, pos)
    }

    // MARK: - InputEngineDelegate（Android 事件皆在主執行緒，直接更新 UI）

    override fun engineDidUpdateComposing(text: String) {
        showingSuggestions = false
        candidateBar?.setComposing(text)
        syncPageWithEngine()
    }

    override fun engineDidUpdateCandidates(candidates: List<String>) {
        if (showingSuggestions) return
        candidateBar?.setCandidates(candidates, suggestions = false)
        if (candidates.isEmpty() && engine.composing.isEmpty()) {
            refreshIdleBar()
        }
        syncPageWithEngine()
    }

    override fun engineDidCommit(text: String) {
        commitToEditor(text)
    }

    override fun engineDidCommitPair(left: String, right: String) {
        commitToEditor(left + right)
        moveCursor(-right.length)
    }

    override fun engineDidClearComposing() {
        candidateBar?.setComposing("")
        if (!showingSuggestions) refreshIdleBar()
        syncPageWithEngine()
    }

    override fun engineDidShowToast(text: String) = showToast(text, 1.2)

    override fun engineDidShowCodeHint(text: String, duration: Double) = showToast(text, duration)

    override fun engineDidDeleteBack() {
        deleteBackward()
    }

    override fun engineDidSuggest(suggestions: List<String>) {
        showingSuggestions = true
        engine.setCandidates(suggestions)
        candidateBar?.setComposing("")
        candidateBar?.setCandidates(suggestions, suggestions = true)
    }

    override fun engineDidPasteText(text: String) {
        commitToEditor(text)
    }

    // MARK: - Zhuyin page sync

    private fun syncPageWithEngine() {
        val kv = keyboardView ?: return
        val wantZhuyin = engine.isZhuyinMode
        if (wantZhuyin && kv.currentPage != KeyboardView.PageKind.ZHUYIN) {
            kv.currentPage = KeyboardView.PageKind.ZHUYIN
            kv.reloadKeys()
        } else if (!wantZhuyin && kv.currentPage == KeyboardView.PageKind.ZHUYIN) {
            kv.currentPage = KeyboardView.PageKind.LETTERS
            kv.reloadKeys()
        }
    }

    private fun showToast(text: String, duration: Double) {
        val toast = toastLabel ?: return
        toastHideRunnable?.let { handler.removeCallbacks(it) }
        toast.text = "  $text  "
        toast.visibility = View.VISIBLE
        toast.alpha = 1f
        val hide = Runnable {
            toast.animate().alpha(0f).setDuration(250).withEndAction {
                toast.visibility = View.GONE
            }.start()
        }
        toastHideRunnable = hide
        handler.postDelayed(hide, (duration * 1000).toLong())
    }
}
