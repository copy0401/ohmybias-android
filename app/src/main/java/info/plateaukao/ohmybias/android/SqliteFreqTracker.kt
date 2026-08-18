package info.plateaukao.ohmybias.android

import android.database.sqlite.SQLiteDatabase
import android.os.Process
import info.plateaukao.ohmybias.shared.AppEnv
import info.plateaukao.ohmybias.shared.FreqTracker
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/// freq.db SQLite 字頻學習 — 資料表結構與 iOS 版相同（freq/bigram/pinned 三表），
/// 檔案可跨平台共用。批次寫入（50 筆一 transaction）與 500 筆衰減同 iOS。
///
/// 效能（取法 sweetlime）：三表整份載入記憶體，查詢（每個按鍵都會呼叫）純走記憶體 —
/// 鍵擊路徑零 SQLite。所有 DB 存取集中在單執行緒背景 executor（THREAD_PRIORITY_BACKGROUND），
/// 開 WAL 讓寫入不擋讀取；DB 常駐不關（IME 長生命週期）。記憶體是即時權威資料、
/// DB 是持久層：decay 兩邊各自套同因子，下次啟動自 DB 還原。
class SqliteFreqTracker : FreqTracker {
    /// 只在 executor 執行緒上存取（openAndLoad 先於其他工作排入）
    private var db: SQLiteDatabase? = null
    private var recordCount = 0
    private val pending = ArrayList<Triple<String, String, String>>()  // (table, key, char)
    private val batchSize = 50
    private val freqCache = HashMap<String, HashMap<String, Int>>()    // code → (char → n)
    private val bigramCache = HashMap<String, HashMap<String, Int>>()  // prev → (char → n)
    private val pinnedCache = HashMap<String, List<String>>()
    private val lock = Any()
    private val loaded = CountDownLatch(1)
    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
            r.run()
        }, "ohmybias-freq")
    }

    init {
        executor.execute { openAndLoad() }
    }

    private fun openAndLoad() {
        try {
            File(AppEnv.sharedDir).mkdirs()
            val d = SQLiteDatabase.openOrCreateDatabase(AppEnv.freqPath, null)
            d.enableWriteAheadLogging()
            d.execSQL("CREATE TABLE IF NOT EXISTS freq(code TEXT, char TEXT, n INTEGER, PRIMARY KEY(code,char))")
            d.execSQL("CREATE TABLE IF NOT EXISTS bigram(prev TEXT, char TEXT, n INTEGER, PRIMARY KEY(prev,char))")
            d.execSQL("CREATE TABLE IF NOT EXISTS pinned(code TEXT PRIMARY KEY, chars TEXT NOT NULL)")
            // 預設固定排序（常見同碼字衝突）
            d.execSQL("INSERT OR IGNORE INTO pinned(code,chars) VALUES('hj','手乎')")
            val f = loadCounts(d, "SELECT code,char,n FROM freq")
            val b = loadCounts(d, "SELECT prev,char,n FROM bigram")
            val p = HashMap<String, List<String>>()
            d.rawQuery("SELECT code, chars FROM pinned", null).use { c ->
                while (c.moveToNext()) p[c.getString(0)] = c.getString(1).map { it.toString() }
            }
            synchronized(lock) {
                db = d
                // 記憶體目前只含建構後的即時紀錄（同時在 pending 排隊）— 疊加 DB 既有計數即為完整狀態
                for ((key, m) in f) {
                    val cur = freqCache.getOrPut(key) { HashMap() }
                    for ((ch, n) in m) cur[ch] = (cur[ch] ?: 0) + n
                }
                for ((key, m) in b) {
                    val cur = bigramCache.getOrPut(key) { HashMap() }
                    for ((ch, n) in m) cur[ch] = (cur[ch] ?: 0) + n
                }
                for ((k, v) in p) if (k !in pinnedCache) pinnedCache[k] = v
            }
        } catch (e: Exception) {
        } finally {
            loaded.countDown()  // 開檔失敗也要放行查詢（純記憶體運作）
        }
    }

    private fun loadCounts(d: SQLiteDatabase, sql: String): HashMap<String, HashMap<String, Int>> {
        val r = HashMap<String, HashMap<String, Int>>()
        d.rawQuery(sql, null).use { c ->
            while (c.moveToNext()) {
                r.getOrPut(c.getString(0)) { HashMap() }[c.getString(1)] = c.getInt(2)
            }
        }
        return r
    }

    /// 首次查詢若早於載入完成則短暫等待（實務上載入遠快於第一個按鍵）
    private fun awaitLoaded() {
        try { loaded.await(500, TimeUnit.MILLISECONDS) } catch (_: InterruptedException) {}
    }

    private fun bump(cache: HashMap<String, HashMap<String, Int>>, key: String, char: String) {
        val m = cache.getOrPut(key) { HashMap() }
        m[char] = (m[char] ?: 0) + 1
    }

    // MARK: - Record（記憶體即時更新，DB 批次寫入丟背景）

    override fun record(code: String, char: String) {
        var snapshot: ArrayList<Triple<String, String, String>>? = null
        var doDecay = false
        synchronized(lock) {
            bump(freqCache, code, char)
            pending.add(Triple("freq", code, char))
            recordCount += 1
            if (pending.size >= batchSize) { snapshot = ArrayList(pending); pending.clear() }
            if (recordCount >= 500) {
                recordCount = 0
                doDecay = true
                decayMemoryLocked(0.9)
                // decay 前先送出剩餘 pending — DB 端「先加後衰減」與記憶體一致
                if (snapshot == null && pending.isNotEmpty()) { snapshot = ArrayList(pending); pending.clear() }
            }
        }
        snapshot?.let { s -> executor.execute { writePending(s) } }
        if (doDecay) executor.execute { decayDb(0.9) }
    }

    override fun recordBigram(prev: String, char: String) {
        if (prev.isEmpty()) return
        var snapshot: ArrayList<Triple<String, String, String>>? = null
        synchronized(lock) {
            bump(bigramCache, prev, char)
            pending.add(Triple("bigram", prev, char))
            if (pending.size >= batchSize) { snapshot = ArrayList(pending); pending.clear() }
        }
        snapshot?.let { s -> executor.execute { writePending(s) } }
    }

    override fun recordTrigram(prev2: String, prev1: String, char: String) {
        if (prev2.isEmpty() || prev1.isEmpty()) return
        recordBigram("$prev2|$prev1", char)
    }

    /// executor 執行緒專用
    private fun writePending(rows: List<Triple<String, String, String>>) {
        val d = db ?: return
        if (rows.isEmpty()) return
        try {
            d.beginTransaction()
            for ((table, key, char) in rows) {
                val col = if (table == "bigram") "prev" else "code"
                d.execSQL(
                    "INSERT INTO $table($col,char,n) VALUES(?,?,1) ON CONFLICT($col,char) DO UPDATE SET n=n+1",
                    arrayOf(key, char),
                )
            }
            d.setTransactionSuccessful()
        } catch (e: Exception) {
        } finally {
            try { d.endTransaction() } catch (_: Exception) {}
        }
    }

    /// 送出所有未寫入紀錄並短暫等待完成 — service 收鍵盤/銷毀時呼叫，保住學習資料
    override fun flushAll() {
        val snapshot = synchronized(lock) {
            if (pending.isEmpty()) return
            val s = ArrayList(pending)
            pending.clear()
            s
        }
        try {
            executor.submit { writePending(snapshot) }.get(2, TimeUnit.SECONDS)
        } catch (_: Exception) {}
    }

    // MARK: - Query（純記憶體 — 每鍵擊皆呼叫，不碰 SQLite）

    override fun queryFreq(code: String): Map<String, Int> {
        awaitLoaded()
        synchronized(lock) { return freqCache[code]?.toMap() ?: emptyMap() }
    }

    override fun queryBigram(prev: String): Map<String, Int> {
        awaitLoaded()
        synchronized(lock) { return bigramCache[prev]?.toMap() ?: emptyMap() }
    }

    // MARK: - Pinned

    override fun pin(code: String, chars: List<String>) {
        awaitLoaded()
        synchronized(lock) { pinnedCache[code] = chars }
        executor.execute {
            try {
                db?.execSQL("INSERT OR REPLACE INTO pinned(code,chars) VALUES(?,?)", arrayOf(code, chars.joinToString("")))
            } catch (_: Exception) {}
        }
    }

    override fun unpin(code: String) {
        awaitLoaded()
        synchronized(lock) { pinnedCache.remove(code) }
        executor.execute {
            try {
                db?.execSQL("DELETE FROM pinned WHERE code=?", arrayOf(code))
            } catch (_: Exception) {}
        }
    }

    override fun pinnedChars(forCode: String): List<String>? {
        awaitLoaded()
        synchronized(lock) { return pinnedCache[forCode] }
    }

    override fun reloadPinned() {
        executor.execute {
            val d = db ?: return@execute
            try {
                val p = HashMap<String, List<String>>()
                d.rawQuery("SELECT code, chars FROM pinned", null).use { c ->
                    while (c.moveToNext()) p[c.getString(0)] = c.getString(1).map { it.toString() }
                }
                synchronized(lock) {
                    pinnedCache.clear()
                    pinnedCache.putAll(p)
                }
            } catch (_: Exception) {}
        }
    }

    // MARK: - Maintenance

    override fun decay(factor: Double) {
        var snapshot: ArrayList<Triple<String, String, String>>? = null
        synchronized(lock) {
            decayMemoryLocked(factor)
            if (pending.isNotEmpty()) { snapshot = ArrayList(pending); pending.clear() }
        }
        snapshot?.let { s -> executor.execute { writePending(s) } }
        executor.execute { decayDb(factor) }
    }

    private fun decayMemoryLocked(factor: Double) {
        for (m in freqCache.values) {
            for ((k, v) in m.entries.toList()) m[k] = maxOf(1, (v * factor).toInt())
        }
        for (m in bigramCache.values) {
            for ((k, v) in m.entries.toList()) m[k] = maxOf(1, (v * factor).toInt())
        }
    }

    /// executor 執行緒專用 — 記憶體端已同步套過同因子；此處僅維護持久層並修剪表大小
    private fun decayDb(factor: Double) {
        val d = db ?: return
        try {
            d.beginTransaction()
            d.execSQL("UPDATE freq SET n=MAX(1,CAST(n*? AS INTEGER))", arrayOf(factor))
            d.execSQL("DELETE FROM freq WHERE n<1")
            d.execSQL("DELETE FROM freq WHERE n<=1 AND rowid NOT IN (SELECT rowid FROM freq ORDER BY n DESC LIMIT 5000)")
            d.execSQL("UPDATE bigram SET n=MAX(1,CAST(n*? AS INTEGER))", arrayOf(factor))
            d.execSQL("DELETE FROM bigram WHERE n<1")
            d.execSQL("DELETE FROM bigram WHERE n<=1 AND rowid NOT IN (SELECT rowid FROM bigram ORDER BY n DESC LIMIT 5000)")
            d.setTransactionSuccessful()
        } catch (e: Exception) {
        } finally {
            try { d.endTransaction() } catch (_: Exception) {}
        }
    }

    override fun reset() {
        synchronized(lock) {
            pending.clear()
            freqCache.clear()
            bigramCache.clear()
            recordCount = 0
        }
        executor.execute {
            try {
                db?.execSQL("DELETE FROM freq")
                db?.execSQL("DELETE FROM bigram")
            } catch (_: Exception) {}
        }
    }
}
