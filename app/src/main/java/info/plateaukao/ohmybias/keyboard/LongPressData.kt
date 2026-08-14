package info.plateaukao.ohmybias.keyboard

import android.icu.text.DateFormat
import android.icu.text.SimpleDateFormat
import android.icu.util.Calendar
import android.icu.util.ULocale
import java.util.Date
import java.util.TimeZone

/// 長按選單資料 — 取自 sweetlime 皮膚 hintSymbolsData.libsonnet（longPressLayout '2'：大寫在首位）。
/// 字母鍵：大寫/小寫/變音符變體直接輸出；左半鍵盤選單向右展開（預設在首）、
/// 右半鍵盤鏡像（預設在尾），避免選單超出螢幕。
/// 逗號/句號鍵：皮膚原以 Rime 腳本輸出時間日期，此處以 android.icu 曆法原生實作。
object LongPressData {

    class Option(val label: String, val dateKind: DateKind? = null)

    class Menu(val options: List<Option>, val defaultIndex: Int)

    enum class DateKind {
        TIME,           // 21:03
        DATE_SLASH,     // 2026/08/14
        DATE_CHINESE,   // 二〇二六年八月十四日
        DATE_ROC,       // 民國115年8月14日
        DATE_JAPANESE,  // 令和8年8月14日
        DATE_ENGLISH,   // August 14, 2026
        DATE_LUNAR,     // 丙午年七月初二
        TIME_ZONE,      // GMT+8
    }

    /// 選取時解析選項為實際輸出文字
    fun resolve(option: Option): String {
        val kind = option.dateKind ?: return option.label
        val now = Date()
        return when (kind) {
            DateKind.TIME -> format(now, "zh-TW", "HH:mm")
            DateKind.DATE_SLASH -> format(now, "zh-TW", "yyyy/MM/dd")
            DateKind.DATE_CHINESE -> chineseDate(now)
            DateKind.DATE_ROC -> format(now, "zh-TW-u-ca-roc", "Gy年M月d日")
            DateKind.DATE_JAPANESE -> format(now, "ja-JP-u-ca-japanese", "Gy年M月d日")
            DateKind.DATE_ENGLISH -> format(now, "en-US", "MMMM d, yyyy")
            DateKind.DATE_LUNAR -> lunarDate(now)
            DateKind.TIME_ZONE -> TimeZone.getDefault().getDisplayName(false, TimeZone.SHORT)
                ?: TimeZone.getDefault().id
        }
    }

    private fun format(date: Date, localeTag: String, fmt: String): String {
        val loc = ULocale.forLanguageTag(localeTag)
        val f = SimpleDateFormat(fmt, loc)
        f.calendar = Calendar.getInstance(loc)
        return f.format(date)
    }

    /// 二〇二六年八月十四日
    private fun chineseDate(date: Date): String {
        val cal = java.util.Calendar.getInstance()
        cal.time = date
        val digits = listOf("〇", "一", "二", "三", "四", "五", "六", "七", "八", "九")
        val year = cal.get(java.util.Calendar.YEAR).toString().map { digits[it - '0'] }.joinToString("")
        fun number(n: Int): String {
            // 1–31：十進位中文讀法（十四、二十一）
            if (n <= 10) return if (n == 10) "十" else digits[n]
            val tens = n / 10; val ones = n % 10
            val head = if (tens == 1) "十" else "${digits[tens]}十"
            return if (ones == 0) head else head + digits[ones]
        }
        return "${year}年${number(cal.get(java.util.Calendar.MONTH) + 1)}月${number(cal.get(java.util.Calendar.DAY_OF_MONTH))}日"
    }

    /// 丙午年七月初二（去掉開頭的西元年數字）
    private fun lunarDate(date: Date): String {
        val loc = ULocale.forLanguageTag("zh-TW-u-ca-chinese")
        val f = DateFormat.getDateInstance(Calendar.getInstance(loc), DateFormat.LONG, loc)
        return f.format(date).dropWhile { it.isDigit() }
    }

    // MARK: - 字母鍵選單

    /// 各字母的變音符變體（小寫, 大寫）— 同 sweetlime
    private val accents: Map<String, Pair<String, String>> = mapOf(
        "q" to ("ɋ" to "Ɋ"), "w" to ("ẁ" to "Ẁ"), "e" to ("ē" to "Ē"), "r" to ("ŕ" to "Ŕ"), "t" to ("ṫ" to "Ṫ"),
        "y" to ("ȳ" to "Ȳ"), "u" to ("ū" to "Ū"), "i" to ("ī" to "Ī"), "o" to ("ō" to "Ō"), "p" to ("ṕ" to "Ṕ"),
        "a" to ("ā" to "Ā"), "s" to ("ś" to "Ś"), "d" to ("ḋ" to "Ḋ"), "f" to ("ḟ" to "Ḟ"), "g" to ("ḡ" to "Ḡ"),
        "h" to ("ḣ" to "Ḣ"), "j" to ("ĵ" to "Ĵ"), "k" to ("ḱ" to "Ḱ"), "l" to ("ĺ" to "Ĺ"),
        "z" to ("ź" to "Ź"), "x" to ("ẋ" to "Ẋ"), "c" to ("ć" to "Ć"), "v" to ("ṽ" to "Ṽ"),
        "b" to ("ḃ" to "Ḃ"), "n" to ("ń" to "Ń"), "m" to ("ḿ" to "Ḿ"),
    )

    /// 左半鍵盤（選單自鍵位向右展開）
    private val leftHalf: Set<String> = setOf("q", "w", "e", "r", "t", "a", "s", "d", "f", "g", "z", "x", "c", "v")

    /// 字母鍵長按選單；key 為小寫字母。
    /// 順序依 cskin longPressLayout：'2' 大寫在首位（預設）、'1' 小寫在首位。
    fun letterMenu(key: String): Menu? {
        val (accLower, accUpper) = accents[key] ?: return null
        val upper = key.uppercase()
        val upperFirst = info.plateaukao.ohmybias.shared.SkinSettings.shared.longPressLayout != "1"
        val near = if (upperFirst)
            listOf(Option(upper), Option(key), Option(accUpper), Option(accLower))
        else
            listOf(Option(key), Option(upper), Option(accLower), Option(accUpper))
        return if (key in leftHalf) Menu(near, 0)
        else Menu(near.reversed(), near.size - 1)
    }

    /// 逗號鍵：時間/日期插入
    val commaMenu = Menu(
        listOf(
            Option("時間", DateKind.TIME),
            Option("日期", DateKind.DATE_SLASH),
            Option("中文", DateKind.DATE_CHINESE),
            Option("民國", DateKind.DATE_ROC),
            Option("日本", DateKind.DATE_JAPANESE),
        ), 0
    )

    /// 句號鍵：英文/農曆/時區（皮膚的組合/節氣為 Rime 腳本，不移植）
    val periodMenu = Menu(
        listOf(
            Option("英文", DateKind.DATE_ENGLISH),
            Option("農曆", DateKind.DATE_LUNAR),
            Option("時區", DateKind.TIME_ZONE),
        ), 0
    )
}
