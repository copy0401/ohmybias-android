package info.plateaukao.ohmybias.shared

/// 聯想引擎：送字後產生聯想。
/// 極簡版：詞級語料只有萌典詞組（WikiCorpus）＋使用者自訂詞（UserPhrases）。
class SuggestionEngine(
    private val wikiCorpus: WikiCorpus = WikiCorpus.shared,
    private val bigramSuggest: BigramSuggest = BigramSuggest.shared,
    private val prefs: IMEPreferences = DefaultPreferences,
) {
    companion object {
        val shared: SuggestionEngine by lazy { SuggestionEngine() }
    }

    private val skipChars: Set<String> = setOf(
        "的", "了", "在", "是", "和", "與", "或", "而", "但", "也", "都", "就", "被", "把", "讓",
        "給", "從", "到", "對", "為", "著", "過", "嗎", "呢", "吧", "啊", "喔", "哦", "啦",
    )

    fun suggest(recentCommitted: String, lastText: String): List<String> {
        if (recentCommitted.isEmpty()) return emptyList()
        val lastChar = recentCommitted.takeLast(1)
        val isSkipChar = lastChar in skipChars

        val strategy = prefs.suggestStrategy
        val prefix = if (recentCommitted.length >= 2)
            recentCommitted.takeLast(minOf(4, recentCommitted.length)) else ""

        val suggestions = ArrayList<String>()
        val seen = HashSet<String>()

        // 使用者自訂詞（user_phrases.txt）優先
        var poolUser: List<String> = emptyList()
        if (!isSkipChar) {
            if (prefix.isNotEmpty()) {
                poolUser = UserPhrases.shared.completions(prefix)
                    .map { it.substring(prefix.length) }
                    .filter { it.isNotEmpty() }
            }
            if (poolUser.isEmpty()) {
                poolUser = UserPhrases.shared.suggest(lastChar)
            }
        }

        val pool2 = if (!isSkipChar && prefix.isNotEmpty())
            wikiCorpus.suggestWordCorpus(prefix)
                .map { if (it.startsWith(prefix)) it.substring(prefix.length) else it }
                .filter { it.isNotEmpty() }
        else emptyList()

        var pool3: List<String> = emptyList()
        if (recentCommitted.length >= 2) {
            for (len in minOf(4, recentCommitted.length) downTo 2) {
                val p = recentCommitted.takeLast(len)
                pool3 = wikiCorpus.suggestAllDomains(p)
                    .map { if (it.startsWith(p)) it.substring(p.length) else it }
                    .filter { it.isNotEmpty() }
                if (pool3.isNotEmpty()) break
            }
        }
        // 單字 fallback — 極簡版由萌典供應（一般語料，需尊重 skipChars）
        if (pool3.isEmpty() && !isSkipChar && recentCommitted.isNotEmpty()) {
            val p = recentCommitted.takeLast(1)
            pool3 = wikiCorpus.suggestDomainTerms(p, 5)
        }

        // Jingjing-ti 詞組擴充：獨立 pool，支援單字前綴
        var poolJJ: List<String> = emptyList()
        for (len in minOf(4, recentCommitted.length) downTo 1) {
            val p = recentCommitted.takeLast(len)
            poolJJ = wikiCorpus.suggestJingjing(p, 5)
            if (poolJJ.isNotEmpty()) break
        }

        val pool4 = ArrayList<String>()
        if (!isSkipChar && prefs.charSuggest) {
            if (recentCommitted.length >= 2) {
                val prev2 = recentCommitted.takeLast(2).take(1)
                val prev1 = recentCommitted.takeLast(1)
                pool4 += wikiCorpus.suggestTrigram(prev2, prev1)
            }
            pool4 += bigramSuggest.suggest(lastText)
        }

        val ordered: List<List<String>> = when (strategy) {
            "domain" -> listOf(poolUser, poolJJ, pool3, pool2, pool4)
            "char" -> listOf(pool4, poolUser, poolJJ, pool2, pool3)
            else -> listOf(poolUser, pool2, poolJJ, pool3, pool4)
        }
        for (pool in ordered) {
            for (s in pool) {
                if (seen.add(s) && !wikiCorpus.isRegionDemoted(s)) suggestions.add(s)
            }
        }

        // Emoji
        for (e in wikiCorpus.suggestEmoji(recentCommitted.takeLast(1))) {
            if (seen.add(e)) suggestions.add(e)
        }

        return suggestions.take(10)
    }
}
