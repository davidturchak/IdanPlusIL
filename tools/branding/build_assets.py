#!/usr/bin/env python3
"""Generate IdanPlusIL launcher, banner and splash assets from the source logo.

Run once when the source logo changes; the outputs are committed. This is
authoring, not a build step.

    python3 tools/branding/build_assets.py path/to/logo.png

Why this is not a one-liner: the source is an opaque PNG on white, and ~23% of
the mark's own area is near-white highlight *inside* the flame petals. Keying on
luminance alone (alpha = 255 - luma) punches those cores out and leaves the flame
as a hollow outline. Background removal therefore has to be connectivity-based:
flood-fill inward from the border, so enclosed highlights are never touched.

Pure PIL by design - numpy is not installed on the build machine.
"""

import os
import sys
from PIL import Image, ImageDraw, ImageFilter

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
RES = os.path.join(ROOT, "app", "src", "main", "res")

BG_HEX = (0x0D, 0x0D, 0x10)      # brand_background
BANNER_BG = (0x16, 0x16, 0x1B)   # brand_surface
WORDMARK_INK = (0xF2, 0xEF, 0xF2)

FLOOD_THRESH = 25
RING_PX = 2
SENTINEL = (255, 0, 255)


def key_background(src):
    """Return RGBA with the white background keyed out, highlights preserved."""
    w, h = src.size
    rgb = src.convert("RGB")

    # 1. Connectivity-based background mask: flood inward from all four corners.
    probe = rgb.copy()
    draw = ImageDraw.Draw(probe)
    for xy in ((0, 0), (w - 1, 0), (0, h - 1), (w - 1, h - 1)):
        ImageDraw.floodfill(probe, xy, SENTINEL, thresh=FLOOD_THRESH)

    ppx = probe.load()
    bg = Image.new("L", (w, h), 0)
    bgpx = bg.load()
    for y in range(h):
        for x in range(w):
            if ppx[x, y] == SENTINEL:
                bgpx[x, y] = 255

    # 2. The flood stops at the soft edge ramp, leaving an opaque white fringe.
    #    Treat only pixels within RING_PX of proven background as ramp, and
    #    unpremultiply white there. Interior highlights are far from the ring
    #    and stay fully opaque.
    bg_dilated = bg.filter(ImageFilter.MaxFilter(RING_PX * 2 + 1))
    dpx = bg_dilated.load()

    out = Image.new("RGBA", (w, h))
    opx = out.load()
    spx = rgb.load()
    for y in range(h):
        for x in range(w):
            r, g, b = spx[x, y]
            if bgpx[x, y]:
                opx[x, y] = (r, g, b, 0)
            elif dpx[x, y]:
                # Edge ramp: how far this pixel is from pure white.
                a = 255 - min(r, g, b)
                if a <= 0:
                    opx[x, y] = (r, g, b, 0)
                else:
                    af = a / 255.0
                    # Clamp the divisor. At the outer edge the pixel is nearly
                    # pure white, so `a` is tiny and an exact unpremultiply
                    # amplifies rounding noise into near-black - a dark fringe
                    # that is very visible against a dark UI. Capping the
                    # amplification at ~2.5x keeps the ramp colour plausible;
                    # the alpha still does the real work.
                    afe = max(af, 0.4)
                    inv = 255.0 * (1.0 - afe)
                    opx[x, y] = (
                        max(0, min(255, int((r - inv) / afe))),
                        max(0, min(255, int((g - inv) / afe))),
                        max(0, min(255, int((b - inv) / afe))),
                        a,
                    )
            else:
                opx[x, y] = (r, g, b, 255)

    # 3. Feather so downscaling cannot reintroduce a halo.
    r, g, b, a = out.split()
    a = a.filter(ImageFilter.GaussianBlur(0.8))
    return Image.merge("RGBA", (r, g, b, a))


def split_layers(keyed):
    """Split the keyed image into (mark, wordmark) by colour, not by bbox."""
    w, h = keyed.size
    px = keyed.load()
    mark = Image.new("RGBA", (w, h), (0, 0, 0, 0))
    word = Image.new("RGBA", (w, h), (0, 0, 0, 0))
    mpx, wpx = mark.load(), word.load()
    for y in range(h):
        for x in range(w):
            r, g, b, a = px[x, y]
            if a == 0:
                continue
            sat = max(r, g, b) - min(r, g, b)
            luma = (r * 299 + g * 587 + b * 114) // 1000
            if sat > 60:
                mpx[x, y] = (r, g, b, a)
            elif luma < 140:
                # Black-on-white glyph -> proper alpha matte, preserving the
                # antialiasing rather than compositing one flat colour over it.
                wpx[x, y] = WORDMARK_INK + (min(a, 255 - luma),)
    return mark, word


def fit(img, box_w, box_h):
    """Downscale (never upscale) to fit a box, preserving aspect."""
    b = img.getbbox()
    if b:
        img = img.crop(b)
    scale = min(box_w / img.width, box_h / img.height, 1.0)
    size = (max(1, int(img.width * scale)), max(1, int(img.height * scale)))
    return img.resize(size, Image.LANCZOS)


def on_canvas(img, size, bg=None, coverage=1.0):
    canvas = Image.new("RGBA", size, (bg + (255,)) if bg else (0, 0, 0, 0))
    scaled = fit(img, int(size[0] * coverage), int(size[1] * coverage))
    canvas.alpha_composite(
        scaled, ((size[0] - scaled.width) // 2, (size[1] - scaled.height) // 2)
    )
    return canvas


def save(img, relpath, mode="RGBA"):
    path = os.path.join(RES, relpath)
    os.makedirs(os.path.dirname(path), exist_ok=True)
    (img.convert("RGB") if mode == "RGB" else img).save(path)
    print(f"  {relpath:52s} {img.width}x{img.height}")


def main():
    src_path = sys.argv[1] if len(sys.argv) > 1 else None
    if not src_path or not os.path.exists(src_path):
        sys.exit("usage: build_assets.py <logo.png>")

    src = Image.open(src_path)
    print(f"source: {src.size[0]}x{src.size[1]} {src.mode}")

    keyed = key_background(src)
    mark, word = split_layers(keyed)

    lockup = Image.alpha_composite(
        Image.new("RGBA", keyed.size, (0, 0, 0, 0)), mark
    )
    lockup.alpha_composite(word)

    print("outputs:")
    # TV banner: required to be opaque, so we pick our own dark ground and the
    # source's white background stops being a problem at all.
    save(on_canvas(lockup, (320, 180), bg=BANNER_BG, coverage=0.80),
         "drawable-xhdpi/banner.png", mode="RGB")

    # Header lockup, transparent, at 4x the 36dp render height for headroom.
    save(fit(lockup, 4000, 288), "drawable-nodpi/logo_lockup_dark.png")

    # Splash: mark only, 2/3 coverage to match the API 31 splash safe area.
    save(on_canvas(mark, (640, 640), coverage=0.66),
         "drawable-nodpi/splash_icon.png")

    # Adaptive icon foreground: mark at 66%, the safe zone.
    save(on_canvas(mark, (432, 432), coverage=0.66),
         "drawable-v26/ic_launcher_foreground.png")

    # Legacy launcher icons.
    for density, px in (("mdpi", 48), ("hdpi", 72), ("xhdpi", 96),
                        ("xxhdpi", 144), ("xxxhdpi", 192)):
        icon = on_canvas(mark, (px, px), bg=BG_HEX, coverage=0.72)
        save(icon, f"mipmap-{density}/ic_launcher.png", mode="RGB")
        save(icon, f"mipmap-{density}/ic_launcher_round.png", mode="RGB")

    for name in ("ic_launcher", "ic_launcher_round"):
        path = os.path.join(RES, "mipmap-anydpi-v26", f"{name}.xml")
        os.makedirs(os.path.dirname(path), exist_ok=True)
        with open(path, "w") as fh:
            fh.write(
                '<?xml version="1.0" encoding="utf-8"?>\n'
                '<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">\n'
                '    <background android:drawable="@color/brand_background" />\n'
                '    <foreground android:drawable="@drawable/ic_launcher_foreground" />\n'
                '</adaptive-icon>\n'
            )
        print(f"  mipmap-anydpi-v26/{name}.xml")


if __name__ == "__main__":
    main()
