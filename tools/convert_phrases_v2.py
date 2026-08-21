#!/usr/bin/env python3
"""phrases.bin PHMM（v1）→ PHM2（v2）。
用法：tools/convert_phrases_v2.py <phrases.bin v1> <phrases.bin v2 輸出>

萌典詞組沒有留下原始建檔腳本，v2 由 v1 機械轉換，內容（鍵、每鍵詞序、詞數上限 30）完全相同。
v1 每個詞以 u8 長度 + UTF-32LE 全字存放，首字與鍵重複；v2 省法：
- 詞只存「去掉首字」的餘字（讀取端把鍵字接回去）。
- 餘字存 UTF-16 code unit（u16，非 BMP 以 surrogate pair 佔兩格）而非 u32。
- counts 改 u8（上限 30）。
APK 內（deflate 後）約 251 KB → 約 165 KB。Android shared/WikiCorpus.kt 與 iOS Shared/WikiCorpus.swift
兩個讀取端同步改；檔案跨平台共用，兩邊 Resources 放同一份。

=== PHM2 ===
  +0   "PHM2"
  +4   N u32  首字鍵數
  +8   keys    u32 × N  排序 codepoint
  +8+4N offsets u32 × N  各鍵第一個詞在 blob 的位移（相對 blob 起點）
  +8+8N counts  u8  × N  各鍵詞數
  +8+9N blob：每詞 u8 unitCnt + u16 × unitCnt（餘字的 UTF-16 code unit）
"""
import struct
import sys


def main() -> None:
    src, dst = sys.argv[1], sys.argv[2]
    d = open(src, "rb").read()
    assert d[:4] == b"PHMM", "來源不是 PHMM"
    n = struct.unpack_from("<I", d, 4)[0]
    keys = struct.unpack_from(f"<{n}I", d, 8)
    offs = struct.unpack_from(f"<{n}I", d, 8 + 4 * n)
    cnts = struct.unpack_from(f"<{n}H", d, 8 + 8 * n)
    base = 8 + 10 * n

    blob = bytearray()
    new_offs = []
    new_cnts = []
    total = 0
    for i in range(n):
        p = base + offs[i]
        assert cnts[i] <= 255
        new_offs.append(len(blob))
        new_cnts.append(cnts[i])
        for _ in range(cnts[i]):
            ln = d[p]
            cps = struct.unpack_from(f"<{ln}I", d, p + 1)
            p += 1 + 4 * ln
            assert ln >= 1 and cps[0] == keys[i], f"詞首字與鍵不符：key={keys[i]:x} phrase={cps}"
            rest = "".join(chr(c) for c in cps[1:]).encode("utf-16-le")
            units = len(rest) // 2
            assert units <= 255
            blob += struct.pack("<B", units) + rest
            total += 1
    assert all(k == keys[i] and (i == 0 or keys[i - 1] < k) for i, k in enumerate(keys)), "鍵未排序"

    out = (b"PHM2" + struct.pack("<I", n)
           + struct.pack(f"<{n}I", *keys)
           + struct.pack(f"<{n}I", *new_offs)
           + struct.pack(f"<{n}B", *new_cnts)
           + bytes(blob))
    open(dst, "wb").write(out)
    print(f"{src}: {len(d)} bytes → {dst}: {len(out)} bytes（{n} 鍵、{total} 詞）")


if __name__ == "__main__":
    main()
