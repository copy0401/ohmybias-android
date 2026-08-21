#!/usr/bin/env python3
"""zhuyin/pinyin/char_freq JSON → mmap 二進位（v2：ZYM2 / PYM2 / CFM2，LE）。
用法：tools/gen_data_bins.py <json 來源目錄> <輸出目錄>
來源目錄需含 zhuyin_data.json、pinyin_data.json、char_freq.json
（資料源頭在 ohmybias-ios/Resources/，上游為 Yabomish 整理）。

v2 相對 v1（SSMM/SCMM/CFMM）的省法 — 三檔合計 APK 內約 246 KB → 約 100 KB：
- char_to_zhuyins 完全是 zhuyin_to_chars 的反向索引，只有「讀音順序」是額外資訊
  （常用讀音在前）；v2 只存一份字表，反查存「音節索引」u16 而非重複 UTF-8 注音字串。
- 字存 UTF-16 code unit（u16，非 BMP 以 surrogate pair 佔兩格）而非 u32。
- codepoint 鍵表拆 BMP 與非 BMP 兩段：BMP 段以 32 筆為一區塊、區塊首鍵存絕對值 u16、
  區塊內存與前一鍵的差 u16（差值極小、deflate 壓得掉）；非 BMP 段存排序 u32。
  查詢：先二進位搜尋區塊首鍵，再線性掃 ≤32 筆差值（見下「CPKT」）。
- pinyin_to_chars 1342 個音節有 1338 個與某注音音節的字表逐字相同 → 存音節索引別名，
  只有 yu1–yu4（ㄧㄡ＋ㄩ 合併表）原樣內嵌。
- char_freq 只用於排序 → 存 dense rank（u16，0 = 最常用）而非 u32 頻次。

所有表皆 mmap 零 heap、鍵表固定寬度可二進位搜尋。讀取端：shared/DataMaps.kt。

=== CPKT（codepoint 鍵表，ZYM2 / CFM2 共用的子結構）===
  N 個排序 codepoint，拆 BMP（NB 個）與非 BMP（N−NB 個）；字的索引 i：BMP 段 0..NB−1，
  非 BMP 段接續 NB..N−1。
  heads ：u16 × ceil(NB/32) — 每區塊首鍵的絕對 codepoint
  deltas：u16 × NB — 區塊首鍵記 0，其餘記與前一鍵的差（BMP 內差值恆 < 65536）
  ext   ：u32 × (N−NB) — 非 BMP 排序 codepoint
  查 cp：cp > 0xFFFF → 二進位搜尋 ext；否則二進位搜尋 heads 找最後一個 ≤ cp 的區塊，
  自區塊首鍵起累加 deltas 至相等（命中）或超過（未命中）。

=== ZYM2（zhuyin_data.bin）===
  +0   "ZYM2"
  +4   S  u32  音節數
  +8   C  u32  反查字數（BMP + 非 BMP）
  +12  CB u32  反查字數中 BMP 的個數
  +16  R  u32  讀音總數（反查表的值總數）
  +20  U  u32  字表 code unit 總數
  +24  sylIdxOff u32 / +28 sylBlobOff / +32 unitsOff / +36 headsOff / +40 deltasOff
  +44  extOff / +48 blockStartOff / +52 countsOff / +56 readingsOff（皆為檔內絕對位移）
  +60  header 結束
  sylIdx（8B/筆，依注音字串 UTF-8 位元組排序）：strOff u16（相對 sylBlob）、strLen u8、
         unitCnt u8、unitStart u32（units 陣列索引）
  sylBlob：UTF-8 注音字串
  units：u16 × U — 每音節的字依序排列的 UTF-16 code unit
  heads / deltas / ext：反查字的 CPKT（N = C、NB = CB）
  blockStart：u16 × ceil(C/32) — 每區塊第一個字的讀音在 readings 的起始索引
  counts：u8 × C — 字 i 的讀音數；字 i 的讀音起點 = blockStart[i/32] + Σ counts[i/32*32 .. i−1]
  readings：u16 × R — 音節索引（sylIdx 的列號），順序 = 常用讀音在前

=== PYM2（pinyin_data.bin）===
  +0   "PYM2"
  +4   P  u32  拼音音節數
  +8   S  u32  建檔時 ZYM2 的音節數（讀取端核對，避免兩檔不同版）
  +12  idxOff u32 / +16 blobOff / +20 unitsOff；+24 header 結束
  idx（8B/筆，依拼音 UTF-8 位元組排序）：strOff u16、strLen u8、unitCnt u8、ref u32
       unitCnt == 0 → 別名：ref = ZYM2 音節索引，字表同該注音音節
       unitCnt  > 0 → 內嵌：ref = 本檔 units 起始索引，unitCnt 個 code unit
  blob：UTF-8 拼音鍵；units：u16

=== CFM2（char_freq.bin）===
  +0   "CFM2"
  +4   N u32 字數；+8 NB u32 其中 BMP 個數；+12 header 結束
  heads / deltas / ext：CPKT；ranks：u16 × N（dense rank，0 = 最常用）
  讀取端以 0xFFFF − rank 當「頻次」回傳，愈大愈常用，查無 → 0（同 v1 語意）。
"""
import json
import struct
import sys
from pathlib import Path


def utf16_units(s: str) -> list[int]:
    b = s.encode("utf-16-le")
    return list(struct.unpack(f"<{len(b) // 2}H", b))


def split_keys(cps: list[int]) -> tuple[list[int], list[int]]:
    """排序後拆成 BMP（u16）與非 BMP（u32）兩段。"""
    cps = sorted(cps)
    bmp = [c for c in cps if c <= 0xFFFF]
    ext = [c for c in cps if c > 0xFFFF]
    return bmp, ext


BLOCK = 32


def cpkt(cps: list[int]) -> tuple[bytes, int, int, list[int]]:
    """codepoint 鍵表 → (heads+deltas+ext 位元組, N, NB, 依索引排列的 codepoint)。"""
    bmp, ext = split_keys(cps)
    heads = [bmp[i] for i in range(0, len(bmp), BLOCK)]
    deltas = [0 if i % BLOCK == 0 else bmp[i] - bmp[i - 1] for i in range(len(bmp))]
    assert all(0 <= d <= 0xFFFF for d in deltas)
    return pack_u16s(heads) + pack_u16s(deltas) + pack_u32s(ext), len(bmp) + len(ext), len(bmp), bmp + ext


def cpkt_layout(nb: int) -> tuple[int, int]:
    """回傳 (heads 位元組數, deltas 位元組數)。"""
    return 2 * ((nb + BLOCK - 1) // BLOCK), 2 * nb


def pack_u16s(xs) -> bytes:
    return struct.pack(f"<{len(xs)}H", *xs)


def pack_u32s(xs) -> bytes:
    return struct.pack(f"<{len(xs)}I", *xs)


def build_zym2(z2c: dict[str, list[str]], c2z: dict[str, list[str]]) -> tuple[bytes, list[str]]:
    syllables = sorted(z2c.keys(), key=lambda k: k.encode("utf-8"))
    syl_index = {s: i for i, s in enumerate(syllables)}
    syl_blob = bytearray()
    syl_idx = bytearray()
    units: list[int] = []
    for s in syllables:
        sb = s.encode("utf-8")
        us = utf16_units("".join(z2c[s]))
        assert len(sb) <= 255 and len(us) <= 255, f"音節 {s!r} 超出 u8 範圍"
        assert len(syl_blob) <= 0xFFFF
        syl_idx += struct.pack("<HBBI", len(syl_blob), len(sb), len(us), len(units))
        syl_blob += sb
        units += us

    for ch, readings in c2z.items():
        assert len(ch) == 1, f"反查鍵必須是單字：{ch!r}"
        for z in readings:
            assert z in syl_index, f"反查表有字表沒有的注音：{ch!r} → {z!r}"
    keys_bytes, c_total, c_bmp, ordered_cps = cpkt([ord(ch) for ch in c2z])
    ordered = [chr(c) for c in ordered_cps]
    counts: list[int] = []
    block_starts: list[int] = []
    readings: list[int] = []
    for i, ch in enumerate(ordered):
        if i % BLOCK == 0:
            block_starts.append(len(readings))
        rs = c2z[ch]
        assert 0 < len(rs) <= 255
        counts.append(len(rs))
        readings += [syl_index[z] for z in rs]
    assert len(readings) <= 0xFFFF, "讀音總數超過 u16"

    heads_len, deltas_len = cpkt_layout(c_bmp)
    header_size = 60
    syl_idx_off = header_size
    syl_blob_off = syl_idx_off + len(syl_idx)
    units_off = syl_blob_off + len(syl_blob)
    heads_off = units_off + 2 * len(units)
    deltas_off = heads_off + heads_len
    ext_off = deltas_off + deltas_len
    block_start_off = heads_off + len(keys_bytes)
    counts_off = block_start_off + 2 * len(block_starts)
    readings_off = counts_off + len(counts)
    header = b"ZYM2" + struct.pack(
        "<IIIIIIIIIIIIII",
        len(syllables), c_total, c_bmp, len(readings), len(units),
        syl_idx_off, syl_blob_off, units_off, heads_off, deltas_off, ext_off,
        block_start_off, counts_off, readings_off,
    )
    assert len(header) == header_size
    out = (header + bytes(syl_idx) + bytes(syl_blob) + pack_u16s(units)
           + keys_bytes + pack_u16s(block_starts) + bytes(counts) + pack_u16s(readings))
    return out, syllables


def build_pym2(p2c: dict[str, list[str]], z2c: dict[str, list[str]], syllables: list[str]) -> bytes:
    by_chars = {tuple(z2c[s]): i for i, s in enumerate(syllables)}
    keys = sorted(p2c.keys(), key=lambda k: k.encode("utf-8"))
    blob = bytearray()
    idx = bytearray()
    units: list[int] = []
    inline = []
    for k in keys:
        kb = k.encode("utf-8")
        assert len(kb) <= 255 and len(blob) <= 0xFFFF
        chars = tuple(p2c[k])
        if chars in by_chars:
            idx += struct.pack("<HBBI", len(blob), len(kb), 0, by_chars[chars])
        else:
            us = utf16_units("".join(chars))
            assert 0 < len(us) <= 255, f"拼音 {k!r} 內嵌字表超出 u8"
            idx += struct.pack("<HBBI", len(blob), len(kb), len(us), len(units))
            units += us
            inline.append(k)
        blob += kb
    header_size = 24
    idx_off = header_size
    blob_off = idx_off + len(idx)
    units_off = blob_off + len(blob)
    header = b"PYM2" + struct.pack("<IIIII", len(keys), len(syllables), idx_off, blob_off, units_off)
    assert len(header) == header_size
    print(f"  pinyin 內嵌（非別名）音節：{inline}")
    return header + bytes(idx) + bytes(blob) + pack_u16s(units)


def build_cfm2(cf: dict[str, int]) -> bytes:
    for k in cf:
        assert len(k) == 1, f"char_freq 鍵必須是單字：{k!r}"
    distinct = sorted(set(cf.values()), reverse=True)
    rank_of = {v: i for i, v in enumerate(distinct)}
    assert len(distinct) <= 0xFFFF
    keys_bytes, n, nb, ordered = cpkt([ord(k) for k in cf])
    ranks = [rank_of[cf[chr(c)]] for c in ordered]
    header = b"CFM2" + struct.pack("<II", n, nb)
    return header + keys_bytes + pack_u16s(ranks)


def main() -> None:
    src = Path(sys.argv[1])
    out = Path(sys.argv[2])
    out.mkdir(parents=True, exist_ok=True)

    zy = json.loads((src / "zhuyin_data.json").read_text("utf-8"))
    z2c = zy["zhuyin_to_chars"]
    zym2, syllables = build_zym2(z2c, zy["char_to_zhuyins"])
    (out / "zhuyin_data.bin").write_bytes(zym2)

    py = json.loads((src / "pinyin_data.json").read_text("utf-8"))
    (out / "pinyin_data.bin").write_bytes(build_pym2(py["pinyin_to_chars"], z2c, syllables))

    cf = json.loads((src / "char_freq.json").read_text("utf-8"))
    (out / "char_freq.bin").write_bytes(build_cfm2(cf))

    for name in ("zhuyin_data.bin", "pinyin_data.bin", "char_freq.bin"):
        print(f"{name}: {(out / name).stat().st_size} bytes")


if __name__ == "__main__":
    main()
