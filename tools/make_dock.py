# Dựng các mảnh "dock kính mờ" cho Projectivy: mỗi app một ảnh 640x360,
# nền trắng mờ ~12% (Projectivy giữ alpha nên nhìn xuyên ra nền), logo tách khỏi nền banner gốc.
# Mảnh đầu bo trái, mảnh cuối bo phải, giữa vuông để ghép liền thành một thanh.
import sys, os, colorsys
from PIL import Image, ImageDraw, ImageFilter

W, H = 640, 360
OUT_W, OUT_H = 512, 288
ORDER = ["youtube", "netflix", "fptplay", "tv360", "play", "hbo", "vieon"]
# nền banner là màu đậm -> logo chuyển trắng; nền sáng -> giữ màu logo, chữ xám/đen chuyển trắng
COLORED_BG = {"tv360", "iqiyi", "vieon", "hbo"}
KEEP_INNER = {"youtube"}
GLASS_ALPHA = int(sys.argv[2]) if len(sys.argv) > 2 else 30
RADIUS = 68

root = sys.argv[1]
os.makedirs(f"{root}/dock", exist_ok=True)


def bg_color(im):
    px = im.load(); w, h = im.size
    edge = [px[x, y] for x in range(0, w, 4) for y in (2, h - 3)] + [px[x, y] for y in range(0, h, 4) for x in (2, w - 3)]
    edge.sort(key=lambda p: sum(p[:3]))
    return edge[len(edge) // 2][:3]


def cut_logo(name):
    src = Image.open(f"{root}/banner/{name}.png").convert("RGBA")
    # siêu lấy mẫu: phóng to trước khi tách nền để mép mịn, thu nhỏ sau
    f = max(1, round(1600 / src.width))
    src = src.resize((src.width * f, src.height * f), Image.BICUBIC)
    bg = bg_color(src)
    rgb = Image.new("RGB", src.size)
    alpha = Image.new("L", src.size)
    sp, rp, ap = src.load(), rgb.load(), alpha.load()
    # nền sáng: chỉ những vùng giống nền VÀ nối với mép ảnh mới là nền (giữ phần trắng bên trong logo)
    near = Image.new("L", src.size, 0); np_ = near.load()
    for y in range(src.height):
        for x in range(src.width):
            r, g, b, _ = sp[x, y]
            if ((r - bg[0]) ** 2 + (g - bg[1]) ** 2 + (b - bg[2]) ** 2) ** 0.5 < 60:
                np_[x, y] = 255
    for pt in [(0, 0), (src.width - 1, 0), (0, src.height - 1), (src.width - 1, src.height - 1)]:
        if np_[pt] == 255:
            ImageDraw.floodfill(near, pt, 128)
    for y in range(src.height):
        for x in range(src.width):
            r, g, b, a = sp[x, y]
            if name in COLORED_BG:
                # logo trắng trên nền màu: tách theo độ trắng (HBO chụp từ màn hình nên ngưỡng thấp hơn)
                lo = 110 if name == "hbo" else 150
                al = max(0.0, min(1.0, (min(r, g, b) - lo) / 80))
                rp[x, y] = (255, 255, 255)
            else:
                d = ((r - bg[0]) ** 2 + (g - bg[1]) ** 2 + (b - bg[2]) ** 2) ** 0.5
                al = max(0.0, min(1.0, (d - 25) / 110))
                # tam giác trắng YouTube: chỉ trong vùng nút play (1/3 trái), không lấp lòng chữ
                inner = np_[x, y] == 255 and name in KEEP_INNER and x < src.width * 0.36
                if inner:
                    al = 1.0
                    rp[x, y] = (255, 255, 255)
                else:
                    # khử lẫn màu nền ở mép: c = (pixel - (1-a)*nền) / a
                    k = max(al, 0.05)
                    c = tuple(max(0, min(255, int((v - (1 - k) * bv) / k))) for v, bv in zip((r, g, b), bg))
                    s_ = colorsys.rgb_to_hsv(c[0] / 255, c[1] / 255, c[2] / 255)[1]
                    rp[x, y] = (255, 255, 255) if s_ < 0.25 else c
            al = al * al * (3 - 2 * al)  # smoothstep cho mép chuyển mềm
            ap[x, y] = int(255 * al * a / 255)
    alpha = alpha.filter(ImageFilter.GaussianBlur(f * 0.35))
    out = rgb.convert("RGBA"); out.putalpha(alpha)
    bbox = alpha.point(lambda v: 255 if v > 8 else 0).getbbox()
    return out.crop(bbox) if bbox else out


# logo trong suốt bạn gửi: file "nam.png" có 4 dải theo chiều dọc
USER_BANDS = {"fptplay": (446, 693), "netflix": (801, 1065), "tv360": (1162, 1428)}
USER_FILES = {"youtube": "youtube.png", "hbo": "hbo.png", "vieon": "vieon.png"}


def user_logo(name):
    if name in USER_BANDS:
        y0, y1 = USER_BANDS[name]
        im = Image.open(f"{root}/user/nam.png").convert("RGBA")
        im = im.crop((0, y0 - 4, im.width, y1 + 4))
    elif name in USER_FILES:
        im = Image.open(f"{root}/user/{USER_FILES[name]}").convert("RGBA")
    else:
        return None
    px = im.load()
    for y in range(im.height):
        for x in range(im.width):
            r, g, b, a = px[x, y]
            if a == 0:
                continue
            h_, s_, v_ = colorsys.rgb_to_hsv(r / 255, g / 255, b / 255)
            # chữ đen YouTube, HBO bạc -> trắng cho nổi trên kính tối
            if (name == "hbo" and s_ < 0.2) or v_ < 0.4:
                px[x, y] = (255, 255, 255, a)
    bbox = im.getchannel("A").point(lambda v: 255 if v > 10 else 0).getbbox()
    return im.crop(bbox)


def smooth_resize(im, size):
    # thu nhỏ trên alpha premultiplied để mép không viền đen, không răng cưa
    return im.convert("RGBa").resize(size, Image.LANCZOS).convert("RGBA")


def glass_tile(i, logo):
    tile = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    mask = Image.new("L", (W * 4, H * 4), 0)
    md = ImageDraw.Draw(mask)
    r = RADIUS * 4
    md.rounded_rectangle([0, 0, W * 4 - 1, H * 4 - 1], r, fill=255,
                         corners=(i == 0, i == len(ORDER) - 1, i == len(ORDER) - 1, i == 0))
    mask = mask.resize((W, H), Image.LANCZOS)
    glass = Image.new("RGBA", (W, H), (255, 255, 255, GLASS_ALPHA))
    tile.paste(glass, (0, 0), mask)
    # viền sáng mảnh trên và dưới (và hai đầu thanh), không bóng loáng
    line = Image.new("L", (W * 4, H * 4), 0)
    ld = ImageDraw.Draw(line)
    ld.rounded_rectangle([2, 2, W * 4 - 3, H * 4 - 3], r, outline=255, width=7,
                         corners=(i == 0, i == len(ORDER) - 1, i == len(ORDER) - 1, i == 0))
    if i != 0:
        ld.rectangle([0, 9, 9, H * 4 - 10], fill=0)
    if i != len(ORDER) - 1:
        ld.rectangle([W * 4 - 10, 9, W * 4, H * 4 - 10], fill=0)
    line = line.resize((W, H), Image.LANCZOS).point(lambda v: int(v * 0.30))
    tile.paste(Image.new("RGBA", (W, H), (255, 255, 255, 255)), (0, 0), line)
    # logo vừa khung 86% x 62%
    s = min(W * 0.86 / logo.width, H * 0.62 / logo.height)
    lg = smooth_resize(logo, (max(1, int(logo.width * s)), max(1, int(logo.height * s))))
    tile.alpha_composite(lg, ((W - lg.width) // 2, (H - lg.height) // 2))
    return tile


# Kiểu Apple TV: khay kính chạy liền qua các mảnh, bên trong mỗi mảnh là một ô app nền đặc, bo góc, có khe.
# nền ô và màu chữ logo cho từng app (None = giữ màu logo)
CARD = {
    "youtube": ((255, 255, 255), (33, 33, 33)),
    "netflix": ((20, 20, 20), None),
    "fptplay": ((255, 255, 255), None),
    "tv360": ((255, 255, 255), None),
    "play": ((255, 255, 255), (60, 64, 67)),
    "hbo": ((40, 20, 90), None),
    "vieon": ((15, 15, 15), None),
}


def recolor_light(logo, rgb):
    # chữ trắng/xám nhạt trong logo -> màu tối, để đọc được trên ô nền trắng
    px = logo.load()
    # chừa vùng nút đỏ: tam giác trắng của YouTube phải giữ trắng, chỉ chữ mới đổi màu
    red = Image.new("L", logo.size, 0); rp = red.load()
    for y in range(logo.height):
        for x in range(logo.width):
            r, g, b, a = px[x, y]
            if a > 128 and r > 180 and g < 90 and b < 90:
                rp[x, y] = 255
    keep = red.getbbox() or (0, 0, 0, 0)
    for y in range(logo.height):
        for x in range(logo.width):
            if keep[0] <= x < keep[2] and keep[1] <= y < keep[3]:
                continue
            r, g, b, a = px[x, y]
            if a and colorsys.rgb_to_hsv(r / 255, g / 255, b / 255)[1] < 0.2 and min(r, g, b) > 150:
                px[x, y] = (*rgb, a)
    return logo


def apple_tile(i, name, logo):
    n = len(ORDER)
    S = 4  # vẽ phóng 4 lần rồi thu nhỏ để mép mịn
    tile = Image.new("RGBA", (W * S, H * S), (0, 0, 0, 0))
    first, last = i == 0, i == n - 1
    # khay kính: liền mạch, chỉ bo ở hai đầu dock
    tray = Image.new("L", tile.size, 0)
    ImageDraw.Draw(tray).rounded_rectangle([0, 0, W * S - 1, H * S - 1], int(H * S * 0.22), fill=255,
                                           corners=(first, last, last, first))
    tile.paste(Image.new("RGBA", tile.size, (255, 255, 255, GLASS_ALPHA)), (0, 0), tray)
    # ô app bên trong, cách mép khay đều, có khe với ô bên cạnh
    mx, my = int(W * S * 0.06), int(H * S * 0.12)
    if first: mx0 = int(W * S * 0.09)
    else: mx0 = mx
    mx1 = int(W * S * 0.09) if last else mx
    box = [mx0, my, W * S - mx1, H * S - my]
    bg, txt = CARD[name]
    card = Image.new("L", tile.size, 0)
    ImageDraw.Draw(card).rounded_rectangle(box, int((box[3] - box[1]) * 0.13), fill=255)
    tile.paste(Image.new("RGBA", tile.size, (*bg, 255)), (0, 0), card)
    tile = tile.resize((W, H), Image.LANCZOS)
    if txt:
        logo = recolor_light(logo.copy(), txt)
    cw, ch = (box[2] - box[0]) / S, (box[3] - box[1]) / S
    s = min(cw * 0.84 / logo.width, ch * 0.72 / logo.height)
    lg = smooth_resize(logo, (max(1, int(logo.width * s)), max(1, int(logo.height * s))))
    cx, cy = (box[0] + box[2]) / 2 / S, (box[1] + box[3]) / 2 / S
    tile.alpha_composite(lg, (int(cx - lg.width / 2), int(cy - lg.height / 2)))
    return tile


if __name__ == "__main__" and "--apple" in sys.argv:
    for i, n in enumerate(ORDER):
        smooth_resize(apple_tile(i, n, user_logo(n) or cut_logo(n)), (OUT_W, OUT_H)).save(f"{root}/dock/{n}.png")
    prev = Image.new("RGBA", (OUT_W * len(ORDER) + 80, OUT_H + 80), (0, 0, 0, 255))
    pv = prev.load()
    for y in range(prev.height):
        for x in range(prev.width):
            t = x / prev.width
            pv[x, y] = (int(30 + 40 * t), int(50 + 20 * (1 - t)), int(90 + 40 * (1 - t)), 255)
    for i, n in enumerate(ORDER):
        prev.alpha_composite(Image.open(f"{root}/dock/{n}.png"), (40 + i * OUT_W, 40))
    prev.save(f"{root}/dock_preview.png")
    print("ok apple")
    sys.exit(0)

if __name__ == "__main__":
    for i, n in enumerate(ORDER):
        # dựng ở 640x360 rồi thu nhỏ chất lượng cao về đúng cỡ ô trên TV (cỡ S = 256x144),
        # để Projectivy không phải co ảnh lúc chạy (co nhanh là nguồn răng cưa)
        smooth_resize(glass_tile(i, user_logo(n) or cut_logo(n)), (OUT_W, OUT_H)).save(f"{root}/dock/{n}.png")
    # ảnh xem trước: ghép liền trên nền giả lập màu động
    prev = Image.new("RGBA", (OUT_W * len(ORDER), H // 2 + 120), (0, 0, 0, 255))
    pv = prev.load()
    for y in range(prev.height):
        for x in range(prev.width):
            t = x / prev.width
            pv[x, y] = (int(120 + 80 * t), int(40 + 30 * (1 - t)), int(70 + 60 * (1 - t)), 255)
    prev = prev.filter(ImageFilter.GaussianBlur(30))
    for i, n in enumerate(ORDER):
        im = Image.open(f"{root}/dock/{n}.png")
        prev.alpha_composite(im, (i * OUT_W, 60))
    prev.save(f"{root}/dock_preview.png")
    print("ok")
