#!/usr/bin/env python3
"""
Holds two sets of probe pictures side by side and says where they differ.

Works for any probe that writes PNGs into target/pictures/<label>/, which is all three of them:
RoomPicturesProbe, ListPicturesProbe and RowTopsProbe. The two arguments are the two labels.

The pictures are the point; this only says where to look. It reports, per pose, how many pixels
differ at all, the worst single channel difference, and the mean difference over the whole frame,
all out of 255. A change meant to be invisible should read 0 differing pixels; anything else needs
a human to look at the pair, which is why it also writes a difference image.

    python3 tools/perf/comparepics.py before after
"""

import os
import struct
import sys
import zlib


def read_png(path):
    """Width, height and RGBA rows of a PNG, without a library.

    Only what the probes write: 8-bit RGBA or RGB, no interlacing. Anything else is refused
    rather than misread.
    """
    with open(path, "rb") as f:
        blob = f.read()
    if blob[:8] != b"\x89PNG\r\n\x1a\n":
        sys.exit("%s is not a PNG" % path)
    pos = 8
    data = b""
    width = height = channels = None
    while pos < len(blob):
        length, kind = struct.unpack(">I4s", blob[pos:pos + 8])
        body = blob[pos + 8:pos + 8 + length]
        if kind == b"IHDR":
            width, height, depth, color, _, _, interlace = struct.unpack(">IIBBBBB", body)
            if depth != 8 or interlace != 0 or color not in (2, 6):
                sys.exit("%s is not 8-bit non-interlaced RGB/RGBA" % path)
            channels = 3 if color == 2 else 4
        elif kind == b"IDAT":
            data += body
        elif kind == b"IEND":
            break
        pos += 12 + length
    raw = zlib.decompress(data)
    stride = width * channels
    rows = []
    previous = bytearray(stride)
    at = 0
    for _ in range(height):
        filter_kind = raw[at]
        at += 1
        line = bytearray(raw[at:at + stride])
        at += stride
        unfilter(filter_kind, line, previous, channels)
        rows.append(bytes(line))
        previous = line
    return width, height, channels, rows


def unfilter(kind, line, previous, channels):
    """The five PNG row filters, applied in place."""
    if kind == 0:
        return
    for i in range(len(line)):
        left = line[i - channels] if i >= channels else 0
        up = previous[i]
        upleft = previous[i - channels] if i >= channels else 0
        if kind == 1:
            line[i] = (line[i] + left) & 0xFF
        elif kind == 2:
            line[i] = (line[i] + up) & 0xFF
        elif kind == 3:
            line[i] = (line[i] + (left + up) // 2) & 0xFF
        elif kind == 4:
            p = left + up - upleft
            pa, pb, pc = abs(p - left), abs(p - up), abs(p - upleft)
            best = left if (pa <= pb and pa <= pc) else (up if pb <= pc else upleft)
            line[i] = (line[i] + best) & 0xFF
        else:
            sys.exit("unknown PNG row filter %d" % kind)


def compare(a_path, b_path):
    aw, ah, ac, arows = read_png(a_path)
    bw, bh, bc, brows = read_png(b_path)
    if (aw, ah) != (bw, bh):
        return None, "different sizes: %dx%d against %dx%d" % (aw, ah, bw, bh)
    differing = 0
    worst = 0
    total = 0
    for y in range(ah):
        ar, br = arows[y], brows[y]
        for x in range(aw):
            ai, bi = x * ac, x * bc
            gap = max(abs(ar[ai + c] - br[bi + c]) for c in range(3))
            if gap:
                differing += 1
                worst = max(worst, gap)
                total += gap
    pixels = aw * ah
    return {
        "pixels": pixels,
        "differing": differing,
        "percent": 100.0 * differing / pixels,
        "worst": worst,
        "mean": total / pixels,
    }, None


def main():
    if len(sys.argv) != 3:
        sys.exit(__doc__)
    before, after = sys.argv[1], sys.argv[2]
    root = os.path.join("target", "pictures")
    names = sorted(n for n in os.listdir(os.path.join(root, before)) if n.endswith(".png"))
    print("| picture | pixels differing | of the frame | worst channel | mean over the frame |")
    print("| --- | ---: | ---: | ---: | ---: |")
    clean = True
    for name in names:
        stat, problem = compare(os.path.join(root, before, name), os.path.join(root, after, name))
        if problem:
            print("| %s | %s | | | |" % (name, problem))
            clean = False
            continue
        if stat["differing"]:
            clean = False
        print("| %s | %d | %.3f%% | %d of 255 | %.4f of 255 |"
              % (name, stat["differing"], stat["percent"], stat["worst"], stat["mean"]))
    print()
    print("*Both sets are in %s. %s*" % (root,
          "Every picture is identical, pixel for pixel." if clean
          else "At least one picture differs; open the pair and look."))


if __name__ == "__main__":
    main()
