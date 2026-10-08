"""Voice Camera logo, drawn in the same 108x108 space as the Android adaptive-icon vector."""
from PIL import Image, ImageDraw

CREAM = (240, 236, 228, 255)
GOLD = (232, 197, 71, 255)
RED = (255, 59, 48, 255)
DARK = (10, 10, 15, 255)
LENS = (22, 22, 32, 255)


def _gradient_bg(size):
    img = Image.new("RGBA", (size, size))
    px = img.load()
    top, bottom = (22, 22, 36), (8, 8, 13)
    for y in range(size):
        t = y / (size - 1)
        c = tuple(int(top[i] + (bottom[i] - top[i]) * t) for i in range(3)) + (255,)
        for x in range(size):
            px[x, y] = c
    return img


def draw_camera(size, with_background=True, scale=1.0):
    """Return a size x size RGBA image. `scale` shrinks the artwork around the centre (for margins)."""
    ss = 4                                     # supersampling for smooth edges
    big = size * ss
    k = big / 108.0                            # 108-unit design space -> pixels
    base = _gradient_bg(big) if with_background else Image.new("RGBA", (big, big), (0, 0, 0, 0))
    art = Image.new("RGBA", (big, big), (0, 0, 0, 0))
    d = ImageDraw.Draw(art)

    def P(x, y):
        # scale about the centre of the design space
        return ((54 + (x - 54) * scale) * k, (54 + (y - 54) * scale) * k)

    def R(x1, y1, x2, y2):
        a, b = P(x1, y1)
        c, e = P(x2, y2)
        return [a, b, c, e]

    s = scale
    # --- camera body: rounded box with the little viewfinder bump on top
    d.rounded_rectangle(R(26, 40, 82, 76), radius=4 * s * k, fill=CREAM)
    d.polygon([P(42, 41), P(46, 34), P(62, 34), P(66, 41)], fill=CREAM)
    # --- lens: gold ring, dark glass
    d.ellipse(R(54 - 15, 58 - 15, 54 + 15, 58 + 15), fill=GOLD)
    d.ellipse(R(54 - 12.2, 58 - 12.2, 54 + 12.2, 58 + 12.2), fill=LENS)
    # --- microphone inside the lens
    d.rounded_rectangle(R(51.4, 50.8, 56.6, 59.4), radius=2.6 * s * k, fill=GOLD)
    w = max(1, int(1.4 * s * k))
    d.arc(R(54 - 5.4, 57.4 - 5.4, 54 + 5.4, 57.4 + 5.4), 0, 180, fill=GOLD, width=w)
    d.line([P(54, 62.8), P(54, 65.2)], fill=GOLD, width=w)
    d.line([P(51.4, 65.2), P(56.6, 65.2)], fill=GOLD, width=w)
    # --- red "recording" dot on the body
    d.ellipse(R(30.2, 43.2, 36.2, 49.2), fill=RED)

    out = Image.alpha_composite(base, art)
    return out.resize((size, size), Image.LANCZOS)


if __name__ == "__main__":
    import sys
    draw_camera(512).convert("RGB").save(sys.argv[1] if len(sys.argv) > 1 else "icon_test.png")
