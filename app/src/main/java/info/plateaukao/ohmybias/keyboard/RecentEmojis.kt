package info.plateaukao.ohmybias.keyboard

import info.plateaukao.ohmybias.shared.AppEnv
import java.io.File
import java.util.concurrent.Executors

/// 表情面板「常用」分類 — 最近使用的 emoji，MRU 順序（最新在前），上限 40。
/// 存 recent_emojis.txt 一行一個；記錄先改記憶體、寫檔丟背景執行緒（點按路徑零 I/O）。
object RecentEmojis {
    private const val MAX = 40
    private val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "ohmybias-recent") }

    private val items = mutableListOf<String>()
    private var loaded = false

    private fun file() = File(AppEnv.sharedDir, "recent_emojis.txt")

    @Synchronized
    fun all(): List<String> {
        loadIfNeeded()
        return items.toList()
    }

    @Synchronized
    fun record(emoji: String) {
        loadIfNeeded()
        items.remove(emoji)
        items.add(0, emoji)
        while (items.size > MAX) items.removeAt(items.size - 1)
        val snapshot = items.joinToString("\n")
        executor.execute {
            try { file().writeText(snapshot, Charsets.UTF_8) } catch (_: Exception) {}
        }
    }

    /// 測試用：清空記憶體狀態強迫下次重新讀檔；deleteFile=false 保留檔案（模擬重啟）
    @Synchronized
    internal fun resetForTest(deleteFile: Boolean = true) {
        awaitWrites()
        items.clear()
        loaded = false
        if (deleteFile) file().delete()
    }

    /// 測試用：等待背景寫檔完成
    internal fun awaitWrites() { executor.submit { }.get() }

    private fun loadIfNeeded() {
        if (loaded) return
        loaded = true
        val f = file()
        if (!f.exists()) return
        val content = try { f.readText(Charsets.UTF_8) } catch (e: Exception) { "" }
        for (line in content.split("\n")) {
            val s = line.trim()
            if (s.isNotEmpty() && items.size < MAX) items.add(s)
        }
    }
}
