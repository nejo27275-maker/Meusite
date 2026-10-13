#!/usr/bin/env python3
"""Generate a tiny Bedrock block texture using only Python's standard library."""
from pathlib import Path
import struct
import zlib

SIZE = 16
pixels = []
for y in range(SIZE):
    row = []
    for x in range(SIZE):
        edge = x in (0, 1, SIZE - 2, SIZE - 1) or y in (0, 1, SIZE - 2, SIZE - 1)
        if edge:
            color = (10, 24, 46, 255)
        elif x in (3, 12) or y in (3, 12):
            color = (26, 83, 136, 255)
        elif (x + y) % 7 == 0:
            color = (255, 176, 56, 255)
        elif (x in (7, 8) or y in (7, 8)):
            color = (111, 230, 255, 255)
        else:
            color = (24, 160, 206, 255)
        row.extend(color)
    pixels.append(bytes(row))

def chunk(kind: bytes, data: bytes) -> bytes:
    payload = kind + data
    return struct.pack(">I", len(data)) + payload + struct.pack(">I", zlib.crc32(payload) & 0xFFFFFFFF)

raw = b"".join(b"\x00" + row for row in pixels)
png = (
    b"\x89PNG\r\n\x1a\n"
    + chunk(b"IHDR", struct.pack(">IIBBBBB", SIZE, SIZE, 8, 6, 0, 0, 0))
    + chunk(b"IDAT", zlib.compress(raw, 9))
    + chunk(b"IEND", b"")
)
target = Path("addon/resource_pack/textures/blocks/linkblocks_controller.png")
target.parent.mkdir(parents=True, exist_ok=True)
target.write_bytes(png)
print(f"Generated {target} ({len(png)} bytes)")
