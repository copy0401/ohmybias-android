package info.plateaukao.ohmybias.shared

import java.io.File
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/// 對應 iOS DebugLog — debugMode 開啟時寫 sharedDir/debug.log（含輪替）。
/// 訊息以 lambda 延遲建構 — debugMode 關閉（正常使用）時鍵擊路徑零字串組建。
object DebugLog {
    private const val MAX_SIZE = 512 * 1024L
    /// 由 Prefs 層在啟動時綁定；JVM 測試預設關閉
    var isEnabled: () -> Boolean = { false }

    inline fun log(msg: () -> String) {
        if (isEnabled()) write(msg())
    }

    fun write(msg: String) {
        try {
            val dir = File(AppEnv.sharedDir)
            dir.mkdirs()
            val f = File(dir, "debug.log")
            if (f.exists() && f.length() > MAX_SIZE) {
                val old = File(dir, "debug.log.old")
                old.delete()
                f.renameTo(old)
            }
            val ts = ZonedDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
            f.appendText("[$ts] $msg\n")
        } catch (_: Exception) {
        }
    }
}
