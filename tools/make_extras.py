# Ô "Cài đặt" (bánh răng) cho dock và banner của chính launcher.
# Chạy: python tools/make_extras.py
import os, math
from PIL import Image, ImageDraw, ImageFont

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.join(HERE, "..")
CW, CH, S = 464, 290, 4


def rounded(w, h, r, color):
    m = Image.new("L", (w * S, h * S), 0)
    ImageDraw.Draw(m).rounded_rectangle([0, 0, w * S - 1, h * S - 1], int(r * S), fill=255)
    m = m.resize((w, h), Image.LANCZOS)
    im = Image.new("RGBA", (w, h), (0, 0, 0, 0))
    im.paste(Image.new("RGBA", (w, h), color), (0, 0), m)
    return im


def gear(size, color):
    g = Image.new("L", (size * S, size * S), 0)
    d = ImageDraw.Draw(g)
    c, ro, ri, teeth = size * S / 2, size * S * 0.46, size * S * 0.34, 8
    pts = []
    for k in range(teeth * 4):
        a = 2 * math.pi * k / (teeth * 4)
        r = ro if (k % 4) in (1, 2) else ri
        pts.append((c + r * math.cos(a), c + r * math.sin(a)))
    d.polygon(pts, fill=255)
    hole = size * S * 0.15
    d.ellipse([c - hole, c - hole, c + hole, c + hole], fill=0)
    g = g.resize((size, size), Image.LANCZOS)
    im = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    im.paste(Image.new("RGBA", (size, size), color), (0, 0), g)
    return im


if __name__ == "__main__":
    card = rounded(CW, CH, CH * 0.12, (142, 142, 147, 255))
    gr = gear(int(CH * 0.56), (255, 255, 255, 255))
    card.alpha_composite(gr, ((CW - gr.width) // 2, (CH - gr.height) // 2))
    card.save(os.path.join(ROOT, "assets", "cards", "settings.png"))

    font = ImageFont.truetype(os.path.join(ROOT, "assets", "fonts", "Inter.ttf"), 44)
    try:
        font.set_variation_by_axes([28, 600])
    except Exception:
        pass
    b = rounded(320, 180, 0, (14, 14, 18, 255))
    glass = rounded(260, 76, 24, (255, 255, 255, 46))
    b.alpha_composite(glass, (30, 52))
    d = ImageDraw.Draw(b)
    t = "Glass Home"
    w = d.textlength(t, font=font)
    d.text(((320 - w) / 2, 64), t, font=font, fill=(255, 255, 255, 235))
    b.save(os.path.join(ROOT, "res", "drawable-nodpi", "banner.png"))
    print("ok")
