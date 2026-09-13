"""Generates the app icon (legacy + adaptive) for all Android densities from assets/logo.png.
Run once with: python scripts/generate_icons.py
"""
import os
from PIL import Image, ImageDraw

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SOURCE = os.path.join(ROOT, "assets", "logo.png")
RES_DIR = os.path.join(ROOT, "android", "app", "src", "main", "res")

# (density, legacy launcher px, adaptive foreground/background canvas px)
DENSITIES = [
    ("mdpi", 48, 108),
    ("hdpi", 72, 162),
    ("xhdpi", 96, 216),
    ("xxhdpi", 144, 324),
    ("xxxhdpi", 192, 432),
]

BACKGROUND_COLOR = (20, 100, 200, 255)  # sampled mid-tone blue from the logo gradient


def circular_mask(size):
    mask = Image.new("L", (size, size), 0)
    draw = ImageDraw.Draw(mask)
    draw.ellipse((0, 0, size - 1, size - 1), fill=255)
    return mask


def make_legacy(logo: Image.Image, px: int) -> Image.Image:
    return logo.resize((px, px), Image.LANCZOS).convert("RGBA")


def make_round(square: Image.Image) -> Image.Image:
    size = square.size[0]
    out = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    out.paste(square, (0, 0))
    out.putalpha(circular_mask(size))
    return out


def make_adaptive_foreground(logo: Image.Image, canvas_px: int) -> Image.Image:
    # Adaptive icons render inside a 108dp canvas but only the inner ~66dp is
    # guaranteed visible after masking, so scale the artwork down and center it.
    canvas = Image.new("RGBA", (canvas_px, canvas_px), (0, 0, 0, 0))
    inner = int(canvas_px * 0.62)
    scaled = logo.resize((inner, inner), Image.LANCZOS)
    offset = (canvas_px - inner) // 2
    canvas.paste(scaled, (offset, offset), scaled)
    return canvas


def make_adaptive_background(canvas_px: int) -> Image.Image:
    return Image.new("RGBA", (canvas_px, canvas_px), BACKGROUND_COLOR)


def main():
    logo = Image.open(SOURCE).convert("RGBA")
    print(f"Loaded {SOURCE} ({logo.size[0]}x{logo.size[1]})")

    for density, legacy_px, adaptive_px in DENSITIES:
        mipmap_dir = os.path.join(RES_DIR, f"mipmap-{density}")
        os.makedirs(mipmap_dir, exist_ok=True)

        square = make_legacy(logo, legacy_px)
        square.save(os.path.join(mipmap_dir, "ic_launcher.png"))

        round_icon = make_round(square)
        round_icon.save(os.path.join(mipmap_dir, "ic_launcher_round.png"))

        fg = make_adaptive_foreground(logo, adaptive_px)
        fg.save(os.path.join(mipmap_dir, "ic_launcher_foreground.png"))

        bg = make_adaptive_background(adaptive_px)
        bg.save(os.path.join(mipmap_dir, "ic_launcher_background.png"))

        print(f"  {density}: legacy {legacy_px}px, adaptive {adaptive_px}px")

    # Play Store listing icon: Google requires exactly 512x512, 32-bit PNG
    # (i.e. an RGBA file — even though our design is fully opaque, the PNG
    # must carry an alpha channel or Play Console's asset validator rejects it).
    store_dir = os.path.join(ROOT, "store_assets")
    os.makedirs(store_dir, exist_ok=True)
    listing_icon = logo.resize((512, 512), Image.LANCZOS).convert("RGBA")
    listing_icon.save(os.path.join(store_dir, "play_store_icon_512.png"))
    print("Wrote store_assets/play_store_icon_512.png (512x512, 32-bit RGBA)")

    # Adaptive icon XML (mipmap-anydpi-v26) referencing the per-density foreground/background PNGs.
    anydpi_dir = os.path.join(RES_DIR, "mipmap-anydpi-v26")
    os.makedirs(anydpi_dir, exist_ok=True)
    xml = (
        '<?xml version="1.0" encoding="utf-8"?>\n'
        '<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">\n'
        '    <background android:drawable="@mipmap/ic_launcher_background" />\n'
        '    <foreground android:drawable="@mipmap/ic_launcher_foreground" />\n'
        "</adaptive-icon>\n"
    )
    for name in ("ic_launcher.xml", "ic_launcher_round.xml"):
        with open(os.path.join(anydpi_dir, name), "w", encoding="utf-8") as f:
            f.write(xml)
    print("Wrote mipmap-anydpi-v26 adaptive icon XML")


if __name__ == "__main__":
    main()
