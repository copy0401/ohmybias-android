package info.plateaukao.ohmybias.shared

/// SSMM/SCMM 區塊讀取器：string → [string]（mmap、key 依 UTF-8 位元組序二進位搜尋）。
/// SSMM 值為字串；SCMM 值為單一 codepoint（u32 陣列，較省空間）。格式見 tools/gen_data_bins.py。
class StringListMap private constructor(
    private val d: BinData,
    private val base: Int,
    private val codepointValues: Boolean,
) {
    private val keyCount = d.u32(base + 4).toInt()
    private val keyIdxOff = base + d.u32(base + 8).toInt()
    private val valIdxOff = base + d.u32(base + 12).toInt()
    private val keyBlobOff = base + d.u32(base + 16).toInt()
    private val valBlobOff = base + d.u32(base + 20).toInt()

    companion object {
        fun at(d: BinData, off: Int): StringListMap? {
            if (d.u8(off + 2) != 'M'.code || d.u8(off + 3) != 'M'.code) return null
            return when {
                d.u8(off) == 'S'.code && d.u8(off + 1) == 'S'.code -> StringListMap(d, off, codepointValues = false)
                d.u8(off) == 'S'.code && d.u8(off + 1) == 'C'.code -> StringListMap(d, off, codepointValues = true)
                else -> null
            }
        }
    }

    val isEmpty: Boolean get() = keyCount <= 0

    /// key（12B/筆）：keyOff u32、keyLen u16、valStart u32、valCnt u16
    private fun compareKey(i: Int, target: ByteArray): Int {
        val idx = keyIdxOff + i * 12
        val off = keyBlobOff + d.u32(idx).toInt()
        val len = d.u16(idx + 4)
        val n = minOf(len, target.size)
        for (j in 0 until n) {
            val a = d.u8(off + j)
            val b = target[j].toInt() and 0xFF
            if (a != b) return a - b
        }
        return len - target.size
    }

    fun get(key: String): List<String> {
        if (keyCount <= 0) return emptyList()
        val target = key.toByteArray(Charsets.UTF_8)
        var lo = 0; var hi = keyCount - 1
        while (lo <= hi) {
            val mid = (lo + hi) / 2
            val cmp = compareKey(mid, target)
            when {
                cmp == 0 -> return readValues(mid)
                cmp < 0 -> lo = mid + 1
                else -> hi = mid - 1
            }
        }
        return emptyList()
    }

    private fun readValues(i: Int): List<String> {
        val idx = keyIdxOff + i * 12
        val valStart = d.u32(idx + 6).toInt()
        val valCnt = d.u16(idx + 10)
        val r = ArrayList<String>(valCnt)
        if (codepointValues) {
            for (j in 0 until valCnt) {
                val cp = d.u32(valBlobOff + (valStart + j) * 4).toInt()
                if (Character.isValidCodePoint(cp)) r.add(String(Character.toChars(cp)))
            }
        } else {
            for (j in 0 until valCnt) {
                val v = valIdxOff + (valStart + j) * 6
                val off = valBlobOff + d.u32(v).toInt()
                val len = d.u16(v + 4)
                r.add(d.utf8String(off, len))
            }
        }
        return r
    }
}

/// CFMM 讀取器：codepoint → 頻次（mmap、(u32,u32) 對依 codepoint 排序）。
class CharFreqMap private constructor(private val d: BinData) {
    private val count = d.u32(4).toInt()

    companion object {
        fun of(d: BinData): CharFreqMap? {
            if (d.u8(0) != 'C'.code || d.u8(1) != 'F'.code ||
                d.u8(2) != 'M'.code || d.u8(3) != 'M'.code
            ) return null
            return CharFreqMap(d)
        }
    }

    val isEmpty: Boolean get() = count <= 0

    fun get(codePoint: Int): Int {
        if (count <= 0) return 0
        val target = codePoint.toLong() and 0xFFFFFFFFL
        var lo = 0; var hi = count - 1
        while (lo <= hi) {
            val mid = (lo + hi) / 2
            val k = d.u32(8 + mid * 8)
            when {
                k == target -> return d.u32(8 + mid * 8 + 4).toInt()
                k < target -> lo = mid + 1
                else -> hi = mid - 1
            }
        }
        return 0
    }

    /// 單一 codepoint 字串的頻次；其他長度回 0
    fun get(char: String): Int {
        if (char.isEmpty() || char.codePointCount(0, char.length) != 1) return 0
        return get(char.codePointAt(0))
    }
}
