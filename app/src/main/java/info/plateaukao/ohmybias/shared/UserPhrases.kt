package info.plateaukao.ohmybias.shared

import java.io.File

/// 使用者常用語（♥ 面板內容＋聯想自訂詞＋自訂組字碼捷徑）。
/// 純文字檔 `user_phrases.txt` 一行一詞：`詞` 或 `詞<TAB>組字碼`。
/// 有組字碼的詞可直接用鍵盤打碼叫出，不必開 ♥ 面板（見 [CINTable.shortcutLookup]）。
class UserPhrases private constructor() {
    companion object {
        val shared: UserPhrases by lazy { UserPhrases() }

        const val FILE_NAME = "user_phrases.txt"

        /// 組字碼允許的字元 — 字母頁打得出來的鍵（嘸蝦米碼本身只用這些）
        private val codeChars = ('a'..'z').toSet() + setOf(',', '.', '\'', '[', ']')

        /// 組字碼是否合法（只含允許字元、非空、不以 ,, 開頭 — 那是指令前綴）
        fun isValidCode(code: String): Boolean =
            code.isNotEmpty() && code.all { it in codeChars } && !code.startsWith(",,")

        /// 解析檔案內容 — 空行跳過；單字（長度 < 2）一樣保留為常用語，
        /// 但不進聯想表（聯想要「首字＋餘字」才有意義）
        fun parse(content: String): List<Entry> {
            val out = ArrayList<Entry>()
            for (line in content.split("\n")) {
                val parts = line.split("\t", limit = 2)
                val phrase = parts[0].trim()
                if (phrase.isEmpty()) continue
                val code = parts.getOrNull(1)?.trim()?.lowercase()?.takeIf { isValidCode(it) }
                out.add(Entry(phrase, code))
            }
            return out
        }

        fun serialize(entries: List<Entry>): String = buildString {
            for (e in entries) {
                if (e.phrase.isEmpty()) continue
                append(e.phrase)
                e.code?.let { append('\t').append(it) }
                append('\n')
            }
        }
    }

    /// 一筆常用語；[code] 為自訂組字碼（null = 只在面板／聯想出現）
    data class Entry(val phrase: String, val code: String? = null)

    private var _entries: List<Entry> = emptyList()
    private var table: Map<Char, List<String>> = emptyMap()
    private var _shortcuts: Map<String, List<String>> = emptyMap()

    init {
        reload()
    }

    fun reload() {
        val f = File(AppEnv.sharedDir, FILE_NAME)
        val content = if (f.exists()) try { f.readText(Charsets.UTF_8) } catch (e: Exception) { "" } else ""
        apply(parse(content))
    }

    /// 寫檔並立即生效（設定頁儲存用；IME 同 process 直接看得到）
    fun save(entries: List<Entry>) {
        File(AppEnv.sharedDir, FILE_NAME).writeText(serialize(entries), Charsets.UTF_8)
        apply(entries)
    }

    private fun apply(entries: List<Entry>) {
        val m = HashMap<Char, MutableList<String>>()
        val sc = HashMap<String, MutableList<String>>()
        for (e in entries) {
            if (e.phrase.length >= 2) m.getOrPut(e.phrase[0]) { mutableListOf() }.add(e.phrase)
            e.code?.let { sc.getOrPut(it) { mutableListOf() }.add(e.phrase) }
        }
        _entries = entries
        table = m
        _shortcuts = sc
    }

    /// 檔案順序的全部條目（設定頁編輯用）
    val entries: List<Entry> get() = _entries

    /// 組字碼 → 常用語（一碼可對多詞，依檔案順序）
    val shortcuts: Map<String, List<String>> get() = _shortcuts

    /// 回傳以 char 開頭的詞（去除首字後的餘字）
    fun suggest(after: String, limit: Int = 3): List<String> {
        val ch = after.firstOrNull() ?: return emptyList()
        val phrases = table[ch] ?: return emptyList()
        return phrases.take(limit).map { it.substring(1) }
    }

    /// 全部常用語（供 ♥ 面板列出）
    fun allPhrases(): List<String> = _entries.map { it.phrase }.sorted()

    /// 回傳以 prefix 開頭的完整詞
    fun completions(forPrefix: String, limit: Int = 3): List<String> {
        val first = forPrefix.firstOrNull() ?: return emptyList()
        val phrases = table[first] ?: return emptyList()
        return phrases.filter { it.startsWith(forPrefix) && it.length > forPrefix.length }.take(limit)
    }
}
