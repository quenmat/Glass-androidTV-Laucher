# Mock thẻ Ngoại hạng Anh chế độ thu gọn: rộng bằng ô giá (236), xếp dọc như ô giá.
# python tools/mock_epl_compact.py <ảnh nền> <boot.json> <fixtures.json> <thư mục logo t{code}.png> <thư mục ra>
#   ra: epl_compact.png (hai trạng thái cạnh nhau: chưa có trận đang đá / đang có trận)
import json, os, sys, datetime
from PIL import Image, ImageDraw, ImageFilter, ImageFont

HERE = os.path.dirname(os.path.abspath(__file__))
FONT = os.path.join(HERE, "..", "assets", "fonts", "Inter.ttf")
S = 3
src, boot_p, fx_p, badge_dir, outdir = sys.argv[1:6]
FAV = 1   # mã Premier League của đội yêu thích (1 = Man United)
NICE = {"Man Utd": "Man United", "Nott'm Forest": "Nottingham", "Coventry City": "Coventry", "Hull City": "Hull", "Ipswich Town": "Ipswich"}
THU = ["Thứ Hai", "Thứ Ba", "Thứ Tư", "Thứ Năm", "Thứ Sáu", "Thứ Bảy", "Chủ Nhật"]


def font(size, weight):
    f = ImageFont.truetype(FONT, size * S)
    try: f.set_variation_by_axes([min(32, max(14, size)), weight])
    except Exception: pass
    return f


boot = json.load(open(boot_p, encoding="utf-8"))
fx = json.load(open(fx_p, encoding="utf-8"))
teams = {t["id"]: {"code": t["code"], "name": NICE.get(t["name"], t["name"])} for t in boot["teams"]}
fav_id = next(i for i, t in teams.items() if t["code"] == FAV)

# bảng xếp hạng từ các trận đã xong
st = {i: {"pts": 0, "gd": 0, "gf": 0} for i in teams}
for f in fx:
    if not (f.get("finished") or f.get("finished_provisional")) or f["team_h_score"] is None: continue
    h, a, hs, as_ = f["team_h"], f["team_a"], f["team_h_score"], f["team_a_score"]
    st[h]["gf"] += hs; st[a]["gf"] += as_; st[h]["gd"] += hs - as_; st[a]["gd"] += as_ - hs
    if hs > as_: st[h]["pts"] += 3
    elif hs < as_: st[a]["pts"] += 3
    else: st[h]["pts"] += 1; st[a]["pts"] += 1
order = sorted(teams, key=lambda i: (-st[i]["pts"], -st[i]["gd"], -st[i]["gf"]))
fav_rank = order.index(fav_id)
ranks = [0, 1, fav_rank if fav_rank >= 2 else 2]
moves = {order[0]: 0, order[1]: 1, order[ranks[2]]: -1}   # minh hoạ lên/xuống

nxt = next(f for f in sorted(fx, key=lambda f: f["kickoff_time"] or "z")
           if f["kickoff_time"] and not f.get("finished") and fav_id in (f["team_h"], f["team_a"]))
kick = datetime.datetime.fromisoformat(nxt["kickoff_time"].replace("Z", "+00:00")) + datetime.timedelta(hours=7)

badges = {}
def badge(code, px):
    key = (code, px)
    if key not in badges:
        b = Image.open(os.path.join(badge_dir, "t%d.png" % code)).convert("RGBA")
        while b.width // 2 >= px: b = b.resize((b.width // 2, b.height // 2), Image.BILINEAR)
        badges[key] = b.resize((px, round(b.height * px / b.width)), Image.LANCZOS)
    return badges[key]


W0, H0 = 1920, 640
bg = Image.open(src).convert("RGB")
k = max(1920 / bg.width, 1080 / bg.height)
bg = bg.resize((round(bg.width * k), round(bg.height * k)), Image.LANCZOS).crop((0, 0, W0, H0)).convert("RGBA")
bg.alpha_composite(Image.new("RGBA", (W0, H0), (0, 0, 0, 70)))
blur = bg.filter(ImageFilter.GaussianBlur(30))
TILE_W, R = 236, 20


def glass(img, box, alpha=0xA8):
    x0, y0, x1, y1 = box
    g = blur.crop(box)
    g.alpha_composite(Image.new("RGBA", g.size, (8, 8, 10, alpha)))
    m = Image.new("L", ((x1 - x0) * S, (y1 - y0) * S))
    ImageDraw.Draw(m).rounded_rectangle((0, 0, m.width - 1, m.height - 1), R * S, fill=255)
    img.paste(g, (x0, y0), m.resize(g.size, Image.LANCZOS))


class Ink:
    def __init__(self, w, h):
        self.l = Image.new("RGBA", (w * S, h * S), (0, 0, 0, 0)); self.d = ImageDraw.Draw(self.l); self.icons = []
    def t(self, x, y, s, f, rgba, anchor="ls"):
        self.d.text((x * S, y * S), s, font=f, fill=rgba, anchor=anchor)
    def rect(self, box, r, fill):
        self.d.rounded_rectangle([v * S for v in box], r * S, fill=fill)
    def icon(self, im, cx, cy):
        self.icons.append((im, round(cx - im.width / 2), round(cy - im.height / 2)))
    def done(self, img):
        img.alpha_composite(self.l.resize(img.size, Image.LANCZOS))
        for im, x, y in self.icons: img.alpha_composite(im, (x, y))


F_LBL, F_SM, F_TEAM, F_SCORE, F_ROW, F_MV = font(13, 600), font(14, 500), font(18, 500), font(19, 600), font(16, 500), font(12, 500)
WHITE, MUTED, FAINT = (255, 255, 255, 214), (255, 255, 255, 140), (255, 255, 255, 96)


def tile(img, x0, y0, live):
    """Vẽ thẻ thu gọn, trả về chiều cao."""
    pad, h = 18, 0
    rows_match = 2
    hgt = 40 + (0 if live else 22) + rows_match * 30 + 22 + 3 * 26 + 12
    glass(img, (x0, y0, x0 + TILE_W, y0 + hgt))
    ink = Ink(*img.size)
    y = y0 + 30
    ink.t(x0 + pad, y, "NGOẠI HẠNG ANH", F_LBL, MUTED)
    if live:
        ink.d.ellipse([(x0 + TILE_W - pad - 52) * S, (y - 9) * S, (x0 + TILE_W - pad - 44) * S, (y - 1) * S], fill=(76, 224, 122, 255))
        ink.t(x0 + TILE_W - pad, y, "67'", F_LBL, (127, 227, 160, 255), "rs")
    else:
        ink.t(x0 + TILE_W - pad, y, "Trận tới", F_LBL, FAINT, "rs")
    y += 10
    if not live:
        y += 22
        ink.t(x0 + pad, y, "%s %d/%d, %02d:%02d" % (THU[kick.weekday()], kick.day, kick.month, kick.hour, kick.minute), F_SM, MUTED)
    score = {nxt["team_h"]: 2, nxt["team_a"]: 1}
    for tid in (nxt["team_h"], nxt["team_a"]):
        y += 30
        mine = tid == fav_id
        if mine: ink.rect((x0 + 8, y - 22, x0 + TILE_W - 8, y + 6), 8, (255, 255, 255, 30))
        ink.icon(badge(teams[tid]["code"], 22), x0 + pad + 11, y - 8)
        ink.t(x0 + pad + 32, y, teams[tid]["name"], F_TEAM, (255, 255, 255, 245) if mine else (255, 255, 255, 199))
        if live: ink.t(x0 + TILE_W - pad, y, str(score[tid]), F_SCORE, WHITE, "rs")
    y += 14
    ink.rect((x0 + pad, y, x0 + TILE_W - pad, y + 1), 0, (255, 255, 255, 31))
    y += 8
    for n, ri in enumerate(ranks):
        y += 26
        tid = order[ri]
        mine = tid == fav_id
        if mine: ink.rect((x0 + 8, y - 19, x0 + TILE_W - 8, y + 6), 8, (255, 255, 255, 30))
        ink.t(x0 + pad + 16, y, str(ri + 1), F_ROW, (255, 255, 255, 217) if mine else (255, 255, 255, 115), "rs")
        ink.icon(badge(teams[tid]["code"], 20), x0 + pad + 34, y - 6)
        ink.t(x0 + pad + 50, y, teams[tid]["name"], F_ROW, (255, 255, 255, 245) if mine else (255, 255, 255, 199))
        mv = moves.get(tid, 0)
        if mv > 0: ink.t(x0 + TILE_W - pad - 30, y - 1, "▲%d" % mv, F_MV, (127, 217, 156, 204), "rs")
        elif mv < 0: ink.t(x0 + TILE_W - pad - 30, y - 1, "▼%d" % -mv, F_MV, (245, 154, 144, 204), "rs")
        ink.t(x0 + TILE_W - pad, y, str(st[tid]["pts"]), F_ROW, (255, 255, 255, 217), "rs")
    ink.done(img)
    return hgt


def screen(live):
    img = bg.copy()
    # nút bánh răng + đồng hồ + cột giá để so cỡ
    ink = Ink(*img.size)
    ink.d.ellipse([39 * S, 36 * S, 99 * S, 96 * S], fill=(0, 0, 0, 71))
    # bánh răng giản lược (font không có ký tự bánh răng): vòng tròn + chấm giữa
    ink.d.ellipse([58 * S, 55 * S, 80 * S, 77 * S], outline=(255, 255, 255, 240), width=3 * S)
    ink.d.ellipse([65 * S, 62 * S, 73 * S, 70 * S], fill=(255, 255, 255, 240))
    ink.t(1881, 80, "16:20", font(34, 600), (255, 255, 255, 209), "rs")
    ink.done(img)
    for i, (nm, val, chg, col) in enumerate([("BTC", "78.175,82", "▲ 0,83%", (127, 217, 156, 217)),
                                             ("ETH", "2.272,11", "▲ 1,22%", (127, 217, 156, 217)),
                                             ("Vàng", "13.760.000", "", (245, 154, 144, 217))]):
        x0, y0 = 1881 - TILE_W, 116 + i * 114
        glass(img, (x0, y0, x0 + TILE_W, y0 + 96))
        ink = Ink(*img.size)
        ink.t(x0 + 20, y0 + 34, nm, font(18, 500), (255, 255, 255, 128))
        if chg: ink.t(x0 + TILE_W - 20, y0 + 34, chg, font(18, 500), col, "rs")
        ink.t(x0 + 20, y0 + 74, val, font(30, 600), col)
        ink.done(img)
    tile(img, 39, 116, live)
    return img


a, b = screen(False), screen(True)
crop = (0, 0, 1920, 520)
sheet = Image.new("RGB", (1920, 1060), (18, 18, 20))
sheet.paste(a.crop(crop).convert("RGB"), (0, 0))
sheet.paste(b.crop(crop).convert("RGB"), (0, 540))
sheet.save(os.path.join(outdir, "epl_compact.png"))
zoom = Image.new("RGB", (620, 400), (18, 18, 20))
zoom.paste(a.crop((20, 100, 320, 480)).convert("RGB"), (0, 10))
zoom.paste(b.crop((20, 100, 320, 480)).convert("RGB"), (310, 10))
zoom.resize((1240, 800), Image.LANCZOS).save(os.path.join(outdir, "epl_compact_zoom.png"))
print("ok", teams[nxt["team_h"]]["name"], "-", teams[nxt["team_a"]]["name"], kick, "fav rank", fav_rank + 1)
