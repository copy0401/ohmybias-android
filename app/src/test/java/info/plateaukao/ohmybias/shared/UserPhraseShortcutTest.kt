package info.plateaukao.ohmybias.shared

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/// 常用語自訂組字碼 — 檔案格式、字表捷徑查詢、引擎候選順序
class UserPhraseShortcutTest {
    init { TestEnv.touch() }

    @After
    fun cleanup() {
        UserPhrases.shared.save(emptyList())
    }

    @Test
    fun parseAndSerializeRoundtrip() {
        val text = "蝦米輸入法\n你好\txm\n  \n單\tq\n壞碼\tA B\n指令\t,,x\n"
        val entries = UserPhrases.parse(text)
        assertEquals(listOf(
            UserPhrases.Entry("蝦米輸入法"),
            UserPhrases.Entry("你好", "xm"),
            UserPhrases.Entry("單", "q"),
            UserPhrases.Entry("壞碼"),      // 不合法的碼丟掉、詞保留
            UserPhrases.Entry("指令"),      // ,, 前綴不能當碼
        ), entries)
        assertEquals("蝦米輸入法\n你好\txm\n單\tq\n壞碼\n指令\n", UserPhrases.serialize(entries))
        assertEquals("round trip", entries, UserPhrases.parse(UserPhrases.serialize(entries)))
    }

    @Test
    fun codeValidation() {
        assertTrue(UserPhrases.isValidCode("abc"))
        assertTrue(UserPhrases.isValidCode("a,.'[]"))
        assertTrue(UserPhrases.isValidCode(",a"))
        assertFalse(UserPhrases.isValidCode(""))
        assertFalse(UserPhrases.isValidCode(",,a"))
        assertFalse(UserPhrases.isValidCode("Ab"))
        assertFalse(UserPhrases.isValidCode("a b"))
        assertFalse(UserPhrases.isValidCode("a1"))
    }

    @Test
    fun saveReloadAndTableShortcuts() {
        UserPhrases.shared.save(listOf(
            UserPhrases.Entry("蝦米輸入法", "xm"),
            UserPhrases.Entry("明天見", "ab"),
            UserPhrases.Entry("再見", "ab"),
            UserPhrases.Entry("只在面板"),
        ))
        UserPhrases.shared.reload()
        assertEquals(mapOf("xm" to listOf("蝦米輸入法"), "ab" to listOf("明天見", "再見")), UserPhrases.shared.shortcuts)
        assertEquals(listOf("再見", "只在面板", "明天見", "蝦米輸入法"), UserPhrases.shared.allPhrases())

        val table = makeFixtureTable()
        assertEquals("lookup 不含捷徑", listOf("明"), table.lookup("ab"))
        assertEquals(listOf("明天見", "再見"), table.shortcutLookup("ab"))
        assertEquals(listOf("蝦米輸入法"), table.shortcutLookup("XM"))
        assertTrue("捷徑前綴可續打", table.hasPrefix("x"))
        assertEquals(setOf('m'), table.validNextKeys("x"))
    }

    @Test
    fun freeCodeShowsPhraseAsOnlyCandidate() {
        UserPhrases.shared.save(listOf(UserPhrases.Entry("蝦米輸入法", "xm")))
        val (engine, mock) = makeEngine()
        engine.handleLetter("x")
        assertEquals("x 無字表候選、只有捷徑前綴", emptyList<String>(), mock.candidateUpdates.last())
        engine.handleLetter("m")
        assertEquals(listOf("蝦米輸入法"), mock.candidateUpdates.last())
        engine.handleSpace()
        assertEquals(listOf("蝦米輸入法"), mock.commits)
    }

    @Test
    fun takenCodeListsPhraseAfterExistingCandidates() {
        UserPhrases.shared.save(listOf(UserPhrases.Entry("明天見", "ab"), UserPhrases.Entry("再見", "hj")))
        val (engine, mock) = makeEngine()
        engine.handleLetter("a"); engine.handleLetter("b")
        assertEquals(listOf("明", "明天見"), mock.candidateUpdates.last())
        engine.handleSpace()
        assertEquals("空白仍送出字表首選", listOf("明"), mock.commits)

        engine.handleLetter("h"); engine.handleLetter("j")
        val cands = mock.candidateUpdates.last()
        assertEquals(3, cands.size)
        assertEquals("捷徑排最後", "再見", cands.last())
        engine.selectCandidate(2)
        assertEquals(listOf("明", "再見"), mock.commits)
    }

    @Test
    fun freeCodeAutoCommitsWhenUniqueCandidateEnabled() {
        UserPhrases.shared.save(listOf(UserPhrases.Entry("蝦米輸入法", "xm")))
        val (engine, mock) = makeEngine(MockPrefs(autoCommit = true))
        engine.handleLetter("x"); engine.handleLetter("m")
        assertEquals("唯一候選且無法續打 → 直接上屏", listOf("蝦米輸入法"), mock.commits)
    }

    @Test
    fun longShortcutRaisesMaxCodeLength() {
        UserPhrases.shared.save(listOf(UserPhrases.Entry("長碼", "abcdef")))
        val table = makeFixtureTable()
        assertEquals(6, table.maxCodeLength)
    }
}
