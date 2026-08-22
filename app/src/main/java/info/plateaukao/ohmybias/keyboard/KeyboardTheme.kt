package info.plateaukao.ohmybias.keyboard

import android.graphics.Color
import info.plateaukao.ohmybias.shared.SkinSettings

/// 鍵盤主題 — 執行時讀 SkinSettings（匯入的 .cskin 調色盤/字級），
/// 未定義的鍵回退到內建 sweetlime 預設值（作者 Ryan 的「蝦米輸入法」皮膚）。
/// 手繪線稿風：淺色＝白鍵黑框、深色＝黑鍵灰框、功能鍵反白。
/// Android 端：service 於建立鍵盤 view 時設定 isDark，view 全部在建構時取色；
/// 深淺色切換時整個 input view 重建。
/// 效能（取法 sweetlime 的字級/度量快取）：色值與字級每（皮膚世代 × 深淺色）解析一次，
/// onDraw 讀的是快取欄位 — 不再每次繪製都做 JSON 查找＋十六進位字串解析。
object KeyboardTheme {

    /// 目前是否深色 — 由 OhMyBiasImeService 依 Configuration 設定
    var isDark = false

    /// 鍵面字級縮放 — 隨鍵盤高度調整連動（上限 1.2，避免撐爆鍵帽）；由 service 設定
    var keyFontScale = 1f

    /// 依 palette key 取色；skin 未定義時用預設 #RRGGBB(AA)
    private fun pal(key: String, lightDefault: String, darkDefault: String): Int =
        pal(arrayOf(key), lightDefault, darkDefault)

    /// 帶別名鏈的取色：依序試 skin palette 的每個 key，全部未定義才用內建預設。
    /// 對應 sweetlime CskinParser 的 fallback（如 textSystem → 皮膚自己的 textMain）—
    /// 匯入皮膚缺 v2 鍵時必須鏈回皮膚內的相容色，跳到內建常數會產生暗底暗字。
    private fun pal(keys: Array<String>, lightDefault: String, darkDefault: String): Int {
        val skin = SkinSettings.shared
        for (k in keys) {
            skin.colorHex(k, isDark)?.let { return parse(it) }
        }
        return parse(if (isDark) darkDefault else lightDefault)
    }

    /// #RRGGBB 或 #RRGGBBAA（與 cskin 同格式，alpha 在尾端）
    private fun parse(s: String): Int {
        val h = s.removePrefix("#")
        return try {
            when (h.length) {
                6 -> {
                    val v = h.toLong(16)
                    Color.rgb(((v shr 16) and 0xFF).toInt(), ((v shr 8) and 0xFF).toInt(), (v and 0xFF).toInt())
                }
                8 -> {
                    val v = h.toLong(16)
                    val a = (v and 0xFF).toInt()
                    val rgb = v shr 8
                    Color.argb(a, ((rgb shr 16) and 0xFF).toInt(), ((rgb shr 8) and 0xFF).toInt(), (rgb and 0xFF).toInt())
                }
                else -> Color.MAGENTA
            }
        } catch (e: Exception) {
            Color.MAGENTA
        }
    }

    // MARK: - 解析快取（每皮膚世代 × 深淺色解析一次）

    private class Resolved {
        val background = pal("bg", "#FFFFFF", "#000000")
        val keyNormal = pal("keyNormal", "#FFFFFF", "#000000")
        val keyNormalHighlight = pal("keyNormalHighlight", "#EBEBEB", "#1A1A1A")
        val keySystem = pal("keySystem", "#D6D6D696", "#CCCCCC")
        val keySystemHighlight = pal("keySystemHighlight", "#C7C7C7", "#BBBBBB")
        val textMain = pal("textMain", "#000000", "#BBBBBB")
        val textSub = pal("textSub", "#666666", "#555555")
        val textSystem = pal(arrayOf("textSystem", "textMain"), "#000000", "#1A1A1A")
        val border = pal("border", "#000000", "#BBBBBB")
        val systemBorder = pal(arrayOf("systemBorder", "border"), "#000000", "#333333")
        val borderHighlight = pal(arrayOf("borderHighlight", "border"), "#000000", "#BBBBBB")
        val systemBorderHighlight =
            pal(arrayOf("systemBorderHighlight", "systemBorder", "border"), "#000000", "#333333")
        val borderWidth = (SkinSettings.shared.paletteNumber("borderSize", isDark) ?: 1.0).toFloat()
        /// 按下時的邊框寬 — 皮膚未定義時同一般邊框寬（維持原本外觀）
        val borderWidthHighlight =
            (SkinSettings.shared.paletteNumber("borderSizeHighlight", isDark)
                ?: SkinSettings.shared.paletteNumber("borderSize", isDark) ?: 1.0).toFloat()
        val toolbarColor = pal("toolbarColor", "#000000", "#CCCCCC")
        val toolbarBackground = pal(arrayOf("toolbarBg", "bg"), "#F0F0F0", "#000000")
        val candidateText = pal("candidateUnselectedText", "#000000", "#CCCCCC")
        val candidateSelectedText = pal("candidateSelectedText", "#000000", "#000000")
        val candidateSelectedBackground = pal("candidateSelectedBg", "#FFFFFF", "#CCCCCC")
        val panelLeftBackground = pal("panelLeftBg", "#F0F0F0", "#1C1C1E")
        val panelRightBackground = pal("panelRightBg", "#FFFFFF", "#000000")
        val panelText = pal(arrayOf("panelLeftText", "textSub"), "#000000", "#F2F2F7")
        val panelCategoryHighlight = pal(arrayOf("panelCategoryHighlight", "candidateSelectedBg"), "#000000", "#F2F2F7")
        val panelCategorySelectedText = pal(arrayOf("panelCategorySelectedText", "candidateSelectedText"), "#F0F0F0", "#1C1C1E")
        val bubbleShellBackground = pal(arrayOf("bubbleShellBg", "keySystemHighlight"), "#E9E2E2", "#FFFFFF")
        val bubbleSelectedBackground = pal(arrayOf("bubbleSelectedBg", "keySystem"), "#000000", "#1A1A1A")
        val bubbleTextSelected = pal("bubbleTextSelected", "#FFFFFF", "#FFFFFF")
        val bubbleTextUnselected = pal("bubbleTextUnselected", "#000000", "#1A1A1A")
        // 字級（未含 keyFontScale — 縮放在 getter 端乘上，重建 view 改 scale 不必重解析）
        val alphabetSize = size("lowercaseSize", 23.0)
        val systemSize = size("systemSize", 16.0)
        val numberSize = size("numberSize", 24.0)
        val swipeHintSize = size("swipeSize", 10.0)
        val toolbarIconSize = size("toolbarSize", 27.0) * 0.63f
        val panelSymbolSize = size("panelLargeSymbolSize", 26.0)
        val panelKaomojiSize = size("panelLargeKaomojiSize", 20.0)
        val panelEmojiSize = size("panelLargeEmojiSize", 30.0)
        val panelSmallSize = size("panelSmallSize", 18.0)
    }

    private fun size(key: String, def: Double): Float =
        SkinSettings.shared.fontSize(key, def).toFloat()

    private var cache: Resolved? = null
    private var cachedGeneration = -1
    private var cachedDark = false

    /// 只在主執行緒存取（所有 view 繪製/建構皆在主執行緒）
    private val res: Resolved
        get() {
            val g = SkinSettings.shared.generation
            cache?.let { if (cachedGeneration == g && cachedDark == isDark) return it }
            val r = Resolved()
            cache = r
            cachedGeneration = g
            cachedDark = isDark
            return r
        }

    // MARK: - 調色盤（palette key 同 cskin settings.json globalSettings.palette）

    /// 鍵盤整體背景
    val background: Int get() = res.background

    /// 一般鍵背景／按下
    val keyNormal: Int get() = res.keyNormal
    val keyNormalHighlight: Int get() = res.keyNormalHighlight

    /// 功能鍵（shift、⌫、123…）背景／按下 — 深色模式反白
    val keySystem: Int get() = res.keySystem
    val keySystemHighlight: Int get() = res.keySystemHighlight

    /// 主文字
    val textMain: Int get() = res.textMain

    /// 角標提示文字（上滑/下滑符號）
    val textSub: Int get() = res.textSub

    /// 功能鍵文字（內建深色設計鍵底反白故用深字；匯入皮膚未定義時鏈回其 textMain）
    val textSystem: Int get() = res.textSystem

    /// 一般鍵邊框
    val border: Int get() = res.border

    /// 功能鍵邊框（未定義時鏈回皮膚的一般邊框）
    val systemBorder: Int get() = res.systemBorder

    /// 按下時的邊框色（未定義時鏈回該鍵平時的邊框色 → 外觀與舊皮膚一致）
    val borderHighlight: Int get() = res.borderHighlight
    val systemBorderHighlight: Int get() = res.systemBorderHighlight

    val borderWidth: Float get() = res.borderWidth

    /// 按下時的邊框寬（未定義時同 borderWidth）
    val borderWidthHighlight: Float get() = res.borderWidthHighlight

    const val cornerRadius = 8f

    /// 工具列（背景未定義時鏈回皮膚的鍵盤背景 — 同 sweetlime）
    val toolbarColor: Int get() = res.toolbarColor
    val toolbarBackground: Int get() = res.toolbarBackground

    /// 候選列
    val candidateText: Int get() = res.candidateText
    val candidateSelectedText: Int get() = res.candidateSelectedText
    val candidateSelectedBackground: Int get() = res.candidateSelectedBackground

    /// 面板（符號/emoji/顏文字）左欄分類、右欄內容（缺鍵時鏈回皮膚相容色）。
    /// 分類標籤是小型導覽文字 → 鏈回皮膚的高對比 textSub（textMain 可能是半透明鍵面字色）；
    /// 選中分類用皮膚保證成對的 candidateSelected 前景/背景。
    val panelLeftBackground: Int get() = res.panelLeftBackground
    val panelRightBackground: Int get() = res.panelRightBackground
    val panelText: Int get() = res.panelText
    val panelCategoryHighlight: Int get() = res.panelCategoryHighlight
    val panelCategorySelectedText: Int get() = res.panelCategorySelectedText

    /// 長按氣泡（殼/選中底未定義時鏈回皮膚的功能鍵色，避免與氣泡文字撞色）
    val bubbleShellBackground: Int get() = res.bubbleShellBackground
    val bubbleSelectedBackground: Int get() = res.bubbleSelectedBackground
    val bubbleTextSelected: Int get() = res.bubbleTextSelected
    val bubbleTextUnselected: Int get() = res.bubbleTextUnselected

    // MARK: - 字級（cskin globalSettings.groups；單位 dip 與 iOS pt 對應）

    val alphabetFontSize: Float get() = res.alphabetSize * keyFontScale  // 小寫字母
    val systemFontSize: Float get() = res.systemSize * keyFontScale     // 功能鍵
    val numberFontSize: Float get() = res.numberSize * keyFontScale     // 數字鍵盤
    val swipeHintFontSize: Float get() = res.swipeHintSize * keyFontScale  // 角標
    val toolbarIconSize: Float get() = res.toolbarIconSize  // 工具列
    val panelSymbolFontSize: Float get() = res.panelSymbolSize
    val panelKaomojiFontSize: Float get() = res.panelKaomojiSize
    val panelEmojiFontSize: Float get() = res.panelEmojiSize
    val panelSmallFontSize: Float get() = res.panelSmallSize
}
