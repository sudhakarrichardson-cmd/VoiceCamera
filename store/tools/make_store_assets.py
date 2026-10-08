import os
from PIL import Image, ImageDraw, ImageFont, ImageFilter
from icon_art import draw_camera

ROOT = r"C:\Personnel\2026\Apps\VoiceCamera\store"
RAW = os.path.join(ROOT, "raw_flip5")
PHONE_DIR = os.path.join(ROOT, "play_phone_screenshots")
ICON_DIR = os.path.join(ROOT, "icon")
for d in (PHONE_DIR, ICON_DIR):
    os.makedirs(d, exist_ok=True)

FONT_B = r"C:\Windows\Fonts\segoeuib.ttf"
FONT_R = r"C:\Windows\Fonts\segoeui.ttf"
CREAM = (240, 236, 228)
GOLD = (232, 197, 71)
DIM = (170, 166, 180)


def font(path, size):
    return ImageFont.truetype(path, size)


def gradient(w, h, top=(22, 22, 36), bottom=(8, 8, 13)):
    img = Image.new("RGB", (w, h))
    px = img.load()
    for y in range(h):
        t = y / (h - 1)
        c = tuple(int(top[i] + (bottom[i] - top[i]) * t) for i in range(3))
        for x in range(w):
            px[x, y] = c
    return img


def rounded(img, radius):
    mask = Image.new("L", img.size, 0)
    ImageDraw.Draw(mask).rounded_rectangle([0, 0, img.size[0] - 1, img.size[1] - 1], radius=radius, fill=255)
    out = img.convert("RGBA")
    out.putalpha(mask)
    return out


def app_content(raw_name):
    """The app area only: drops the status bar (clock, notification icons) and the navigation bar."""
    im = Image.open(os.path.join(RAW, raw_name)).convert("RGB")
    w, h = im.size
    return im.crop((0, 95, w, 2495))


def phone(content, height, border=14, radius=64):
    """The screenshot inside a simple dark phone frame."""
    w = int(content.size[0] * height / content.size[1])
    shot = content.resize((w, height), Image.LANCZOS)
    shot = rounded(shot, radius - border // 2)
    frame = Image.new("RGBA", (w + 2 * border, height + 2 * border), (0, 0, 0, 0))
    d = ImageDraw.Draw(frame)
    d.rounded_rectangle([0, 0, frame.size[0] - 1, frame.size[1] - 1], radius=radius, fill=(42, 42, 54, 255))
    frame.alpha_composite(shot, (border, border))
    return frame


def glow(canvas, cx, cy, radius, color=(232, 197, 71), strength=70):
    layer = Image.new("RGBA", canvas.size, (0, 0, 0, 0))
    ImageDraw.Draw(layer).ellipse([cx - radius, cy - radius, cx + radius, cy + radius], fill=color + (strength,))
    layer = layer.filter(ImageFilter.GaussianBlur(radius // 2))
    canvas.alpha_composite(layer)


def centered_text(d, text, y, fnt, fill, width):
    w = d.textlength(text, font=fnt)
    d.text(((width - w) / 2, y), text, font=fnt, fill=fill)


# ------------------------------------------------------------------ icon
draw_camera(512, with_background=True, scale=1.3).convert("RGBA").save(os.path.join(ICON_DIR, "play_icon_512.png"), optimize=True)
draw_camera(1024, with_background=False, scale=1.0).save(os.path.join(ICON_DIR, "logo_transparent_1024.png"), optimize=True)

# ------------------------------------------------------------------ phone screenshots 1080 x 1920
SHOTS = [
    ("01_say_it_it_records.png", "01_camera_ready.png", "Say it. It records.", "Hands-free video and photos"),
    ("02_countdown.png", "05_countdown.png", "A countdown, then go", "Time to step back and get ready"),
    ("03_stops_by_itself.png", "06_recording.png", "Stops on its own", "Record exactly as long as you choose"),
    ("04_photo_or_video.png", "03_photo_mode.png", "Photo or video", "Switch modes in one tap"),
    ("05_zoom.png", "02_zoom.png", "Zoom 0.5x to 10x", "Tap a step or pinch the picture"),
    ("06_keep_your_best.png", "07_review.png", "Keep your best take", "Review, mark favorites, delete the rest"),
    ("07_your_defaults.png", "04_settings.png", "Your own defaults", "Set the usual wait and recording length"),
]
W, H = 1080, 1920
for out_name, raw_name, title, subtitle in SHOTS:
    canvas = gradient(W, H).convert("RGBA")
    glow(canvas, W // 2, 1150, 520)
    d = ImageDraw.Draw(canvas)
    centered_text(d, title, 70, font(FONT_B, 84), CREAM, W)
    centered_text(d, subtitle, 188, font(FONT_R, 44), GOLD, W)
    p = phone(app_content(raw_name), height=1500)
    canvas.alpha_composite(p, ((W - p.size[0]) // 2, 330))
    canvas.convert("RGB").save(os.path.join(PHONE_DIR, out_name), optimize=True)
    print("saved", out_name)

# ------------------------------------------------------------------ feature graphic 1024 x 500
FW, FH = 1024, 500
fg = gradient(FW, FH, (24, 24, 40), (8, 8, 13)).convert("RGBA")
glow(fg, 760, 250, 260, strength=60)
d = ImageDraw.Draw(fg)
icon = rounded(draw_camera(150, with_background=True, scale=1.3), 34)
fg.alpha_composite(icon, (60, 120))
d.text((60, 292), "Voice Camera", font=font(FONT_B, 70), fill=CREAM)
d.text((62, 372), "Say it. It records.", font=font(FONT_B, 38), fill=GOLD)
d.text((62, 424), "Hands-free video and photos", font=font(FONT_R, 26), fill=DIM)
back = phone(app_content("01_camera_ready.png"), height=400, border=8, radius=38)
front = phone(app_content("06_recording.png"), height=440, border=8, radius=42)
fg.alpha_composite(back, (640, 70))
fg.alpha_composite(front, (780, 30))
fg.convert("RGB").save(os.path.join(ROOT, "feature_graphic_1024x500.png"), optimize=True)
print("saved feature_graphic_1024x500.png")
