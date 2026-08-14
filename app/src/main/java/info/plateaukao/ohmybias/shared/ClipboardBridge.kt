package info.plateaukao.ohmybias.shared

/// 對應 iOS ClipboardProcessor（UIKit）— 引擎層透過此橋接存取剪貼簿與簡繁轉換，
/// Android 層啟動時掛上實作（ClipboardManager + ICU Transliterator）；JVM 測試為 no-op。
object ClipboardBridge {
    /// 讀取剪貼簿純文字（去格式）；無內容回 null
    var plainText: () -> String? = { null }

    /// 簡 → 繁
    var toTraditional: (String) -> String = { it }

    /// 繁 → 簡
    var toSimplified: (String) -> String = { it }
}
