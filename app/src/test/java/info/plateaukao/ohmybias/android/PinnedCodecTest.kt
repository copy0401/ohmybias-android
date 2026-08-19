package info.plateaukao.ohmybias.android

import org.junit.Assert.assertEquals
import org.junit.Test

/// pinned.chars 的存/取 —— 舊版無分隔串接，多字候選與非 BMP 字重開 process 就拆爛。
class PinnedCodecTest {

    private fun roundTrip(chars: List<String>) =
        SqliteFreqTracker.decodePinned(SqliteFreqTracker.encodePinned(chars))

    @Test
    fun singleCharsRoundTrip() {
        assertEquals(listOf("手", "乎"), roundTrip(listOf("手", "乎")))
    }

    @Test
    fun multiCharCandidatesRoundTrip() {
        // .cin 詞組值（一個候選就是一個詞）— 舊版會被拆成一個個單字
        assertEquals(listOf("台北", "台中"), roundTrip(listOf("台北", "台中")))
        assertEquals(listOf("蝦米輸入法"), roundTrip(listOf("蝦米輸入法")))
    }

    @Test
    fun nonBmpCharsRoundTrip() {
        // 中日韓擴充 B 區（U+20000 起）在 UTF-16 是 surrogate pair — 舊版逐 unit 切會拆成兩個孤兒
        val extB = String(Character.toChars(0x20000))
        val emoji = String(Character.toChars(0x1F600))
        assertEquals(listOf(extB, emoji), roundTrip(listOf(extB, emoji)))
    }

    @Test
    fun emptyListRoundTrip() {
        assertEquals(emptyList<String>(), roundTrip(emptyList()))
    }

    @Test
    fun legacyRowsStillDecode() {
        // 既有 freq.db 裡的舊格式（含內建的 hj → 手乎）要照樣讀得出來
        assertEquals(listOf("手", "乎"), SqliteFreqTracker.decodePinned("手乎"))
        // 舊格式的非 BMP 單字至少不再被拆成孤兒 surrogate
        val extB = String(Character.toChars(0x20000))
        assertEquals(listOf(extB), SqliteFreqTracker.decodePinned(extB))
    }
}
