package info.plateaukao.ohmybias.shared

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/// v2 資料表（ZYM2 / PYM2 / CFM2 / PHM2）讀取端 — 期望值取自 v1 bin 解析結果，
/// 涵蓋轉檔最容易出錯的邊界：非 BMP 字（surrogate pair）、多讀音順序、拼音別名與內嵌、
/// 頻次同分、PHM2 首字接回、區塊差值鍵表的命中／未命中。
class DataBinsV2Test {
    init { TestEnv.touch() }

    private fun bin(name: String) = BinData.mapped(File(AppEnv.sharedDir, name).path)!!

    @Test
    fun zhuyinTable() {
        val zt = ZhuyinTable.of(bin("zhuyin_data.bin"))!!
        assertEquals(1351, zt.syllableCount)
        val ba = zt.charsForZhuyin("ㄅㄚ")
        assertEquals(15, ba.size)
        assertEquals(listOf("八", "巴", "吧", "扒", "芭"), ba.take(5))
        assertEquals(listOf("ㄙㄢ", "ㄙㄚ", "ㄙㄢˋ"), zt.zhuyinsOf("三"))       // 讀音順序：常用在前
        assertEquals(listOf("ㄍㄨㄞˇ"), zt.zhuyinsOf("\uD840\uDC65"))          // 𠁥 U+20065：非 BMP 鍵走 ext 段
        assertEquals(listOf("ㄆㄞˋ"), zt.zhuyinsOf("\uD840\uDCA2"))            // 𠂢 U+200A2
        val bo2 = zt.charsForZhuyin("ㄅㄛˊ")
        assertEquals(62, bo2.size)
        assertTrue("𩱚 (surrogate pair) kept as one char", "\uD867\uDC5A" in bo2)
        assertTrue(bo2.all { it.codePointCount(0, it.length) == 1 })
        assertEquals(emptyList<String>(), zt.charsForZhuyin("ㄅㄨㄅㄨ"))
        assertEquals(emptyList<String>(), zt.zhuyinsOf("A"))
        assertEquals(emptyList<String>(), zt.zhuyinsOf("\uD87E\uDC00"))       // U+2F800 不在表內
        assertEquals(emptyList<String>(), zt.zhuyinsOf("三三"))
    }

    @Test
    fun pinyinTable() {
        val zt = ZhuyinTable.of(bin("zhuyin_data.bin"))!!
        val pt = PinyinTable.of(bin("pinyin_data.bin"), zt)!!
        assertEquals(zt.charsForZhuyin("ㄅㄚ"), pt.get("ba1"))                // 別名 → 同注音字表
        val yu2 = pt.get("yu2")                                               // 內嵌（ㄧㄡ＋ㄩ 合併表）
        assertEquals(92, yu2.size)
        assertEquals(listOf("由", "遊", "游"), yu2.take(3))
        assertEquals(listOf("驢", "閭", "櫚"), pt.get("lü2").take(3))
        assertEquals(emptyList<String>(), pt.get("zzz"))
    }

    @Test
    fun charFreq() {
        val cf = CharFreqMap.of(bin("char_freq.bin"))!!
        val de = cf.get("的"); val wo = cf.get("我"); val da = cf.get("龘")
        assertTrue("的 > 我 > 龘 > unknown", de > wo && wo > da && da > 0)
        assertEquals(0xFFFF, de)                                              // rank 0 = 最常用
        assertEquals(cf.get("㑳"), cf.get("扦"))                              // v1 同頻次 290 → 同 rank
        assertTrue(cf.get("\uD840\uDC89") > 0)                                // 𠂉 U+20089 非 BMP
        assertEquals(0, cf.get("A"))
        assertEquals(0, cf.get("的的"))
    }

    @Test
    fun phrasesV2() {
        val corpus = WikiCorpus.shared
        assertEquals(1, corpus.domainBinCount)
        val tai = corpus.suggestPhrases("臺", 30)
        assertEquals(30, tai.size)
        assertEquals(listOf("臺灣", "臺北市", "臺中", "臺南市"), tai.take(4))  // 首字接回、三字詞完整
        assertEquals(listOf("\uD84D\uDE28橠"), corpus.suggestPhrases("\uD84D\uDE28"))  // 𣘨 U+23628 非 BMP 鍵
        assertEquals(emptyList<String>(), corpus.suggestPhrases("A"))
        assertTrue("市" in corpus.phraseCompletions("臺北", 3))
    }
}
