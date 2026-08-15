package info.plateaukao.ohmybias.android

import android.content.ClipboardManager
import android.content.Context
import android.icu.text.Transliterator
import info.plateaukao.ohmybias.shared.ClipboardBridge
import info.plateaukao.ohmybias.shared.DebugLog

/// 剪貼簿 + 簡繁轉換 — 掛上 shared/ClipboardBridge。
/// 簡繁轉換用 ICU Transliterator（Hans-Hant；minSdk 29 保證可用），
/// 對應 iOS 的 StringTransform("Hans-Hant")。
object ClipboardProcessor {

    fun install(context: Context) {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        ClipboardBridge.plainText = {
            try {
                cm.primaryClip?.takeIf { it.itemCount > 0 }
                    ?.getItemAt(0)?.coerceToText(context)?.toString()
            } catch (e: Exception) {
                DebugLog.log { "Clipboard read: ${e.message}" }; null
            }
        }
        ClipboardBridge.toTraditional = { text -> transliterate("Hans-Hant", text) }
        ClipboardBridge.toSimplified = { text -> transliterate("Hant-Hans", text) }
    }

    private fun transliterate(id: String, text: String): String = try {
        Transliterator.getInstance(id).transliterate(text)
    } catch (e: Exception) {
        DebugLog.log { "Transliterator $id: ${e.message}" }
        text
    }
}
