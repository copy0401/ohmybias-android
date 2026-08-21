package info.plateaukao.ohmybias.shared

import org.json.JSONObject
import java.io.File

/// 同音字查詢：字 → 注音 → 同音字（按字頻排序）。
/// 資料優先走 mmap 二進位（zhuyin_data.bin/pinyin_data.bin/char_freq.bin — v2 格式 ZYM2/PYM2/CFM2，
/// 零 heap、零解析；格式見 tools/gen_data_bins.py），找不到 .bin 時回退舊版 JSON（升級相容）。
class ZhuyinLookup {
    companion object {
        val shared: ZhuyinLookup by lazy { ZhuyinLookup() }
    }

    data class Reading(val zhuyin: String, val chars: List<String>)

    // mmap 二進位
    private var zhuyinBin: ZhuyinTable? = null
    private var pinyinBin: PinyinTable? = null
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
            return
        }
        loadJsonFallback()
    }

    /// zhuyin_data.bin（ZYM2）為主；pinyin_data.bin（PYM2，別名指向 ZYM2 音節）與 char_freq.bin（CFM2）各自獨立檔
    private fun loadBins(): Boolean {
        val zf = dataFile("zhuyin_data.bin") ?: return false
        val d = BinData.mapped(zf.path) ?: return false
        val zt = ZhuyinTable.of(d) ?: return false
        zhuyinBin = zt
        dataFile("pinyin_data.bin")?.let { pf ->
            BinData.mapped(pf.path)?.let { pd -> pinyinBin = PinyinTable.of(pd, zt) }
        }
        dataFile("char_freq.bin")?.let { ff ->
            BinData.mapped(ff.path)?.let { fd -> freqBin = CharFreqMap.of(fd) }
        }
        return true
    }

    private fun loadJsonFallback() {
        val f = dataFile("zhuyin_data.json") ?: run {
            return
        }
        try {
            val json = JSONObject(f.readText(Charsets.UTF_8))
            zhuyinToChars = parseStringListMap(json.getJSONObject("zhuyin_to_chars"))
            charToZhuyins = parseStringListMap(json.getJSONObject("char_to_zhuyins"))
            loaded = true
        } catch (e: Exception) {
            return
        }
        dataFile("char_freq.json")?.let { ff ->
            try {
                val json = JSONObject(ff.readText(Charsets.UTF_8))
                val m = HashMap<String, Int>()
                for (k in json.keys()) m[k] = json.getInt(k)
                charFreq = m
            } catch (_: Exception) {}
        }
        dataFile("pinyin_data.json")?.let { pf ->
            try {
                val json = JSONObject(pf.readText(Charsets.UTF_8))
                pinyinToChars = parseStringListMap(json.getJSONObject("pinyin_to_chars"))
            } catch (_: Exception) {}
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
        zhuyinBin?.zhuyinsOf(char) ?: (charToZhuyins[char] ?: emptyList())

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
        return zhuyinBin?.charsForZhuyin(zhuyin) ?: (zhuyinToChars[zhuyin] ?: emptyList())
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
