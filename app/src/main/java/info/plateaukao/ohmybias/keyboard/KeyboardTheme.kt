package info.plateaukao.ohmybias.keyboard

import android.graphics.Color
import info.plateaukao.ohmybias.shared.SkinSettings

/// 鍵盤主題 — 執行時讀 SkinSettings（匯入的 .cskin 調色盤/字級），
/// 未定義的鍵回退到內建 sweetlime 預設值（作者 Ryan 的「蝦米輸入法」皮膚）。
/// 手繪線稿風：淺色＝白鍵黑框、深色＝黑鍵灰框、功能鍵反白。
/// Android 端：service 於建立鍵盤 view 時設定 isDark，view 全部在建構時取色；
/// 深淺色切換時整個 input view 重建。
object KeyboardTheme {

    /// 目前是否深色 — 由 OhMyBiasImeService 依 Configuration 設定
    var isDark = false

    /// 依 palette key 取色；skin 未定義時用預設 #RRGGBB(AA)
    private fun pal(key: String, lightDefault: String, darkDefault: String): Int {
        val hex = SkinSettings.shared.colorHex(key, isDark) ?: (if (isDark) darkDefault else lightDefault)
        return parse(hex)
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

    // MARK: - 調色盤（palette key 同 cskin settings.json globalSettings.palette）

    /// 鍵盤整體背景
    val background: Int get() = pal("bg", "#FFFFFF", "#000000")

    /// 一般鍵背景／按下
    val keyNormal: Int get() = pal("keyNormal", "#FFFFFF", "#000000")
    val keyNormalHighlight: Int get() = pal("keyNormalHighlight", "#EBEBEB", "#1A1A1A")

    /// 功能鍵（shift、⌫、123…）背景／按下 — 深色模式反白
    val keySystem: Int get() = pal("keySystem", "#D6D6D696", "#CCCCCC")
    val keySystemHighlight: Int get() = pal("keySystemHighlight", "#C7C7C7", "#BBBBBB")

    /// 主文字
    val textMain: Int get() = pal("textMain", "#000000", "#BBBBBB")

    /// 角標提示文字（上滑/下滑符號）
    val textSub: Int get() = pal("textSub", "#666666", "#555555")

    /// 功能鍵文字（深色模式時鍵底反白故用深字）
    val textSystem: Int get() = pal("textSystem", "#000000", "#1A1A1A")

    /// 一般鍵邊框
    val border: Int get() = pal("border", "#000000", "#BBBBBB")

    /// 功能鍵邊框
    val systemBorder: Int get() = pal("systemBorder", "#000000", "#333333")

    val borderWidth: Float
        get() = (SkinSettings.shared.paletteNumber("borderSize", dark = false) ?: 1.0).toFloat()

    const val cornerRadius = 8f

    /// 工具列
    val toolbarColor: Int get() = pal("toolbarColor", "#000000", "#CCCCCC")
    val toolbarBackground: Int get() = pal("toolbarBg", "#F0F0F0", "#000000")

    /// 候選列
    val candidateText: Int get() = pal("candidateUnselectedText", "#000000", "#CCCCCC")
    val candidateSelectedText: Int get() = pal("candidateSelectedText", "#000000", "#000000")
    val candidateSelectedBackground: Int get() = pal("candidateSelectedBg", "#FFFFFF", "#CCCCCC")

    /// 面板（符號/emoji/顏文字）左欄分類、右欄內容
    val panelLeftBackground: Int get() = pal("panelLeftBg", "#F0F0F0", "#1C1C1E")
    val panelRightBackground: Int get() = pal("panelRightBg", "#FFFFFF", "#000000")
    val panelText: Int get() = pal("panelLeftText", "#000000", "#F2F2F7")
    val panelCategoryHighlight: Int get() = pal("panelCategoryHighlight", "#000000", "#F2F2F7")

    /// 長按氣泡
    val bubbleShellBackground: Int get() = pal("bubbleShellBg", "#E9E2E2", "#FFFFFF")
    val bubbleSelectedBackground: Int get() = pal("bubbleSelectedBg", "#000000", "#1A1A1A")
    val bubbleTextSelected: Int get() = pal("bubbleTextSelected", "#FFFFFF", "#FFFFFF")
    val bubbleTextUnselected: Int get() = pal("bubbleTextUnselected", "#000000", "#1A1A1A")

    // MARK: - 字級（cskin globalSettings.groups；單位 dip 與 iOS pt 對應）

    private fun size(key: String, def: Double): Float =
        SkinSettings.shared.fontSize(key, def).toFloat()

    val alphabetFontSize: Float get() = size("lowercaseSize", 23.0)      // 小寫字母
    val systemFontSize: Float get() = size("systemSize", 16.0)           // 功能鍵
    val numberFontSize: Float get() = size("numberSize", 24.0)           // 數字鍵盤
    val swipeHintFontSize: Float get() = size("swipeSize", 10.0)         // 角標
    val toolbarIconSize: Float get() = size("toolbarSize", 27.0) * 0.63f // 工具列
    val panelSymbolFontSize: Float get() = size("panelLargeSymbolSize", 26.0)
    val panelKaomojiFontSize: Float get() = size("panelLargeKaomojiSize", 20.0)
    val panelEmojiFontSize: Float get() = size("panelLargeEmojiSize", 30.0)
    val panelSmallFontSize: Float get() = size("panelSmallSize", 18.0)
}
