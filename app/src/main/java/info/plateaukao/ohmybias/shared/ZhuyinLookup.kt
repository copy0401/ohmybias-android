package info.plateaukao.ohmybias.shared

import org.json.JSONObject
import java.io.File

/// 同音字查詢：字 → 注音 → 同音字（按字頻排序）
class ZhuyinLookup {
    companion object {
        val shared: ZhuyinLookup by lazy { ZhuyinLookup() }
    }

    data class Reading(val zhuyin: String, val chars: List<String>)

    private var charToZhuyins: Map<String, List<String>> = emptyMap()
    private var zhuyinToChars: Map<String, List<String>> = emptyMap()
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
        val f = dataFile("zhuyin_data.json") ?: run {
            DebugLog.log("ZhuyinLookup: zhuyin_data.json not found"); return
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
        DebugLog.log("OhMyBiasIM: zhuyin loaded — ${zhuyinToChars.size} readings, ${charToZhuyins.size} chars, ${charFreq.size} freq")
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

    // MARK: - 排序

    fun sortByFreq(chars: List<String>): List<String> {
        ensureLoaded()
        return chars.sortedByDescending { charFreq[it] ?: 0 }
    }

    /// 向下相容 overload — bigram 移除後 prevChar 不再使用
    fun sortByFreq(chars: List<String>, prevChar: String?, curZhuyin: String): List<String> = sortByFreq(chars)

    // MARK: - 查詢

    fun lookup(char: String): List<Reading> {
        ensureLoaded()
        val zhuyins = charToZhuyins[char] ?: return emptyList()
        // char_to_zhuyins 的順序 = 常用讀音在前，直接保留
        val all = zhuyins.mapNotNull { zy ->
            val raw = zhuyinToChars[zy] ?: return@mapNotNull null
            val filtered = raw.filter { it != char }
            if (filtered.isEmpty()) null else Reading(zy, filtered)
        }
        if (DefaultPreferences.homophoneMultiReading) return all
        return all.firstOrNull()?.let { listOf(it) } ?: emptyList()
    }

    // MARK: - 反查

    fun charsForZhuyin(zhuyin: String): List<String> {
        ensureLoaded()
        return zhuyinToChars[zhuyin] ?: emptyList()
    }

    fun charsForPinyin(pinyin: String): List<String> {
        ensureLoaded()
        pinyinToChars[pinyin]?.let { if (it.isNotEmpty()) return it }
        val converted = pinyin.replace("v", "ü")
        if (converted != pinyin) pinyinToChars[converted]?.let { if (it.isNotEmpty()) return it }
        return emptyList()
    }
}
