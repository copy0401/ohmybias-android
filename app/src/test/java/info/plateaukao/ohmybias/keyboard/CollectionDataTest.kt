package info.plateaukao.ohmybias.keyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/// 直接解析 src/main/assets/collections.txt — 驗證格式與自 Kotlin 常數轉出時的內容一致
class CollectionDataTest {
    private val data by lazy {
        val f = File("src/main/assets/collections.txt")
        assertTrue("找不到 ${f.absolutePath}", f.exists())
        f.inputStream().use { CollectionData.parse(it) }
    }

    @Test
    fun sectionAndItemCounts() {
        assertEquals(listOf("symbols", "emojis", "kaomojis"), data.keys.toList())
        assertEquals(51, data["symbols"]!!.size)
        assertEquals(13, data["emojis"]!!.size)
        assertEquals(16, data["kaomojis"]!!.size)
        assertEquals(5959, data.values.sumOf { sec -> sec.sumOf { it.second.size } })
    }

    @Test
    fun spotValues() {
        val symbols = data["symbols"]!!
        assertEquals("半標", symbols[0].first)
        assertEquals(listOf(",", ".", ";"), symbols[0].second.take(3))
        assertEquals("\\", symbols[0].second[9])                 // 反斜線
        assertEquals("\"", symbols[0].second[20])                // 雙引號
        assertEquals("${'$'}", symbols[0].second[13])            // 錢號
        assertEquals("😀", data["emojis"]!![0].second[0])
        assertEquals("🙂‍↕️", data["emojis"]!![0].second[35])    // ZWJ 序列完整
        val cheeks = data["kaomojis"]!!.first { it.first == "臉頰" }
        assertEquals("\u200A", cheeks.second[0])                 // hair space 項目不可被 trim 掉
    }

    @Test
    fun noEmptyTitlesOrSections() {
        for ((_, sections) in data) for ((title, items) in sections) {
            assertTrue(title.isNotEmpty())
            assertTrue(title, items.isNotEmpty())
        }
    }
}
