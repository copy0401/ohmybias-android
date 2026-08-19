package info.plateaukao.ohmybias.shared

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/// 自動送出後的「吃空白」與換輸入框重置 —— 兩者都是狀態活過頭造成的輸入異常。
class InputEngineSessionTest {

    init { TestEnv.touch() }

    // MARK: - 唯一候選自動送出後的空白

    @Test
    fun autoCommitEatsFollowingSpaceOnce() {
        val (engine, mock) = makeEngine(MockPrefs(autoCommit = true))
        engine.handleLetter("a"); engine.handleLetter("b")
        assertEquals("唯一候選自動送出", "明", mock.commits.last())
        assertEquals("送出後 composing 清空", "", engine.composing)
        assertTrue("習慣性補的那一下空白被吃掉", engine.consumeEatNextSpace())
        assertFalse("只吃一次", engine.consumeEatNextSpace())
    }

    @Test
    fun autoCommitDoesNotEatNextWordCommitSpace() {
        // 舊 bug：旗標活到下一個字，害那個字的空白鍵送不出候選（要按兩下才上屏）
        val (engine, mock) = makeEngine(MockPrefs(autoCommit = true))
        engine.handleLetter("a"); engine.handleLetter("b")   // 自動送出「明」
        val commits = mock.commits.size
        engine.handleLetter("h"); engine.handleLetter("j")   // hj 兩個候選，不自動送出
        assertEquals("hj 不自動送出", commits, mock.commits.size)
        assertFalse("打了新字母就不該再吃空白", engine.consumeEatNextSpace())
        engine.handleSpace()
        assertEquals("下一個字的空白鍵照常送出首選", commits + 1, mock.commits.size)
    }

    @Test
    fun spaceCommitsNormallyWithoutAutoCommit() {
        val (engine, mock) = makeEngine()
        engine.handleLetter("a")
        engine.handleSpace()
        assertEquals("日", mock.commits.last())
        assertFalse("沒自動送出就沒有要吃的空白", engine.consumeEatNextSpace())
    }

    // MARK: - 換輸入框／結束輸入

    @Test
    fun resetSessionClearsPendingComposing() {
        // 舊 bug：A 欄位沒送出的組字碼活到 B 欄位，B 的第一個空白把它送了出去
        val (engine, mock) = makeEngine()
        engine.handleLetter("a")
        assertEquals("a", engine.composing)
        engine.resetSession()
        assertEquals("換欄位後不留組字碼", "", engine.composing)
        assertTrue("換欄位後不留候選", engine.currentCandidates.isEmpty())
        val commits = mock.commits.size
        engine.handleSpace()
        assertEquals("新欄位的空白鍵不會送出上個欄位的候選", commits, mock.commits.size)
    }

    @Test
    fun resetSessionClearsSpecialModes() {
        val (engine, _) = makeEngine()
        engine.switchToMode("zh")
        assertTrue("已進注音模式", engine.isZhuyinMode)
        engine.resetSession()
        assertFalse("換欄位後退出注音", engine.isZhuyinMode)
        assertFalse("換欄位後不在任何查詢模式", engine.isInSpecialMode)
    }

    @Test
    fun resetSessionClearsCommittedContext() {
        // bigram/trigram/聯想的語境屬於上一個欄位，不該帶到新欄位
        val (engine, _) = makeEngine()
        engine.handleLetter("a"); engine.handleSpace()
        assertEquals("日", engine.lastCommittedText)
        engine.resetSession()
        assertEquals("換欄位後語境清空", "", engine.lastCommittedText)
    }

    @Test
    fun resetSessionKeepsEnglishMode() {
        // 中英模式是使用者偏好（會存進 Prefs），不隨換欄位重置
        val (engine, _) = makeEngine()
        engine.toggleEnglishMode()
        assertTrue(engine.isEnglishMode)
        engine.resetSession()
        assertTrue("中英模式保留", engine.isEnglishMode)
    }
}
