# Đồng bộ thư mục ảnh nền trên máy tính lên box (bộ ảnh chung bg/chung/ của Glass Home).
# Mỗi ảnh: bỏ nếu nhỏ hơn Full HD hoặc trùng nội dung, cắt giữa về 16:9 rồi thu về 1920x1080 (JPEG 90).
# Chỉ đẩy ảnh box chưa có; ảnh đã xoá khỏi thư mục máy tính thì xoá luôn trên box.
# python tools/sync_wallpapers.py [thư mục ảnh] [địa chỉ box] [bộ ảnh trên box]
#   ảnh nền dock:        python tools/sync_wallpapers.py "D:\Wallpaper"                         (bộ "chung")
#   ảnh màn hình chờ:    python tools/sync_wallpapers.py "D:\lock screen" 192.168.0.77:5555 khoa
import glob, hashlib, os, re, subprocess, sys, tempfile
from PIL import Image

SRC = sys.argv[1] if len(sys.argv) > 1 else r"D:\Wallpaper"
BOX = sys.argv[2] if len(sys.argv) > 2 else "192.168.0.77:5555"
SET = sys.argv[3] if len(sys.argv) > 3 else "chung"
DEST = "/sdcard/Android/data/vn.glass.home/files/bg/" + SET
ADB = os.path.join(os.environ["LOCALAPPDATA"], r"Microsoft\WinGet\Packages\Google.PlatformTools_Microsoft.Winget.Source_8wekyb3d8bbwe\platform-tools\adb.exe")
KEEP = set()   # chỉ ảnh chủ nhà cung cấp: box giữ đúng những gì có trong thư mục máy tính


def adb(*a):
    return subprocess.run([ADB, "-s", BOX, *a], capture_output=True, text=True, encoding="utf-8").stdout


def load_skip():
    # tools/wallpaper-skip.txt: ảnh chủ nhà không muốn lên TV (so theo tên file, bỏ đuôi)
    p = os.path.join(os.path.dirname(os.path.abspath(__file__)), "wallpaper-skip.txt")
    if not os.path.exists(p):
        return set()
    lines = (l.strip() for l in open(p, encoding="utf-8"))
    return {os.path.splitext(l)[0].lower() for l in lines if l and not l.startswith("#")}


SKIP = load_skip()


def out_name(path):
    stem = os.path.splitext(os.path.basename(path))[0]
    return re.sub(r"[^A-Za-z0-9._-]+", "_", stem).strip("_") + ".jpg"


adb("shell", f"mkdir -p {DEST}")
on_box = set(adb("shell", f"ls {DEST}").split())
seen, want, fresh, skipped = set(), set(), [], []
tmp = tempfile.mkdtemp()
for f in sorted(glob.glob(os.path.join(SRC, "*"))):
    if not f.lower().endswith((".jpg", ".jpeg", ".png", ".webp")):
        continue
    if os.path.splitext(os.path.basename(f))[0].lower() in SKIP:
        skipped.append(os.path.basename(f) + " (trong wallpaper-skip.txt)"); continue
    h = hashlib.md5(open(f, "rb").read()).hexdigest()
    if h in seen:
        skipped.append(os.path.basename(f) + " (trùng)"); continue
    seen.add(h)
    name = out_name(f)
    im = Image.open(f)
    w, hh = im.size
    if w < 1920 or hh < 1080:
        skipped.append(f"{os.path.basename(f)} (nhỏ {w}x{hh})"); continue
    want.add(name)
    if name in on_box:
        continue
    im = im.convert("RGB")
    t = min(w, hh * 16 // 9); th = t * 9 // 16
    im = im.crop(((w - t) // 2, (hh - th) // 2, (w - t) // 2 + t, (hh - th) // 2 + th)).resize((1920, 1080), Image.LANCZOS)
    im.save(os.path.join(tmp, name), quality=90)
    fresh.append(name)

for n in fresh:
    adb("push", os.path.join(tmp, n), f"{DEST}/{n}")
gone = sorted(n for n in on_box - want - KEEP if not n.startswith("dt-"))   # ảnh gửi từ điện thoại (dt-...) giữ nguyên
for n in gone:
    adb("shell", f"rm '{DEST}/{n}'")
print(f"mới: {len(fresh)}  xoá trên box: {len(gone)}  tổng trên box: {len(want) + len(KEEP & on_box)}")
for n in fresh: print("  +", n)
for n in gone: print("  -", n)
for s in skipped: print("  bỏ:", s)
