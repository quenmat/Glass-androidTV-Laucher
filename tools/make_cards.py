# Tạo ảnh ô app (card) cho Glass Home: nền đặc bo góc + logo, xuất vào assets/cards/<key>.png.
# Khay kính do launcher tự vẽ, nên ở đây chỉ có ô.
# Chạy: python tools/make_cards.py
import os, sys, importlib.util
from PIL import Image, ImageDraw

HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(HERE, "..", "assets", "cards")
CW, CH = 464, 290          # 16:10 như Apple TV; = 2 lần cỡ ô lúc được chọn (232x145), launcher tự thu nhỏ sạch
RADIUS = 0.12              # bo góc theo chiều cao ô

spec = importlib.util.spec_from_file_location("md", os.path.join(HERE, "make_dock.py"))
sys.argv = [sys.argv[0], os.path.join(HERE, "logos"), "30"]
md = importlib.util.module_from_spec(spec); spec.loader.exec_module(md)


def card(name):
    bg, txt = md.CARD[name]
    S = 4
    m = Image.new("L", (CW * S, CH * S), 0)
    ImageDraw.Draw(m).rounded_rectangle([0, 0, CW * S - 1, CH * S - 1], int(CH * S * RADIUS), fill=255)
    m = m.resize((CW, CH), Image.LANCZOS)
    im = Image.new("RGBA", (CW, CH), (0, 0, 0, 0))
    im.paste(Image.new("RGBA", (CW, CH), (*bg, 255)), (0, 0), m)
    logo = md.user_logo(name) or md.cut_logo(name)
    if txt:
        logo = md.recolor_light(logo.copy(), txt)
    s = min(CW * 0.70 / logo.width, CH * 0.52 / logo.height)
    lg = md.smooth_resize(logo, (max(1, int(logo.width * s)), max(1, int(logo.height * s))))
    im.alpha_composite(lg, ((CW - lg.width) // 2, (CH - lg.height) // 2))
    return im


if __name__ == "__main__":
    os.makedirs(OUT, exist_ok=True)
    for n in md.ORDER:
        card(n).save(os.path.join(OUT, f"{n}.png"))
        print(n)
