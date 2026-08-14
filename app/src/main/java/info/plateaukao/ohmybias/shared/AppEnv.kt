package info.plateaukao.ohmybias.shared

import java.io.File

/// 對應 iOS AppConstants — Android 單一 APK 內 IME service 與設定 Activity 直接共用 filesDir，
/// 不需 App Group。啟動時由 OhMyBiasApp 設定 sharedDir（引擎層藉此保持純 JVM、可在主機測試）。
object AppEnv {
    /// 共享資料夾（liu.cin/liu.bin、freq.db、tables/、user_phrases.txt、複製自 assets 的資料檔）
    var sharedDir: String = System.getProperty("java.io.tmpdir") + "/ohmybias_shared"

    val cinPath: String get() = "$sharedDir/liu.cin"
    val freqPath: String get() = "$sharedDir/freq.db"
    val tablesDir: String get() = "$sharedDir/tables"

    fun ensureDirs() {
        File(sharedDir).mkdirs()
        File(tablesDir).mkdirs()
    }
}
