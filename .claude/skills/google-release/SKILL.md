---
name: google-release
description: 發佈 OhMyBias 米到 Google Play（Gradle Play Publisher）— 版本號、release notes、上傳 internal 軌、晉升 production、更新商店 listing。使用者說「發 Play 版」「上架新版」「release to Play」「promote to production」時用。
---

# Google Play 發佈流程

Play 版是 `playRelease` build type（`info.plateaukao.ohmybias.g`），與 GitHub 版
（`release`，自家 keystore）是**兩條獨立通道**：GitHub 穩定版另走 tag + `gh release`，
rolling pre-release 由 CI 自動發，都與本流程無關。

## 前置條件（每次先確認）

1. `~/.secrets/ohmybias-keystore.properties` 存在（storeFile/storePassword/keyAlias/
   keyPassword/playCredentials — 與 einkbro/calliplus 共用 upload key 與服務帳戶）。
   **缺檔時不會失敗，會退回 debug 簽章** — 上傳前務必驗證簽章（見下）。
2. 服務帳戶 `play-publisher@calliplus.iam.gserviceaccount.com` 對本 app 有發佈權限
   （權限是逐 app 授予的；`PERMISSION_DENIED` = 使用者要去 console
   「使用者與權限」把它加進來，這一步只有使用者能做）。
3. Claude 無法讀寫 `~/.secrets/`（classifier 擋）— 需要動 secrets 時請使用者自己跑
   （建議 `! <指令>` 形式）。

## 發佈新版

1. **版本**：`app/build.gradle.kts` 的 `versionCode`（單調遞增，Play 硬性要求）與
   `versionName` 同步調升。GitHub 與 Play 版共用同一組版本號。
2. **Release notes**：更新 `app/src/main/play/release-notes/zh-TW/default.txt` 與
   `en-US/default.txt`（各 ≤500 字元），隨 bundle 一起上傳。
3. **測試**：`./gradlew testDebugUnitTest` 全綠才繼續。
4. **Commit + push**（依全域 cap/ADR 流程）。
5. **組 AAB 並驗證簽章**（防 secrets 缺檔時默默用 debug key）：
   ```bash
   ./gradlew bundlePlayRelease
   keytool -printcert -jarfile app/build/outputs/bundle/playRelease/app-playRelease.aab | grep Owner
   # 必須是 CN=Daniel Kao, OU=Daniel Studio（2018 upload key），不可是 Android Debug
   ```
6. **上傳 internal 軌**：
   ```bash
   ./gradlew publishPlayReleaseBundle
   ```
   app 還是 console 草稿（首次上架尚未過審）時要加 `--release-status draft`。
7. **晉升 production**（internal 驗證 OK 後；production 永遠明確指定，不設預設）：
   ```bash
   ./gradlew promotePlayReleaseArtifact --promote-track production
   ```

## 只更新商店 listing（文案／截圖／圖檔）

素材都在 repo：`app/src/main/play/listings/{zh-TW,en-US}/`（zh-TW 是預設語言，
graphics 只放 zh-TW，其他語言 fallback）。改完後：

```bash
./gradlew publishPlayReleaseListing
```

截圖用模擬器擷取（sim-use 驅動、走真實 IME 路徑打字），1080×2400 原尺寸即可。
Icon 512／feature graphic 1024×500 由 adaptive icon 素材程式化合成 — 腳本做法見
ADR `ohmybias-android-play-store-release-prep.md`。

## 疑難排解

- `PERMISSION_DENIED`：服務帳戶未授權本 app（見前置條件 2）。
- 上傳成功但 console 沒看到 listing：`publishPlayReleaseBundle` 只傳 bundle＋
  release notes，文案圖檔要另跑 `publishPlayReleaseListing`。
- versionCode 已存在：忘了調升第 1 步。
- AAB 是 debug 簽章：secrets 檔缺失或路徑錯（前置條件 1）。
