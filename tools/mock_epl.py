# Mock lịch Ngoại hạng Anh trên màn hình chính, vẽ chồng lên ảnh chụp thật của launcher (1920x1080).
# python tools/mock_epl.py <ảnh chụp home> <fixtures.json> <teams.json> <thư mục logo> <ra.png> <A|B>
#   A: thẻ kính tối bên trái, đủ 10 trận của vòng, chia theo ngày
#   B: một ô nhỏ trong cột giá bên phải, hiện 1 trận sắp đá (xoay vòng qua các trận)
import datetime, json, os, sys
from PIL import Image, ImageDraw, ImageFilter, ImageFont

HERE = os.path.dirname(os.path.abspath(__file__))
FONT = os.path.join(HERE, "..", "assets", "fonts", "Inter.ttf")
base_p, fx_p, teams_p, badge_dir, out, variant = sys.argv[1:7]
S = 3
THU = ["Thứ Hai", "Thứ Ba", "Thứ Tư", "Thứ Năm", "Thứ Sáu", "Thứ Bảy", "Chủ Nhật"]
SHORT_VI = {"Man Utd": "Man United", "Nott'm Forest": "Nottingham", "Coventry City": "Coventry",
            "Hull City": "Hull", "Ipswich Town": "Ipswich", "Crystal Palace": "Crystal Palace"}


def font(size, w):
    f = ImageFont.truetype(FONT, size * S)
    try: f.set_variation_by_axes([min(32, max(14, size)), w])
    except Exception: pass
    return f


base = Image.open(base_p).convert("RGBA")
W, H = base.size
teams = json.load(open(teams_p))
fx = json.load(open(fx_p))
for f in fx:
    f["vn"] = datetime.datetime.fromisoformat(f["kickoff_time"].replace("Z", "+00:00")) + datetime.timedelta(hours=7)


def badge(team_id, size):
    code = teams[str(team_id)][2]
    b = Image.open(os.path.join(badge_dir, f"t{code}.png")).convert("RGBA")
    b.thumbnail((size * S, size * S), Image.LANCZOS)
    return b


def glass(x, y, w, h, r, dark=0x47):
    # kính tối như ô giá: nền nhoè (thu 1/24 rồi phóng lại) + đen mờ, bo góc mịn
    small = base.resize((W // 24, H // 24), Image.LANCZOS).resize((W, H), Image.BILINEAR).filter(ImageFilter.GaussianBlur(6))
    area = small.crop((x, y, x + w, y + h))
    area.alpha_composite(Image.new("RGBA", (w, h), (0, 0, 0, dark)))
    m = Image.new("L", (w * S, h * S), 0)
    ImageDraw.Draw(m).rounded_rectangle([0, 0, w * S - 1, h * S - 1], r * S, fill=255)
    base.paste(area, (x, y), m.resize((w, h), Image.LANCZOS))


layer = Image.new("RGBA", (W * S, H * S), (0, 0, 0, 0))
d = ImageDraw.Draw(layer)


def text(x, y, s, f, rgba, anchor="ls"):
    d.text((x * S, y * S), s, font=f, fill=rgba, anchor=anchor)


def paste_badge(img, x, y):   # x, y = tâm logo (toạ độ màn hình)
    layer.alpha_composite(img, (int(x * S - img.width / 2), int(y * S - img.height / 2)))


if variant == "A":
    x0, y0, w = 40, 116, 560
    days = []
    for f in sorted(fx, key=lambda f: f["vn"]):
        key = f["vn"].date()
        if not days or days[-1][0] != key: days.append((key, []))
        days[-1][1].append(f)
    ROW, HEAD = 42, 40
    h = 26 + 34 + sum(HEAD + ROW * len(ms) for _, ms in days) + 14
    glass(x0, y0, w, h, 20, dark=0x8C)   # kính đậm hơn ô giá: nhiều chữ nhỏ, cần nền yên
    lab, dayf, timef, team = font(17, 600), font(17, 500), font(20, 500), font(20, 500)
    text(x0 + 24, y0 + 40, "NGOẠI HẠNG ANH", lab, (255, 255, 255, 150))
    text(x0 + w - 24, y0 + 40, "Vòng 9", lab, (255, 255, 255, 150), "rs")
    y = y0 + 60
    cx = x0 + 24 + 70 + (w - 48 - 70) / 2   # giữa cột trận (sau cột giờ)
    for day, ms in days:
        y += HEAD
        text(x0 + 24, y - 10, f"{THU[day.weekday()]}, {day.day}/{day.month}", dayf, (255, 255, 255, 115))
        for f in ms:
            y += ROW
            mid = y - 14
            text(x0 + 24, y - 6, f["vn"].strftime("%H:%M"), timef, (255, 255, 255, 170))
            hn = SHORT_VI.get(teams[str(f["team_h"])][1], teams[str(f["team_h"])][1])
            an = SHORT_VI.get(teams[str(f["team_a"])][1], teams[str(f["team_a"])][1])
            text(cx - 50, y - 6, hn, team, (255, 255, 255, 232), "rs")
            paste_badge(badge(f["team_h"], 26), cx - 28, mid)
            text(cx, y - 6, "–", timef, (255, 255, 255, 110), "ms")
            paste_badge(badge(f["team_a"], 26), cx + 28, mid)
            text(cx + 50, y - 6, an, team, (255, 255, 255, 232))
else:
    # B: ô cùng cỡ, cùng chất kính với 3 ô giá, nằm dưới ô Vàng; hiện trận sắp đá gần nhất
    TRAY_W = 6 * 272 + 5 * 30 + 60
    right = (1920 + TRAY_W) // 2
    tw, th, gap = 236, 96, 18
    x0, y0 = right - tw, 116 + 3 * (th + gap)
    glass(x0, y0, tw, th, round(th * 0.206), dark=0x73)
    f = sorted(fx, key=lambda f: f["vn"])[0]
    lab = font(18, 500)
    wd = ["T2", "T3", "T4", "T5", "T6", "T7", "CN"][f["vn"].weekday()]
    text(x0 + 20, y0 + 18 + 13, "Vòng 9", lab, (255, 255, 255, 150))
    text(x0 + tw - 20, y0 + 18 + 13, f"{wd} {f['vn'].strftime('%H:%M')}", lab, (255, 255, 255, 150), "rs")
    mid = y0 + 66
    big = font(24, 600)
    paste_badge(badge(f["team_h"], 26), x0 + 20 + 13, mid)
    text(x0 + 20 + 32, mid + 8, teams[str(f["team_h"])][0], big, (255, 255, 255, 245))
    text(x0 + tw - 20 - 32, mid + 8, teams[str(f["team_a"])][0], big, (255, 255, 255, 245), "rs")
    paste_badge(badge(f["team_a"], 26), x0 + tw - 20 - 13, mid)

base.alpha_composite(layer.resize((W, H), Image.LANCZOS))
base.convert("RGB").save(out)
print("ok", variant)
