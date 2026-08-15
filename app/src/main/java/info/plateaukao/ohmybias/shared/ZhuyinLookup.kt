package info.plateaukao.ohmybias.shared

import org.json.JSONObject
import java.io.File

/// 同音字查詢：字 → 注音 → 同音字（按字頻排序）。
/// 資料優先走 mmap 二進位（zhuyin_data.bin/pinyin_data.bin/char_freq.bin，零 heap、零解析），
/// 找不到 .bin 時回退舊版 JSON（升級相容）。
class ZhuyinLookup {
    companion object {
        val shared: ZhuyinLookup by lazy { ZhuyinLookup() }
    }

    data class Reading(val zhuyin: String, val chars: List<String>)

    // mmap 二進位
    private var z2cBin: StringListMap? = null
    private var c2zBin: StringListMap? = null
    private var pinyinBin: StringListMap? = null
    private var freqBin: CharFreqMap? = null

    // JSON fallback
    private var zhuyinToChars: Map<String, List<String>> = emptyMap()
    private var charToZhuyins: Map<String, List<String>> = emptyMap()
    private var pinyinToChars: Map<String, List<String>> = emptyMap()
    private var charFreq: Map<String, Int> = emptyMap()

    private var loaded = false

    private fun dataFile(name: String): File? {
        val f = File(AppEnv.sharedDir, name)
        return if (f.exists()) f else null
    }

    private fun ensureLoaded() {
        if (loaded) return
        if (!MemoryBudget.canAfford(MemoryBudget.zhuyinLookup)) return
        if (loadBins()) {
            loaded = true
            DebugLog.log("OhMyBiasIM: zhuyin bins mmapped")
            return
        }
        loadJsonFallback()
    }

    /// zhuyin_data.bin = ZYMM 容器（兩個 SSMM）；pinyin/char_freq 各自獨立檔
    private fun loadBins(): Boolean {
        val zf = dataFile("zhuyin_data.bin") ?: return false
        val d = BinData.mapped(zf.path) ?: return false
        if (d.u8(0) != 'Z'.code || d.u8(1) != 'Y'.code || d.u8(2) != 'M'.code || d.u8(3) != 'M'.code) return false
        val z2c = StringListMap.at(d, d.u32(4).toInt()) ?: return false
        val c2z = StringListMap.at(d, d.u32(8).toInt()) ?: return false
        z2cBin = z2c; c2zBin = c2z
        dataFile("pinyin_data.bin")?.let { pf ->
            BinData.mapped(pf.path)?.let { pd -> pinyinBin = StringListMap.at(pd, 0) }
        }
        dataFile("char_freq.bin")?.let { ff ->
            BinData.mapped(ff.path)?.let { fd -> freqBin = CharFreqMap.of(fd) }
        }
        return true
    }

    private fun loadJsonFallback() {
        val f = dataFile("zhuyin_data.json") ?: run {
            DebugLog.log("ZhuyinLookup: zhuyin data not found"); return
        }
        try {
            val json = JSONObject(f.readText(Charsets.UTF_8))
            zhuyinToChars = parseStringListMap(json.getJSONObject("zhuyin_to_chars"))
            charToZhuyins = parseStringListMap(json.getJSONObject("char_to_zhuyins"))
            loaded = true
        } catch (e: Exception) {
            DebugLog.log("ZhuyinLookup: zhuyin_data.json parse failed: ${e.message}")
            return
        }
        dataFile("char_freq.json")?.let { ff ->
            try {
                val json = JSONObject(ff.readText(Charsets.UTF_8))
                val m = HashMap<String, Int>()
                for (k in json.keys()) m[k] = json.getInt(k)
                charFreq = m
            } catch (e: Exception) {
                DebugLog.log("ZhuyinLookup read char_freq: ${e.message}")
            }
        }
        DebugLog.log("OhMyBiasIM: zhuyin json loaded — ${zhuyinToChars.size} readings, ${charToZhuyins.size} chars")
        dataFile("pinyin_data.json")?.let { pf ->
            try {
                val json = JSONObject(pf.readText(Charsets.UTF_8))
                pinyinToChars = parseStringListMap(json.getJSONObject("pinyin_to_chars"))
            } catch (e: Exception) {
                DebugLog.log("ZhuyinLookup read pinyin_data: ${e.message}")
            }
        }
    }

    private fun parseStringListMap(obj: JSONObject): Map<String, List<String>> {
        val m = HashMap<String, List<String>>()
        for (k in obj.keys()) {
            val arr = obj.getJSONArray(k)
            val list = ArrayList<String>(arr.length())
            for (i in 0 until arr.length()) list.add(arr.getString(i))
            m[k] = list
        }
        return m
    }

    // MARK: - 內部查詢（bin 優先）

    private fun freqOf(char: String): Int =
        freqBin?.get(char) ?: (charFreq[char] ?: 0)

    private fun zhuyinsOf(char: String): List<String> =
        c2zBin?.get(char) ?: (charToZhuyins[char] ?: emptyList())

    // MARK: - 排序

    fun sortByFreq(chars: List<String>): List<String> {
        ensureLoaded()
        return chars.sortedByDescending { freqOf(it) }
    }

    /// 向下相容 overload — bigram 移除後 prevChar 不再使用
    fun sortByFreq(chars: List<String>, prevChar: String?, curZhuyin: String): List<String> = sortByFreq(chars)

    // MARK: - 查詢

    fun lookup(char: String): List<Reading> {
        ensureLoaded()
        val zhuyins = zhuyinsOf(char)
        if (zhuyins.isEmpty()) return emptyList()
        // char_to_zhuyins 的順序 = 常用讀音在前，直接保留
        val all = zhuyins.mapNotNull { zy ->
            val raw = charsForZhuyin(zy)
            val filtered = raw.filter { it != char }
            if (filtered.isEmpty()) null else Reading(zy, filtered)
        }
        if (DefaultPreferences.homophoneMultiReading) return all
        return all.firstOrNull()?.let { listOf(it) } ?: emptyList()
    }

    // MARK: - 反查

    fun charsForZhuyin(zhuyin: String): List<String> {
        ensureLoaded()
        return z2cBin?.get(zhuyin) ?: (zhuyinToChars[zhuyin] ?: emptyList())
    }

    fun charsForPinyin(pinyin: String): List<String> {
        ensureLoaded()
        pinyinLookup(pinyin).let { if (it.isNotEmpty()) return it }
        val converted = pinyin.replace("v", "ü")
        if (converted != pinyin) pinyinLookup(converted).let { if (it.isNotEmpty()) return it }
        return emptyList()
    }

    private fun pinyinLookup(key: String): List<String> =
        pinyinBin?.get(key) ?: (pinyinToChars[key] ?: emptyList())
}
