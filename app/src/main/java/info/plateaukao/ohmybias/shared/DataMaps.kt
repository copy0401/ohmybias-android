package info.plateaukao.ohmybias.shared

/// v2 資料表讀取器（ZYM2 / PYM2 / CFM2）— 全部 mmap、零 heap、鍵表可二進位搜尋。
/// 格式與省法見 tools/gen_data_bins.py 檔頭說明。

/// CPKT：codepoint 鍵表。BMP 段以 32 筆為區塊（區塊首鍵絕對值 u16 + 區塊內 u16 差值），
/// 非 BMP 段為排序 u32。回傳鍵的索引（BMP 段 0..nb−1，非 BMP 段接續），查無回 −1。
class CodepointKeys(private val d: BinData, val count: Int, private val bmpCount: Int, headsOff: Int) {
    private val headCount = (bmpCount + BLOCK - 1) / BLOCK
    private val headsOff = headsOff
    private val deltasOff = headsOff + 2 * headCount
    private val extOff = deltasOff + 2 * bmpCount

    /// 三段合計位元組數（接在後面的區塊由此算起點）
    val byteSize: Int get() = 2 * headCount + 2 * bmpCount + 4 * (count - bmpCount)

    fun indexOf(cp: Int): Int {
        if (cp > 0xFFFF) {
            var lo = 0; var hi = count - bmpCount - 1
            val target = cp.toLong()
            while (lo <= hi) {
                val mid = (lo + hi) ushr 1
                val k = d.u32(extOff + 4 * mid)
                when {
                    k == target -> return bmpCount + mid
                    k < target -> lo = mid + 1
                    else -> hi = mid - 1
                }
            }
            return -1
        }
        if (headCount == 0) return -1
        // 最後一個首鍵 ≤ cp 的區塊
        var lo = 0; var hi = headCount - 1; var blk = -1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (d.u16(headsOff + 2 * mid) <= cp) { blk = mid; lo = mid + 1 } else hi = mid - 1
        }
        if (blk < 0) return -1
        var i = blk * BLOCK
        var k = d.u16(headsOff + 2 * blk)
        if (k == cp) return i
        val end = minOf(i + BLOCK, bmpCount)
        i++
        while (i < end) {
            k += d.u16(deltasOff + 2 * i)
            if (k == cp) return i
            if (k > cp) return -1
            i++
        }
        return -1
    }

    companion object {
        const val BLOCK = 32
    }
}

/// ZYM2：注音音節 → 字（UTF-16 code unit 表）＋ 字 → 音節索引（反查）。
class ZhuyinTable private constructor(private val d: BinData) {
    val syllableCount = d.u32(4).toInt()
    private val charCount = d.u32(8).toInt()
    private val charBmpCount = d.u32(12).toInt()
    private val sylIdxOff = d.u32(24).toInt()
    private val sylBlobOff = d.u32(28).toInt()
    private val unitsOff = d.u32(32).toInt()
    private val keys = CodepointKeys(d, charCount, charBmpCount, d.u32(36).toInt())
    private val blockStartOff = d.u32(48).toInt()
    private val countsOff = d.u32(52).toInt()
    private val readingsOff = d.u32(56).toInt()

    companion object {
        fun of(d: BinData): ZhuyinTable? {
            if (d.count < 60 || d.u8(0) != 'Z'.code || d.u8(1) != 'Y'.code ||
                d.u8(2) != 'M'.code || d.u8(3) != '2'.code
            ) return null
            return ZhuyinTable(d)
        }
    }

    val isEmpty: Boolean get() = syllableCount <= 0

    /// sylIdx（8B/筆）：strOff u16、strLen u8、unitCnt u8、unitStart u32
    fun syllable(i: Int): String {
        val o = sylIdxOff + 8 * i
        return d.utf8String(sylBlobOff + d.u16(o), d.u8(o + 2))
    }

    /// 音節 i 的字（每個 code point 一個字串）
    fun charsOfSyllable(i: Int): List<String> {
        if (i < 0 || i >= syllableCount) return emptyList()
        val o = sylIdxOff + 8 * i
        return d.utf16Chars(unitsOff + 2 * d.u32(o + 4).toInt(), d.u8(o + 3))
    }

    private fun compareSyllable(i: Int, target: ByteArray): Int {
        val o = sylIdxOff + 8 * i
        val off = sylBlobOff + d.u16(o)
        val len = d.u8(o + 2)
        val n = minOf(len, target.size)
        for (j in 0 until n) {
            val a = d.u8(off + j)
            val b = target[j].toInt() and 0xFF
            if (a != b) return a - b
        }
        return len - target.size
    }

    /// 注音字串 → 音節索引（依 UTF-8 位元組序二進位搜尋），查無回 −1
    fun syllableIndex(zhuyin: String): Int {
        val target = zhuyin.toByteArray(Charsets.UTF_8)
        var lo = 0; var hi = syllableCount - 1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val cmp = compareSyllable(mid, target)
            when {
                cmp == 0 -> return mid
                cmp < 0 -> lo = mid + 1
                else -> hi = mid - 1
            }
        }
        return -1
    }

    fun charsForZhuyin(zhuyin: String): List<String> = charsOfSyllable(syllableIndex(zhuyin))

    /// 字 → 注音列表（順序 = 常用讀音在前）
    fun zhuyinsOf(char: String): List<String> {
        if (char.isEmpty() || char.codePointCount(0, char.length) != 1) return emptyList()
        val i = keys.indexOf(char.codePointAt(0))
        if (i < 0) return emptyList()
        val blk = i / CodepointKeys.BLOCK
        var start = d.u16(blockStartOff + 2 * blk)
        for (j in blk * CodepointKeys.BLOCK until i) start += d.u8(countsOff + j)
        val cnt = d.u8(countsOff + i)
        val r = ArrayList<String>(cnt)
        for (k in 0 until cnt) {
            val s = d.u16(readingsOff + 2 * (start + k))
            if (s < syllableCount) r.add(syllable(s))
        }
        return r
    }
}

/// PYM2：拼音 → 字。多數音節是 ZYM2 音節的別名（字表相同），少數原樣內嵌。
class PinyinTable private constructor(private val d: BinData, private val zhuyin: ZhuyinTable) {
    private val count = d.u32(4).toInt()
    private val idxOff = d.u32(12).toInt()
    private val blobOff = d.u32(16).toInt()
    private val unitsOff = d.u32(20).toInt()

    companion object {
        /// 建檔時的音節數須與手上的 ZYM2 一致，否則別名索引會指錯 → 視為無資料
        fun of(d: BinData, zhuyin: ZhuyinTable): PinyinTable? {
            if (d.count < 24 || d.u8(0) != 'P'.code || d.u8(1) != 'Y'.code ||
                d.u8(2) != 'M'.code || d.u8(3) != '2'.code
            ) return null
            if (d.u32(8).toInt() != zhuyin.syllableCount) return null
            return PinyinTable(d, zhuyin)
        }
    }

    /// idx（8B/筆）：strOff u16、strLen u8、unitCnt u8（0 = 別名）、ref u32
    private fun compareKey(i: Int, target: ByteArray): Int {
        val o = idxOff + 8 * i
        val off = blobOff + d.u16(o)
        val len = d.u8(o + 2)
        val n = minOf(len, target.size)
        for (j in 0 until n) {
            val a = d.u8(off + j)
            val b = target[j].toInt() and 0xFF
            if (a != b) return a - b
        }
        return len - target.size
    }

    fun get(pinyin: String): List<String> {
        if (count <= 0) return emptyList()
        val target = pinyin.toByteArray(Charsets.UTF_8)
        var lo = 0; var hi = count - 1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val cmp = compareKey(mid, target)
            when {
                cmp == 0 -> {
                    val o = idxOff + 8 * mid
                    val unitCnt = d.u8(o + 3)
                    val ref = d.u32(o + 4).toInt()
                    return if (unitCnt == 0) zhuyin.charsOfSyllable(ref)
                           else d.utf16Chars(unitsOff + 2 * ref, unitCnt)
                }
                cmp < 0 -> lo = mid + 1
                else -> hi = mid - 1
            }
        }
        return emptyList()
    }
}

/// CFM2：codepoint → 頻次序。存 dense rank（0 = 最常用），回傳 0xFFFF − rank 當頻次，
/// 愈大愈常用；查無回 0（同 v1 語意，排序時墊底）。
class CharFreqMap private constructor(private val d: BinData) {
    private val count = d.u32(4).toInt()
    private val keys = CodepointKeys(d, count, d.u32(8).toInt(), 12)
    private val ranksOff = 12 + keys.byteSize

    companion object {
        fun of(d: BinData): CharFreqMap? {
            if (d.count < 12 || d.u8(0) != 'C'.code || d.u8(1) != 'F'.code ||
                d.u8(2) != 'M'.code || d.u8(3) != '2'.code
            ) return null
            return CharFreqMap(d)
        }
    }

    val isEmpty: Boolean get() = count <= 0

    fun get(codePoint: Int): Int {
        if (count <= 0) return 0
        val i = keys.indexOf(codePoint)
        return if (i < 0) 0 else 0xFFFF - d.u16(ranksOff + 2 * i)
    }

    /// 單一 codepoint 字串的頻次；其他長度回 0
    fun get(char: String): Int {
        if (char.isEmpty() || char.codePointCount(0, char.length) != 1) return 0
        return get(char.codePointAt(0))
    }
}
