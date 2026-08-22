package info.plateaukao.ohmybias.android

import android.content.Context
import android.content.SharedPreferences
import info.plateaukao.ohmybias.shared.DefaultPreferences
import info.plateaukao.ohmybias.shared.IMEPreferences

/// 偏好設定 — SharedPreferences（單一 APK，IME 與設定頁直接共用；對應 iOS App Group UserDefaults）。
object Prefs : IMEPreferences {
    private lateinit var sp: SharedPreferences

    fun install(context: Context) {
        sp = context.getSharedPreferences("OhMyBiasPrefs", Context.MODE_PRIVATE)
        DefaultPreferences.backing = this
        DefaultPreferences.setSuggestEnabled = { suggestEnabled = it }
    }

    /// 監看偏好變更 — IME 與設定頁同 process，設定頁一改鍵盤就能即時反應
    /// （SharedPreferences 只保 weak reference，呼叫端須自行持有 listener）
    fun addListener(l: SharedPreferences.OnSharedPreferenceChangeListener) =
        sp.registerOnSharedPreferenceChangeListener(l)

    fun removeListener(l: SharedPreferences.OnSharedPreferenceChangeListener) =
        sp.unregisterOnSharedPreferenceChangeListener(l)

    /// 唯一候選且無法再延伸時自動送出
    override var autoCommit: Boolean
        get() = sp.getBoolean("autoCommit", false)
        set(v) = sp.edit().putBoolean("autoCommit", v).apply()

    /// 頂字上屏：滿碼且有候選時，下一鍵自動送出首選、開始下一字。
    /// 預設關 — 開啟時像 weekly 這種前四碼是有效字根的英文字無法直通
    override var overflowAutoCommit: Boolean
        get() = sp.getBoolean("overflowAutoCommit", false)
        set(v) = sp.edit().putBoolean("overflowAutoCommit", v).apply()

    /// 送字後顯示字根碼提示
    override var showCodeHint: Boolean
        get() = sp.getBoolean("showCodeHint", false)
        set(v) = sp.edit().putBoolean("showCodeHint", v).apply()

    /// 聯想總開關
    override var suggestEnabled: Boolean
        get() = sp.getBoolean("suggestEnabled", true)
        set(v) = sp.edit().putBoolean("suggestEnabled", v).apply()

    /// 無候選時嘗試相鄰鍵模糊比對
    override var fuzzyMatch: Boolean
        get() = sp.getBoolean("fuzzyMatch", true)
        set(v) = sp.edit().putBoolean("fuzzyMatch", v).apply()

    /// 聯想策略（極簡版僅萌典詞組，維持介面相容）
    override var suggestStrategy: String
        get() = sp.getString("suggestStrategy", "general") ?: "general"
        set(v) = sp.edit().putString("suggestStrategy", v).apply()

    /// 詞級語料 — 極簡版只有萌典
    override var wordCorpus: String
        get() = sp.getString("wordCorpus", "moedict") ?: "moedict"
        set(v) = sp.edit().putString("wordCorpus", v).apply()

    /// 字級聯想（bigram/trigram）— 極簡版無字級語料，保留介面
    override var charSuggest: Boolean
        get() = sp.getBoolean("charSuggest", true)
        set(v) = sp.edit().putBoolean("charSuggest", v).apply()

    /// 地區用詞：tw / cn（極簡版無地區語料，保留介面）
    override var regionVariant: String
        get() = sp.getString("regionVariant", "tw") ?: "tw"
        set(v) = sp.edit().putString("regionVariant", v).apply()

    /// 標點配對：打「自動補」
    override var punctuationPairing: Boolean
        get() = sp.getBoolean("punctuationPairing", true)
        set(v) = sp.edit().putBoolean("punctuationPairing", v).apply()

    /// 同音字查詢包含多音字的罕見讀音
    override var homophoneMultiReading: Boolean
        get() = sp.getBoolean("homophoneMultiReading", false)
        set(v) = sp.edit().putBoolean("homophoneMultiReading", v).apply()

    /// 上次使用的語言模式（true = 英文）— 鍵盤啟動時還原
    var lastEnglishMode: Boolean
        get() = sp.getBoolean("lastEnglishMode", false)
        set(v) = sp.edit().putBoolean("lastEnglishMode", v).apply()

    /// 中文（米）模式字母鍵以大寫顯示 — 嘸蝦米字根表慣用大寫，與實體鍵帽一致；
    /// 只影響鍵面標籤，送出的組字碼不變。英文模式仍依 shift 決定大小寫（同 iOS 鍵名）
    var uppercaseLettersInChinese: Boolean
        get() = sp.getBoolean("uppercaseLettersInChinese", false)
        set(v) = sp.edit().putBoolean("uppercaseLettersInChinese", v).apply()

    /// 按鍵觸覺回饋
    var hapticFeedback: Boolean
        get() = sp.getBoolean("hapticFeedback", true)
        set(v) = sp.edit().putBoolean("hapticFeedback", v).apply()

    /// 隱藏底列 🌐 鍵（空白鍵加寬；長按工具列米/英仍可開輸入法選單）
    var hideGlobeKey: Boolean
        get() = sp.getBoolean("hideGlobeKey", false)
        set(v) = sp.edit().putBoolean("hideGlobeKey", v).apply()

    /// 組字候選時保留工具列：候選改為覆蓋在工具列上（同聯想列），右側留一顆鍵可點。
    /// 預設關 — 候選整條佔滿
    var keepToolbarWithCandidates: Boolean
        get() = sp.getBoolean("keepToolbarWithCandidates", false)
        set(v) = sp.edit().putBoolean("keepToolbarWithCandidates", v).apply()

    /// 鍵盤高度縮放（0.85–1.40；1.0 = 預設 224dp/180dp）— 大螢幕手機可調大
    var keyboardHeightScale: Float
        get() = sp.getFloat("keyboardHeightScale", 1.0f)
        set(v) = sp.edit().putFloat("keyboardHeightScale", v.coerceIn(0.85f, 1.4f)).apply()

    /// 按鍵間距縮放（0.0–1.5；1.0 = 預設 上下 6dp／左右 3dp／排距 8dp／鍵距 5dp）—
    /// 調小讓鍵面更大更好按，0 = 鍵與鍵完全貼合
    var keySpacingScale: Float
        get() = sp.getFloat("keySpacingScale", 1.0f)
        set(v) = sp.edit().putFloat("keySpacingScale", v.coerceIn(0f, 1.5f)).apply()

    /// 詞庫開關 — 極簡版僅萌典詞組，預設開啟
    override fun domainEnabled(key: String): Boolean =
        if (sp.contains(key)) sp.getBoolean(key, false) else key == "domain_phrases"

    override fun domainPriority(key: String): Int = sp.getInt(key + "_pri", 0)
}
