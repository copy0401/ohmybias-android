package info.plateaukao.ohmybias.shared

import java.io.File

/// `,,PIN` 固定排序 — 碼 → 使用者指定的同碼字順序（`,,UNPIN碼` 解除）。
/// 純文字檔 `pinned.txt` 一行一碼：`碼<TAB>字<TAB>字…`（一字一欄，非 BMP 字不必另行編碼），
/// 與 iOS 版格式相同、檔案可互通。條目通常只有幾筆，啟動整份讀進小字典即可。
/// 取代原本 freq.db 的 pinned 表 — 字頻學習已移除，固定排序是唯一保留的候選重排。
class PinnedOrder(private val explicitPath: String? = null) {
    private val file: File get() = File(explicitPath ?: "${AppEnv.sharedDir}/$FILE_NAME")
    private var table: Map<String, List<String>> =
        if (file.exists()) parse(file.readText(Charsets.UTF_8)) else DEFAULTS

    private fun save() {
        runCatching { file.writeText(serialize(table), Charsets.UTF_8) }
    }

    fun chars(forCode: String): List<String>? = table[forCode.lowercase()]

    fun pin(code: String, chars: List<String>) {
        table = table + (code.lowercase() to chars)
        save()
    }

    fun unpin(code: String) {
        table = table - code.lowercase()
        save()
    }

    /// 固定的字依指定順序排前面，其餘維持原序；不增減候選
    fun apply(candidates: List<String>, forCode: String): List<String> {
        val pinned = table[forCode.lowercase()]
        if (pinned.isNullOrEmpty()) return candidates
        val pinSet = pinned.toSet()
        return pinned.filter { it in candidates } + candidates.filter { it !in pinSet }
    }

    companion object {
        const val FILE_NAME = "pinned.txt"
        /// 沒有檔案時的內建預設（常見同碼字衝突）
        val DEFAULTS: Map<String, List<String>> = mapOf("hj" to listOf("手", "乎"))
        val shared: PinnedOrder by lazy { PinnedOrder() }

        fun parse(content: String): Map<String, List<String>> {
            val t = LinkedHashMap<String, List<String>>()
            for (line in content.split('\n')) {
                val fields = line.split('\t').map { it.trim() }
                if (fields.size < 2 || fields[0].isEmpty()) continue
                val chars = fields.drop(1).filter { it.isNotEmpty() }
                if (chars.isNotEmpty()) t[fields[0].lowercase()] = chars
            }
            return t
        }

        fun serialize(t: Map<String, List<String>>): String =
            t.keys.sorted().joinToString("") { (listOf(it) + t.getValue(it)).joinToString("\t") + "\n" }
    }
}
