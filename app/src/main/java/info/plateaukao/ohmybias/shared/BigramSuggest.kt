package info.plateaukao.ohmybias.shared

import java.io.File

/// 字級 bigram 聯想（mmap 二進位）。
/// 極簡版不隨附 bigram.bin — 使用者可自行放入 sharedDir 啟用字級聯想。
/// Format: BGMM header + sorted UTF-32LE keys + offsets + counts + UTF-32LE values
class BigramSuggest {
    companion object {
        val shared: BigramSuggest by lazy { BigramSuggest() }
    }

    private var data: BinData? = null
    private var keyCount = 0
    private var keysOffset = 0
    private var offsetsOffset = 0
    private var countsOffset = 0
    private var valuesOffset = 0

    init {
        load()
    }

    private fun load() {
        val f = File(AppEnv.sharedDir, "bigram.bin")
        if (!f.exists()) return  // 不隨附 — 無檔即停用
        val d = BinData.mapped(f.path) ?: run {
            return
        }
        if (d.count < 12 || d.u8(0) != 0x42 || d.u8(1) != 0x47 || d.u8(2) != 0x4D || d.u8(3) != 0x4D) return
        keyCount = d.u32(4).toInt()
        keysOffset = 8
        offsetsOffset = keysOffset + keyCount * 4
        countsOffset = offsetsOffset + keyCount * 4
        valuesOffset = countsOffset + keyCount * 2
        if (valuesOffset > d.count) return
        data = d
    }

    fun suggest(after: String, limit: Int = 6): List<String> {
        val d = data ?: return emptyList()
        if (keyCount <= 0 || after.isEmpty()) return emptyList()
        val target = after.codePointAt(0).toLong()
        var lo = 0; var hi = keyCount - 1
        while (lo <= hi) {
            val mid = (lo + hi) / 2
            val k = d.u32(keysOffset + mid * 4)
            when {
                k == target -> {
                    val valOff = d.u32(offsetsOffset + mid * 4).toInt()
                    val count = minOf(d.u16(countsOffset + mid * 2), limit)
                    val r = ArrayList<String>(count)
                    for (i in 0 until count) {
                        val off = valuesOffset + (valOff + i) * 4
                        if (off + 4 > d.count) break
                        val cp = d.u32(off).toInt()
                        if (Character.isValidCodePoint(cp)) r.add(String(Character.toChars(cp)))
                    }
                    return r
                }
                k < target -> lo = mid + 1
                else -> hi = mid - 1
            }
        }
        return emptyList()
    }
}
