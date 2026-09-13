"""Generates the 1024x500 Play Store feature graphic from the real app icon,
wordmark, and an actual in-app screenshot.
Run with: python scripts/generate_feature_graphic.py
"""
import os
from PIL import Image, ImageDraw, ImageFilter, ImageFont

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
LOGO_PATH = os.path.join(ROOT, "assets", "logo.png")
SCREENSHOT_PATH = os.path.join(ROOT, "store_assets", "screenshots", "03_page_review.png")
OUT_PATH = os.path.join(ROOT, "store_assets", "feature_graphic.png")

W, H = 1024, 500
NAVY_TOP = (30, 58, 95)
NAVY_BOTTOM = (14, 26, 43)
RESCUE_ORANGE = (255, 122, 41)

FONT_CANDIDATES_BOLD = [r"C:\Windows\Fonts\segoeuib.ttf", r"C:\Windows\Fonts\arialbd.ttf"]
FONT_CANDIDATES_REGULAR = [r"C:\Windows\Fonts\segoeui.ttf", r"C:\Windows\Fonts\arial.ttf"]


def load_font(candidates, size):
    for path in candidates:
        if os.path.exists(path):
            return ImageFont.truetype(path, size)
    return ImageFont.load_default()


def vertical_gradient(size, top_color, bottom_color):
    w, h = size
    gradient = Image.new("RGB", (1, h))
    for y in range(h):
        t = y / max(1, h - 1)
        color = tuple(int(top_color[i] + (bottom_color[i] - top_color[i]) * t) for i in range(3))
        gradient.putpixel((0, y), color)
    return gradient.resize((w, h))


def rounded_mask(size, radius):
    mask = Image.new("L", size, 0)
    draw = ImageDraw.Draw(mask)
    draw.rounded_rectangle((0, 0, size[0] - 1, size[1] - 1), radius=radius, fill=255)
    return mask


def main():
    canvas = vertical_gradient((W, H), NAVY_TOP, NAVY_BOTTOM).convert("RGB")

    # Soft rescue-orange glow in the lower-left, behind the icon, for a touch of brand accent.
    glow = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    glow_draw = ImageDraw.Draw(glow)
    glow_draw.ellipse((-160, 320, 260, 660), fill=(*RESCUE_ORANGE, 55))
    glow = glow.filter(ImageFilter.GaussianBlur(90))
    canvas.paste(glow, (0, 0), glow)

    draw = ImageDraw.Draw(canvas)

    # App icon.
    icon_size = 220
    if os.path.exists(LOGO_PATH):
        icon = Image.open(LOGO_PATH).convert("RGBA").resize((icon_size, icon_size), Image.LANCZOS)
        icon.putalpha(rounded_mask((icon_size, icon_size), int(icon_size * 0.22)))
        icon_pos = (56, (H - icon_size) // 2 - 30)
        canvas.paste(icon, icon_pos, icon)

    # Wordmark + tagline.
    title_font = load_font(FONT_CANDIDATES_BOLD, 74)
    tagline_font = load_font(FONT_CANDIDATES_REGULAR, 30)
    text_x = 56
    text_y = (H // 2) + 90
    draw.text((text_x, text_y), "PaperRescue", font=title_font, fill=(255, 255, 255))
    draw.text((text_x, text_y + 82), "Scan it. Rescue it. PDF it.", font=tagline_font, fill=(210, 224, 240))

    # Phone-framed real screenshot on the right, slightly rotated for dynamism.
    if os.path.exists(SCREENSHOT_PATH):
        shot = Image.open(SCREENSHOT_PATH).convert("RGB")
        frame_h = 460
        frame_w = int(frame_h * shot.width / shot.height)
        shot = shot.resize((frame_w, frame_h), Image.LANCZOS)

        bezel = 14
        framed = Image.new("RGBA", (frame_w + bezel * 2, frame_h + bezel * 2), (0, 0, 0, 0))
        fdraw = ImageDraw.Draw(framed)
        fdraw.rounded_rectangle(
            (0, 0, framed.width - 1, framed.height - 1), radius=34, fill=(15, 18, 24, 255)
        )
        shot_mask = rounded_mask((frame_w, frame_h), 22)
        framed.paste(shot, (bezel, bezel), Image.merge("RGBA", (*shot.split(), shot_mask)))

        rotated = framed.rotate(-6, expand=True, resample=Image.BICUBIC)

        # Soft drop shadow.
        shadow = Image.new("RGBA", canvas.size, (0, 0, 0, 0))
        shadow_shape = Image.new("RGBA", rotated.size, (0, 0, 0, 140))
        shadow_shape.putalpha(rotated.split()[3])
        shadow_pos = (W - rotated.width - 24, (H - rotated.height) // 2 + 14)
        shadow.paste(shadow_shape, shadow_pos, shadow_shape)
        shadow = shadow.filter(ImageFilter.GaussianBlur(16))
        canvas.paste(shadow, (0, 0), shadow)

        phone_pos = (W - rotated.width - 40, (H - rotated.height) // 2)
        canvas.paste(rotated, phone_pos, rotated)

    # Google Play requires the feature graphic to be a 24-bit PNG/JPEG with NO
    # alpha channel — `canvas` has stayed in RGB mode throughout, so this is
    # already compliant; convert explicitly so a future edit can't regress it.
    canvas.convert("RGB").save(OUT_PATH)
    print(f"Wrote {OUT_PATH} ({W}x{H}, {canvas.mode} -> RGB, no alpha)")


if __name__ == "__main__":
    main()
