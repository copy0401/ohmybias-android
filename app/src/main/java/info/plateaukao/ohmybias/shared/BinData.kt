package info.plateaukao.ohmybias.shared

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

/// 對應 iOS 的 Data + u16/u32 helper — mmap 二進位檔的 little-endian 讀取。
/// .bin 索引常是 6-byte stride，offset 不保證對齊 — ByteBuffer 的 getShort/getInt
/// 本身容許非對齊存取，行為與 iOS 的 loadUnaligned 相同。越界一律回 0（同 iOS guard）。
class BinData private constructor(private val buf: ByteBuffer) {
    val count: Int = buf.limit()

    companion object {
        /// mmap 檔案（對應 Data(mappedIfSafe)）；失敗回 null
        fun mapped(path: String): BinData? = try {
            RandomAccessFile(path, "r").use { raf ->
                val map = raf.channel.map(FileChannel.MapMode.READ_ONLY, 0, raf.length())
                map.order(ByteOrder.LITTLE_ENDIAN)
                BinData(map)
            }
        } catch (e: Exception) {
            null
        }

        /// 整檔讀入（對應 Data(contentsOf:)）
        fun of(bytes: ByteArray): BinData {
            val b = ByteBuffer.wrap(bytes)
            b.order(ByteOrder.LITTLE_ENDIAN)
            return BinData(b)
        }

        fun of(file: File): BinData? = try { of(file.readBytes()) } catch (e: Exception) { null }
    }

    fun u8(off: Int): Int {
        if (off < 0 || off >= count) return 0
        return buf.get(off).toInt() and 0xFF
    }

    fun u16(off: Int): Int {
        if (off < 0 || off + 2 > count) return 0
        return buf.getShort(off).toInt() and 0xFFFF
    }

    fun u32(off: Int): Long {
        if (off < 0 || off + 4 > count) return 0
        return buf.getInt(off).toLong() and 0xFFFFFFFFL
    }

    /// 取 [start, start+len) 位元組（越界回空）
    fun bytes(start: Int, len: Int): ByteArray {
        if (start < 0 || len < 0 || start + len > count) return ByteArray(0)
        val r = ByteArray(len)
        for (i in 0 until len) r[i] = buf.get(start + i)
        return r
    }

    fun asciiString(start: Int, len: Int): String = String(bytes(start, len), Charsets.US_ASCII)
    fun utf8String(start: Int, len: Int): String = String(bytes(start, len), Charsets.UTF_8)
}
