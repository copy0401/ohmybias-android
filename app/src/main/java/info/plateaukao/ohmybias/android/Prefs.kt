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

    /// 唯一候選且無法再延伸時自動送出
    override var autoCommit: Boolean
        get() = sp.getBoolean("autoCommit", false)
        set(v) = sp.edit().putBoolean("autoCommit", v).apply()

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

    /// 按鍵觸覺回饋
    var hapticFeedback: Boolean
        get() = sp.getBoolean("hapticFeedback", true)
        set(v) = sp.edit().putBoolean("hapticFeedback", v).apply()

    /// 隱藏底列 🌐 鍵（空白鍵加寬；長按工具列米/英仍可開輸入法選單）
    var hideGlobeKey: Boolean
        get() = sp.getBoolean("hideGlobeKey", false)
        set(v) = sp.edit().putBoolean("hideGlobeKey", v).apply()

    /// 鍵盤高度縮放（0.85–1.40；1.0 = 預設 224dp/180dp）— 大螢幕手機可調大
    var keyboardHeightScale: Float
        get() = sp.getFloat("keyboardHeightScale", 1.0f)
        set(v) = sp.edit().putFloat("keyboardHeightScale", v.coerceIn(0.85f, 1.4f)).apply()

    /// Debug 記錄至 sharedDir/debug.log
    var debugMode: Boolean
        get() = sp.getBoolean("debugMode", false)
        set(v) = sp.edit().putBoolean("debugMode", v).apply()

    /// 詞庫開關 — 極簡版僅萌典詞組，預設開啟
    override fun domainEnabled(key: String): Boolean =
        if (sp.contains(key)) sp.getBoolean(key, false) else key == "domain_phrases"

    override fun domainPriority(key: String): Int = sp.getInt(key + "_pri", 0)
}
