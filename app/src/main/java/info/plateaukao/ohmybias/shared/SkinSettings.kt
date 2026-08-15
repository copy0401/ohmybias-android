package info.plateaukao.ohmybias.shared

import org.json.JSONObject
import java.io.File

/// cskin 皮膚設定 — 讀取匯入的 .cskin 內 `jsonnet/settings.json`（存於 sharedDir
/// 的 skin_settings.json）。未匯入時用內建 sweetlime 預設值。
/// 只讀「配置」層（工具列/調色盤/字級/版面選項）；jsonnet 鍵盤佈局編譯不在此層。
class SkinSettings private constructor() {
    companion object {
        val shared: SkinSettings by lazy { SkinSettings() }

        val settingsPath: String get() = AppEnv.sharedDir + "/skin_settings.json"

        /// 內建預設工具列（sweetlime toolbarButtons，全選(10)位置依使用者決定放 ♥ 常用語(5)）
        val defaultToolbarButtons = listOf(1, 3, 9, 7, 16, 17, 8, 5, 13, 2)
    }

    /// 設定世代 — reload()/apply() 時遞增；KeyboardTheme 據此判斷解析快取是否過期
    var generation = 0; private set

    var skinName = "sweetlime（內建）"; private set
    var isImported = false; private set
    var toolbarButtons: List<Int> = defaultToolbarButtons; private set

    /// 'panel' = 九宮格數字、'row' = Row 數字
    var keyboardLayout = "panel"; private set

    /// '1' 空白最小（逗號句號較大）、'2' 適中、'3' 空白最大
    var spaceKeyLayout = "3"; private set

    /// '2' 長按選單大寫在首位、'1' 小寫在首位
    var longPressLayout = "2"; private set

    /// 全域開關：swipeUp / swipeDown / longPress / showSwipeUpText / showSwipeDownText
    var enabledFeatures: Set<String> = defaultFeatures(); private set

    private var paletteLight: JSONObject = JSONObject()
    private var paletteDark: JSONObject = JSONObject()
    private var fontGroups: Map<String, Double> = emptyMap()

    init {
        reload()
    }

    private fun defaults() {
        skinName = "sweetlime（內建）"
        isImported = false
        toolbarButtons = defaultToolbarButtons
        keyboardLayout = "panel"
        spaceKeyLayout = "3"
        longPressLayout = "2"
        enabledFeatures = defaultFeatures()
        paletteLight = JSONObject(); paletteDark = JSONObject(); fontGroups = emptyMap()
    }

    fun reload() {
        generation += 1
        defaults()
        val f = File(settingsPath)
        if (!f.exists()) return
        val data = try { f.readText(Charsets.UTF_8) } catch (e: Exception) { return }
        apply(data)
    }

    /// 解析 settings.json 內容（獨立出來供測試餵資料）。
    /// 支援兩種 schema：舊版巢狀（toolbar/layout/swipe/globalSettings 區塊）與
    /// 新版扁平（toolbarButtons/palette/groups/spaceKeyLayout 直接在頂層、
    /// 滑動開關為 enableSwipeUpActions 等布林）— 新版 cskin 匯出器已改用扁平格式。
    fun apply(jsonText: String) {
        generation += 1
        val root = try { JSONObject(jsonText) } catch (e: Exception) { return }
        isImported = true
        root.optJSONObject("skinInfo")?.optString("name")?.let {
            if (it.isNotEmpty()) skinName = it
        }
        // 工具列：新版頂層 / 舊版 toolbar 區塊
        val buttons = root.optJSONArray("toolbarButtons")
            ?: root.optJSONObject("toolbar")?.optJSONArray("toolbarButtons")
        buttons?.let {
            val ids = ArrayList<Int>()
            for (i in 0 until it.length()) {
                val v = it.opt(i)
                if (v is Number) ids.add(v.toInt())
            }
            if (ids.isNotEmpty()) toolbarButtons = ids
        }
        // 版面：新版頂層鍵優先，舊版 layout 區塊 fallback
        optStr(root, "spaceKeyLayout")?.let { spaceKeyLayout = it }
        optStr(root, "keyboardLayout")?.let { keyboardLayout = it }
        optStr(root, "longPressLayout")?.let { longPressLayout = it }
        root.optJSONObject("layout")?.let { layout ->
            optStr(layout, "keyboardLayout")?.let { keyboardLayout = it }
            optStr(layout, "spaceKeyLayout")?.let { spaceKeyLayout = it }
            optStr(layout, "longPressLayout")?.let { longPressLayout = it }
        }
        // 滑動/長按開關：新版布林旗標
        if (root.has("enableSwipeUpActions") || root.has("enableSwipeDownActions") ||
            root.has("enableLongPressActions")
        ) {
            val set = HashSet<String>()
            if (root.optBoolean("enableSwipeUpActions", true)) set.add("swipeUp")
            if (root.optBoolean("enableSwipeDownActions", true)) set.add("swipeDown")
            if (root.optBoolean("enableLongPressActions", true)) set.add("longPress")
            if (root.optBoolean("showSwipeUpText", true)) set.add("showSwipeUpText")
            if (root.optBoolean("showSwipeDownText", true)) set.add("showSwipeDownText")
            enabledFeatures = set
        }
        // 舊版 swipe 區塊
        root.optJSONObject("swipe")?.optJSONArray("globalEnabledFeatures")?.let { features ->
            val set = HashSet<String>()
            for (i in 0 until features.length()) set.add(features.getString(i))
            enabledFeatures = set
        }
        // 調色盤/字級：新版頂層 / 舊版 globalSettings 區塊
        val paletteObj = root.optJSONObject("palette")
            ?: root.optJSONObject("globalSettings")?.optJSONObject("palette")
        paletteObj?.let { palette ->
            paletteLight = palette.optJSONObject("light") ?: JSONObject()
            paletteDark = palette.optJSONObject("dark") ?: JSONObject()
        }
        val groupsObj = root.optJSONObject("groups")
            ?: root.optJSONObject("globalSettings")?.optJSONObject("groups")
        groupsObj?.let { groups ->
            val m = HashMap<String, Double>()
            for (k in groups.keys()) {
                val v = groups.opt(k)
                if (v is Number) m[k] = v.toDouble()
            }
            fontGroups = m
        }
    }

    private fun optStr(obj: JSONObject, key: String): String? =
        (obj.opt(key) as? String)?.takeIf { it.isNotEmpty() }

    // MARK: - 查詢

    val swipeUpEnabled: Boolean get() = "swipeUp" in enabledFeatures
    val swipeDownEnabled: Boolean get() = "swipeDown" in enabledFeatures
    val longPressEnabled: Boolean get() = "longPress" in enabledFeatures
    val showSwipeUpText: Boolean get() = "showSwipeUpText" in enabledFeatures
    val showSwipeDownText: Boolean get() = "showSwipeDownText" in enabledFeatures

    /// 調色盤色值（#RRGGBB / #RRGGBBAA 字串）；未定義回 null 由呼叫端用預設
    fun colorHex(key: String, dark: Boolean): String? {
        val v = (if (dark) paletteDark else paletteLight).opt(key)
        return v as? String
    }

    /// 調色盤數值（如 borderSize）
    fun paletteNumber(key: String, dark: Boolean): Double? {
        val v = (if (dark) paletteDark else paletteLight).opt(key)
        return (v as? Number)?.toDouble()
    }

    /// 字級（globalSettings.groups）
    fun fontSize(key: String, default: Double): Double = fontGroups[key] ?: default
}

private fun defaultFeatures(): Set<String> =
    setOf("swipeUp", "swipeDown", "longPress", "showSwipeUpText", "showSwipeDownText")
