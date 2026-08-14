package info.plateaukao.ohmybias.shared

/// iOS 鍵盤 extension 有約 60MB 記憶體上限，需預算管控；Android IME 無此限制。
/// 保留 API（引擎層原始碼與 iOS 對齊），一律回「可負擔」。
object MemoryBudget {
    const val cinTable = 10
    const val freqTracker = 2
    const val phrasesBin = 1
    const val zhuyinLookup = 4
    const val reverseTable = 4
    const val uiOverhead = 3
    const val collectionPanel = 1

    fun canAfford(mb: Int): Boolean = true
}
