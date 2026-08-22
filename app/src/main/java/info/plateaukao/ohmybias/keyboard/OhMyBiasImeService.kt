package info.plateaukao.ohmybias.keyboard

import android.annotation.SuppressLint
import android.annotation.TargetApi
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.inputmethodservice.InputMethodService
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.ExtractedTextRequest
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import info.plateaukao.ohmybias.MainActivity
import info.plateaukao.ohmybias.android.Prefs
import info.plateaukao.ohmybias.android.SqliteFreqTracker
import info.plateaukao.ohmybias.shared.CINTable
import info.plateaukao.ohmybias.shared.ClipboardBridge
import info.plateaukao.ohmybias.shared.InputEngine
import info.plateaukao.ohmybias.shared.InputEngineDelegate
import info.plateaukao.ohmybias.shared.SkinSettings
import kotlin.math.abs

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
    private var builtBodyHeight = 0
    /// 尺寸類偏好即時生效 — 設定頁拖滑桿時鍵盤就跟著變（同 process；
    /// SharedPreferences 只保 weak reference，必須自己持有這個欄位）
    private val prefsListener = android.content.SharedPreferences
        .OnSharedPreferenceChangeListener { _, key ->
            when (key) {
                "keyboardHeightScale" -> handler.post { rebuildForHeightChange() }
                "keySpacingScale" -> handler.post { keyboardView?.requestLayout() }
            }
        }
    /// 建這組 view 時的皮膚世代 —— 設定頁匯入 .cskin 後（同 process）整個 view 要重建，
    /// 否則候選列工具列與鍵面會用不同皮膚（工具列按鈕只在 CandidateBar 建構時讀一次）
    private var builtSkinGeneration = -1
    /// 引擎目前載入的字表世代 —— 設定頁匯入新 liu.cin 後要重載
    private var loadedTableGeneration = CINTable.generation

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
        warmUpReverseCache()
        Prefs.addListener(prefsListener)
    }

    /// 反查表（查碼提示/注音同音字）整表建立成本高 — 背景預熱，
    /// 免得第一次用到的那個按鍵卡住（取法 sweetlime 的 prefetchCache）
    private fun warmUpReverseCache() {
        Thread({
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
            engine.cinTable.warmUpReverseCache()
        }, "ohmybias-warmup").start()
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        super.onFinishInputView(finishingInput)
        // 離開輸入框時把未寫入的字頻紀錄排進落盤佇列 — process 被殺也不掉學習資料
        engine.freqTracker.flushAll()
    }

    /// 輸入階段結束（換輸入框／換 app）—— 未送出的組字狀態不能跨欄位存活，
    /// 否則新欄位的第一個空白鍵會把上一個欄位遺留的候選字送出去，
    /// 還會把它記成新欄位的字頻／bigram 樣本。
    override fun onFinishInput() {
        super.onFinishInput()
        engine.resetSession()
        showingSuggestions = false
        refreshIdleBar()
    }

    override fun onDestroy() {
        Prefs.removeListener(prefsListener)
        engine.freqTracker.flushAll()
        super.onDestroy()
    }

    /// 高度改變 — 只改 layoutParams IME 視窗不會可靠重量測，整組視圖重建
    /// （鍵面尺寸/字級都在建構時算好）；組字狀態留在 engine，重建後補回候選列與頁面
    private fun rebuildForHeightChange() {
        if (rootView == null) return
        setInputView(onCreateInputView())
        // 新建的 KeyboardView 用預設 Enter 標籤／🌐 鍵狀態 — 補回目前欄位的值
        keyboardView?.syncSessionState(
            shouldOfferSwitching() && !Prefs.hideGlobeKey,
            returnLabel(currentInputEditorInfo),
        )
        refreshIdleBar()
        syncPageWithEngine()
    }

    override fun onEvaluateFullscreenMode(): Boolean = false

    /// 導覽模式（手勢 ↔ 3 鍵）在鍵盤收起時切換的話，既有視圖不會收到新 insets
    /// 派發，padding 停在舊值 → 導覽列又蓋回最下排。每次視窗顯示時主動要求
    /// 重新派發，讓 onCreateInputView 掛的 listener 拿到當下的導覽列高度。
    override fun onWindowShown() {
        super.onWindowShown()
        if (Build.VERSION.SDK_INT >= 35) {
            rootView?.let { r ->
                r.rootWindowInsets?.let { applyNavBarPadding(r, it) }
                r.requestApplyInsets()
            }
        }
    }

    @TargetApi(35)
    private fun applyNavBarPadding(v: View, insets: WindowInsets) {
        // navigationBars ∪ tappableElement：Android 15/16 導覽列在 navigationBars；
        // Android 17 起收鍵盤箭頭/地球飾件列只算在 tappableElement（nav 只剩手勢 pill）
        val type = WindowInsets.Type.navigationBars() or WindowInsets.Type.tappableElement()
        val insetBottom = insets.getInsets(type).bottom
        // One UI（Samsung S25U 實測）系統本來就把 IME 排在導覽列上方、卻仍回報
        // 導覽列 inset — 照抄會多墊出一整條空白。故墊的是「視圖實際延伸進導覽列
        // 區域」的重疊量：AOSP 視圖底邊＝螢幕底 → 全額；One UI 底邊已在導覽列
        // 頂 → 0。視圖底邊由框架錨定，不受自身 padding 影響，計算收斂不震盪。
        var pad = insetBottom
        if (v.isLaidOut) {
            val screenBottom = getSystemService(WindowManager::class.java)
                .maximumWindowMetrics.bounds.bottom
            val loc = IntArray(2)
            v.getLocationOnScreen(loc)
            pad = (loc[1] + v.height - (screenBottom - insetBottom)).coerceIn(0, insetBottom)
        }
        if (pad != v.paddingBottom) v.setPadding(0, 0, 0, pad)
    }

    @SuppressLint("InflateParams")
    override fun onCreateInputView(): View {
        KeyboardTheme.isDark = isDarkMode()
        KeyboardTheme.keyFontScale = minOf(Prefs.keyboardHeightScale, 1.2f)
        builtForDark = KeyboardTheme.isDark
        builtSkinGeneration = SkinSettings.shared.generation

        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.clipChildren = false
        root.clipToPadding = false
        root.setBackgroundColor(KeyboardTheme.toolbarBackground)

        // Android 15 起 targetSdk 35+ 的 IME 視窗強制 edge-to-edge，會延伸到系統
        // 導覽列底下 — 不自己墊高的話 3 鍵導覽列／手勢區會直接蓋住最下排按鍵。
        // 舊版系統由框架自動把 IME 排在導覽列上方，不需處理。
        if (Build.VERSION.SDK_INT >= 35) {
            root.setOnApplyWindowInsetsListener { v, insets ->
                applyNavBarPadding(v, insets)
                insets
            }
            // insets 派發常在 layout 前（isLaidOut=false 時先全額墊）；layout 完成後
            // 用實際幾何重算一次，把 One UI 的多餘 padding 修掉（值不變就不再觸發）。
            root.addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
                v.rootWindowInsets?.let { applyNavBarPadding(v, it) }
            }
        }

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

        builtBodyHeight = keyboardBodyHeight()
        root.addView(frame, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, builtBodyHeight))

        bar.onSelect = { idx -> didSelectCandidate(idx) }
        bar.onCommitComposing = {
            haptic()
            engine.commitComposingRaw()
        }
        bar.onToolbarKey = { action ->
            // 聯想列是覆蓋在工具列上的 —— 點得到的那幾顆鍵要先把聯想收掉再做事，
            // 否則按完（例如收折鍵盤）下次開鍵盤還留著上一輪的過期聯想
            if (showingSuggestions) clearSuggestions()
            handleKey(action)
        }
        bar.onDismissSuggestions = {
            haptic()
            clearSuggestions()
        }
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
        val base = if (landscape) 180f else 224f
        return dp(base * Prefs.keyboardHeightScale)
    }

    private fun isDarkMode(): Boolean =
        (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        // 設定頁剛匯入新字表（同 process）— 重載，否則鍵盤一直用舊表直到 process 重啟
        if (CINTable.generation != loadedTableGeneration) {
            loadedTableGeneration = CINTable.generation
            engine.loadTable()
            warmUpReverseCache()
        }
        // 深淺色、轉向、高度或皮膚改變時重建整個鍵盤（顏色/尺寸/工具列按鈕都在建構時
        // 解析；直接改 layoutParams IME 視窗不會可靠重量測）
        if (isDarkMode() != builtForDark || keyboardBodyHeight() != builtBodyHeight ||
            SkinSettings.shared.generation != builtSkinGeneration
        ) {
            setInputView(onCreateInputView())
        }
        keyboardView?.syncSessionState(shouldOfferSwitching() && !Prefs.hideGlobeKey, returnLabel(info))
        keyboardView?.syncSpacingIfNeeded()
        refreshIdleBar()
        syncPageWithEngine()
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
                // 符號頁直接送出（不經引擎組字）；成對標點仍補右半並把游標放中間
                engine.handleEscape()
                val right = engine.pairedRight(action.s)
                if (right != null) commitPair(action.s, right) else commitToEditor(action.s)
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
            is KeyAction.OpenUserPhrases -> {
                val intent = Intent(this, MainActivity::class.java)
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                intent.putExtra(MainActivity.EXTRA_OPEN_USER_PHRASES, true)
                startActivity(intent)
            }
            is KeyAction.Globe -> {
                if (!switchToNextIme()) {
                    (getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager)
                        .showInputMethodPicker()
                }
            }
            is KeyAction.ShowImePicker -> {
                (getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager)
                    .showInputMethodPicker()
            }
            is KeyAction.VoiceInput -> {
                engine.handleEscape()
                startVoiceInput()
            }
            // 編輯動作 — 同 sweetlime 原始實作（iOS 版因 extension API 缺失無法支援）
            is KeyAction.SelectAll -> {
                engine.handleEscape()
                currentInputConnection?.performContextMenuAction(android.R.id.selectAll)
            }
            is KeyAction.Copy -> {
                engine.handleEscape()
                currentInputConnection?.performContextMenuAction(android.R.id.copy)
            }
            is KeyAction.Cut -> {
                engine.handleEscape()
                currentInputConnection?.performContextMenuAction(android.R.id.cut)
            }
            is KeyAction.Undo -> {
                engine.handleEscape()
                sendCtrlKey(KeyEvent.KEYCODE_Z, shift = false)
            }
            is KeyAction.Redo -> {
                engine.handleEscape()
                sendCtrlKey(KeyEvent.KEYCODE_Z, shift = true)
            }
            is KeyAction.ToggleSimpTrad -> {
                val target = if (engine.inputMode == InputEngine.InputMode.S) "t" else "s"
                engine.switchToMode(target)
            }
        }
        // ,, 指令（如 ,,ZH）可能切換引擎模式 — 每次按鍵後同步頁面
        syncPageWithEngine()
    }

    /// Ctrl(+Shift)+按鍵組合送給編輯器（復原/重做 — EditText 內建 undo manager 走此路徑）
    private fun sendCtrlKey(keyCode: Int, shift: Boolean) {
        val ic = currentInputConnection ?: return
        var meta = KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON
        if (shift) meta = meta or KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON
        val now = SystemClock.uptimeMillis()
        ic.sendKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0, meta))
        ic.sendKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, keyCode, 0, meta))
    }

    private fun switchToNextIme(): Boolean = try {
        switchToNextInputMethod(false)
    } catch (e: Exception) {
        false
    }

    /// 語音輸入 — 同 sweetlime `LIMEService.startVoiceInput()`：Android IME 不能自己開聽寫視窗，
    /// 作法是切換到一個已啟用的「語音輸入法」，由它接手聽寫並把結果送進同一個輸入框；
    /// 使用者說完後語音輸入法自己會切回來（或按 🌐 切回）。找不到就提示。
    private fun startVoiceInput() {
        val id = voiceImeId()
        if (id == null) {
            showToast("找不到語音輸入法", 1.8)
            return
        }
        val ok = try {
            switchInputMethod(id); true
        } catch (e: Exception) {
            false
        }
        if (!ok) showToast("無法切換語音輸入法", 1.8)
    }

    /// 挑一個可用的語音輸入法 ID — 三段搜尋同 sweetlime `LIMEUtilities.isVoiceSearchServiceExist()`
    private fun voiceImeId(): String? {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
        val enabled = try { imm.enabledInputMethodList } catch (e: Exception) { return null }

        // 一：已知的 Google 語音輸入法（有 GMS 的機器最快路徑）
        val known = setOf(
            "com.google.android.voicesearch/.ime.VoiceInputMethodService",
            "com.google.android.googlequicksearchbox/com.google.android.voicesearch.ime.VoiceInputMethodService",
            "com.google.android.tts/com.google.android.apps.speech.tts.googletts.settings.asr.voiceime.VoiceInputMethodService",
        )
        enabled.firstOrNull { it.id in known }?.let { return it.id }

        // 二：任何看起來像語音的已啟用輸入法 — 宣告 mode="voice" 的 subtype，或 ID 帶 voice/speech
        for (imi in enabled) {
            for (i in 0 until imi.subtypeCount) {
                if (imi.getSubtypeAt(i).mode == "voice") return imi.id
            }
            val lower = imi.id.lowercase()
            if (lower.contains("voice") || lower.contains("speech")) return imi.id
        }

        // 三：退而求其次切到 Gboard — 它有內建語音但不提供語音專用 IME/subtype，
        // 至少讓使用者能按 Gboard 自己的麥克風鍵
        return enabled.firstOrNull { it.id.startsWith("com.google.android.inputmethod.latin/") }?.id
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
            // 唯一候選自動送出後習慣性補的那一下空白要吃掉，不輸出多餘空格。
            // （組字為空的空白鍵不會進引擎的 handleSpace，所以在這裡問）
            if (engine.consumeEatNextSpace()) return
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

    /// 游標相對移動（±n 個 code point，不會切開 surrogate pair — emoji 被切開後
    /// 下一次刪除／送字會弄壞那個字）。
    /// 主路徑用 ExtractedText 算絕對位置直接 setSelection；取不到的編輯器
    /// （部分 WebView/Compose 欄位回 null）退回送方向鍵，由編輯器自己算邊界 ——
    /// 舊版在這裡直接 return，成對標點的游標就留在右半邊外面而不是中間。
    private fun moveCursor(offset: Int) {
        val ic = currentInputConnection ?: return
        if (offset == 0) return
        val extracted = ic.getExtractedText(ExtractedTextRequest(), 0)
        val text = extracted?.text
        // selectionStart 是相對於 startOffset 的視窗座標 — 長文件不從 0 起算，
        // 直接當絕對位置會把游標丟到別的地方
        if (extracted != null && text != null && extracted.selectionStart in 0..text.length) {
            val target = offsetByCodePoints(text.toString(), extracted.selectionStart, offset)
            val absolute = extracted.startOffset + target
            if (absolute >= 0) {
                ic.setSelection(absolute, absolute)
                return
            }
        }
        val keyCode = if (offset < 0) KeyEvent.KEYCODE_DPAD_LEFT else KeyEvent.KEYCODE_DPAD_RIGHT
        repeat(minOf(abs(offset), 1000)) { sendDownUpKey(keyCode) }
    }

    /// 自 from（UTF-16 index）位移 n 個 code point，越界夾到頭尾
    private fun offsetByCodePoints(text: String, from: Int, n: Int): Int {
        val total = text.codePointCount(0, text.length)
        val cur = text.codePointCount(0, from)
        return text.offsetByCodePoints(0, (cur + n).coerceIn(0, total))
    }

    private fun sendDownUpKey(keyCode: Int) {
        val ic = currentInputConnection ?: return
        val now = SystemClock.uptimeMillis()
        ic.sendKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0))
        ic.sendKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, keyCode, 0))
    }

    // MARK: - InputEngineDelegate（Android 事件皆在主執行緒，直接更新 UI）

    override fun engineDidUpdateComposing(text: String) {
        showingSuggestions = false
        candidateBar?.setComposing(text)
        syncPageWithEngine()
    }

    override fun engineDidUpdateCandidates(candidates: List<String>) {
        // 送字時引擎會先清候選、之後才（在有結果時）發聯想。聯想顯示中收到的這個清空
        // 不能一律吞掉 —— 否則下一輪沒有聯想時，候選列會留著上一輪的過期聯想，
        // 而引擎的候選已空，點下去每一個都沒反應。清掉後若隨即有新聯想，
        // engineDidSuggest 會在同一個呼叫堆疊裡補上，畫面不會閃。
        if (showingSuggestions) {
            if (candidates.isEmpty()) {
                showingSuggestions = false
                refreshIdleBar()
            }
            return
        }
        candidateBar?.setCandidates(candidates, suggestions = false)
        if (candidates.isEmpty() && engine.composing.isEmpty()) {
            refreshIdleBar()
        }
        syncPageWithEngine()
    }

    override fun engineDidCommit(text: String) {
        commitToEditor(text)
    }

    override fun engineDidCommitPair(left: String, right: String) = commitPair(left, right)

    private fun commitPair(left: String, right: String) {
        commitToEditor(left + right)
        // moveCursor 以 code point 為單位 — 游標要停在成對標點中間
        moveCursor(-right.codePointCount(0, right.length))
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
        // 使用者從工具列開的面板（符號/emoji/顏文字/常用語/123）優先 —— 注音模式是
        // 黏著旗標，不擋掉的話每次按鍵後都會把面板搶回注音頁，面板等於打不開
        if (kv.isShowingToolbarPage) return
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
