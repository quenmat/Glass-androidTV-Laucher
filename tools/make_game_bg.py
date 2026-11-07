# Ảnh nền cho ô game khi ảnh gốc nhỏ (PIC1 của đĩa PSP chỉ 480x272): nền là chính ảnh đó làm mờ mạnh và tối đi
# (mờ có chủ đích nên không lộ vỡ hạt), ảnh gốc phóng đúng 2 lần (960x544) đặt giữa phần trên màn hình, bo góc, bóng mềm.
# python tools/make_game_bg.py <PIC1.PNG> <ra.jpg> [--no-art]
#   --no-art: chỉ nền mờ (dùng sau bảng Apps, nơi lưới ô ứng dụng đã che giữa màn hình)
import sys
from PIL import Image, ImageDraw, ImageFilter

src, out = sys.argv[1], sys.argv[2]
W, H = 1920, 1080
art = Image.open(src).convert("RGB")

# nền: phủ kín màn hình, nhoè mạnh, tối ~45%, tối thêm về hai mép và đáy
k = max(W / art.width, H / art.height)
bg = art.resize((round(art.width * k), round(art.height * k)), Image.BICUBIC)
bg = bg.crop(((bg.width - W) // 2, (bg.height - H) // 2, (bg.width - W) // 2 + W, (bg.height - H) // 2 + H))
bg = bg.filter(ImageFilter.GaussianBlur(48)).convert("RGBA")
bg.alpha_composite(Image.new("RGBA", (W, H), (0, 0, 0, 115)))
vig = Image.new("L", (W, H), 0)
vp = vig.load()
for y in range(0, H, 2):
    for x in range(0, W, 2):
        dx, dy = (x - W / 2) / (W / 2), (y - H * 0.42) / (H * 0.7)
        v = int(min(255, max(0, (dx * dx + dy * dy - 0.35) * 150)))
        for a in range(2):
            for b in range(2):
                if x + a < W and y + b < H: vp[x + a, y + b] = v
bg.alpha_composite(Image.merge("RGBA", (Image.new("L", (W, H)),) * 3 + (vig,)))

if "--no-art" in sys.argv:
    bg.convert("RGB").save(out, quality=93)
    print("ok", out)
    sys.exit(0)

# ảnh gốc gấp đôi, giữa ngang, phía trên dock (dock bắt đầu ~ y 785)
aw, ah = art.width * 2, art.height * 2
big = art.resize((aw, ah), Image.LANCZOS)
x0, y0 = (W - aw) // 2, 110
R, S = 24, 4
mask = Image.new("L", (aw * S, ah * S), 0)
ImageDraw.Draw(mask).rounded_rectangle([0, 0, aw * S - 1, ah * S - 1], R * S, fill=255)
mask = mask.resize((aw, ah), Image.LANCZOS)
shadow = Image.new("L", (W, H), 0)
shadow.paste(mask.point(lambda v: v * 150 // 255), (x0, y0 + 14))
shadow = shadow.filter(ImageFilter.GaussianBlur(28))
bg.alpha_composite(Image.merge("RGBA", (Image.new("L", (W, H)),) * 3 + (shadow,)))
bg.paste(big, (x0, y0), mask)
bg.convert("RGB").save(out, quality=93)
print("ok", out)
