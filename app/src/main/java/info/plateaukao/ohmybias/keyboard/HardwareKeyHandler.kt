package info.plateaukao.ohmybias.keyboard

import android.view.KeyCharacterMap
import android.view.KeyEvent
import info.plateaukao.ohmybias.shared.InputEngine

/// 實體鍵盤按鍵 → 引擎。語意對齊 macOS 版 `YabomishInputController.handleWithNewEngine`：
/// - 英文模式／Ctrl・Alt・Meta 組合／CapsLock 亮著：整顆放行給 app（回 false）
/// - 中文模式：a–z 與 , . 進引擎組字；數字鍵選字；空白送字；Enter 把組字碼原樣上屏；Esc 清組字
/// - 按住 Shift：Shift+字母 = 暫時英文（小寫直出，同官方嘸蝦米）；Shift+Space = 全形空白；
///   Shift+8（*）= 萬用字元查碼；其他 Shift 組合先結掉組字再放行
/// - 單按 Shift（300ms 內放開、期間沒按別的鍵）= 中英切換
/// - 組字為空時的空白／Enter／Backspace 一律放行，由 app 自己處理（保留 Shift+Enter、選取刪除等原生行為）
///
/// 回 true = 已處理；同一顆鍵的 keyUp 也要吞掉（`onKeyUp`），否則 app 會收到孤兒 up 事件。
class HardwareKeyHandler(private val host: Host) {

    interface Host {
        val engine: InputEngine
        val showingSuggestions: Boolean
        fun clearSuggestions()
        fun commitText(text: String)
        /// 同軟體鍵盤字母鍵路徑（含 VRSF 快選）
        fun handleLetter(ch: String)
        fun toggleLanguage()
        /// 視窗被 Back 收掉後再打字要重新叫出，否則組字看不見
        fun ensureShown()
    }

    private val engine get() = host.engine
    private var pendingShiftToggle = false
    private val consumed = HashSet<Int>()

    companion object {
        /// 標準注音鍵盤配置（大千式）— 同 macOS 版 keyCodeToZhuyin
        private val zhuyinKeys: Map<Char, String> = mapOf(
            '1' to "ㄅ", 'q' to "ㄆ", 'a' to "ㄇ", 'z' to "ㄈ",
            '2' to "ㄉ", 'w' to "ㄊ", 's' to "ㄋ", 'x' to "ㄌ",
            'e' to "ㄍ", 'd' to "ㄎ", 'c' to "ㄏ",
            'r' to "ㄐ", 'f' to "ㄑ", 'v' to "ㄒ",
            '5' to "ㄓ", 't' to "ㄔ", 'g' to "ㄕ", 'b' to "ㄖ",
            'y' to "ㄗ", 'h' to "ㄘ", 'n' to "ㄙ",
            'u' to "ㄧ", 'j' to "ㄨ", 'm' to "ㄩ",
            '8' to "ㄚ", 'i' to "ㄛ", 'k' to "ㄜ", ',' to "ㄝ",
            '9' to "ㄞ", 'o' to "ㄟ", 'l' to "ㄠ", '.' to "ㄡ",
            '0' to "ㄢ", 'p' to "ㄣ", ';' to "ㄤ", '/' to "ㄥ", '-' to "ㄦ",
        )
        private val zhuyinTones: Map<Char, String> = mapOf('6' to "ˊ", '3' to "ˇ", '4' to "ˋ", '7' to "˙")
        private const val SHIFT_TAP_MS = 300
    }

    /// 數字鍵 → 候選索引：畫面上標的是 1 起算（0 = 第 10 個），與候選列／氣泡的前綴一致；
    /// 不走 engine.selectByDigit（那是 %selkey 的位置，部分字表從 0 起算會差一格）。
    /// 超出候選數回 -1
    private fun candidateIndex(ch: Char): Int {
        if (ch !in '0'..'9') return -1
        val idx = if (ch == '0') 9 else ch - '1'
        return if (idx < engine.currentCandidates.size) idx else -1
    }

    private fun consume(keyCode: Int): Boolean {
        consumed.add(keyCode)
        host.ensureShown()
        return true
    }

    /// 結掉目前組字：有候選送首選，否則丟掉（同 macOS：Shift 組合／非字根符號前的處理）
    private fun flushComposing() {
        if (host.showingSuggestions) host.clearSuggestions()
        if (engine.composing.isEmpty()) return
        if (engine.currentCandidates.isNotEmpty()) engine.handleSpace() else engine.handleEscape()
    }

    /// 依鍵盤配置解出可列印字元；死鍵（COMBINING_ACCENT）與控制字元回 null
    private fun printableChar(event: KeyEvent): Char? {
        val c = event.getUnicodeChar(event.metaState)
        if (c and KeyCharacterMap.COMBINING_ACCENT != 0) return null
        return if (c > 0x1F) c.toChar() else null
    }

    fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_SHIFT_LEFT || keyCode == KeyEvent.KEYCODE_SHIFT_RIGHT) {
            pendingShiftToggle = event.repeatCount == 0 &&
                !event.isCtrlPressed && !event.isAltPressed && !event.isMetaPressed
            return false
        }
        pendingShiftToggle = false  // Shift 期間按了別的鍵 → 不是單按
        if (event.isCtrlPressed || event.isAltPressed || event.isMetaPressed) return false
        if (keyCode == KeyEvent.KEYCODE_BACK) return false
        // 查碼模式優先於英文模式（issue #6）— 英打進注音/拼音查碼時實體鍵仍要進查碼流程
        if (engine.isEnglishMode && !engine.isZhuyinMode && !engine.isPinyinMode) return false
        if (event.isCapsLockOn) {
            // CapsLock = 英文直通（同 Windows 版慣例）；組字中先結掉再放行
            flushComposing()
            return false
        }

        val ch = printableChar(event)
        if (engine.isZhuyinMode) return zhuyinKeyDown(keyCode, ch)
        if (engine.isPinyinMode) return pinyinKeyDown(keyCode, ch)

        if (event.isShiftPressed) {
            return when {
                ch == '*' && engine.composing.isNotEmpty() -> { engine.handleWildcard(); consume(keyCode) }
                keyCode == KeyEvent.KEYCODE_SPACE -> { flushComposing(); host.commitText("　"); consume(keyCode) }
                ch != null && ch in 'A'..'Z' -> { flushComposing(); host.commitText(ch.lowercaseChar().toString()); consume(keyCode) }
                else -> { flushComposing(); false }  // Shift+數字／符號：結掉組字後放行
            }
        }

        when (keyCode) {
            KeyEvent.KEYCODE_SPACE -> {
                if (engine.composing.isEmpty()) {
                    if (host.showingSuggestions) host.clearSuggestions()
                    // 唯一候選自動送出後習慣性補的那一下空白要吃掉
                    return if (engine.consumeEatNextSpace()) consume(keyCode) else false
                }
                engine.handleSpace()
                return consume(keyCode)
            }
            KeyEvent.KEYCODE_DEL -> {
                if (engine.composing.isEmpty()) {
                    if (host.showingSuggestions) host.clearSuggestions()
                    return false
                }
                engine.handleBackspace()
                return consume(keyCode)
            }
            KeyEvent.KEYCODE_ESCAPE -> {
                if (engine.composing.isEmpty()) {
                    if (engine.isInSpecialMode) { engine.handleEscape(); return consume(keyCode) }
                    if (host.showingSuggestions) { host.clearSuggestions(); return consume(keyCode) }
                    return false
                }
                engine.handleEscape()
                return consume(keyCode)
            }
            KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                if (engine.composing.isEmpty() && !engine.isInSpecialMode) {
                    if (host.showingSuggestions) host.clearSuggestions()
                    return false
                }
                engine.handleEnter()
                return consume(keyCode)
            }
            KeyEvent.KEYCODE_TAB -> return false
        }

        if (ch == null) return false  // 方向鍵等非列印鍵：放行

        if (ch in 'a'..'z' || ch == ',' || ch == '.') {
            host.handleLetter(ch.toString())
            return consume(keyCode)
        }
        if (ch in '0'..'9') {
            if (!host.showingSuggestions) {
                val idx = candidateIndex(ch)
                if (idx >= 0) { engine.selectCandidate(idx); return consume(keyCode) }
            }
            if (engine.composing.isNotEmpty()) return consume(keyCode)  // 組字中沒對應候選 → 吞掉
            if (host.showingSuggestions) host.clearSuggestions()
            return false
        }
        // 其他可列印符號（- = \ ` ' ; / [ ] 等非字根鍵）：結掉組字後放行
        flushComposing()
        return false
    }

    private fun zhuyinKeyDown(keyCode: Int, ch: Char?): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_ESCAPE -> {
                if (engine.currentCandidates.isNotEmpty() || engine.composing.isNotEmpty()) engine.handleEscape()
                else engine.exitZhuyinMode()
                return consume(keyCode)
            }
            KeyEvent.KEYCODE_DEL -> { engine.handleBackspace(); return consume(keyCode) }
            KeyEvent.KEYCODE_SPACE -> { engine.handleZhuyinSpace(); return consume(keyCode) }
            KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                if (engine.currentCandidates.isEmpty()) return false
                engine.selectCandidate(0)
                return consume(keyCode)
            }
        }
        if (ch == null) return false
        val lower = ch.lowercaseChar()
        if (engine.currentCandidates.isNotEmpty()) {
            val idx = candidateIndex(lower)
            if (idx >= 0) engine.selectCandidate(idx)
            return consume(keyCode)  // 候選顯示中：其餘鍵吞掉（同 macOS）
        }
        zhuyinTones[lower]?.let { engine.handleZhuyinTone(it); return consume(keyCode) }
        zhuyinKeys[lower]?.let { engine.handleZhuyinSymbol(it); return consume(keyCode) }
        return consume(keyCode)
    }

    private fun pinyinKeyDown(keyCode: Int, ch: Char?): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_ESCAPE -> { engine.handlePinyinEscape(); return consume(keyCode) }
            KeyEvent.KEYCODE_DEL -> { engine.handlePinyinBackspace(); return consume(keyCode) }
            KeyEvent.KEYCODE_SPACE -> { engine.handlePinyinSpace(); return consume(keyCode) }
            KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> { engine.exitPinyinMode(); return consume(keyCode) }
        }
        if (ch == null) return false
        val lower = ch.lowercaseChar()
        if (engine.currentCandidates.isNotEmpty() && lower in '0'..'9') {
            val idx = candidateIndex(lower)
            if (idx >= 0) engine.selectPinyinCandidate(idx)
            return consume(keyCode)
        }
        if (lower in '1'..'5') { engine.handlePinyinTone(lower - '0'); return consume(keyCode) }
        if (lower in 'a'..'z') { engine.handlePinyinLetter(lower.toString()); return consume(keyCode) }
        return false
    }

    fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_SHIFT_LEFT || keyCode == KeyEvent.KEYCODE_SHIFT_RIGHT) {
            if (pendingShiftToggle && event.eventTime - event.downTime < SHIFT_TAP_MS) host.toggleLanguage()
            pendingShiftToggle = false
            return false
        }
        return consumed.remove(keyCode)
    }
}
