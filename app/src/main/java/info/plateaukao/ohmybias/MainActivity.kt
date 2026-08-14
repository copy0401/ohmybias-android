package info.plateaukao.ohmybias

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.util.TypedValue
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import info.plateaukao.ohmybias.android.Prefs
import info.plateaukao.ohmybias.shared.AppEnv
import info.plateaukao.ohmybias.shared.CINCompiler
import info.plateaukao.ohmybias.shared.CINTable
import info.plateaukao.ohmybias.shared.SkinSettings
import java.io.File
import java.util.zip.ZipInputStream

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
        root.setPadding(dp(20f), dp(16f), dp(20f), dp(24f))

        // ── 啟用鍵盤 ──
        root.addView(sectionTitle("啟用鍵盤"))
        root.addView(footnote("設定 → 系統 → 語言與輸入法 → 螢幕鍵盤 → 啟用「OhMyBias 米」"))
        root.addView(button("打開鍵盤設定") {
            startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
        })
        root.addView(button("切換輸入法") {
            (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).showInputMethodPicker()
        })

        // ── 字表 ──
        root.addView(sectionTitle("字表"))
        tableStatus = footnote("")
        root.addView(tableStatus)
        root.addView(button("匯入 liu.cin") {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT)
            intent.addCategory(Intent.CATEGORY_OPENABLE)
            intent.type = "*/*"
            startActivityForResult(intent, pickCinRequest)
        })
        importMessage = footnote("")
        root.addView(importMessage)

        // ── 皮膚 ──
        root.addView(sectionTitle("皮膚"))
        skinStatus = footnote("")
        root.addView(skinStatus)
        root.addView(button("匯入皮膚（.cskin）") {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT)
            intent.addCategory(Intent.CATEGORY_OPENABLE)
            intent.type = "*/*"
            startActivityForResult(intent, pickSkinRequest)
        })
        root.addView(button("還原內建皮膚") {
            File(SkinSettings.settingsPath).delete()
            SkinSettings.shared.reload()
            skinMessage.text = "已還原內建皮膚 — 重開鍵盤生效"
            refreshSkinStatus()
        })
        skinMessage = footnote("")
        root.addView(skinMessage)

        // ── 聯想 ──
        root.addView(sectionTitle("聯想"))
        root.addView(toggle("聯想詞（萌典詞組）", Prefs.suggestEnabled) { Prefs.suggestEnabled = it })
        root.addView(button("自訂詞（user_phrases.txt）") { showUserPhrasesEditor() })

        // ── 輸入 ──
        root.addView(sectionTitle("輸入"))
        root.addView(toggle("唯一候選自動送出", Prefs.autoCommit) { Prefs.autoCommit = it })
        root.addView(toggle("相鄰鍵模糊比對", Prefs.fuzzyMatch) { Prefs.fuzzyMatch = it })
        root.addView(toggle("送字後顯示字根提示", Prefs.showCodeHint) { Prefs.showCodeHint = it })
        root.addView(toggle("成對標點自動補右半", Prefs.punctuationPairing) { Prefs.punctuationPairing = it })
        root.addView(toggle("按鍵觸覺回饋", Prefs.hapticFeedback) { Prefs.hapticFeedback = it })
        root.addView(toggle("同音字含罕見讀音", Prefs.homophoneMultiReading) { Prefs.homophoneMultiReading = it })

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
        setContentView(scroll)

        refreshTableStatus()
        refreshSkinStatus()
    }

    // MARK: - UI helpers

    private fun sectionTitle(text: String): TextView {
        val t = TextView(this)
        t.text = text
        t.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14f)
        t.setTypeface(null, Typeface.BOLD)
        t.setPadding(0, dp(18f), 0, dp(6f))
        return t
    }

    private fun footnote(text: String): TextView {
        val t = TextView(this)
        t.text = text
        t.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13f)
        t.setPadding(0, dp(2f), 0, dp(2f))
        return t
    }

    private fun button(text: String, onClick: () -> Unit): Button {
        val b = Button(this)
        b.text = text
        b.isAllCaps = false
        b.setOnClickListener { onClick() }
        return b
    }

    private fun toggle(text: String, initial: Boolean, onChange: (Boolean) -> Unit): Switch {
        @Suppress("UseSwitchCompatOrMaterialCode")
        val s = Switch(this)
        s.text = text
        s.isChecked = initial
        s.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15f)
        s.setPadding(0, dp(6f), 0, dp(6f))
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

    /// .cskin = zip，取其中 jsonnet/settings.json 的配置層
    private fun handleSkinImport(uri: Uri) {
        try {
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
            val json = settingsJson ?: run {
                skinMessage.text = "找不到 settings.json — 請確認是有效的 .cskin 檔"; return
            }
            File(SkinSettings.settingsPath).writeBytes(json)
            SkinSettings.shared.reload()
            skinMessage.text = if (SkinSettings.shared.isImported)
                "已套用「${SkinSettings.shared.skinName}」— 重開鍵盤生效"
            else "settings.json 格式無法解析"
            refreshSkinStatus()
        } catch (e: Exception) {
            skinMessage.text = "匯入失敗：${e.message}"
        }
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
