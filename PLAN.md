# OhMyBias 米 Android 移植計畫

從 `~/src/ohmybias-ios` 移植嘸蝦米輸入法到 Android。目標：**功能對等**的 Android IME（InputMethodService），
純 Kotlin、零第三方依賴（僅 androidx 官方庫最小集），文件/commit/註解/UI 繁體中文。

## 平台對應決策

| iOS | Android |
|---|---|
| 鍵盤 appex + 容器 app（App Group 共享） | **單一 APK**：IME service + 設定 Activity（直接共用 `filesDir`，無需 App Group） |
| `UserDefaults(suiteName:)` | `SharedPreferences`（單一 process，直接共用） |
| `Data(mappedIfSafe)` mmap | `FileChannel.map` → `MappedByteBuffer`（包成 `BinData` 提供 u16/u32 unaligned LE 讀取） |
| Bundle 資源（phrases.bin 等） | `assets/` → 首次啟動複製到 `filesDir/shared/`（統一走檔案路徑，引擎層無 Android 依賴） |
| SQLite3 C API（FreqTracker） | `FreqTracker` 抽成 interface：`SqliteFreqTracker`（android.database.sqlite）＋ `MemoryFreqTracker`（JVM 測試用） |
| StringTransform Hans-Hant（剪貼簿簡繁轉換） | `android.icu.text.Transliterator`（minSdk 29 起可用） |
| UIInputViewController + textDocumentProxy | `InputMethodService` + `InputConnection` |
| SwiftUI 容器 app | 傳統 View 設定頁（LinearLayout + Switch），含「測試輸入」EditText 供模擬器驗證 |
| iCloud 字頻合併 | 不移植（Android 無對應；保留 API 空實作） |
| 鍵盤 extension 60MB 記憶體上限 | 無此限制 — `MemoryBudget.canAfford` 恆真（保留 API） |
| 地球鍵 handleInputModeList | `switchToNextInputMethod` / `showInputMethodPicker` |
| returnKeyType → Enter 鍵文字 | `EditorInfo.imeOptions` → 搜尋/前往/送出… |

不變的核心約束：
- **liu.cin 有版權（行易）— 只能使用者自行匯入、on-device 編譯，絕不預編/隨附 liu.bin。**
- liu.bin（CINM）與 phrases.bin（PHMM）二進位格式與 iOS 完全相同（可跨平台共用檔案）。
- `Shared/` 對應的 `shared/` 套件禁止 import Android API（例外清單見下），引擎在 JVM 上可測。

## 模組結構

```
app/src/main/java/info/plateaukao/ohmybias/
  shared/      ← 引擎層（純 Kotlin/JVM，對應 iOS Shared/）
    AppEnv.kt          — sharedDir 路徑（由 Application 設定；測試指到 temp dir）
    BinData.kt         — MappedByteBuffer 包裝：u8/u16/u32 LE unaligned
    InputEngine.kt     — 核心狀態機（組字/選字/,, 指令/注音/拼音/同音/PIN）
    CINTable.kt        — CINM mmap 讀取 + .cin 文字 fallback + overlay + t2s/s2t
    CINCompiler.kt     — .cin → CINM 編譯（匯入時 on-device）
    CandidateRanker.kt — 字頻排序/模式過濾/模糊比對
    FreqTracker.kt     — interface + MemoryFreqTracker
    SuggestionEngine.kt / WikiCorpus.kt / BigramSuggest.kt / UserPhrases.kt
    ZhuyinLookup.kt    — zhuyin_data.json / pinyin_data.json / char_freq.json
    IMEPreferences.kt / SkinSettings.kt / MemoryBudget.kt / DebugLog.kt
  android/     ← Android 實體化
    OhMyBiasApp.kt         — Application：建目錄、複製 assets、設定 AppEnv
    Prefs.kt               — SharedPreferences 實作 IMEPreferences
    SqliteFreqTracker.kt   — android.database.sqlite 實作（freq/bigram/pinned 三表同 iOS）
    ClipboardProcessor.kt  — ClipboardManager + Transliterator
  keyboard/    ← IME UI（對應 OhMyBiasKeyboard/）
    OhMyBiasImeService.kt  — InputMethodService = InputEngineDelegate
    KeyboardView.kt        — 字母/數字/符號/注音/九宮格五頁 + KeyButton（滑動/長按/連刪/游標拖曳）
    CandidateBar.kt        — 候選列 + sweetlime 工具列
    KeyboardTheme.kt / LongPressData.kt / LongPressPopup.kt / SwipeData.kt
    CollectionData.kt / CollectionPanelView.kt — 符號/emoji/顏文字/常用語面板
  MainActivity.kt          — 設定頁（啟用鍵盤/匯入 liu.cin/偏好 toggle/自訂詞/測試輸入框）
app/src/main/assets/       — phrases.bin, zhuyin_data.json, pinyin_data.json, char_freq.json, t2s.json, s2t.json
app/src/test/              — JUnit 引擎測試（移植 Tests/main.swift 全部案例）
```

## 里程碑

- [x] **M0 專案骨架**：Gradle 8.13 wrapper + AGP 8.10 + Kotlin 2.1，compileSdk 35 / minSdk 29；
      manifest 註冊 IME service（method.xml）。
- [x] **M1 引擎移植（JVM 可測）**：shared/ 全部檔案 + JUnit 測試移植
      （fixture .cin 編譯 roundtrip、組字/送出/退格/Escape、VRSF、標點配對、,, 指令、模式切換、
      SkinSettings 解析、WikiCorpus 詞組、SuggestionEngine、聯想開關）→ 13 tests 全綠。
- [x] **M2 鍵盤 UI**：KeyboardView 五頁 + KeyButton 觸控（點按/上滑/下滑/長按選單/⌫ 連刪/空白鍵游標拖曳）、
      CandidateBar（composing/候選/聯想藍字/工具列互斥顯示）、toast、LongPressPopup、
      CollectionPanelView 面板、KeyboardTheme 深淺色。
- [x] **M3 Android 整合**：SqliteFreqTracker、ClipboardProcessor、assets 複製、Prefs、
      設定 Activity（啟用鍵盤/匯入 liu.cin(SAF)/偏好/自訂詞/測試輸入框）。
- [ ] **M4 模擬器驗證**（Pixel_7_API_34；經軟體鍵盤實際點擊，非 adb input text）：
      逐項過驗證清單（下）。
- [ ] **M5 收尾**：README/CHANGELOG（繁中）、CLAUDE.md、初始 commit。

## 模擬器驗證清單（M4）

環境：`adb shell ime enable/set`，匯入測試字表（開發機的 liu.cin 或 fixture .cin）。
每項在 EditText 上經**軟體鍵盤點擊**驗證：

- [x] 鍵盤出現、五排配置正確（qwerty + 底列 123/逗號/空白/句號/⏎；角標齊全）
- [x] 打碼組字 → composing 顯示於候選列左側，候選字出現（t → 臺）
- [x] 空白鍵送出首選；VRSF 快選（hj 打 v 選第 2 候選 乎）
- [x] 候選點選上屏；聯想詞（藍字）出現並可點選送出（臺 → 灣/北市/中/南市/中市，點灣得臺灣）
- [x] 退格：組字中刪碼、空時刪文（⌫ 長按連刪待驗）
- [x] `,,` 指令：`,,C` toast、`,,UNPINhj` 解除固定（`,,H`/`,,S`/`,,TO`/`,,PIN` 待驗）
- [x] 注音查碼頁：ㄅ+ㄚ+一聲 → 巴八吧芭…（字頻排序）→ 點選上屏並自動退出回字母頁
- [x] 中英切換（工具列「米」鍵）；英文模式 shift 大寫（qQ）
- [x] 數字九宮格頁（Row 數字頁/全形符號頁待驗）
- [x] 字母鍵上滑手勢（Enter 上滑進注音）；長按彈出選單待驗
- [x] 標點配對：「」成對送出且游標在中間（後續注音字 巴 落在引號內證明）
- [x] 工具列渲染＋符號面板（分類切換/插入/返回）；emoji/顏文字/常用語面板待驗
- [ ] 設定頁：偏好 toggle 生效（聯想開關）、匯入 .cin 成功編譯（狀態列已驗 — SAF 待驗）、自訂詞聯想生效
- [x] 字頻學習：連選 乎 三次後 hj 排序 乎 前移；（`,,RS` 待驗）
- [ ] 深色模式外觀正確
- [ ] 其餘待驗：⌫ 連刪、長按選單、`,,H/S/TO/PYS/PIN/RS/V`、Row 數字頁、全形符號頁、
      emoji/顏文字/常用語面板、空白鍵游標拖曳、句首/句尾/貼上下滑、深色模式

## 進度記錄

（每輪 loop 迭代在此追記——完成項打勾、記錄遇到的問題與決策）

- 2026-08-15 初版計畫；repo 建立。
- 2026-08-15 M0–M3 完成：引擎全移植（13 JVM tests 綠）、鍵盤 UI、Android 整合；模擬器核心流程驗證通過
  （組字/送出/聯想/VRSF/注音/字頻/標點配對/符號面板）。修正一個關鍵繪圖 bug：clipChildren=false 下
  KeyboardView.onDraw 用 drawColor 會蓋掉候選列 → 改畫有界矩形。已知細節：,,ZH 後頁面同步已補
  （handleKey 後 syncPageWithEngine）；工具列 ☺/♥ 加 U+FE0E 強制文字樣式。
