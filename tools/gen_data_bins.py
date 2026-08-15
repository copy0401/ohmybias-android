#!/usr/bin/env python3
"""zhuyin/pinyin/char_freq JSON → mmap 二進位（與 CINM/PHMM 同家族的 LE 格式）。

用法：tools/gen_data_bins.py <json 來源目錄> <輸出目錄>
來源目錄需含 zhuyin_data.json、pinyin_data.json、char_freq.json
（資料源頭在 ohmybias-ios/Resources/，上游為 Yabomish 整理）。

格式：

SSMM 區塊（string → [string]，key 依 UTF-8 位元組排序供二進位搜尋）
  +0   "SSMM"
  +4   keyCount u32
  +8   keyIdxOff u32（區塊內相對位移，以下同）
  +12  valIdxOff u32
  +16  keyBlobOff u32
  +20  valBlobOff u32
  +24  reserved u32 ×2
  keyIdx（12B/筆）：keyOff u32（相對 keyBlob）、keyLen u16、valStart u32（valIdx 索引）、valCnt u16
  valIdx（6B/筆）：valOff u32（相對 valBlob）、valLen u16
  keyBlob / valBlob：UTF-8

SCMM 區塊：同 SSMM 但值為單一 codepoint（valIdxOff 保留為 0、不存在 valIdx；
  值直接是 valBlob 起的 u32 codepoint 陣列，keyIdx 的 valStart 為陣列索引）
  — 適用值全為單字的 map（zhuyin_to_chars / pinyin_to_chars），較 SSMM 省約四成。

zhuyin_data.bin = ZYMM 容器：+0 "ZYMM"、+4 z2cOff u32、+8 c2zOff u32（絕對位移）
                 → z2c 為 SCMM、c2z 為 SSMM（值是注音字串）
pinyin_data.bin = 單一 SCMM 區塊
char_freq.bin  = CFMM：+0 "CFMM"、+4 count u32、+8 起 (codepoint u32, freq u32) 依 codepoint 排序
"""
import json
import struct
import sys
from pathlib import Path


def ssmm_block(mapping: dict) -> bytes:
    keys = sorted(mapping.keys(), key=lambda k: k.encode("utf-8"))
    key_blob = bytearray()
    val_blob = bytearray()
    key_idx = bytearray()
    val_idx = bytearray()
    val_start = 0
    for k in keys:
        kb = k.encode("utf-8")
        key_off = len(key_blob)
        key_blob += kb
        vals = mapping[k]
        key_idx += struct.pack("<IHIH", key_off, len(kb), val_start, len(vals))
        for v in vals:
            vb = v.encode("utf-8")
            val_idx += struct.pack("<IH", len(val_blob), len(vb))
            val_blob += vb
            val_start += 1
    header_size = 32
    key_idx_off = header_size
    val_idx_off = key_idx_off + len(key_idx)
    key_blob_off = val_idx_off + len(val_idx)
    val_blob_off = key_blob_off + len(key_blob)
    header = b"SSMM" + struct.pack(
        "<IIIIIII", len(keys), key_idx_off, val_idx_off, key_blob_off, val_blob_off, 0, 0
    )
    assert len(header) == header_size
    return bytes(header) + bytes(key_idx) + bytes(val_idx) + bytes(key_blob) + bytes(val_blob)


def scmm_block(mapping: dict) -> bytes:
    """值全為單一 codepoint 的 map — 值存 u32 陣列，無 valIdx。"""
    keys = sorted(mapping.keys(), key=lambda k: k.encode("utf-8"))
    key_blob = bytearray()
    val_blob = bytearray()
    key_idx = bytearray()
    val_start = 0
    for k in keys:
        kb = k.encode("utf-8")
        key_off = len(key_blob)
        key_blob += kb
        vals = mapping[k]
        key_idx += struct.pack("<IHIH", key_off, len(kb), val_start, len(vals))
        for v in vals:
            cps = [ord(c) for c in v]
            assert len(cps) == 1, f"SCMM 值必須是單一 codepoint：{v!r}"
            val_blob += struct.pack("<I", cps[0])
            val_start += 1
    header_size = 32
    key_idx_off = header_size
    key_blob_off = key_idx_off + len(key_idx)
    val_blob_off = key_blob_off + len(key_blob)
    header = b"SCMM" + struct.pack(
        "<IIIIIII", len(keys), key_idx_off, 0, key_blob_off, val_blob_off, 0, 0
    )
    assert len(header) == header_size
    return bytes(header) + bytes(key_idx) + bytes(key_blob) + bytes(val_blob)


def main() -> None:
    src = Path(sys.argv[1])
    out = Path(sys.argv[2])
    out.mkdir(parents=True, exist_ok=True)

    zy = json.loads((src / "zhuyin_data.json").read_text("utf-8"))
    z2c = scmm_block(zy["zhuyin_to_chars"])
    c2z = ssmm_block(zy["char_to_zhuyins"])
    container = b"ZYMM" + struct.pack("<II", 12, 12 + len(z2c))
    (out / "zhuyin_data.bin").write_bytes(container + z2c + c2z)

    py = json.loads((src / "pinyin_data.json").read_text("utf-8"))
    (out / "pinyin_data.bin").write_bytes(scmm_block(py["pinyin_to_chars"]))

    cf = json.loads((src / "char_freq.json").read_text("utf-8"))
    pairs = sorted((ord(k), v) for k, v in cf.items())
    body = b"".join(struct.pack("<II", cp, n) for cp, n in pairs)
    (out / "char_freq.bin").write_bytes(b"CFMM" + struct.pack("<I", len(pairs)) + body)

    for name in ("zhuyin_data.bin", "pinyin_data.bin", "char_freq.bin"):
        print(f"{name}: {(out / name).stat().st_size} bytes")


if __name__ == "__main__":
    main()
