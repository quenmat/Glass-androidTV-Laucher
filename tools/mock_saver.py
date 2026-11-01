# Mock màn hình chờ (screensaver) của Glass Home, 1920x1080, đúng font Inter của launcher.
# python tools/mock_saver.py <ảnh nền> <ra.png> [A|B]
#   A: cụm chữ góc dưới trái (kiểu Apple TV)   B: cụm chữ giữa màn hình
import os, sys, datetime
from PIL import Image, ImageDraw, ImageFilter, ImageFont

HERE = os.path.dirname(os.path.abspath(__file__))
FONT = os.path.join(HERE, "..", "assets", "fonts", "Inter.ttf")
W, H, S = 1920, 1080, 3          # vẽ chữ phóng 3 lần rồi thu nhỏ cho mịn
src, out = sys.argv[1], sys.argv[2]
variant = sys.argv[3] if len(sys.argv) > 3 else "A"
time_s, solar_s, lunar_s = sys.argv[4], sys.argv[5], sys.argv[6]


def font(size, weight):
    f = ImageFont.truetype(FONT, size * S)
    try: f.set_variation_by_axes([min(32, max(14, size)), weight])
    except Exception: pass
    return f


# nền: ảnh đang hiện, phóng nhẹ (đang trôi chậm), tối thêm ~45% + loang tối về đáy và hai bên
bg = Image.open(src).convert("RGB")
bg = bg.resize((int(W * 1.06), int(H * 1.06)), Image.LANCZOS).crop((40, 20, 40 + W, 20 + H)).convert("RGBA")
bg.alpha_composite(Image.new("RGBA", (W, H), (0, 0, 0, 115)))
grad = Image.new("L", (W, H))
gp = grad.load()
for y in range(H):
    for x in range(0, W, 4):
        fx = min(x, W - 1 - x) / (W * 0.25)
        side = max(0.0, 1 - fx) ** 2 * 90
        bottom = max(0.0, (y - H * 0.45) / (H * 0.55)) ** 1.6 * (150 if variant == "A" else 90)
        v = int(min(255, side + bottom))
        for dx in range(4):
            if x + dx < W: gp[x + dx, y] = v
bg.alpha_composite(Image.merge("RGBA", (Image.new("L", (W, H)), Image.new("L", (W, H)), Image.new("L", (W, H)), grad)))

# chữ: vẽ trên lớp phóng S lần
layer = Image.new("RGBA", (W * S, H * S), (0, 0, 0, 0))
d = ImageDraw.Draw(layer)
f_time, f_solar, f_lunar = font(190, 250), font(40, 500), font(32, 400)


def put(x, y, s, f, rgba, anchor="ls"):
    d.text((x * S, y * S), s, font=f, fill=rgba, anchor=anchor)


if variant == "A":
    x0, base = 120, 860
    put(x0 - 8, base, time_s, f_time, (255, 255, 255, 240))
    put(x0, base + 70, solar_s, f_solar, (255, 255, 255, 225))
    put(x0, base + 120, lunar_s, f_lunar, (255, 255, 255, 150))
else:
    # B (đã chọn): giữa màn hình, trong 1/3 phía trên; quầng tối mềm sau chữ; dòng đếm ngược Tết ánh vàng ấm
    tet_s = sys.argv[7] if len(sys.argv) > 7 else ""
    halo = Image.new("L", (1300, 560))
    hp = halo.load()
    for y in range(560):
        for x in range(0, 1300, 2):
            dx, dy = (x - 650) / 650, (y - 280) / 280
            t = max(0.0, 1 - (dx * dx + dy * dy) ** 0.5)
            v = int(0x50 * t * t * (3 - 2 * t))
            hp[x, y] = v
            if x + 1 < 1300: hp[x + 1, y] = v
    # bản chốt: cụm giờ ở góc dưới trái (không che nhân vật), đáy tối dần, quầng sau cụm giờ
    floor = Image.new("L", (W, 480))
    for y in range(480):
        t = y / 479
        v = int(0x40 * (t / 0.5)) if t < 0.5 else int(0x40 + (0x8C - 0x40) * ((t - 0.5) / 0.5))
        floor.paste(v, (0, y, W, y + 1))
    bg.alpha_composite(Image.merge("RGBA", (Image.new("L", floor.size),) * 3 + (floor,)), (0, H - 480))
    halo = halo.resize((1200, 620))
    bg.alpha_composite(Image.merge("RGBA", (Image.new("L", halo.size),) * 3 + (halo,)), (-250, H + 150 - 620))
    x0 = 110
    f_time, f_tet = font(170, 250), font(28, 500)
    put(x0 - 8, H - 72 - 50 - 62, time_s, f_time, (255, 255, 255, 240))
    put(x0, H - 72 - 50, solar_s, f_solar, (255, 255, 255, 224))
    put(x0, H - 72, lunar_s, f_lunar, (255, 255, 255, 158))
    # đếm ngược Tết tách riêng ở góc dưới phải
    put(W - 96, H - 72, tet_s, f_tet, (255, 226, 176, 184), "rs")

layer = layer.resize((W, H), Image.LANCZOS)
# bóng chữ rất nhạt để đọc được cả trên vùng ảnh sáng
shadow = layer.split()[3].filter(ImageFilter.GaussianBlur(10)).point(lambda v: int(v * 0.35))
bg.alpha_composite(Image.merge("RGBA", (Image.new("L", (W, H)),) * 3 + (shadow,)))
bg.alpha_composite(layer)
bg.convert("RGB").save(out, quality=92)
print("ok", variant)
