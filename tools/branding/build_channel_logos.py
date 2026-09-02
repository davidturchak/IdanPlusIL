#!/usr/bin/env python3
"""Normalise channel card art into the bundled logo drawables.

Run once when a channel's artwork changes; the outputs are committed. This is
authoring, not a build step.

    python3 tools/branding/build_channel_logos.py path/to/dir

The input directory holds one image per channel named `<channel id>.<ext>`
(`11.png`, `27.jpg`, ...). Each becomes
`app/src/main/res/drawable-nodpi/logo_<id>.webp`, which `channels.json`
references by bare resource name (`"logo": "logo_11"`).

Why a script: the sources are opaque card images in a mix of 4:3 and 16:9 with
their own backgrounds (white, black, brand colour). The card shows them
full-bleed at 16:9, and cropping 4:3 art to 16:9 clips the circular "13" and
"14" marks. So each image is fitted inside 640x360 and the margins are filled
with the image's own edge colour, which makes the letterboxing invisible.

Pure PIL by design - numpy is not installed on the build machine.
"""

import os
import sys
from collections import Counter

from PIL import Image

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
OUT_DIR = os.path.join(ROOT, "app", "src", "main", "res", "drawable-nodpi")

W, H = 640, 360
WEBP_QUALITY = 90


def edge_colour(img):
    """Most common colour along the four edges - the image's own background."""
    w, h = img.size
    px = img.load()
    samples = Counter()
    for x in range(w):
        samples[px[x, 0]] += 1
        samples[px[x, h - 1]] += 1
    for y in range(h):
        samples[px[0, y]] += 1
        samples[px[w - 1, y]] += 1
    return samples.most_common(1)[0][0]


def normalise(path):
    img = Image.open(path).convert("RGB")
    bg = edge_colour(img)
    scale = min(W / img.width, H / img.height)
    size = (max(1, round(img.width * scale)), max(1, round(img.height * scale)))
    fitted = img.resize(size, Image.LANCZOS)
    canvas = Image.new("RGB", (W, H), bg)
    canvas.paste(fitted, ((W - size[0]) // 2, (H - size[1]) // 2))
    return canvas


def main(argv):
    if len(argv) != 2 or not os.path.isdir(argv[1]):
        sys.exit(__doc__)
    os.makedirs(OUT_DIR, exist_ok=True)
    written = 0
    for name in sorted(os.listdir(argv[1])):
        stem, ext = os.path.splitext(name)
        if ext.lower() not in (".png", ".jpg", ".jpeg", ".webp"):
            continue
        if not stem.isalnum():
            sys.exit(f"{name}: file stem must be the channel id (letters/digits only)")
        out = os.path.join(OUT_DIR, f"logo_{stem.lower()}.webp")
        normalise(os.path.join(argv[1], name)).save(out, "WEBP", quality=WEBP_QUALITY, method=6)
        print(f"{name} -> {os.path.relpath(out, ROOT)} ({os.path.getsize(out)} bytes)")
        written += 1
    print(f"{written} logos written")


if __name__ == "__main__":
    main(sys.argv)
