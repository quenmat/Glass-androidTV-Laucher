# Ô logo cho game (464x290, bo góc như các ô khác): nền là ảnh bìa làm mờ và tối, logo tên game (cắt từ ảnh bìa
# hoặc ICON0 của đĩa) đặt giữa, mép logo hoà dần vào nền vì logo không có nền trong suốt.
# python tools/make_game_card.py <ảnh nền> <ảnh chứa logo> <x0,y0,x1,y1 vùng logo hoặc "all"> <ra.png>
import sys
from PIL import Image, ImageDraw, ImageFilter

bg_src, logo_src, box, out = sys.argv[1:5]
CW, CH, S = 464, 290, 4

base = Image.open(bg_src).convert("RGB")
k = max(CW / base.width, CH / base.height)
bg = base.resize((round(base.width * k), round(base.height * k)), Image.BICUBIC)
bg = bg.crop(((bg.width - CW) // 2, (bg.height - CH) // 2, (bg.width - CW) // 2 + CW, (bg.height - CH) // 2 + CH))
bg = bg.filter(ImageFilter.GaussianBlur(14)).convert("RGBA")
bg.alpha_composite(Image.new("RGBA", (CW, CH), (8, 8, 12, 150)))

logo = Image.open(logo_src).convert("RGB")
if box != "all":
    logo = logo.crop(tuple(int(v) for v in box.split(",")))
# ô hiện trên màn hình ở cỡ 300x169 (bảng Apps): logo phóng tối đa 1,25 lần so với ảnh gốc trên màn hình,
# để logo nhỏ (ảnh trong đĩa PSP chỉ ~200 điểm ảnh) không vỡ hạt; thà nhỏ mà nét
SHOWN = 300 / CW
scale = min(CW * 0.84 / logo.width, CH * 0.62 / logo.height, 1.25 / SHOWN)
lw, lh = round(logo.width * scale), round(logo.height * scale)
logo = logo.resize((lw, lh), Image.LANCZOS).filter(ImageFilter.UnsharpMask(1.6, 70, 2))
# mép hoà dần: mặt nạ chữ nhật bo góc làm nhoè, giữa đặc
m = Image.new("L", (lw, lh), 0)
pad = max(6, min(lw, lh) // 9)
ImageDraw.Draw(m).rounded_rectangle([pad, pad, lw - pad, lh - pad], pad, fill=255)
m = m.filter(ImageFilter.GaussianBlur(pad * 0.8))
bg.paste(logo, ((CW - lw) // 2, (CH - lh) // 2), m)

mask = Image.new("L", (CW * S, CH * S), 0)
ImageDraw.Draw(mask).rounded_rectangle([0, 0, CW * S - 1, CH * S - 1], int(CH * 0.12 * S), fill=255)
card = Image.new("RGBA", (CW, CH), (0, 0, 0, 0))
card.paste(bg, (0, 0), mask.resize((CW, CH), Image.LANCZOS))
card.save(out)
print("ok", out)
