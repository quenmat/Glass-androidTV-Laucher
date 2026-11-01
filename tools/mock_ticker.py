# Mock khối giá kiểu widget Apple: 2 ô kính cạnh nhau dưới đồng hồ, cấu trúc giống hệt nhau
# (dòng trên: tên + thông tin phụ nhỏ; dòng dưới: giá to). Vẽ 3x rồi thu nhỏ, số tabular (tnum).
# python tools/mock_ticker.py <mock/home.png> <ra.png>
import os, sys
from PIL import Image, ImageDraw, ImageFilter, ImageFont

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.join(HERE, "..")
src, out = sys.argv[1], sys.argv[2]
S = 4


def font(size, w):
    f = ImageFont.truetype(os.path.join(ROOT, "assets", "fonts", "Inter.ttf"), size * S)
    try: f.set_variation_by_axes([min(32, max(14, size)), w])
    except Exception: pass
    return f


def text(d, xy, s, f, fill, tnum=False):
    try: d.text(xy, s, font=f, fill=fill, features=["tnum"] if tnum else None)
    except Exception: d.text(xy, s, font=f, fill=fill)


def tlen(d, s, f, tnum=False):
    try: return d.textlength(s, font=f, features=["tnum"] if tnum else None)
    except Exception: return d.textlength(s, font=f)


base = Image.open(src).convert("RGBA")
W = base.width
# mép phải thẳng với mép phải khay dock (khay rộng 1842, căn giữa)
TRAY_W = 8 * 200 + 7 * 26 + 60
RIGHT, TOP = (W + TRAY_W) // 2, 116
# bo góc cùng tỉ lệ với khay dock (bán kính ~0.206 chiều cao)
TW, TH, GAP, PAD = 236, 96, 18, 20
R = round(TH * 0.206)
tiles = [
    ("BTC", "▼ 0,21%", (255, 123, 114), "80.413,52", (255, 123, 114)),
    ("Vàng 9999", "", (255, 255, 255, 140), "14.100.000", (255, 255, 255, 245)),
]
lab, meta, val = font(18, 500), font(18, 500), font(30, 600)

for i, (name, extra, ecol, price, pcol) in enumerate(tiles):
    x0 = RIGHT - (len(tiles) - i) * TW - (len(tiles) - 1 - i) * GAP
    # cùng chất kính với dock (FrostView): nền thu 1/24 rồi phóng lại + trắng sữa 18%
    small = base.resize((W // 24, base.height // 24), Image.LANCZOS)
    blur = small.resize((W, base.height), Image.BILINEAR).filter(ImageFilter.GaussianBlur(6))
    area = blur.crop((x0, TOP, x0 + TW, TOP + TH))
    area.alpha_composite(Image.new("RGBA", (TW, TH), (0, 0, 0, 71)))   # kính tối như FrostView.dark()
    big = area.resize((TW * S, TH * S), Image.BICUBIC)
    d = ImageDraw.Draw(big)
    # viền gần như vô hình (~6%), lùi vào nửa điểm ảnh để nằm gọn trong mép bo
    h = S // 2
    # kính tối: không viền (như FrostView.dark())
    # dòng trên: tên trái, phụ phải
    text(d, (PAD * S, PAD * S - 2 * S), name, lab, (255, 255, 255, 150))
    ec = ecol if len(ecol) == 4 else (*ecol, 255)
    text(d, ((TW - PAD) * S - tlen(d, extra, meta), PAD * S - 2 * S), extra, meta, ec)
    # dòng dưới: giá to, thẳng mép trái
    text(d, (PAD * S, (PAD + 26) * S), price, val, pcol if len(pcol) == 4 else (*pcol, 255), True)
    mask = Image.new("L", (TW * S, TH * S), 0)
    ImageDraw.Draw(mask).rounded_rectangle([0, 0, TW * S - 1, TH * S - 1], R * S, fill=255)
    base.paste(big.resize((TW, TH), Image.LANCZOS), (x0, TOP), mask.resize((TW, TH), Image.LANCZOS))

base.convert("RGB").save(out)
base.crop((W - 760, 20, W - 20, 260)).convert("RGB").save(out.replace(".png", "_zoom.png"))
print("ok")
