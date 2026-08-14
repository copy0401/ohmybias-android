package info.plateaukao.ohmybias.shared

import java.io.File

/// 使用者自訂詞（聯想用）。純文字檔一行一詞。
/// 查詢：給定首字，回傳以其開頭的詞。
class UserPhrases private constructor() {
    companion object {
        val shared: UserPhrases by lazy { UserPhrases() }
    }

    private var table: Map<Char, List<String>> = emptyMap()

    init {
        reload()
    }

    fun reload() {
        val m = HashMap<Char, MutableList<String>>()
        val f = File(AppEnv.sharedDir, "user_phrases.txt")
        if (f.exists()) {
            val content = try { f.readText(Charsets.UTF_8) } catch (e: Exception) { "" }
            for (line in content.split("\n")) {
                val phrase = line.trim()
                if (phrase.length < 2) continue
                m.getOrPut(phrase[0]) { mutableListOf() }.add(phrase)
            }
        }
        table = m
    }

    /// 回傳以 char 開頭的詞（去除首字後的餘字）
    fun suggest(after: String, limit: Int = 3): List<String> {
        val ch = after.firstOrNull() ?: return emptyList()
        val phrases = table[ch] ?: return emptyList()
        return phrases.take(limit).map { it.substring(1) }
    }

    /// 全部自訂詞（供常用語面板列出）
    fun allPhrases(): List<String> = table.values.flatten().sorted()

    /// 回傳以 prefix 開頭的完整詞
    fun completions(forPrefix: String, limit: Int = 3): List<String> {
        val first = forPrefix.firstOrNull() ?: return emptyList()
        val phrases = table[first] ?: return emptyList()
        return phrases.filter { it.startsWith(forPrefix) && it.length > forPrefix.length }.take(limit)
    }
}
