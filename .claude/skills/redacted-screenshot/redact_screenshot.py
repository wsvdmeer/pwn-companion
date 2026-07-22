#!/usr/bin/env python3
"""
Auto-redact sensitive data from a PwnCompanion screenshot using its uiautomator dump.

Blacks out any UI node whose *text* looks sensitive (IP address, GPS coordinate, MAC,
the device/node name, or any extra literal you pass) and any node whose
*content-description* matches a sensitive tag (default: the e-ink "device screen",
which has the node name + BT IP baked into the bitmap that uiautomator can't read).

Usage:
    python redact_screenshot.py --img shot.png --ui ui.xml --out shot_redacted.png \
        [--name your-device-name] [--extra SSID_A SSID_B ...] \
        [--desc "device screen"] [--box x1,y1,x2,y2 ...] [--pad 4]

Exit is non-zero if it redacted nothing AND --expect-redactions was passed — a guard
so you never publish a "redacted" shot that actually redacted nothing by mistake.
"""
import argparse
import re
import sys
import xml.etree.ElementTree as ET

from PIL import Image, ImageDraw

IP  = re.compile(r"\b\d{1,3}\.\d{1,3}\.\d{1,3}\.\d{1,3}\b")
MAC = re.compile(r"(?:[0-9A-Fa-f]{2}[:\-]){5}[0-9A-Fa-f]{2}")
# lat/lon: a number with >= 4 fractional digits ("12.34567" or the "12,34567" the app shows)
GEO = re.compile(r"-?\d{1,3}[.,]\d{4,}")
BOUNDS = re.compile(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]")


def is_sensitive_text(text, name, extras):
    if not text:
        return False
    if IP.search(text) or MAC.search(text) or GEO.search(text):
        return True
    low = text.lower()
    if name and name.lower() in low:
        return True
    return any(e and e.lower() in low for e in extras)


def parse_bounds(raw):
    m = BOUNDS.search(raw or "")
    if not m:
        return None
    x1, y1, x2, y2 = map(int, m.groups())
    return [x1, y1, x2, y2]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--img", required=True)
    ap.add_argument("--ui", required=True)
    ap.add_argument("--out", required=True)
    ap.add_argument("--name", default="", help="device/node name to redact (e.g. the Pwnagotchi name)")
    ap.add_argument("--extra", nargs="*", default=[], help="extra literal substrings to redact (e.g. SSIDs)")
    ap.add_argument("--desc", nargs="*", default=["device screen"],
                    help="content-desc values to black out entirely (baked-in-image text)")
    ap.add_argument("--box", nargs="*", default=[], help="manual x1,y1,x2,y2 regions to black out")
    ap.add_argument("--pad", type=int, default=4, help="pixels of padding around each redaction")
    ap.add_argument("--expect-redactions", action="store_true",
                    help="fail (exit 2) if nothing was redacted — guard against silent no-ops")
    a = ap.parse_args()

    im = Image.open(a.img).convert("RGB")
    draw = ImageDraw.Draw(im)
    W, H = im.size
    pad = a.pad
    count = 0

    def black(b):
        nonlocal count
        x1, y1, x2, y2 = b
        draw.rectangle(
            [max(0, x1 - pad), max(0, y1 - pad), min(W, x2 + pad), min(H, y2 + pad)],
            fill=(0, 0, 0),
        )
        count += 1

    descs = [d.lower() for d in a.desc]
    for node in ET.parse(a.ui).getroot().iter("node"):
        text = node.attrib.get("text", "")
        cdesc = (node.attrib.get("content-desc", "") or "").lower()
        hit = is_sensitive_text(text, a.name, a.extra) or (cdesc and cdesc in descs)
        if hit:
            b = parse_bounds(node.attrib.get("bounds", ""))
            if b:
                black(b)

    for raw in a.box:
        try:
            b = [int(v) for v in raw.split(",")]
            if len(b) == 4:
                black(b)
        except ValueError:
            print(f"skipping bad --box '{raw}'", file=sys.stderr)

    im.save(a.out)
    print(f"redacted {count} region(s) -> {a.out}  ({W}x{H})")
    if a.expect_redactions and count == 0:
        print("ERROR: --expect-redactions set but nothing matched", file=sys.stderr)
        sys.exit(2)


if __name__ == "__main__":
    main()
