#!/usr/bin/env python3
"""Lists a canvas scene (core/Sources/MoonlightCore/Scene.swift's format, which Scene.kt reads):
its size, kind, paints with their names and colors, and counts of what it draws. Checks that
the buffer reads to its end exactly, as Scene.kt's reader must.

    core: swift build -c release --product moonlight-probe --scratch-path ../build/swift/host
    build/swift/host/release/moonlight-probe photo.ppm relaxed --scene /tmp/s.mlsc
    scripts/read-scene.py /tmp/s.mlsc
"""
import struct
import sys


def main(path):
    data = open(path, "rb").read()
    at = 0

    def take(fmt):
        nonlocal at
        values = struct.unpack_from("<" + fmt, data, at)
        at += struct.calcsize("<" + fmt)
        return values

    def count():
        (n,) = take("I")
        assert n <= len(data) - at, "count past the end"
        return n

    def string():
        nonlocal at
        n = count()
        s = data[at:at + n].decode("utf-8")
        at += n
        return s

    def points():
        n = count()
        return take(f"{2 * n}f")

    assert data[:4] == b"MLSC", "not a scene"
    at = 4
    (version,) = take("I")
    width, height = take("2f")
    (flags,) = take("I")
    print(f"scene v{version}, {width:g}x{height:g}, {'coloring book' if flags & 1 else 'classic'}")
    for i in range(count()):
        srgb = take("3f")
        p3 = take("3f")
        name, shade, hex_code = string(), string(), string()
        hex_srgb = "".join(f"{round(min(max(c, 0), 1) * 255):02X}" for c in srgb)
        print(f"  {i + 1:>3} {name or '-':<24} {shade:<28} {hex_code}  sRGB #{hex_srgb}  P3 "
              + " ".join(f"{c:.3f}" for c in p3))
    regions = count()
    for _ in range(regions):
        take("I f I 4f")
        for _ in range(count()):
            points()
    edges = count()
    drawn = 0
    for _ in range(edges):
        left, right, is_drawn = take("3I")
        assert left < regions and (right < regions or right == 0xFFFFFFFF), "edge region out of range"
        drawn += is_drawn
        points()
    labels = count()
    for _ in range(labels):
        take("3f I f")
    strokes = count()
    for _ in range(strokes):
        points()
    assert at == len(data), f"{len(data) - at} bytes left over"
    print(f"  {regions} areas, {edges} edges ({drawn} drawn), {labels} numbers, {strokes} strokes")


if __name__ == "__main__":
    main(sys.argv[1])
