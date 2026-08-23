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

/// 鍵盤外觀編輯器網站（plateaukao/ohmybias-skin — 匯出 .cskin 後回本頁匯入）
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

        // ── 主題 ──
        root.addView(sectionTitle("主題"))
        skinStatus = footnote("")
        root.addView(skinStatus)
        root.addView(buttonFlow(
            "鍵盤外觀編輯器" to {
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
                skinMessage.text = "已還原內建主題 — 重開鍵盤生效"
                refreshSkinStatus()
            },
        ))
        skinMessage = statusFootnote()
        root.addView(skinMessage)

        // ── 聯想 ──
        root.addView(sectionTitle("聯想"))
        root.addView(toggle("聯想詞（萌典詞組）", Prefs.suggestEnabled) { Prefs.suggestEnabled = it })
        root.addView(buttonFlow("常用語設定" to { startActivity(Intent(this, UserPhrasesActivity::class.java)) }))

        // ── 輸入 ──
        root.addView(sectionTitle("輸入"))
        root.addView(toggle("唯一候選自動送出", Prefs.autoCommit) { Prefs.autoCommit = it })
        root.addView(toggle("滿碼頂字上屏", Prefs.overflowAutoCommit) { Prefs.overflowAutoCommit = it })
        root.addView(footnote("滿碼後續打自動送出首選。開啟時 weekly 這類前四碼恰為字根的英文字無法直通"))
        root.addView(toggle("相鄰鍵模糊比對", Prefs.fuzzyMatch) { Prefs.fuzzyMatch = it })
        root.addView(toggle("送字後顯示字根提示", Prefs.showCodeHint) { Prefs.showCodeHint = it })
        root.addView(toggle("成對標點自動補右半", Prefs.punctuationPairing) { Prefs.punctuationPairing = it })
        root.addView(toggle("按鍵觸覺回饋", Prefs.hapticFeedback) { Prefs.hapticFeedback = it })
        root.addView(toggle("米模式字母鍵顯示大寫", Prefs.uppercaseLettersInChinese) { Prefs.uppercaseLettersInChinese = it })
        root.addView(toggle("隱藏 🌐 鍵（空白鍵加寬）", Prefs.hideGlobeKey) { Prefs.hideGlobeKey = it })
        root.addView(footnote("隱藏後長按工具列「米/英」可開輸入法選單"))
        root.addView(toggle("組字候選時保留工具列", Prefs.keepToolbarWithCandidates) { Prefs.keepToolbarWithCandidates = it })
        root.addView(footnote("候選列覆蓋在工具列上（同聯想列），右側固定留一顆鍵可點"))
        root.addView(toggle("同音字含罕見讀音", Prefs.homophoneMultiReading) { Prefs.homophoneMultiReading = it })

        // 鍵盤高度滑桿（大螢幕手機可調大；拖曳時下方測試輸入框的鍵盤即時跟著變）
        val heightLabel = footnote("")
        fun heightPct() = (Prefs.keyboardHeightScale * 100).toInt()
        fun updateHeightLabel() { heightLabel.text = "鍵盤高度：${heightPct()}%（85–140，即時生效）" }
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

        // 按鍵間距滑桿（調小 = 鍵面更大更緊湊；0% 鍵與鍵完全貼合）
        val gapLabel = footnote("")
        fun gapPct() = (Prefs.keySpacingScale * 100).toInt()
        fun updateGapLabel() { gapLabel.text = "按鍵間距：${gapPct()}%（0–150，100 = 預設，即時生效）" }
        updateGapLabel()
        gapLabel.setPadding(0, dp(10f), 0, dp(2f))
        root.addView(gapLabel)
        val gapSeek = SeekBar(this)
        gapSeek.max = 150
        gapSeek.progress = gapPct().coerceIn(0, 150)
        gapSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                Prefs.keySpacingScale = progress / 100f
                updateGapLabel()
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
        root.addView(gapSeek, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(footnote("鍵盤上下留白、排距與鍵距一起縮放；調小則鍵面變大更好按"))

        // ── 測試輸入 ──
        root.addView(sectionTitle("測試輸入"))
        val testInput = EditText(this)
        testInput.hint = "在此測試鍵盤輸入"
        testInput.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 18f)
        root.addView(testInput, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        // ── 使用說明 ──（原本鍵盤內 ,,H 的完整說明搬來這裡，指令不再佔一個命令碼）
        root.addView(sectionTitle("使用說明"))
        root.addView(footnote(
            """
            ▎基本輸入
            輸入字根碼後按空白鍵送出；V/R/S/F 快速選第 2/3/4/5 個候選字；
            多候選時數字鍵 1–9 選字。

            ▎空白鍵手勢
            左右拖曳：移動游標
            上滑：中↔英快速切換
            右上滑：注音查碼　左上滑：同音字查詢

            ▎鍵盤切換
            [123] 切到數字符號頁；[符] 切到數字頁（嘸蝦米第三行）；
            [米/英] 從數字符號頁回字母頁。

            ▎尺寸調整
            上方滑桿可調鍵盤高度（85–140%）與按鍵間距（0–150%），拖曳即時生效。
            """.trimIndent()
        ))

        root.addView(sectionTitle("指令速查"))
        root.addView(footnote("在輸入框打 ,, 開頭的指令（不必送出）"))
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
            """.trimIndent()
        )
        cheat.typeface = Typeface.MONOSPACE
        root.addView(cheat)

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

    /// liu.cin 動輒數 MB，來源又可能是雲端硬碟（下載時間不定）—— 複製＋編譯全放背景執行緒，
    /// 免得主執行緒卡到 ANR。
    private fun handleCinImport(uri: Uri) {
        importMessage.text = "匯入中…"
        Thread({
            val message = importCin(uri)
            runOnUiThread {
                importMessage.text = message
                refreshTableStatus()
            }
        }, "ohmybias-import").start()
    }

    /// 全程寫暫存檔、成功才 rename 就位：
    /// - 中途失敗（來源讀到一半斷線、不是有效 .cin）不會動到現有字表；
    /// - rename 不影響鍵盤已 mmap 的舊 liu.bin —— 舊 inode 活到它重載為止，
    ///   不會讀到寫一半的內容，檔案變短也不會 SIGBUS。
    private fun importCin(uri: Uri): String {
        val cin = File(AppEnv.cinPath)
        val bin = File(AppEnv.sharedDir, "liu.bin")
        val cinTmp = File(cin.path + ".tmp")
        val binTmp = File(bin.path + ".tmp")
        try {
            contentResolver.openInputStream(uri)?.use { input ->
                cinTmp.outputStream().use { input.copyTo(it) }
            } ?: return "讀取失敗"
            val count = CINCompiler.compile(cinTmp.path, binTmp.path)
            if (count <= 0) return "編譯失敗 — 請確認是有效的 .cin 檔"
            // bin 是實際被查的檔，最後才就位 = 整個匯入的提交點
            if (!cinTmp.renameTo(cin) || !binTmp.renameTo(bin)) return "寫入失敗 — 儲存空間可能不足"
            CINTable.bumpGeneration()  // 鍵盤同 process — 下次進輸入框自動重載
            return "已編譯 $count 個字碼"
        } catch (e: Exception) {
            return "匯入失敗：${e.message}"
        } finally {
            cinTmp.delete(); binTmp.delete()
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
    /// 非使用者主動選檔，先顯示主題名稱確認再套用
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
        val displayName = name?.takeIf { it.isNotEmpty() } ?: "未命名主題"
        AlertDialog.Builder(this)
            .setTitle("套用主題")
            .setMessage("要套用主題「$displayName」嗎？\n（會取代目前的主題，重開鍵盤生效）")
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
        skinStatus.text = "目前主題：${SkinSettings.shared.skinName}"
    }
}
