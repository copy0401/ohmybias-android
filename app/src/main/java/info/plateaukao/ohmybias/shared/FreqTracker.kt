package info.plateaukao.ohmybias.shared

/// 字頻學習 — iOS 版以 SQLite3 C API 實作；Android 拆成介面：
/// 排序邏輯（與 iOS 相同的 unigram/bigram 加權與 pinned 前置）放在介面 default method，
/// 儲存層由 SqliteFreqTracker（android.database.sqlite）與 MemoryFreqTracker（JVM 測試）實作。
interface FreqTracker {

    fun record(code: String, char: String)
    fun recordBigram(prev: String, char: String)
    fun recordTrigram(prev2: String, prev1: String, char: String)

    /// code → (char → n)
    fun queryFreq(code: String): Map<String, Int>
    /// prev → (char → n)
    fun queryBigram(prev: String): Map<String, Int>

    fun pin(code: String, chars: List<String>)
    fun unpin(code: String)
    fun pinnedChars(forCode: String): List<String>?
    fun reloadPinned() {}

    fun reset()
    fun decay(factor: Double = 0.9)
    fun flushAll() {}
    fun saveIfNeeded() {}
    fun deferredMerge() {}

    // MARK: - 排序（與 iOS FreqTracker.sorted / sortedWithContext 相同）

    fun sorted(candidates: List<String>, forCode: String): List<String> {
        if (forCode.startsWith(",")) return candidates
        val pinned = pinnedChars(forCode)
        val counts = queryFreq(forCode)
        var result = if (counts.isNotEmpty())
            candidates.sortedByDescending { counts[it] ?: 0 }
        else candidates
        if (pinned.isNullOrEmpty()) return result
        val pinSet = pinned.toSet()
        val rest = result.filter { it !in pinSet }
        val front = pinned.filter { it in result }
        return front + rest
    }

    fun sortedWithContext(candidates: List<String>, forCode: String, prev: String): List<String> {
        if (forCode.startsWith(",")) return candidates
        if (prev.isEmpty()) return sorted(candidates, forCode)
        val pinned = pinnedChars(forCode)
        val uni = queryFreq(forCode)
        val bi = queryBigram(prev)
        var result: List<String>
        if (uni.isEmpty() && bi.isEmpty()) {
            result = candidates
        } else {
            val uniT = maxOf(1.0, uni.values.sum().toDouble())
            val biT = maxOf(1.0, bi.values.sum().toDouble())
            val total = bi.size + candidates.size
            val alpha = if (total < 100) 0.4 else candidates.size.toDouble() / total
            val scores = HashMap<String, Double>()
            for (c in candidates) {
                val b = bi[c]
                scores[c] = if (b != null) b / biT else alpha * (uni[c] ?: 0) / uniT
            }
            result = candidates.sortedByDescending { scores[it] ?: 0.0 }
        }
        if (pinned.isNullOrEmpty()) return result
        val pinSet = pinned.toSet()
        val rest = result.filter { it !in pinSet }
        val front = pinned.filter { it in result }
        return front + rest
    }

    /// prev 之後的前 N 個學習到的 bigram 建議
    fun topBigrams(prev: String, limit: Int = 3): List<String> {
        if (prev.isEmpty()) return emptyList()
        val counts = queryBigram(prev)
        if (counts.isEmpty()) return emptyList()
        return counts.entries.sortedByDescending { it.value }.take(limit).map { it.key }
    }

    /// 依 bigram 頻率重排聯想候選（穩定：有紀錄者前移，其餘保持原順序）
    fun bigramBoost(prev: String, candidates: List<String>): List<String> {
        if (prev.isEmpty()) return candidates
        val counts = queryBigram(prev)
        if (counts.isEmpty()) return candidates
        val boosted = candidates.filter { counts[it] != null }
            .sortedByDescending { counts[it] ?: 0 }
        val rest = candidates.filter { counts[it] == null }
        return boosted + rest
    }
}

/// 純記憶體實作 — JVM 測試用（也作為 SQLite 開失敗時的 fallback）。
/// 預設帶入與 iOS 相同的內建固定排序（hj → 手乎）。
class MemoryFreqTracker : FreqTracker {
    private val freq = HashMap<String, HashMap<String, Int>>()
    private val bigram = HashMap<String, HashMap<String, Int>>()
    private val pinned = HashMap<String, List<String>>()
    private var recordCount = 0

    init {
        pinned["hj"] = listOf("手", "乎")
    }

    @Synchronized
    override fun record(code: String, char: String) {
        val m = freq.getOrPut(code) { HashMap() }
        m[char] = (m[char] ?: 0) + 1
        recordCount += 1
        if (recordCount >= 500) { recordCount = 0; decay() }
    }

    @Synchronized
    override fun recordBigram(prev: String, char: String) {
        if (prev.isEmpty()) return
        val m = bigram.getOrPut(prev) { HashMap() }
        m[char] = (m[char] ?: 0) + 1
    }

    @Synchronized
    override fun recordTrigram(prev2: String, prev1: String, char: String) {
        if (prev2.isEmpty() || prev1.isEmpty()) return
        recordBigram("$prev2|$prev1", char)
    }

    @Synchronized
    override fun queryFreq(code: String): Map<String, Int> = freq[code]?.toMap() ?: emptyMap()

    @Synchronized
    override fun queryBigram(prev: String): Map<String, Int> = bigram[prev]?.toMap() ?: emptyMap()

    @Synchronized
    override fun pin(code: String, chars: List<String>) { pinned[code] = chars }

    @Synchronized
    override fun unpin(code: String) { pinned.remove(code) }

    @Synchronized
    override fun pinnedChars(forCode: String): List<String>? = pinned[forCode]

    @Synchronized
    override fun reset() {
        freq.clear(); bigram.clear(); recordCount = 0
    }

    @Synchronized
    override fun decay(factor: Double) {
        for (m in freq.values) {
            for ((k, v) in m.entries.toList()) m[k] = maxOf(1, (v * factor).toInt())
        }
        for (m in bigram.values) {
            for ((k, v) in m.entries.toList()) m[k] = maxOf(1, (v * factor).toInt())
        }
    }
}
