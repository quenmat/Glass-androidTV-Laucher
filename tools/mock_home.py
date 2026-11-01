# Mock màn hình chính theo đúng bố cục trong MainActivity (1920x1080), dùng ảnh ô app thật.
# python tools/mock_home.py <ảnh nền> <ảnh nguồn thumbnail> <ra.png>
import os, sys
from PIL import Image, ImageDraw, ImageFilter, ImageFont

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.join(HERE, "..")
bg_path, thumb_src, out = sys.argv[1], sys.argv[2], sys.argv[3]
ORDER = ["youtube", "netflix", "fptplay", "tv360", "play", "hbo", "vieon", "settings"]
SEL = 1
CW, CHh, GAP, PX, PY, R, TRAY_TOP = 200, 125, 26, 30, 22, 15, 500
TG, ROW_TOP, N = 30, 760, 5
W, H, SS = 1920, 1080, 4


def font(size, w):
    f = ImageFont.truetype(os.path.join(ROOT, "assets", "fonts", "Inter.ttf"), size)
    try: f.set_variation_by_axes([28, w])
    except Exception: pass
    return f


def rmask(w, h, r):
    m = Image.new("L", (w * SS, h * SS), 0)
    ImageDraw.Draw(m).rounded_rectangle([0, 0, w * SS - 1, h * SS - 1], r * SS, fill=255)
    return m.resize((w, h), Image.LANCZOS)


def ring(w, h, r, width):
    m = Image.new("L", (w * SS, h * SS), 0)
    ImageDraw.Draw(m).rounded_rectangle([0, 0, w * SS - 1, h * SS - 1], r * SS, outline=255, width=width * SS)
    return m.resize((w, h), Image.LANCZOS)


def vgrad(w, h, a0, a1, power=1.0):
    g = Image.new("L", (1, h))
    for y in range(h): g.putpixel((0, y), int(a0 + (a1 - a0) * (y / max(1, h - 1)) ** power))
    return g.resize((w, h))


# nền + lớp phủ mỏng màu chủ đạo + tối dần dưới đáy
scr = Image.open(bg_path).convert("RGB").crop((820, 150, 1920, 769)).resize((W, H), Image.LANCZOS).convert("RGBA")
bg_clean = scr.copy()
avg = scr.resize((1, 1), Image.LANCZOS).getpixel((0, 0))
scr.alpha_composite(Image.new("RGBA", (W, H), (avg[0] // 3, avg[1] // 3, avg[2] // 3, 56)))
shade = Image.new("RGBA", (W, 620), (0, 0, 0, 0)); shade.putalpha(vgrad(W, 620, 0, 230, 1.2))
scr.alpha_composite(shade, (0, H - 620))
d = ImageDraw.Draw(scr)
cf = font(34, 600)
# đồng hồ thẳng mép phải khay dock
d.text(((W + (8 * 200 + 7 * 26 + 60)) // 2 - d.textlength("20:15", font=cf), 46), "20:15", font=cf, fill=(255, 255, 255, 230))

n = len(ORDER)
tw = n * CW + (n - 1) * GAP + PX * 2; th = CHh + PY * 2
tx = (W - tw) // 2
TR = int(R + PY * 0.9)

# kính mờ: ảnh nền nhoè mạnh (thu nhỏ 1/24 rồi phóng lại như FrostView) + lớp trắng 18% + viền mảnh 12%
small = bg_clean.resize((W // 24, H // 24), Image.LANCZOS)
blur = small.resize((W, H), Image.BILINEAR).filter(ImageFilter.GaussianBlur(6))
frost = blur.crop((tx, TRAY_TOP, tx + tw, TRAY_TOP + th))
frost.alpha_composite(Image.new("RGBA", (tw, th), (255, 255, 255, 46)))
scr.paste(frost, (tx, TRAY_TOP), rmask(tw, th, TR))

# quầng 2 lớp nằm trên kính mờ, dưới các ô
s = 1.16
bw, bh = int(CW * s), int(CHh * s)
cx = tx + PX + SEL * (CW + GAP) + CW // 2; cy = TRAY_TOP + PY + CHh // 2
# cùng tham số với Glow.make (spread = 145): lề > bán kính nhoè để ánh sáng tan về 0 trước mép
SP = 145
gw, gh = CW + 2 * SP, CHh + 2 * SP
io, ii = int(SP * 0.58), int(SP * 0.94)
outer = Image.new("RGBA", (gw, gh), (0, 0, 0, 0))
io = int(SP * 0.88)
ImageDraw.Draw(outer).rounded_rectangle([io, io, gw - io, gh - io], R + (SP - io), fill=(255, 40, 50, 210))
outer = outer.filter(ImageFilter.GaussianBlur(SP * 0.13 / 2))
inner = Image.new("RGBA", (gw, gh), (0, 0, 0, 0))
ImageDraw.Draw(inner).rounded_rectangle([ii, ii, gw - ii, gh - ii], 32, fill=(255, 40, 50, 215))
inner = inner.filter(ImageFilter.GaussianBlur(SP * 0.13 / 2))
scr.alpha_composite(outer, (cx - gw // 2, cy - gh // 2))
scr.alpha_composite(inner, (cx - gw // 2, cy - gh // 2))

edge = Image.new("RGBA", (tw, th), (255, 255, 255, 0)); edge.putalpha(ring(tw, th, TR, 1).point(lambda v: v * 31 // 255))
scr.alpha_composite(edge, (tx, TRAY_TOP))

cards = [Image.open(os.path.join(ROOT, "assets", "cards", f"{k}.png")).convert("RGBA") for k in ORDER]
for i, c in enumerate(cards):
    if i == SEL: continue
    scr.alpha_composite(c.resize((CW, CHh), Image.LANCZOS), (tx + PX + i * (CW + GAP), TRAY_TOP + PY))

# ô đang chọn: bóng mềm + phóng đều từ tâm + ánh mặt ô (không tên app)
shadow = Image.new("RGBA", (bw + 80, bh + 80), (0, 0, 0, 0))
ImageDraw.Draw(shadow).rounded_rectangle([40, 52, 40 + bw, 52 + bh], 18, fill=(0, 0, 0, 140))
scr.alpha_composite(shadow.filter(ImageFilter.GaussianBlur(16)), (cx - bw // 2 - 40, cy - bh // 2 - 40))
big = cards[SEL].resize((bw, bh), Image.LANCZOS)
sheen = Image.new("RGBA", (bw, bh), (255, 255, 255, 0))
sp = sheen.load()
for y in range(bh):
    for x in range(bw):
        t = 1 - min(1, (x / bw + y / bh) / 1.2)
        sp[x, y] = (255, 255, 255, int(64 * t * t))
big.alpha_composite(Image.composite(sheen, Image.new("RGBA", (bw, bh)), big.getchannel("A")))
scr.alpha_composite(big, (cx - bw // 2, cy - bh // 2))

# hàng nội dung
d = ImageDraw.Draw(scr)
rx = tx + PX; TW = (tw - 2 * PX - (N - 1) * TG) // N; TH = round(TW * 9 / 16)
# tiêu đề hàng kiểu Apple: chính trắng semibold, phụ xám nhạt nét thường, cách bằng " – "
hf, sf = font(30, 600), font(30, 400)
# hàng trộn: Netflix đứng đầu, lấp bằng YouTube -> tiêu đề "Xem tiếp", mỗi ô có logo app nhỏ ở góc
d.text((rx, ROW_TOP - 54), "Xem tiếp", font=hf, fill=(255, 255, 255, 255))
nf = Image.open(thumb_src).convert("RGBA")
yt = Image.open(sys.argv[4]).convert("RGBA") if len(sys.argv) > 4 else nf
row = [
    (nf, (57, 707, 553, 983), "Nạp đầy trái tim", 0.45, "netflix"),
    (nf, (560, 738, 892, 950), "Khi anh chạy về phía em", 0.7, "netflix"),
    (yt, (886, 740, 1266, 952), "Nhạc trữ tình", 0.3, "youtube"),
    (yt, (1270, 740, 1652, 952), "Gia đình cún mèo", 0.6, "youtube"),
    (yt, (1656, 740, 1920, 952), "Ngược lối yêu", 0.15, "youtube"),
]
BW, BH = 64, 40
tf = font(21, 600)
for i, (src, box, title, pr, app) in enumerate(row):
    t = src.crop(box).resize((TW, TH), Image.LANCZOS)
    titles, prog = [title] * N, [pr] * N
    sh = Image.new("RGBA", (TW, TH), (0, 0, 0, 0)); sh.putalpha(vgrad(TW, TH, 0, 220, 2.2))
    t.alpha_composite(sh)
    td = ImageDraw.Draw(t)
    td.text((14, TH - 16 - 30), titles[i], font=tf, fill=(255, 255, 255, 255))
    if prog[i]: td.rounded_rectangle([12, TH - 12, 12 + int((TW - 24) * prog[i]), TH - 8], 2, fill=(255, 255, 255, 242))
    badge = Image.open(os.path.join(ROOT, "assets", "cards", f"{app}.png")).convert("RGBA").resize((BW, BH), Image.LANCZOS)
    bsh = Image.new("RGBA", (BW + 20, BH + 20), (0, 0, 0, 0))
    ImageDraw.Draw(bsh).rounded_rectangle([10, 12, 10 + BW, 12 + BH], 5, fill=(0, 0, 0, 120))
    t.alpha_composite(bsh.filter(ImageFilter.GaussianBlur(4)), (0, 0))
    t.alpha_composite(badge, (10, 10))
    layer = Image.new("RGBA", (TW, TH), (0, 0, 0, 0)); layer.paste(t, (0, 0), rmask(TW, TH, 12))
    layer.putalpha(layer.getchannel("A").point(lambda v: int(v * 0.82)))
    scr.alpha_composite(layer, (rx + i * (TW + TG), ROW_TOP))

scr.convert("RGB").save(out)
print("ok")
