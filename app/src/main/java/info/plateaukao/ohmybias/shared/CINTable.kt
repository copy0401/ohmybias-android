package info.plateaukao.ohmybias.shared

import org.json.JSONObject
import java.io.File
import java.util.UUID

/// 統一 CIN 字表 — mmap 二進位（CINM，經 CINCompiler）＋ .cin 文字 fallback。
/// 二進位路徑：liu.bin 以 mmap 載入（零複製）；文字路徑：解析 .cin 進 overlay dict。
class CINTable {

    companion object {
        /// 字表世代 —— 設定頁匯入新 liu.cin 後 +1。鍵盤與設定頁同 process，
        /// service 據此在下次進輸入框時重載（同 SkinSettings.generation 的做法）。
        @Volatile
        var generation: Int = 0
            private set

        fun bumpGeneration() {
            generation += 1
        }
    }

    // MARK: - mmap 二進位（liu.bin）
    private var binData: BinData? = null
    private var entryCount = 0
    private var codesOff = 0
    private var valsOff = 0
    private var stringsOff = 0
    private var charsOff = 0
    /// 已載入 .bin 的 CINM 格式版本（見 CINCompiler.FORMAT_VERSION）
    private var binFormatVersion = 0

    // MARK: - 文字 fallback + overlay（extras — 小型 dict）
    private var overlay: MutableMap<String, MutableList<String>> = HashMap()

    // MARK: - 反查快取（lazy；可由背景執行緒預熱 — @Volatile + 建表鎖保護發佈）
    private val cacheLock = Any()

    /// 本 instance 的載入世代 — 每次 reload()/load() +1。背景預熱走遍整表要一段時間，
    /// 期間若字表被換掉（匯入新表、,,RL），算出來的是舊表的反查結果，發佈上去就會一直
    /// 給錯的字根提示 — 故發佈前先確認世代沒變，變了就不快取（下次存取自然重建）。
    @Volatile private var loadGeneration = 0

    @Volatile private var _reverseTable: MutableMap<String, MutableList<String>>? = null
    private val reverseTable: Map<String, List<String>>
        get() {
            _reverseTable?.let { return it }
            if (!MemoryBudget.canAfford(MemoryBudget.reverseTable)) return emptyMap()
            synchronized(cacheLock) {
                _reverseTable?.let { return it }
                val builtFor = loadGeneration
                val r = HashMap<String, MutableList<String>>()
                val d = binData
                if (d != null) {
                    for (i in 0 until entryCount) {
                        val code = readCode(d, i)
                        for (ch in readChars(d, i)) r.getOrPut(ch) { mutableListOf() }.add(code)
                    }
                }
                for ((code, chars) in overlay) for (c in chars) r.getOrPut(c) { mutableListOf() }.add(code)
                if (builtFor == loadGeneration) _reverseTable = r
                return r
            }
        }

    /// 整表反查建立成本高（走遍所有 entry）— service 啟動後在背景執行緒呼叫，
    /// 免得第一次用到（查碼提示/注音同音字/,,SC）的那個按鍵卡住
    fun warmUpReverseCache() {
        reverseTable
    }

    @Volatile private var _shortestCodes: Map<String, Set<String>>? = null
    val shortestCodesTable: Map<String, Set<String>>
        get() {
            _shortestCodes?.let { return it }
            synchronized(cacheLock) {
                _shortestCodes?.let { return it }
                val r = HashMap<String, Set<String>>()
                for ((ch, codes) in reverseTable) {
                    val m = codes.minOfOrNull { it.length } ?: 0
                    r[ch] = codes.filter { it.length == m }.toSet()
                }
                _shortestCodes = r
                return r
            }
        }

    @Volatile private var _longestCodes: Map<String, Set<String>>? = null
    val longestCodesTable: Map<String, Set<String>>
        get() {
            _longestCodes?.let { return it }
            synchronized(cacheLock) {
                _longestCodes?.let { return it }
                val r = HashMap<String, Set<String>>()
                for ((ch, codes) in reverseTable) {
                    val m = codes.maxOfOrNull { it.length } ?: 0
                    r[ch] = codes.filter { it.length == m }.toSet()
                }
                _longestCodes = r
                return r
            }
        }

    var t2s: Map<String, String> = emptyMap(); private set
    var s2t: Map<String, String> = emptyMap(); private set
    var selKeys: List<Char> = "1234567890".toList(); private set
    var cinName: String = ""; private set
    val isEmpty: Boolean get() = entryCount == 0 && overlay.isEmpty()
    var maxCodeLength: Int = 4; private set

    fun releaseOptionalCaches() {
        _reverseTable = null
        _shortestCodes = null
        _longestCodes = null
    }

    // MARK: - 載入

    fun reload() {
        loadGeneration += 1
        binData = null; entryCount = 0; overlay = HashMap()
        _reverseTable = null; _shortestCodes = null; _longestCodes = null
        t2s = emptyMap(); s2t = emptyMap()

        // 1. sharedDir 的 mmap 二進位
        val userBin = AppEnv.sharedDir + "/liu.bin"
        if (File(userBin).exists()) loadBin(userBin)
        // 1b. 舊版編譯器產出的 .bin（格式版本 0）位移只有 u16，大字表尾端讀出來是錯字。
        //     使用者不該為了拿到修正而重新匯入一次 — 手上還有 liu.cin 就直接重編。
        if (entryCount > 0 && binFormatVersion < CINCompiler.FORMAT_VERSION &&
            File(AppEnv.cinPath).exists()
        ) {
            // 編到暫存檔、成功才換掉 — 重編失敗就沿用舊檔（字是舊的錯法，但還能打）
            val tmp = File("$userBin.tmp")
            if (CINCompiler.compile(AppEnv.cinPath, tmp.path) > 0 && tmp.renameTo(File(userBin))) {
                binData = null; entryCount = 0
                loadBin(userBin)
            }
            tmp.delete()
        }
        // 2. 無 .bin → 嘗試現場編譯 .cin → .bin
        if (entryCount == 0) {
            val cinPath = AppEnv.cinPath
            if (File(cinPath).exists()) {
                CINCompiler.compile(cinPath, userBin)
                if (File(userBin).exists()) loadBin(userBin)
                // 編譯失敗 → 文字 fallback
                if (entryCount == 0) parseCINIntoOverlay(cinPath)
            }
        }
        // 3. Extras
        loadExtras()
        // 4. 簡繁對照表
        loadCharMaps()
        // 5. maxCodeLength
        recomputeMaxCodeLength()
    }

    /// 從 .cin 文字檔載入（先編到暫存 .bin）— 測試與現場使用。
    fun load(cinPath: String) {
        loadGeneration += 1
        val tmp = System.getProperty("java.io.tmpdir") + "/cin_" + UUID.randomUUID() + ".bin"
        CINCompiler.compile(cinPath, tmp)
        binData = null; entryCount = 0; overlay = HashMap()
        _reverseTable = null; _shortestCodes = null; _longestCodes = null
        val f = File(tmp)
        if (f.exists()) {
            BinData.of(f)?.let { parseBinData(it) }
            f.delete()
        }
        if (entryCount == 0) parseCINIntoOverlay(cinPath)
        recomputeMaxCodeLength()
    }

    private fun recomputeMaxCodeLength() {
        maxCodeLength = 4
        val d = binData
        if (d != null) {
            for (i in 0 until entryCount) {
                val len = d.u16(codesOff + i * 6 + 4)
                if (len > maxCodeLength) maxCodeLength = len
            }
        }
        for (k in overlay.keys) if (k.length > maxCodeLength) maxCodeLength = k.length
    }

    // MARK: - 二進位載入

    private fun loadBin(path: String) {
        val d = BinData.mapped(path) ?: run {
            return
        }
        parseBinData(d)
    }

    private fun parseBinData(d: BinData) {
        if (d.count < 128 || d.u8(0) != 0x43 || d.u8(1) != 0x49 || d.u8(2) != 0x4E || d.u8(3) != 0x4D) return
        entryCount = d.u32(4).toInt()
        val skLen = d.u8(8)
        if (skLen in 1..20 && 9 + skLen <= d.count) {
            selKeys = (0 until skLen).map { d.u8(9 + it).toInt().toChar() }
        }
        val cnLen = d.u16(20)
        if (cnLen > 0 && 22 + cnLen <= d.count) cinName = d.utf8String(22, cnLen)
        binFormatVersion = d.u32(88).toInt()
        codesOff = d.u32(96).toInt()
        valsOff = d.u32(100).toInt()
        stringsOff = d.u32(104).toInt()
        charsOff = d.u32(108).toInt()
        if (codesOff < 128 || codesOff >= valsOff || valsOff >= stringsOff || stringsOff >= charsOff || charsOff > d.count) {
            entryCount = 0; return
        }
        binData = d
    }

    // MARK: - 二進位 helpers

    private fun readCode(d: BinData, i: Int): String {
        val off = d.u32(codesOff + i * 6).toInt()
        val len = d.u16(codesOff + i * 6 + 4)
        val start = stringsOff + off
        if (start < 0 || start + len > d.count) return ""
        return d.asciiString(start, len)
    }

    private fun readChars(d: BinData, i: Int): List<String> {
        val entryOff = valsOff + i * 4
        if (entryOff < 0 || entryOff + 4 > d.count) return emptyList()
        // offset 是 u24：低 16 位在前兩 byte、高 8 位在第 4 byte（見 CINCompiler）
        val vOff = d.u16(entryOff) or (d.u8(entryOff + 3) shl 16)
        val vCnt = d.u8(entryOff + 2)
        if (vCnt <= 0) return emptyList()
        val firstOff = charsOff + vOff * 4
        val lastOff = charsOff + (vOff + vCnt - 1) * 4 + 4
        if (firstOff < charsOff || lastOff > d.count) return emptyList()
        val r = ArrayList<String>(vCnt)
        for (j in 0 until vCnt) {
            val cp = d.u32(charsOff + (vOff + j) * 4).toInt()
            if (Character.isValidCodePoint(cp)) r.add(String(Character.toChars(cp)))
        }
        return r
    }

    private fun binSearch(code: String): Int {
        val d = binData ?: return -1
        if (entryCount <= 0) return -1
        val target = code.toByteArray(Charsets.UTF_8)
        var lo = 0; var hi = entryCount - 1
        while (lo <= hi) {
            val mid = (lo + hi) / 2
            val cmp = compareCode(d, mid, target)
            when {
                cmp == 0 -> return mid
                cmp < 0 -> lo = mid + 1
                else -> hi = mid - 1
            }
        }
        return -1
    }

    private fun lowerBound(prefix: String): Int {
        val d = binData ?: return 0
        if (entryCount <= 0) return 0
        val target = prefix.toByteArray(Charsets.UTF_8)
        var lo = 0; var hi = entryCount
        while (lo < hi) {
            val mid = (lo + hi) / 2
            if (comparePrefixCode(d, mid, target) < 0) lo = mid + 1 else hi = mid
        }
        return lo
    }

    private fun compareCode(d: BinData, i: Int, target: ByteArray): Int {
        val idxOff = codesOff + i * 6
        if (idxOff < 0 || idxOff + 6 > d.count) return -1
        val off = stringsOff + d.u32(idxOff).toInt()
        val len = d.u16(idxOff + 4)
        if (off < 0 || off + len > d.count) return -1
        for (j in 0 until len) {
            if (j >= target.size) return 1
            val a = d.u8(off + j)
            val b = target[j].toInt() and 0xFF
            if (a != b) return a - b
        }
        if (len < target.size) return -1
        return 0
    }

    private fun comparePrefixCode(d: BinData, i: Int, prefix: ByteArray): Int {
        val idxOff = codesOff + i * 6
        if (idxOff < 0 || idxOff + 6 > d.count) return -1
        val off = stringsOff + d.u32(idxOff).toInt()
        val len = d.u16(idxOff + 4)
        if (off < 0 || off + len > d.count) return -1
        val n = minOf(len, prefix.size)
        for (j in 0 until n) {
            val a = d.u8(off + j)
            val b = prefix[j].toInt() and 0xFF
            if (a != b) return a - b
        }
        if (len < prefix.size) return -1
        return 0
    }

    private fun codeHasPrefix(d: BinData, i: Int, prefix: ByteArray): Boolean {
        val idxOff = codesOff + i * 6
        if (idxOff < 0 || idxOff + 6 > d.count) return false
        val off = stringsOff + d.u32(idxOff).toInt()
        val len = d.u16(idxOff + 4)
        if (off < 0 || off + len > d.count) return false
        if (len < prefix.size) return false
        for (j in prefix.indices) {
            if (d.u8(off + j) != (prefix[j].toInt() and 0xFF)) return false
        }
        return true
    }

    // MARK: - 文字 CIN 解析（fallback + overlay）

    private fun parseCINIntoOverlay(path: String) {
        val f = File(path)
        // 檔案大小限制
        if (!f.exists()) return
        if (f.length() > 100_000_000L) {
            return
        }
        val content = try { f.readText(Charsets.UTF_8) } catch (e: Exception) { return }
        var inChardef = false
        var lineCount = 0
        val maxLines = 500_000
        for (line in content.lineSequence()) {
            lineCount += 1
            if (lineCount > maxLines) break
            val t = line.trim()
            if (t.startsWith("%selkey ")) {
                val keys = t.substring(8).trim()
                if (keys.isNotEmpty()) selKeys = keys.toList()
                continue
            }
            if (t.startsWith("%cname ")) { cinName = t.substring(7).trim(); continue }
            if (t == "%chardef begin") { inChardef = true; continue }
            if (t == "%chardef end") { inChardef = false; continue }
            if (!inChardef) continue
            val parts = if (t.contains("\t")) t.split("\t", limit = 2) else t.split(" ", limit = 2)
            if (parts.size != 2) continue
            val code = parts[0].lowercase()
            val value = parts[1].trim()
            overlay.getOrPut(code) { mutableListOf() }.add(value)
        }
    }

    // MARK: - Extras + 簡繁對照

    private fun loadExtras() {
        val dir = File(AppEnv.tablesDir)
        val files = dir.listFiles() ?: return
        for (file in files) {
            if (!file.name.endsWith(".txt")) continue
            val content = try { file.readText(Charsets.UTF_8) } catch (e: Exception) { continue }
            for (line in content.lineSequence()) {
                val t = line.trim()
                if (t.isEmpty() || t.startsWith("#")) continue
                val parts = t.split("\t", limit = 2)
                if (parts.size != 2) continue
                overlay.getOrPut(parts[0].lowercase()) { mutableListOf() }.add(parts[1])
            }
        }
    }

    private fun loadCharMaps() {
        t2s = loadCharMap("t2s") ?: emptyMap()
        s2t = loadCharMap("s2t") ?: emptyMap()
    }

    private fun loadCharMap(name: String): Map<String, String>? {
        val f = File(AppEnv.sharedDir, "$name.json")
        if (!f.exists()) return null
        return try {
            val json = JSONObject(f.readText(Charsets.UTF_8))
            val r = HashMap<String, String>()
            for (key in json.keys()) r[key] = json.getString(key)
            r
        } catch (e: Exception) {
            null
        }
    }

    // MARK: - 查詢（公開 API）

    fun lookup(code: String): List<String> {
        val c = code.lowercase()
        var result: List<String> = emptyList()
        val idx = binSearch(c)
        val d = binData
        if (idx >= 0 && d != null) result = readChars(d, idx)
        overlay[c]?.let { extra -> result = extra + result }
        return result
    }

    fun hasPrefix(prefix: String): Boolean {
        val p = prefix.lowercase()
        val d = binData
        if (d != null) {
            val i = lowerBound(p)
            if (i < entryCount && codeHasPrefix(d, i, p.toByteArray(Charsets.UTF_8))) return true
        }
        return overlay.keys.any { it.startsWith(p) }
    }

    fun validNextKeys(after: String): Set<Char> {
        val p = after.lowercase()
        val result = HashSet<Char>()
        val pBytes = p.toByteArray(Charsets.UTF_8)
        val pLen = pBytes.size
        val d = binData
        if (d != null) {
            val start = lowerBound(p)
            for (i in start until entryCount) {
                if (!codeHasPrefix(d, i, pBytes)) break
                val codeLen = d.u16(codesOff + i * 6 + 4)
                if (codeLen > pLen) {
                    val off = stringsOff + d.u32(codesOff + i * 6).toInt()
                    if (off + pLen >= d.count) continue
                    result.add(d.u8(off + pLen).toInt().toChar())
                }
            }
        }
        for (key in overlay.keys) {
            if (key.startsWith(p) && key.length > p.length) result.add(key[p.length])
        }
        return result
    }

    fun wildcardLookup(pattern: String): List<String> {
        val pat = pattern.lowercase()
        if (!pat.contains("*")) return lookup(pat)
        // 逐字元 escape、* → .+（對應 iOS 的 escapedPattern + replacingOccurrences）
        val sb = StringBuilder("^")
        for (ch in pat) {
            if (ch == '*') sb.append(".+")
            else if (ch.isLetterOrDigit()) sb.append(ch)
            else sb.append(Regex.escape(ch.toString()))
        }
        sb.append("$")
        val re = try { Regex(sb.toString()) } catch (e: Exception) { return emptyList() }
        val fix = pat.takeWhile { it != '*' }
        val results = ArrayList<String>()
        val seen = HashSet<String>()
        val d = binData
        if (d != null) {
            val start = if (fix.isEmpty()) 0 else lowerBound(fix)
            val fixBytes = fix.toByteArray(Charsets.UTF_8)
            for (i in start until entryCount) {
                if (fix.isNotEmpty() && !codeHasPrefix(d, i, fixBytes)) break
                val code = readCode(d, i)
                if (re.matches(code)) {
                    for (c in readChars(d, i)) if (seen.add(c)) results.add(c)
                }
            }
        }
        for ((code, chars) in overlay) {
            if (fix.isNotEmpty() && !code.startsWith(fix)) continue
            if (re.matches(code)) {
                for (c in chars) if (seen.add(c)) results.add(c)
            }
        }
        return results
    }

    fun reverseLookup(char: String): List<String> = reverseTable[char] ?: emptyList()
    fun convert(char: String, map: Map<String, String>): String = map[char] ?: char
}
