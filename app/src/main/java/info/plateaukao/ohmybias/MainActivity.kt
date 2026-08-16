package info.plateaukao.ohmybias

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import info.plateaukao.ohmybias.android.Prefs
import info.plateaukao.ohmybias.shared.AppEnv
import info.plateaukao.ohmybias.shared.CINCompiler
import info.plateaukao.ohmybias.shared.CINTable
import info.plateaukao.ohmybias.shared.SkinSettings
import org.json.JSONObject
import java.io.File
import java.util.zip.ZipInputStream

/// 皮膚設計器網站（plateaukao/ohmybias-skin — 匯出 .cskin 後回本頁匯入）
private const val SKIN_DESIGNER_URL = "https://plateaukao.github.io/ohmybias-skin/"

/// 設定頁 — 對應 iOS ContentView：啟用鍵盤、匯入 liu.cin、皮膚、偏好 toggle、
/// 自訂詞編輯、指令速查；外加「測試輸入」欄位供模擬器驗證。
class MainActivity : Activity() {

    private val pickCinRequest = 1001
    private val pickSkinRequest = 1002

    private lateinit var tableStatus: TextView
    private lateinit var importMessage: TextView
    private lateinit var skinStatus: TextView
    private lateinit var skinMessage: TextView

    private fun dp(v: Float): Int = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "OhMyBias 米"

        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setPadding(dp(16f), dp(8f), dp(16f), dp(24f))

        // ── 啟用鍵盤 ──
        root.addView(sectionTitle("啟用鍵盤"))
        root.addView(footnote("設定 ▸ 系統 ▸ 語言與輸入法 ▸ 螢幕鍵盤 ▸ 啟用「OhMyBias 米」"))
        root.addView(buttonFlow(
            "打開鍵盤設定" to { startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)) },
            "切換輸入法" to {
                (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).showInputMethodPicker()
            },
        ))

        // ── 字表 ──
        root.addView(sectionTitle("字表"))
        tableStatus = footnote("")
        root.addView(tableStatus)
        root.addView(buttonFlow(
            "匯入 liu.cin" to {
                val intent = Intent(Intent.ACTION_OPEN_DOCUMENT)
                intent.addCategory(Intent.CATEGORY_OPENABLE)
                intent.type = "*/*"
                startActivityForResult(intent, pickCinRequest)
            },
        ))
        importMessage = statusFootnote()
        root.addView(importMessage)

        // ── 皮膚 ──
        root.addView(sectionTitle("皮膚"))
        skinStatus = footnote("")
        root.addView(skinStatus)
        root.addView(buttonFlow(
            "皮膚設計器" to {
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(SKIN_DESIGNER_URL)))
                } catch (e: Exception) {
                    skinMessage.text = "找不到瀏覽器 — 請自行開啟 $SKIN_DESIGNER_URL"
                }
            },
            "匯入 .cskin" to {
                val intent = Intent(Intent.ACTION_OPEN_DOCUMENT)
                intent.addCategory(Intent.CATEGORY_OPENABLE)
                intent.type = "*/*"
                startActivityForResult(intent, pickSkinRequest)
            },
            "還原內建" to {
                File(SkinSettings.settingsPath).delete()
                SkinSettings.shared.reload()
                skinMessage.text = "已還原內建皮膚 — 重開鍵盤生效"
                refreshSkinStatus()
            },
        ))
        skinMessage = statusFootnote()
        root.addView(skinMessage)

        // ── 聯想 ──
        root.addView(sectionTitle("聯想"))
        root.addView(toggle("聯想詞（萌典詞組）", Prefs.suggestEnabled) { Prefs.suggestEnabled = it })
        root.addView(buttonFlow("自訂詞（user_phrases.txt）" to { showUserPhrasesEditor() }))

        // ── 輸入 ──
        root.addView(sectionTitle("輸入"))
        root.addView(toggle("唯一候選自動送出", Prefs.autoCommit) { Prefs.autoCommit = it })
        root.addView(toggle("滿碼頂字上屏", Prefs.overflowAutoCommit) { Prefs.overflowAutoCommit = it })
        root.addView(footnote("滿碼後續打自動送出首選。開啟時 weekly 這類前四碼恰為字根的英文字無法直通"))
        root.addView(toggle("相鄰鍵模糊比對", Prefs.fuzzyMatch) { Prefs.fuzzyMatch = it })
        root.addView(toggle("送字後顯示字根提示", Prefs.showCodeHint) { Prefs.showCodeHint = it })
        root.addView(toggle("成對標點自動補右半", Prefs.punctuationPairing) { Prefs.punctuationPairing = it })
        root.addView(toggle("按鍵觸覺回饋", Prefs.hapticFeedback) { Prefs.hapticFeedback = it })
        root.addView(toggle("隱藏 🌐 鍵（空白鍵加寬）", Prefs.hideGlobeKey) { Prefs.hideGlobeKey = it })
        root.addView(footnote("隱藏後長按工具列「米/英」可開輸入法選單"))
        root.addView(toggle("同音字含罕見讀音", Prefs.homophoneMultiReading) { Prefs.homophoneMultiReading = it })

        // 鍵盤高度滑桿（大螢幕手機可調大；重開鍵盤生效 — 下方測試輸入框可即時預覽）
        val heightLabel = footnote("")
        fun heightPct() = (Prefs.keyboardHeightScale * 100).toInt()
        fun updateHeightLabel() { heightLabel.text = "鍵盤高度：${heightPct()}%（85–140，重開鍵盤生效）" }
        updateHeightLabel()
        heightLabel.setPadding(0, dp(10f), 0, dp(2f))
        root.addView(heightLabel)
        val heightSeek = SeekBar(this)
        heightSeek.max = 55  // 85% + progress → 85%..140%
        heightSeek.progress = (heightPct() - 85).coerceIn(0, 55)
        heightSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                Prefs.keyboardHeightScale = (85 + progress) / 100f
                updateHeightLabel()
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
        root.addView(heightSeek, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        // ── 測試輸入 ──
        root.addView(sectionTitle("測試輸入"))
        val testInput = EditText(this)
        testInput.hint = "在此測試鍵盤輸入"
        testInput.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 18f)
        root.addView(testInput, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        // ── 指令速查 ──
        root.addView(sectionTitle("指令速查"))
        val cheat = footnote(
            """
            ,,T 繁體  ,,S 簡體  ,,J 日文
            ,,SP 速成  ,,SL 慢打
            ,,TS 繁→簡  ,,ST 簡→繁
            ,,ZH 注音查碼  ,,TO 同音字
            ,,PYS 拼音(簡)  ,,PYT 拼音(繁)
            ,,SG 聯想開關  ,,C 目前模式
            ,,PIN 固定排序  ,,UNPINx 解除
            ,,RS 重置字頻  ,,RL 重載字表
            ,,V 貼上純文字  ,,VT 簡→繁  ,,VS 繁→簡
            ,,H 完整說明
            """.trimIndent()
        )
        cheat.typeface = Typeface.MONOSPACE
        root.addView(cheat)

        // ── 進階 ──
        root.addView(sectionTitle("進階"))
        root.addView(toggle("Debug 記錄", Prefs.debugMode) { Prefs.debugMode = it })

        val scroll = ScrollView(this)
        scroll.addView(root)
        // targetSdk 36 強制 edge-to-edge（Android 15+ 系統列透明、內容延伸到底下）—
        // 以 insets 當 padding 讓內容避開狀態列/導覽列；API 29 無此強制、維持原樣
        if (Build.VERSION.SDK_INT >= 30) {
            scroll.setOnApplyWindowInsetsListener { v, insets ->
                val bars = insets.getInsets(
                    WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout()
                )
                v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
                WindowInsets.CONSUMED
            }
        }
        setContentView(scroll)

        refreshTableStatus()
        refreshSkinStatus()

        // 由檔案管理員／瀏覽器點 .cskin 進來（VIEW intent）— 詢問後套用
        handleViewIntent(intent)
    }

    /// singleTop：設定頁已在最上層時再點 .cskin，intent 從這裡進來
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleViewIntent(intent)
    }

    // MARK: - UI helpers（Android 設定頁風格：accent 分類標題＋ripple 可點列，
    // 取代原本滿版 Material Button — 直向 LinearLayout 預設子視圖 MATCH_PARENT，
    // Button 又自帶大 minHeight/內距，才會整顆撐滿螢幕又佔高）

    /// 解析主題屬性色（colorAccent、textColorSecondary 這類可能是資源參照）
    private fun themeColor(attr: Int): Int {
        val tv = TypedValue()
        theme.resolveAttribute(attr, tv, true)
        return if (tv.resourceId != 0) resources.getColor(tv.resourceId, theme) else tv.data
    }

    private fun sectionTitle(text: String): TextView {
        val t = TextView(this)
        t.text = text
        t.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13f)
        t.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        t.setTextColor(themeColor(android.R.attr.colorAccent))
        t.setPadding(0, dp(20f), 0, dp(4f))
        return t
    }

    /// 動態狀態列 — 沒內容時整列隱藏，不留空隙
    private fun statusFootnote(): TextView = footnote("").apply {
        visibility = android.view.View.GONE
        addTextChangedListener(object : android.text.TextWatcher {
            override fun afterTextChanged(s: android.text.Editable?) {
                visibility = if (s.isNullOrEmpty()) android.view.View.GONE else android.view.View.VISIBLE
            }
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })
    }

    private fun footnote(text: String): TextView {
        val t = TextView(this)
        t.text = text
        t.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13f)
        t.setTextColor(themeColor(android.R.attr.textColorSecondary))
        t.setPadding(0, dp(2f), 0, dp(2f))
        return t
    }

    /// 緊湊 outlined 按鈕 — 透明底、細框圓角＋ripple（Material outlined 樣式），
    /// 並壓掉 Button 預設的大 minWidth/minHeight 與按壓浮起動畫
    private fun compactButton(title: String, onClick: () -> Unit): Button {
        val accent = themeColor(android.R.attr.colorAccent)
        val radius = dp(8f).toFloat()
        val outline = android.graphics.drawable.GradientDrawable().apply {
            setColor(android.graphics.Color.TRANSPARENT)
            cornerRadius = radius
            setStroke(dp(1f), (accent and 0xFFFFFF) or (0x99 shl 24))  // 邊框 = accent 60%
        }
        val mask = android.graphics.drawable.GradientDrawable().apply {
            setColor(android.graphics.Color.WHITE)
            cornerRadius = radius
        }
        val ripple = android.graphics.drawable.RippleDrawable(
            android.content.res.ColorStateList.valueOf((accent and 0xFFFFFF) or (0x33 shl 24)),
            outline, mask,
        )
        val b = Button(this)
        b.text = title
        b.isAllCaps = false
        b.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14f)
        b.setTextColor(accent)
        b.background = ripple
        b.stateListAnimator = null
        b.minWidth = 0; b.minimumWidth = 0
        b.minHeight = 0; b.minimumHeight = dp(40f)
        b.setPadding(dp(14f), 0, dp(14f), 0)
        b.setOnClickListener { onClick() }
        return b
    }

    /// 一列多顆按鈕、放不下自動換行 — 取代一顆一列的滿版大按鈕
    private fun buttonFlow(vararg items: Pair<String, () -> Unit>): ViewGroup {
        val flow = FlowLayout(this, dp(8f))
        for ((title, onClick) in items) flow.addView(compactButton(title, onClick))
        return flow
    }

    /// 簡易流式排版（同 CollectionPanelView 的做法）
    private class FlowLayout(context: android.content.Context, private val spacing: Int) : ViewGroup(context) {
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val w = MeasureSpec.getSize(widthMeasureSpec)
            var x = 0; var y = 0; var rowH = 0
            for (i in 0 until childCount) {
                val c = getChildAt(i)
                c.measure(
                    MeasureSpec.makeMeasureSpec(w, MeasureSpec.AT_MOST),
                    MeasureSpec.UNSPECIFIED,
                )
                if (x > 0 && x + c.measuredWidth > w) { x = 0; y += rowH + spacing; rowH = 0 }
                x += c.measuredWidth + spacing
                if (c.measuredHeight > rowH) rowH = c.measuredHeight
            }
            setMeasuredDimension(w, y + rowH)
        }

        override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
            val w = r - l
            var x = 0; var y = 0; var rowH = 0
            for (i in 0 until childCount) {
                val c = getChildAt(i)
                if (x > 0 && x + c.measuredWidth > w) { x = 0; y += rowH + spacing; rowH = 0 }
                c.layout(x, y, x + c.measuredWidth, y + c.measuredHeight)
                x += c.measuredWidth + spacing
                if (c.measuredHeight > rowH) rowH = c.measuredHeight
            }
        }
    }

    private fun toggle(text: String, initial: Boolean, onChange: (Boolean) -> Unit): Switch {
        @Suppress("UseSwitchCompatOrMaterialCode")
        val s = Switch(this)
        s.text = text
        s.isChecked = initial
        s.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16f)
        s.minHeight = dp(48f)
        s.gravity = Gravity.CENTER_VERTICAL
        s.setOnCheckedChangeListener { _, checked -> onChange(checked) }
        return s
    }

    // MARK: - 匯入

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        when (requestCode) {
            pickCinRequest -> handleCinImport(uri)
            pickSkinRequest -> handleSkinImport(uri)
        }
    }

    private fun handleCinImport(uri: Uri) {
        try {
            val dst = File(AppEnv.cinPath)
            contentResolver.openInputStream(uri)?.use { input ->
                dst.outputStream().use { input.copyTo(it) }
            } ?: run { importMessage.text = "讀取失敗"; return }
            val binDst = AppEnv.sharedDir + "/liu.bin"
            val count = CINCompiler.compile(dst.path, binDst)
            importMessage.text = if (count > 0) "已編譯 $count 個字碼" else "編譯失敗 — 請確認是有效的 .cin 檔"
            refreshTableStatus()
        } catch (e: Exception) {
            importMessage.text = "匯入失敗：${e.message}"
        }
    }

    /// .cskin = zip，取其中 jsonnet/settings.json 的配置層；非 zip 或缺檔回 null
    private fun readSkinSettingsJson(uri: Uri): ByteArray? {
        var settingsJson: ByteArray? = null
        contentResolver.openInputStream(uri)?.use { input ->
            ZipInputStream(input).use { zip ->
                var entry = zip.nextEntry
                var fallback: ByteArray? = null
                while (entry != null) {
                    if (entry.name.endsWith("jsonnet/settings.json")) {
                        settingsJson = zip.readBytes(); break
                    }
                    if (entry.name.endsWith("settings.json") && fallback == null) {
                        fallback = zip.readBytes()
                    }
                    entry = zip.nextEntry
                }
                if (settingsJson == null) settingsJson = fallback
            }
        }
        return settingsJson
    }

    private fun applySkinJson(json: ByteArray) {
        File(SkinSettings.settingsPath).writeBytes(json)
        SkinSettings.shared.reload()
        skinMessage.text = if (SkinSettings.shared.isImported)
            "已套用「${SkinSettings.shared.skinName}」— 重開鍵盤生效"
        else "settings.json 格式無法解析"
        refreshSkinStatus()
    }

    /// SAF 選檔匯入：直接套用（使用者已在選檔時表達意圖）
    private fun handleSkinImport(uri: Uri) {
        try {
            val json = readSkinSettingsJson(uri) ?: run {
                skinMessage.text = "找不到 settings.json — 請確認是有效的 .cskin 檔"; return
            }
            applySkinJson(json)
        } catch (e: Exception) {
            skinMessage.text = "匯入失敗：${e.message}"
        }
    }

    /// 檔案管理員／瀏覽器點 .cskin 開啟本 app（manifest VIEW intent-filter）—
    /// 非使用者主動選檔，先顯示皮膚名稱確認再套用
    private fun handleViewIntent(intent: Intent) {
        if (intent.action != Intent.ACTION_VIEW) return
        val uri = intent.data ?: return
        val json = try { readSkinSettingsJson(uri) } catch (e: Exception) { null }
        if (json == null) {
            skinMessage.text = "找不到 settings.json — 請確認是有效的 .cskin 檔"
            return
        }
        val name = try {
            JSONObject(String(json, Charsets.UTF_8))
                .optJSONObject("skinInfo")?.optString("name")
        } catch (e: Exception) { null }
        val displayName = name?.takeIf { it.isNotEmpty() } ?: "未命名皮膚"
        AlertDialog.Builder(this)
            .setTitle("套用皮膚")
            .setMessage("要套用皮膚「$displayName」嗎？\n（會取代目前的皮膚，重開鍵盤生效）")
            .setPositiveButton("套用") { _, _ -> applySkinJson(json) }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun refreshTableStatus() {
        val table = CINTable()
        table.reload()
        tableStatus.text = if (table.isEmpty) {
            "尚未匯入 liu.cin — 鍵盤無法輸出中文"
        } else {
            val name = table.cinName.ifEmpty { "字表" }
            "已載入：$name（最長碼 ${table.maxCodeLength}）"
        }
    }

    private fun refreshSkinStatus() {
        SkinSettings.shared.reload()
        skinStatus.text = "目前皮膚：${SkinSettings.shared.skinName}"
    }

    // MARK: - 自訂詞編輯器（一行一詞，供聯想使用）

    private fun showUserPhrasesEditor() {
        val path = File(AppEnv.sharedDir, "user_phrases.txt")
        val edit = EditText(this)
        edit.setText(if (path.exists()) path.readText(Charsets.UTF_8) else "")
        edit.hint = "一行一詞，如「蝦米輸入法」\n打「蝦」即出現聯想"
        edit.minLines = 8
        edit.gravity = android.view.Gravity.TOP
        edit.setPadding(dp(16f), dp(12f), dp(16f), dp(12f))
        AlertDialog.Builder(this)
            .setTitle("自訂詞")
            .setView(edit)
            .setPositiveButton("儲存") { _, _ ->
                path.writeText(edit.text.toString(), Charsets.UTF_8)
            }
            .setNegativeButton("取消", null)
            .show()
    }
}
