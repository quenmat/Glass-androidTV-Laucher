# Mock bảng Cài đặt của Glass Home trên ảnh chụp màn hình chính thật, 1920x1080, font Inter.
# python tools/mock_settings.py <ảnh home chụp> <thư mục ra> [thư mục ảnh nhỏ t1..t6.jpg]
#   ra: settings_main.png (bảng chính), settings_bg.png (Ảnh nền Home), settings_qr.png (gửi ảnh từ điện thoại)
import os, random, sys
from PIL import Image, ImageDraw, ImageFilter, ImageFont

HERE = os.path.dirname(os.path.abspath(__file__))
FONT = os.path.join(HERE, "..", "assets", "fonts", "Inter.ttf")
W, H, S = 1920, 1080, 2          # vẽ phóng 2 lần rồi thu nhỏ cho mịn
src, outdir = sys.argv[1], sys.argv[2]
thumb_dir = sys.argv[3] if len(sys.argv) > 3 else None

PX, PY, PW, PH, PR = 40, 48, 648, 984, 32   # phủ trọn chỗ thẻ lịch bóng đá (x=40)     # tấm kính trượt ra từ bên trái
INK = (242, 238, 230)


def font(size, weight):
    f = ImageFont.truetype(FONT, size * S)
    try: f.set_variation_by_axes([min(32, max(14, size)), weight])
    except Exception: pass
    return f


F_TITLE, F_ROW, F_SUB, F_SMALL, F_KEY = font(40, 600), font(28, 500), font(21, 400), font(19, 500), font(24, 600)


def base(dim_rest=True):
    """Ảnh home thật, tối bớt phần ngoài bảng; tấm kính: vùng nền làm nhoè + lớp đen như kính deep() của launcher."""
    bg = Image.open(src).convert("RGBA")
    if dim_rest: bg.alpha_composite(Image.new("RGBA", (W, H), (0, 0, 0, 120)))
    return bg


def glass(img, box, r, alpha=0xB8):
    x0, y0, x1, y1 = box
    blur = img.crop(box).filter(ImageFilter.GaussianBlur(40))
    blur.alpha_composite(Image.new("RGBA", blur.size, (12, 12, 16, alpha)))
    mask = Image.new("L", ((x1 - x0) * S, (y1 - y0) * S))
    ImageDraw.Draw(mask).rounded_rectangle((0, 0, mask.width - 1, mask.height - 1), r * S, fill=255)
    img.paste(blur, (x0, y0), mask.resize(blur.size, Image.LANCZOS))


class Ink:
    """Lớp chữ + khối phóng S lần, dán chồng lên ảnh."""
    def __init__(self):
        self.l = Image.new("RGBA", (W * S, H * S), (0, 0, 0, 0))
        self.d = ImageDraw.Draw(self.l)

    def text(self, x, y, s, f, a=255, anchor="ls", rgb=INK):
        self.d.text((x * S, y * S), s, font=f, fill=rgb + (a,), anchor=anchor)

    def rect(self, box, r, fill, outline=None, width=0):
        self.d.rounded_rectangle([v * S for v in box], r * S, fill=fill, outline=outline, width=width * S)

    def done(self, img):
        img.alpha_composite(self.l.resize((W, H), Image.LANCZOS))
        return img


def icon(ink, kind, cx, cy, a):
    """Biểu tượng nét mảnh (kiểu SF Symbols) trong ô bo góc, tâm (cx, cy); vẽ ở toạ độ phóng S."""
    tile = (255, 255, 255, 30 if a > 200 else 18)
    ink.rect((cx - 30, cy - 30, cx + 30, cy + 30), 16, tile)
    d, k, col = ink.d, S, INK + (a,)
    w = round(2.6 * k)
    P = lambda x, y: ((cx + x) * k, (cy + y) * k)
    if kind == "image":          # khung ảnh: núi + mặt trời
        d.rounded_rectangle([*P(-15, -12), *P(15, 12)], 4 * k, outline=col, width=w)
        d.line([P(-13, 9), P(-4, -1), P(2, 5), P(6, 1), P(13, 8)], fill=col, width=w, joint="curve")
        d.ellipse([*P(4, -8), *P(9, -3)], fill=col)
    elif kind == "saver":        # trăng khuyết + sao nhỏ
        d.ellipse([*P(-13, -13), *P(13, 13)], fill=col)
        d.ellipse([*P(-6, -18), *P(18, 6)], fill=tile)   # khoét bằng đúng màu ô, không thủng xuống nền
        d.ellipse([*P(10, -15), *P(14, -11)], fill=col)
    elif kind == "widget":       # lưới 4 ô
        for gx in (-13, 2):
            for gy in (-13, 2):
                d.rounded_rectangle([*P(gx, gy), *P(gx + 11, gy + 11)], 3 * k, outline=col, width=w)
    elif kind == "system":       # bánh răng
        import math
        for i in range(8):
            t = i * math.pi / 4
            d.line([P(10 * math.cos(t), 10 * math.sin(t)), P(15 * math.cos(t), 15 * math.sin(t))], fill=col, width=round(4.5 * k))
        d.ellipse([*P(-11, -11), *P(11, 11)], outline=col, width=w)
        d.ellipse([*P(-4.5, -4.5), *P(4.5, 4.5)], outline=col, width=w)


def row(ink, y, title, sub, focused=False, arrow="›", kind=None):
    x0, x1 = PX + 24, PX + PW - 24
    if focused:
        ink.rect((x0, y, x1, y + 100), 20, (255, 255, 255, 34))
    tx = x0 + 28
    if kind:
        icon(ink, kind, x0 + 56, y + 50, 240 if focused else 175)
        tx = x0 + 106
    ink.text(tx, y + 46, title, F_ROW, 245 if focused else 205)
    ink.text(tx, y + 80, sub, F_SUB, 150 if focused else 115)
    ink.text(x1 - 28, y + 62, arrow, F_ROW, 170 if focused else 90, anchor="rs")


def hint(ink, s):
    ink.text(PX + 52, PY + PH - 40, s, F_SMALL, 100)


# ---------- 1. bảng chính ----------
img = base()
glass(img, (PX, PY, PX + PW, PY + PH), PR)
ink = Ink()
ink.text(PX + 52, PY + 86, "Cài đặt", F_TITLE, 240)
y = PY + 140
row(ink, y, "Ảnh nền Home", "Ảnh kèm sẵn và ảnh của bạn, đổi mỗi 10 phút", focused=True, kind="image"); y += 112
row(ink, y, "Màn hình chờ", "Bật sau 5 phút, đổi ảnh mỗi 15 phút", kind="saver"); y += 112
row(ink, y, "Widget", "Giá BTC, ETH, vàng và lịch Ngoại hạng Anh", kind="widget"); y += 112
ink.rect((PX + 52, y + 16, PX + PW - 52, y + 18), 1, (255, 255, 255, 26)); y += 40
row(ink, y, "Cài đặt hệ thống", "Mở Cài đặt của Google TV", arrow="↗", kind="system")
hint(ink, "Bấm giữ nút bánh răng để mở thẳng Cài đặt hệ thống")
ink.done(img).convert("RGB").save(os.path.join(outdir, "settings_main.png"))

# ---------- 2. Ảnh nền Home ----------
img = base()
glass(img, (PX, PY, PX + PW, PY + PH), PR)
ink = Ink()
ink.text(PX + 52, PY + 70, "‹  Cài đặt", F_SMALL, 110)
ink.text(PX + 52, PY + 122, "Ảnh nền Home", F_TITLE, 240)

ink.text(PX + 52, PY + 186, "NGUỒN ẢNH", F_SMALL, 105)
y = PY + 204
for label, sub, on, foc in [("Ảnh kèm sẵn", "15 ảnh phong cảnh", False, False),
                            ("Ảnh của bạn", "12 ảnh đã gửi lên", False, False),
                            ("Trộn cả hai", "27 ảnh, xoay vòng lần lượt", True, True)]:
    x0, x1 = PX + 24, PX + PW - 24
    if foc: ink.rect((x0, y, x1, y + 88), 18, (255, 255, 255, 34))
    ink.text(x0 + 28, y + 40, label, F_ROW, 245 if foc else 205)
    ink.text(x0 + 28, y + 72, sub, F_SUB, 145 if foc else 110)
    cx, cy = x1 - 44, y + 44
    ink.d.ellipse([(cx - 13) * S, (cy - 13) * S, (cx + 13) * S, (cy + 13) * S], outline=INK + (200 if on else 90,), width=2 * S)
    if on: ink.d.ellipse([(cx - 7) * S, (cy - 7) * S, (cx + 7) * S, (cy + 7) * S], fill=INK + (235,))
    y += 96

y += 22
ink.text(PX + 52, y, "ĐỔI ẢNH MỖI", F_SMALL, 105); y += 18
opts = ["5 phút", "10 phút", "30 phút", "1 giờ"]
seg_w = (PW - 104) / len(opts)
ink.rect((PX + 52, y, PX + PW - 52, y + 60), 16, (255, 255, 255, 18))
for i, o in enumerate(opts):
    sx = PX + 52 + i * seg_w
    if i == 1: ink.rect((sx + 5, y + 5, sx + seg_w - 5, y + 55), 12, (255, 255, 255, 46))
    ink.text(sx + seg_w / 2, y + 39, o, F_SUB, 235 if i == 1 else 140, anchor="ms")
y += 100

ink.text(PX + 52, y, "ẢNH CỦA BẠN", F_SMALL, 105)
ink.text(PX + PW - 52, y, "Quản lý  ›", F_SMALL, 120, anchor="rs"); y += 18
img_l = ink.done(img)
# dải ảnh nhỏ: lấy vài vùng từ chính ảnh chụp cho có màu thật
thumbs = [(200, 100), (900, 80), (1400, 300), (300, 500), (1100, 450), (1600, 120)]
tw, th = 168, 95
for i, (sx, sy) in enumerate(thumbs):
    tx, ty = PX + 52 + (i % 3) * (tw + 16), y + (i // 3) * (th + 16)
    if thumb_dir: t = Image.open(os.path.join(thumb_dir, "t%d.jpg" % (i + 1))).convert("RGBA").resize((tw, th), Image.LANCZOS)
    else: t = Image.open(src).convert("RGBA").crop((sx, sy, sx + 480, sy + 270)).resize((tw, th), Image.LANCZOS)
    m = Image.new("L", (tw * S, th * S)); ImageDraw.Draw(m).rounded_rectangle((0, 0, tw * S - 1, th * S - 1), 10 * S, fill=255)
    img_l.paste(t, (tx, ty), m.resize((tw, th), Image.LANCZOS))
ink = Ink()
by = y + 2 * (th + 16) + 14
ink.rect((PX + 52, by, PX + PW - 52, by + 64), 16, (255, 255, 255, 22), outline=(255, 255, 255, 40), width=1)
ink.text(PX + PW / 2, by + 42, "+  Thêm ảnh từ điện thoại", F_SUB, 215, anchor="ms")
ink.done(img_l).convert("RGB").save(os.path.join(outdir, "settings_bg.png"))

# ---------- 3. gửi ảnh từ điện thoại ----------
img = base()
img = img.filter(ImageFilter.GaussianBlur(6))
img.alpha_composite(Image.new("RGBA", (W, H), (0, 0, 0, 90)))
CW, CH = 980, 560
cx0, cy0 = (W - CW) // 2, (H - CH) // 2
glass(img, (cx0, cy0, cx0 + CW, cy0 + CH), 36, 0xC4)
ink = Ink()
ink.text(cx0 + 64, cy0 + 100, "Gửi ảnh từ điện thoại", F_TITLE, 240)
lines = ["1.  Điện thoại bắt cùng Wi-Fi với tivi",
         "2.  Mở camera, quét mã bên phải",
         "3.  Chọn ảnh trong máy rồi bấm Gửi"]
for i, s in enumerate(lines):
    ink.text(cx0 + 64, cy0 + 180 + i * 56, s, F_ROW, 205)
ink.text(cx0 + 64, cy0 + 380, "Hoặc mở trình duyệt:", F_SUB, 120)
ink.text(cx0 + 64, cy0 + 420, "http://192.168.0.77:8080", F_KEY, 220)
ink.text(cx0 + 64, cy0 + CH - 56, "Đã nhận 3 ảnh, bấm Quay lại khi xong", F_SMALL, 120)
# mã QR minh hoạ (ngẫu nhiên, không quét được)
q, n = 300, 29
qx, qy = cx0 + CW - 64 - q, cy0 + (CH - q) // 2
ink.rect((qx - 20, qy - 20, qx + q + 20, qy + q + 20), 20, (246, 244, 238, 255))
cell = q / n
rnd = random.Random(7)
def finder(fx, fy):
    ink.rect((qx + fx * cell, qy + fy * cell, qx + (fx + 7) * cell, qy + (fy + 7) * cell), 4, (20, 20, 24, 255))
    ink.rect((qx + (fx + 1) * cell, qy + (fy + 1) * cell, qx + (fx + 6) * cell, qy + (fy + 6) * cell), 3, (246, 244, 238, 255))
    ink.rect((qx + (fx + 2) * cell, qy + (fy + 2) * cell, qx + (fx + 5) * cell, qy + (fy + 5) * cell), 2, (20, 20, 24, 255))
for yy in range(n):
    for xx in range(n):
        if (xx < 8 and yy < 8) or (xx > n - 9 and yy < 8) or (xx < 8 and yy > n - 9): continue
        if rnd.random() < 0.5:
            ink.d.rectangle([(qx + xx * cell) * S, (qy + yy * cell) * S, (qx + (xx + 1) * cell) * S - 1, (qy + (yy + 1) * cell) * S - 1], fill=(20, 20, 24, 255))
finder(0, 0); finder(n - 7, 0); finder(0, n - 7)
ink.done(img).convert("RGB").save(os.path.join(outdir, "settings_qr.png"))
print("ok")
