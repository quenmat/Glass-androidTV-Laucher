# Ô "Truyền hình" cho dock (assets/cards/tv.png) và ảnh từng kênh (16:9) cho hàng kênh + bảng kênh.
# Chạy: python tools/make_tv.py <kenh.m3u> <thư mục ra ảnh kênh> [thư mục logo thật <tên-không-dấu>.png]
# Ảnh kênh đẩy lên box cùng kenh.m3u vào Android/data/vn.glass.home/files/tv/.
import os, re, sys
from PIL import Image, ImageDraw, ImageFont

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.join(HERE, "..")
FONT = os.path.join(ROOT, "assets", "fonts", "Inter.ttf")
CW, CH, S = 464, 290, 4


def font(size, weight=700):
    f = ImageFont.truetype(FONT, size)
    try: f.set_variation_by_axes([32 if size >= 32 else 14, weight])
    except Exception: pass
    return f


def rounded_mask(w, h, r):
    m = Image.new("L", (w, h), 0)
    ImageDraw.Draw(m).rounded_rectangle([0, 0, w - 1, h - 1], r, fill=255)
    return m


def tv_icon(size):
    """TV cổ màn cầu vồng (theo ảnh mẫu), vẽ phẳng; size = bề rộng, vẽ to rồi thu cho mịn."""
    W = size * S; H = int(W * 0.86)
    im = Image.new("RGBA", (W, H), (0, 0, 0, 0)); d = ImageDraw.Draw(im)
    u = W / 100
    ant = (214, 200, 194, 255)
    d.line([(50 * u, 16 * u), (31 * u, 3 * u)], fill=ant, width=int(4.2 * u))
    d.line([(50 * u, 16 * u), (69 * u, 3 * u)], fill=ant, width=int(4.2 * u))
    for x in (31, 69):
        d.ellipse([(x - 2.1) * u, 0.9 * u, (x + 2.1) * u, 5.1 * u], fill=ant)
    d.rounded_rectangle([44 * u, 11 * u, 56 * u, 21 * u], int(3 * u), fill=(140, 110, 99, 255))
    d.rounded_rectangle([12 * u, 72 * u, 22 * u, 84 * u], int(2.5 * u), fill=(140, 110, 99, 255))
    d.rounded_rectangle([0, 17 * u, 100 * u, 75 * u], int(9 * u), fill=(108, 78, 66, 255))
    sx0, sy0, sx1, sy1 = 9 * u, 24 * u, 79 * u, 68 * u
    bars = [(229, 115, 115), (255, 213, 79), (255, 238, 117), (129, 199, 132), (128, 216, 255), (179, 157, 219), (186, 104, 200)]
    scr = Image.new("RGBA", (int(sx1 - sx0), int(sy1 - sy0)))
    sd = ImageDraw.Draw(scr); bw = scr.width / len(bars)
    for k, c in enumerate(bars):
        sd.rectangle([k * bw, 0, (k + 1) * bw + 1, scr.height], fill=c + (255,))
    im.paste(scr, (int(sx0), int(sy0)), rounded_mask(scr.width, scr.height, int(6 * u)))
    knob = (214, 200, 194, 255)
    for y in (27, 33, 39):
        d.rounded_rectangle([84 * u, y * u, 95 * u, (y + 3.6) * u], int(1.8 * u), fill=knob)
    d.ellipse([84.5 * u, 47 * u, 94.5 * u, 57 * u], fill=knob)
    return im.resize((size, int(size * 0.86)), Image.LANCZOS)


def dock_card(out):
    # nền tối như ô Apps/Cài đặt, TV cổ bên trái + chữ "Truyền hình"
    card = Image.new("RGBA", (CW * S, CH * S), (0, 0, 0, 0))
    ImageDraw.Draw(card).rounded_rectangle([0, 0, CW * S - 1, CH * S - 1], int(CH * 0.12 * S), fill=(24, 22, 22, 255))
    card = card.resize((CW, CH), Image.LANCZOS)
    ic = tv_icon(150)
    f = font(46, 700)
    label = "Truyền hình"
    tw = ImageDraw.Draw(card).textlength(label, font=f)
    gap = 26
    x0 = int((CW - (ic.width + gap + tw)) / 2)
    card.alpha_composite(ic, (x0, (CH - ic.height) // 2 - 4))
    ImageDraw.Draw(card).text((x0 + ic.width + gap, CH / 2 - 2), label, font=f, fill=(255, 255, 255, 255), anchor="lm")
    card.save(out)




def slug(name):
    s = name.lower()
    for a, b in (("đ", "d"),):
        s = s.replace(a, b)
    import unicodedata
    s = unicodedata.normalize("NFD", s)
    s = "".join(c for c in s if unicodedata.category(c) != "Mn")
    return re.sub(r"[^a-z0-9]+", "-", s).strip("-")


def channel_art(name, group, out):
    """Kênh chưa có logo thật: tên kênh chữ đậm màu than trên đúng nền xám sáng của ô có logo, dòng nhóm nhỏ dưới."""
    W, H = 640, 360
    top, bot = (214, 214, 218), (190, 190, 197)
    im = Image.new("RGB", (W, H))
    d = ImageDraw.Draw(im)
    for y in range(H):
        t = y / H
        d.line([(0, y), (W, y)], fill=tuple(int(top[i] + (bot[i] - top[i]) * t) for i in range(3)))
    size = 96
    while size > 36:
        f = font(size, 800)
        if d.textlength(name, font=f) <= W - 90: break
        size -= 4
    d.text((W / 2, H / 2 - 12), name, font=f, fill=(38, 38, 46), anchor="mm")
    d.text((W / 2, H - 50), group.upper(), font=font(24, 600), fill=(90, 90, 100), anchor="mm")
    im.save(out)


# logo có phần trắng (vẽ để đặt trên nền tối): dùng nền tối thay vì nền sáng
DARK_BG = {"htv-the-thao"}
# logo chung của đài (không riêng kênh): ghi thêm tên kênh lớn dưới logo
STATION_LABEL = {"h1-ha-noi": "H1", "h2-ha-noi": "H2"}


def logo_art(logo_path, key, out):
    """Ảnh kênh 16:9 từ logo thật: nền xám sáng dịu, logo cắt sát rồi đặt giữa."""
    W, H = 640, 360
    dark = key in DARK_BG
    # nền sáng dịu (xám ngà ~80%), không trắng gắt: đặt cạnh nền tối của launcher đỡ chói mà logo đen vẫn rõ
    top, bot = ((44, 44, 52), (22, 22, 28)) if dark else ((214, 214, 218), (190, 190, 197))
    im = Image.new("RGB", (W, H))
    d = ImageDraw.Draw(im)
    for y in range(H):
        t = y / H
        d.line([(0, y), (W, y)], fill=tuple(int(top[i] + (bot[i] - top[i]) * t) for i in range(3)))
    lg = Image.open(logo_path).convert("RGBA")
    box = lg.getchannel("A").point(lambda a: 255 if a > 16 else 0).getbbox()
    if box: lg = lg.crop(box)
    label = STATION_LABEL.get(key)
    maxw, maxh = (W * 0.72, H * (0.34 if label else 0.56))
    k = min(maxw / lg.width, maxh / lg.height)
    lg = lg.resize((max(1, int(lg.width * k)), max(1, int(lg.height * k))), Image.LANCZOS)
    cy = H * (0.36 if label else 0.5)
    im.paste(lg, (int((W - lg.width) / 2), int(cy - lg.height / 2)), lg)
    if label:
        ImageDraw.Draw(im).text((W / 2, H * 0.74), label, font=font(110, 800), fill=(200, 30, 45), anchor="mm")
    im.save(out)


if __name__ == "__main__":
    dock_card(os.path.join(ROOT, "assets", "cards", "tv.png"))
    logos = sys.argv[3] if len(sys.argv) > 3 else None   # thư mục logo thật <tên-không-dấu>.png (tuỳ chọn)
    if len(sys.argv) > 2:
        os.makedirs(sys.argv[2], exist_ok=True)
        name = group = None
        for line in open(sys.argv[1], encoding="utf-8"):
            m = re.match(r'#EXTINF[^,]*group-title="([^"]*)"[^,]*,(.*)', line.strip())
            if m:
                group, name = m.group(1), m.group(2).strip()
                key = slug(name)
                lp = os.path.join(logos, key + ".png") if logos else None
                if lp and os.path.isfile(lp): logo_art(lp, key, os.path.join(sys.argv[2], key + ".png"))
                else: channel_art(name, group, os.path.join(sys.argv[2], key + ".png"))
    print("ok")
