package info.plateaukao.ohmybias.android

import android.app.Application
import info.plateaukao.ohmybias.shared.AppEnv
import info.plateaukao.ohmybias.shared.DebugLog
import java.io.File

/// Application：初始化共享目錄、複製 assets 資料檔、掛上偏好與剪貼簿橋接。
/// IME service 與設定 Activity 同一 process — 這裡是唯一入口。
class OhMyBiasApp : Application() {

    override fun onCreate() {
        super.onCreate()
        AppEnv.sharedDir = File(filesDir, "shared").path
        AppEnv.ensureDirs()
        copyAssetsIfNeeded()
        Prefs.install(this)
        ClipboardProcessor.install(this)
        DebugLog.isEnabled = { Prefs.debugMode }
    }

    /// assets → sharedDir（引擎層統一走檔案路徑；檔案小、每次啟動比對大小即可）
    private fun copyAssetsIfNeeded() {
        val names = listOf(
            "phrases.bin", "zhuyin_data.json", "pinyin_data.json",
            "char_freq.json", "t2s.json", "s2t.json",
        )
        for (name in names) {
            try {
                val dst = File(AppEnv.sharedDir, name)
                assets.open(name).use { input ->
                    val bytes = input.readBytes()
                    if (!dst.exists() || dst.length() != bytes.size.toLong()) {
                        dst.writeBytes(bytes)
                    }
                }
            } catch (e: Exception) {
                DebugLog.log("copyAssets $name: ${e.message}")
            }
        }
    }
}
