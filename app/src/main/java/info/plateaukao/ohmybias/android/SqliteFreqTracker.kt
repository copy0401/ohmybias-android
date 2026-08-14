package info.plateaukao.ohmybias.android

import android.database.sqlite.SQLiteDatabase
import info.plateaukao.ohmybias.shared.AppEnv
import info.plateaukao.ohmybias.shared.DebugLog
import info.plateaukao.ohmybias.shared.FreqTracker
import java.io.File

/// freq.db SQLite 字頻學習 — 資料表結構與 iOS 版相同（freq/bigram/pinned 三表），
/// 檔案可跨平台共用。批次寫入（50 筆一 transaction）與 500 筆衰減同 iOS。
class SqliteFreqTracker : FreqTracker {
    private var db: SQLiteDatabase? = null
    private var recordCount = 0
    private val pending = ArrayList<Triple<String, String, String>>()  // (table, key, char)
    private val batchSize = 50
    private val pinnedCache = HashMap<String, List<String>>()
    private val lock = Any()

    init {
        try {
            File(AppEnv.sharedDir).mkdirs()
            val d = SQLiteDatabase.openOrCreateDatabase(AppEnv.freqPath, null)
            d.execSQL("CREATE TABLE IF NOT EXISTS freq(code TEXT, char TEXT, n INTEGER, PRIMARY KEY(code,char))")
            d.execSQL("CREATE TABLE IF NOT EXISTS bigram(prev TEXT, char TEXT, n INTEGER, PRIMARY KEY(prev,char))")
            d.execSQL("CREATE TABLE IF NOT EXISTS pinned(code TEXT PRIMARY KEY, chars TEXT NOT NULL)")
            // 預設固定排序（常見同碼字衝突）
            d.execSQL("INSERT OR IGNORE INTO pinned(code,chars) VALUES('hj','手乎')")
            db = d
            loadPinnedCache()
        } catch (e: Exception) {
            DebugLog.log("SqliteFreqTracker open failed: ${e.message}")
        }
    }

    private fun loadPinnedCache() {
        val d = db ?: return
        pinnedCache.clear()
        d.rawQuery("SELECT code, chars FROM pinned", null).use { c ->
            while (c.moveToNext()) {
                val code = c.getString(0)
                val chars = c.getString(1)
                pinnedCache[code] = chars.map { it.toString() }
            }
        }
    }

    // MARK: - Record（批次寫入）

    override fun record(code: String, char: String) = synchronized(lock) {
        pending.add(Triple("freq", code, char))
        recordCount += 1
        if (pending.size >= batchSize) flushLocked()
        if (recordCount >= 500) { recordCount = 0; decayLocked(0.9) }
    }

    override fun recordBigram(prev: String, char: String) {
        if (prev.isEmpty()) return
        synchronized(lock) {
            pending.add(Triple("bigram", prev, char))
            if (pending.size >= batchSize) flushLocked()
        }
    }

    override fun recordTrigram(prev2: String, prev1: String, char: String) {
        if (prev2.isEmpty() || prev1.isEmpty()) return
        recordBigram("$prev2|$prev1", char)
    }

    private fun flushLocked() {
        val d = db ?: return
        if (pending.isEmpty()) return
        try {
            d.beginTransaction()
            for ((table, key, char) in pending) {
                val col = if (table == "bigram") "prev" else "code"
                d.execSQL(
                    "INSERT INTO $table($col,char,n) VALUES(?,?,1) ON CONFLICT($col,char) DO UPDATE SET n=n+1",
                    arrayOf(key, char),
                )
            }
            d.setTransactionSuccessful()
        } catch (e: Exception) {
            DebugLog.log("SqliteFreqTracker flush: ${e.message}")
        } finally {
            try { d.endTransaction() } catch (_: Exception) {}
        }
        pending.clear()
    }

    override fun flushAll() = synchronized(lock) { flushLocked() }

    // MARK: - Query（查詢前先 flush，讓學習立即生效）

    override fun queryFreq(code: String): Map<String, Int> = synchronized(lock) {
        flushLocked()
        queryMap("SELECT char,n FROM freq WHERE code=? ORDER BY n DESC", code)
    }

    override fun queryBigram(prev: String): Map<String, Int> = synchronized(lock) {
        flushLocked()
        queryMap("SELECT char,n FROM bigram WHERE prev=? ORDER BY n DESC", prev)
    }

    private fun queryMap(sql: String, key: String): Map<String, Int> {
        val d = db ?: return emptyMap()
        val result = HashMap<String, Int>()
        try {
            d.rawQuery(sql, arrayOf(key)).use { c ->
                while (c.moveToNext()) result[c.getString(0)] = c.getInt(1)
            }
        } catch (e: Exception) {
            DebugLog.log("SqliteFreqTracker query: ${e.message}")
        }
        return result
    }

    // MARK: - Pinned

    override fun pin(code: String, chars: List<String>) = synchronized(lock) {
        db?.execSQL("INSERT OR REPLACE INTO pinned(code,chars) VALUES(?,?)", arrayOf(code, chars.joinToString("")))
        pinnedCache[code] = chars
        Unit
    }

    override fun unpin(code: String) = synchronized(lock) {
        db?.execSQL("DELETE FROM pinned WHERE code=?", arrayOf(code))
        pinnedCache.remove(code)
        Unit
    }

    override fun pinnedChars(forCode: String): List<String>? = synchronized(lock) { pinnedCache[forCode] }

    override fun reloadPinned() = synchronized(lock) { loadPinnedCache() }

    // MARK: - Maintenance

    override fun decay(factor: Double) = synchronized(lock) { decayLocked(factor) }

    private fun decayLocked(factor: Double) {
        val d = db ?: return
        try {
            d.execSQL("UPDATE freq SET n=MAX(1,CAST(n*$factor AS INTEGER))")
            d.execSQL("DELETE FROM freq WHERE n<1")
            d.execSQL("DELETE FROM freq WHERE n<=1 AND rowid NOT IN (SELECT rowid FROM freq ORDER BY n DESC LIMIT 5000)")
            d.execSQL("UPDATE bigram SET n=MAX(1,CAST(n*$factor AS INTEGER))")
            d.execSQL("DELETE FROM bigram WHERE n<1")
            d.execSQL("DELETE FROM bigram WHERE n<=1 AND rowid NOT IN (SELECT rowid FROM bigram ORDER BY n DESC LIMIT 5000)")
        } catch (e: Exception) {
            DebugLog.log("SqliteFreqTracker decay: ${e.message}")
        }
    }

    override fun reset() = synchronized(lock) {
        pending.clear()
        try {
            db?.execSQL("DELETE FROM freq")
            db?.execSQL("DELETE FROM bigram")
        } catch (e: Exception) {
            DebugLog.log("SqliteFreqTracker reset: ${e.message}")
        }
        recordCount = 0
    }
}
