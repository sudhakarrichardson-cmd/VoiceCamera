import os
from PIL import Image, ImageDraw, ImageFont, ImageFilter

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


# The Play icon and feature graphic are supplied artwork (see store/source/), not generated here.

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
