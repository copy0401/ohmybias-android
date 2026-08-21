package info.plateaukao.ohmybias.keyboard

import android.content.res.AssetManager
import java.io.InputStream

/// 面板頁資料（符號/顏文字/Emoji）— 內容在 assets/collections.txt，由 sweetlime 皮膚
/// collectionData.libsonnet 轉出，與 iOS 版 CollectionData.swift 同源。「常用」分類為動態
/// 使用紀錄，表情面板由 RecentEmojis 提供，不在此靜態表內。
///
/// 原本寫成 Kotlin `listOf(...)` 常數：5,959 個字串各佔一筆 string table ＋ const-string/aput
/// 位元組碼，約 110 KB dex（dex 在 APK 內不壓縮）；改成純文字 asset 後 deflate 僅約 18 KB。
///
/// 檔案格式（UTF-8、LF）：
///   `#symbols` / `#emojis` / `#kaomojis` 一行標示後續分類所屬面板；
///   其餘每行一個分類 = 「分類名 TAB 項目 TAB 項目 …」。
///   項目不可含 TAB／換行，但可為純空白（顏文字「臉頰」有單一 U+200A hair space 項目），解析時不 trim。
object CollectionData {

    @Volatile private var assets: AssetManager? = null

    /// Application.onCreate 掛上 AssetManager；實際讀檔延後到第一次開面板
    fun install(assets: AssetManager) { this.assets = assets }

    private val all: Map<String, List<Pair<String, List<String>>>> by lazy {
        val a = assets ?: return@lazy emptyMap()
        try {
            a.open("collections.txt").use { parse(it) }
        } catch (e: Exception) {
            emptyMap()
        }
    }

    val symbols: List<Pair<String, List<String>>> get() = all["symbols"] ?: emptyList()
    val emojis: List<Pair<String, List<String>>> get() = all["emojis"] ?: emptyList()
    val kaomojis: List<Pair<String, List<String>>> get() = all["kaomojis"] ?: emptyList()

    /// 解析 collections.txt（獨立出來供 JVM 測試直接餵 src/main/assets 的檔案）
    fun parse(input: InputStream): Map<String, List<Pair<String, List<String>>>> {
        val result = LinkedHashMap<String, MutableList<Pair<String, List<String>>>>()
        var current: MutableList<Pair<String, List<String>>>? = null
        input.bufferedReader(Charsets.UTF_8).forEachLine { line ->
            if (line.isEmpty()) return@forEachLine
            if (line[0] == '#') {
                current = result.getOrPut(line.substring(1)) { ArrayList() }
                return@forEachLine
            }
            val cells = line.split('\t')
            current?.add(cells[0] to cells.subList(1, cells.size))
        }
        return result
    }
}
