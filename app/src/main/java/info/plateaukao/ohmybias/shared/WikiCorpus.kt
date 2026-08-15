package info.plateaukao.ohmybias.shared

import java.io.File

/// 極簡語料層：只保留萌典詞組（phrases.bin，PHMM 格式，CC0）。
/// 其餘語料（詞級 n-gram、新聞語料、成語、專業詞典、NER、emoji、地區用詞）不隨附，
/// API 介面與上游 Yabomish 相同（回傳空結果），讓 SuggestionEngine / CandidateRanker 原始碼不需修改。
class WikiCorpus {
    companion object {
        val shared: WikiCorpus by lazy { WikiCorpus() }

        val domainKeys: List<Triple<String, String, String>> = listOf(
            Triple("domain_phrases", "phrases", "萌典詞組"),
        )
    }

    // 萌典詞組（PHMM: header + sorted UTF-32LE 首字 keys + offsets + counts + phrase blob）
    private var phData: BinData? = null
    private var phKeyCount = 0
    private var phKeysOff = 0
    private var phOffsetsOff = 0
    private var phCountsOff = 0
    private var phPhrasesOff = 0

    val domainBinCount: Int get() = if (phData != null) 1 else 0

    init {
        loadPhrases()
    }

    private fun loadPhrases() {
        val p = File(AppEnv.sharedDir, "phrases.bin")
        if (!p.exists()) return
        val d = BinData.mapped(p.path) ?: run {
            DebugLog.log { "WikiCorpus loadPhrases: mmap failed" }; return
        }
        if (d.count < 12 || d.u8(0) != 0x50 || d.u8(1) != 0x48 || d.u8(2) != 0x4D || d.u8(3) != 0x4D) return
        phKeyCount = d.u32(4).toInt()
        phKeysOff = 8
        phOffsetsOff = phKeysOff + phKeyCount * 4
        phCountsOff = phOffsetsOff + phKeyCount * 4
        phPhrasesOff = phCountsOff + phKeyCount * 2
        if (phPhrasesOff > d.count) return
        phData = d
    }

    fun reloadDomains() = loadPhrases()

    // MARK: - 詞組查詢

    /// 單字 → 該字開頭的完整詞組
    fun suggestPhrases(after: String, limit: Int = 4): List<String> = phraseLookup(after, limit)

    /// 多字前綴 → 補全（去除前綴後的餘字），如「馬達」→「加斯加」
    fun phraseCompletions(forPrefix: String, limit: Int = 3): List<String> {
        if (forPrefix.length < 2) return emptyList()
        val first = forPrefix.substring(0, forPrefix.offsetByCodePoints(0, 1))
        val results = ArrayList<String>()
        for (phrase in phraseLookup(first, 30)) {
            if (phrase.startsWith(forPrefix) && phrase.length > forPrefix.length) {
                results.add(phrase.substring(forPrefix.length))
                if (results.size >= limit) break
            }
        }
        return results
    }

    /// 與上游同名：多字前綴補全
    fun completions(forPrefix: String, limit: Int = 3): List<String> =
        phraseCompletions(forPrefix, limit)

    private fun phraseLookup(char: String, limit: Int = 30): List<String> {
        val d = phData ?: return emptyList()
        if (phKeyCount <= 0 || char.isEmpty()) return emptyList()
        val target = char.codePointAt(0).toLong()
        var lo = 0; var hi = phKeyCount - 1
        while (lo <= hi) {
            val mid = (lo + hi) / 2
            val k = d.u32(phKeysOff + mid * 4)
            when {
                k == target -> return readPhrases(d, mid, limit)
                k < target -> lo = mid + 1
                else -> hi = mid - 1
            }
        }
        return emptyList()
    }

    private fun readPhrases(d: BinData, idx: Int, limit: Int): List<String> {
        var pos = phPhrasesOff + d.u32(phOffsetsOff + idx * 4).toInt()
        val count = minOf(d.u16(phCountsOff + idx * 2), limit)
        val r = ArrayList<String>(count)
        for (i in 0 until count) {
            if (pos < 0 || pos >= d.count) break
            val len = d.u8(pos); pos += 1
            if (pos + len * 4 > d.count) break
            val sb = StringBuilder()
            for (j in 0 until len) {
                val cp = d.u32(pos).toInt()
                if (Character.isValidCodePoint(cp)) sb.appendCodePoint(cp)
                pos += 4
            }
            r.add(sb.toString())
        }
        return r
    }

    // MARK: - 上游 API（極簡版由萌典詞組供應或回空）

    /// 詞級語料 — 極簡版一律查萌典
    fun suggestWordCorpus(prefix: String, limit: Int = 5): List<String> =
        phraseCompletions(prefix, limit)

    /// 詞庫（第三層）— 極簡版只有萌典；多字前綴回補全餘字
    fun suggestAllDomains(prefix: String, limit: Int = 5): List<String> =
        phraseCompletions(prefix, limit)

    /// 單字前綴 → 補全餘字（供 SuggestionEngine 單字 fallback 與 CandidateRanker 使用）
    fun suggestDomainTerms(prefix: String, limit: Int = 3): List<String> {
        if (prefix.codePointCount(0, prefix.length) != 1) return phraseCompletions(prefix, limit)
        val results = ArrayList<String>()
        for (phrase in phraseLookup(prefix, 30)) {
            if (phrase.codePointCount(0, phrase.length) <= 1) continue
            results.add(phrase.substring(phrase.offsetByCodePoints(0, 1)))
            if (results.size >= limit) break
        }
        return results
    }

    // 以下語料極簡版不提供 — 介面保留
    fun suggestJingjing(prefix: String, limit: Int = 5): List<String> = emptyList()
    fun suggestTrigram(prev2: String, prev1: String, limit: Int = 4): List<String> = emptyList()
    fun suggestChengyu(prefix: String, limit: Int = 3): List<String> = emptyList()
    fun suggestWordNgram(context: List<String>, limit: Int = 3): List<String> = emptyList()
    fun suggestEmoji(forChar: String, limit: Int = 2): List<String> = emptyList()
    fun isRegionDemoted(term: String): Boolean = false
}
