package info.plateaukao.ohmybias.shared

import java.io.ByteArrayOutputStream
import java.io.File

/// .cin 文字檔 → CINM 二進位（供 mmap 載入）。格式與 iOS 版完全相同，檔案可跨平台共用。
///
/// ⚠️  重要：.cin 檔有版權（如行易的嘸蝦米）。本編譯器只在使用者匯入時 on-device 執行，
///    絕不預編或隨附 .bin — 它們是版權素材的衍生物。
object CINCompiler {

    /// 編譯 srcPath 的 cin → 寫出 bin 至 dstPath。回傳 entry 數，失敗回 0。
    fun compile(src: String, dst: String): Int {
        val content = try { File(src).readText(Charsets.UTF_8) } catch (e: Exception) { return 0 }

        data class Entry(val code: String, val chars: MutableList<String>)
        val entries = mutableListOf<Entry>()
        val codeMap = HashMap<String, Int>()
        var selkeys = "0123456789"
        var cname = ""
        var inChardef = false

        for (line in content.lineSequence()) {
            val t = line.trim()
            if (t.startsWith("%selkey ")) { selkeys = t.substring(8).trim(); continue }
            if (t.startsWith("%cname ")) { cname = t.substring(7).trim(); continue }
            if (t == "%chardef begin") { inChardef = true; continue }
            if (t == "%chardef end") { inChardef = false; continue }
            if (!inChardef) continue
            val parts = if (t.contains("\t")) t.split("\t", limit = 2) else t.split(" ", limit = 2)
            if (parts.size != 2) continue
            val code = parts[0].lowercase()
            val ch = parts[1].trim()
            if (ch.isEmpty()) continue
            val idx = codeMap[code]
            if (idx != null) entries[idx].chars.add(ch)
            else { codeMap[code] = entries.size; entries.add(Entry(code, mutableListOf(ch))) }
        }

        // 依碼排序（ASCII 序）
        entries.sortBy { it.code }
        if (entries.isEmpty()) return 0

        // strings 區
        val stringsBuf = ByteArrayOutputStream()
        val codeEntries = ArrayList<Pair<Int, Int>>(entries.size)  // (off, len)
        for (e in entries) {
            val b = e.code.toByteArray(Charsets.US_ASCII)
            codeEntries.add(stringsBuf.size() to b.size)
            stringsBuf.write(b)
        }

        // chars 區（UTF-32LE code points；cnt = scalar 數）
        val charsBuf = ByteArrayOutputStream()
        val valEntries = ArrayList<Pair<Int, Int>>(entries.size)  // (off, cnt)
        for (e in entries) {
            val off = charsBuf.size() / 4
            var cnt = 0
            for (ch in e.chars) {
                var i = 0
                while (i < ch.length) {
                    val cp = ch.codePointAt(i)
                    writeU32(charsBuf, cp.toLong())
                    cnt += 1
                    i += Character.charCount(cp)
                }
            }
            valEntries.add(off to cnt)
        }

        // Header（128 bytes）
        val headerSize = 128
        val header = ByteArray(headerSize)
        header[0] = 0x43; header[1] = 0x49; header[2] = 0x4E; header[3] = 0x4D  // "CINM"
        putU32(header, 4, entries.size.toLong())
        val skData = selkeys.toByteArray(Charsets.US_ASCII)
        val skLen = minOf(skData.size, 10)
        header[8] = skLen.toByte()
        System.arraycopy(skData, 0, header, 9, skLen)
        val cnData = cname.toByteArray(Charsets.UTF_8).let { if (it.size > 64) it.copyOf(64) else it }
        putU16(header, 20, cnData.size)
        System.arraycopy(cnData, 0, header, 22, cnData.size)

        // Code index（6-byte stride：u32 off + u16 len）
        val codeIdx = ByteArrayOutputStream()
        for ((off, len) in codeEntries) {
            writeU32(codeIdx, off.toLong())
            writeU16(codeIdx, len)
        }

        // Val index（4-byte stride：u16 off + u8 cnt + u8 保留）
        val valIdx = ByteArrayOutputStream()
        for ((off, cnt) in valEntries) {
            if (off > 0xFFFF) {
                DebugLog.log("CINCompiler: val offset $off exceeds UInt16 range, clamping")
                writeU16(valIdx, 0xFFFF)
                valIdx.write(minOf(cnt, 255))
                valIdx.write(0)
                continue
            }
            writeU16(valIdx, off)
            valIdx.write(minOf(cnt, 255))
            valIdx.write(0)
        }

        // 區段位移
        val codesOff = headerSize
        val valsOff = codesOff + codeIdx.size()
        val stringsOff = valsOff + valIdx.size()
        val charsOff = stringsOff + stringsBuf.size()
        putU32(header, 96, codesOff.toLong())
        putU32(header, 100, valsOff.toLong())
        putU32(header, 104, stringsOff.toLong())
        putU32(header, 108, charsOff.toLong())

        return try {
            File(dst).outputStream().use { out ->
                out.write(header)
                codeIdx.writeTo(out)
                valIdx.writeTo(out)
                stringsBuf.writeTo(out)
                charsBuf.writeTo(out)
            }
            entries.size
        } catch (e: Exception) {
            0
        }
    }

    private fun putU32(b: ByteArray, off: Int, v: Long) {
        b[off] = (v and 0xFF).toByte()
        b[off + 1] = ((v shr 8) and 0xFF).toByte()
        b[off + 2] = ((v shr 16) and 0xFF).toByte()
        b[off + 3] = ((v shr 24) and 0xFF).toByte()
    }

    private fun putU16(b: ByteArray, off: Int, v: Int) {
        b[off] = (v and 0xFF).toByte()
        b[off + 1] = ((v shr 8) and 0xFF).toByte()
    }

    private fun writeU32(out: ByteArrayOutputStream, v: Long) {
        out.write((v and 0xFF).toInt())
        out.write(((v shr 8) and 0xFF).toInt())
        out.write(((v shr 16) and 0xFF).toInt())
        out.write(((v shr 24) and 0xFF).toInt())
    }

    private fun writeU16(out: ByteArrayOutputStream, v: Int) {
        out.write(v and 0xFF)
        out.write((v shr 8) and 0xFF)
    }
}
