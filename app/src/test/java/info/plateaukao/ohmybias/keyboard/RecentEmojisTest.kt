package info.plateaukao.ohmybias.keyboard

import info.plateaukao.ohmybias.shared.TestEnv
import org.junit.Assert.assertEquals
import org.junit.Test

class RecentEmojisTest {
    init { TestEnv.touch() }

    @Test
    fun mruOrderDedupeAndCap() {
        RecentEmojis.resetForTest()
        RecentEmojis.record("😀")
        RecentEmojis.record("🥹")
        RecentEmojis.record("😀")  // 重複 → 提到最前
        assertEquals(listOf("😀", "🥹"), RecentEmojis.all())

        for (i in 0 until 50) RecentEmojis.record("e$i")
        assertEquals(40, RecentEmojis.all().size)
        assertEquals("e49", RecentEmojis.all().first())
    }

    @Test
    fun persistsAcrossReload() {
        RecentEmojis.resetForTest()
        RecentEmojis.record("🐶")
        RecentEmojis.record("🐱")
        RecentEmojis.resetForTest(deleteFile = false)  // 模擬重啟 → 下次 all() 重新讀檔
        assertEquals(listOf("🐱", "🐶"), RecentEmojis.all())
    }
}
