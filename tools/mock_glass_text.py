# Mock chữ "kính mờ" cho màn hình chờ: so chữ hiện tại với chữ làm từ chính ảnh nền đã nhoè (hơi trong).
# python tools/mock_glass_text.py <ảnh nền> <thư mục ra>
#   ra: glass_compare.png (ba phương án cạnh nhau, cắt vùng góc dưới trái) và glass_<tên>.png (cả màn hình)
import os, sys
from PIL import Image, ImageChops, ImageDraw, ImageFilter, ImageFont

HERE = os.path.dirname(os.path.abspath(__file__))
FONT = os.path.join(HERE, "..", "assets", "fonts", "Inter.ttf")
W, H, S = 1920, 1080, 3
src, outdir = sys.argv[1], sys.argv[2]
TIME, SOLAR, LUNAR = "21:45", "Chủ Nhật, 1 tháng 11, 2026", "23 tháng Chín năm Bính Ngọ"


def font(size, weight):
    f = ImageFont.truetype(FONT, size * S)
    try: f.set_variation_by_axes([min(32, max(14, size)), weight])
    except Exception: pass
    return f


def background():
    """Giống màn hình chờ thật: ảnh phóng 108%, tối thêm ~45%, đáy tối dần, quầng tối sau cụm giờ."""
    bg = Image.open(src).convert("RGB")
    k = 1.08
    bg = bg.resize((int(W * k), int(H * k)), Image.LANCZOS)
    bg = bg.crop(((bg.width - W) // 2, (bg.height - H) // 2, (bg.width - W) // 2 + W, (bg.height - H) // 2 + H)).convert("RGBA")
    bg.alpha_composite(Image.new("RGBA", (W, H), (0, 0, 0, 0x73)))
    floor = Image.new("L", (W, 480))
    for y in range(480):
        t = y / 479
        floor.paste(int(0x40 * t / 0.5) if t < 0.5 else int(0x40 + (0x8C - 0x40) * (t - 0.5) / 0.5), (0, y, W, y + 1))
    bg.alpha_composite(Image.merge("RGBA", (Image.new("L", floor.size),) * 3 + (floor,)), (0, H - 480))
    return bg


def mask(weight_time):
    """Mặt nạ chữ (vẽ phóng S lần rồi thu về cho mịn), cùng vị trí cụm giờ góc dưới trái như bản thật."""
    m = Image.new("L", (W * S, H * S))
    d = ImageDraw.Draw(m)
    x0 = 110
    d.text(((x0 - 8) * S, (H - 72 - 30 - 42 - 34) * S), TIME, font=font(140, weight_time), fill=255, anchor="ls")
    d.text((x0 * S, (H - 72 - 42) * S), SOLAR, font=font(36, 500), fill=255, anchor="ls")
    d.text((x0 * S, (H - 72) * S), LUNAR, font=font(30, 400), fill=255, anchor="ls")
    return m.resize((W, H), Image.LANCZOS)


def plain(bg, m):
    """Bản hiện tại: chữ kem ấm ~60%."""
    out = bg.copy()
    col = Image.new("RGBA", (W, H), (242, 232, 216, 0))
    col.putalpha(m.point(lambda v: v * 0x99 // 255))
    out.alpha_composite(col)
    return out


def glass(bg, m, tint, alpha):
    """
    Chữ kính mờ phẳng: lòng chữ là ảnh nền nhoè mạnh, sáng lên và pha trắng sữa (thấy màu ảnh xuyên qua, hơi trong).
    Không viền sáng, không bóng lệch (trông nổi khối 3D); chỉ một quầng tối rất mềm đều quanh chữ để tách khỏi nền.
    """
    out = bg.copy()
    sh = m.filter(ImageFilter.GaussianBlur(14)).point(lambda v: v * 45 // 255)
    out.alpha_composite(Image.merge("RGBA", (Image.new("L", (W, H)),) * 3 + (sh,)))
    frost = bg.filter(ImageFilter.GaussianBlur(22)).point(lambda v: min(255, int(v * 1.9 + 34)))
    frost.alpha_composite(Image.new("RGBA", (W, H), (255, 250, 242, tint)))
    frost.putalpha(m.point(lambda v: v * alpha // 255))
    out.alpha_composite(frost)
    return out


bg = background()
thin, bold = mask(320), mask(460)
variants = [("hien_tai", plain(bg, thin)), ("kinh_A", glass(bg, thin, 70, 215)), ("kinh_B", glass(bg, bold, 84, 215))]
for name, im in variants:
    im.convert("RGB").save(os.path.join(outdir, "glass_%s.png" % name))
crop = (40, 700, 900, 1060)
cw, ch = crop[2] - crop[0], crop[3] - crop[1]
sheet = Image.new("RGB", (cw, ch * 3 + 40), (20, 20, 22))
for i, (name, im) in enumerate(variants):
    sheet.paste(im.convert("RGB").crop(crop), (0, i * (ch + 20)))
sheet.save(os.path.join(outdir, "glass_compare.png"))
print("ok")
