# Glass Home

Launcher Android TV kiểu Apple TV: kính mờ, chuyển động mượt, chữ dịu mắt. Làm cho Xiaomi TV Box S Gen 3
(Google TV 14, 1920x1080), giao diện tiếng Việt. Không dùng Gradle, build bằng một script PowerShell.

## Có gì

- **Dock app** trên khay kính mờ: ô đang chọn nổi lên, có quầng sáng "thở" nhẹ. App mới cài tự thêm vào
  dock (trước Cửa hàng Play); dock dài hơn màn hình thì trượt ngang.
- **Hàng nội dung**: bấm xuống từ dock để xem hàng "Xem tiếp" và gợi ý của đúng app đang chọn (đọc từ
  TvProvider). Phim từng thấy được nhớ lại (tối đa 8 mỗi app), nên hàng Netflix dần đủ ô. App không đăng
  nội dung thì hàng để trống.
- **Ảnh nền** kèm sẵn 16 ảnh phong cảnh (Unsplash), hoặc ảnh của bạn gửi từ điện thoại, hoặc trộn cả hai; đổi ảnh
  mỗi 10 phút (chỉnh được). Lớp phủ tự đậm lên khi ảnh sáng để chữ và logo không chói.
- **Ô giá** góc trên phải: BTC, ETH (Binance) và vàng (PNJ / BTMC), màu xanh đỏ theo biến động 24 giờ.
- **Lịch Ngoại hạng Anh** của vòng đang đá: giờ đá, tỉ số, phút thi đấu khi đang đá (cập nhật mỗi phút
  lúc có trận). Vòng đá xong vẫn giữ tới hết ngày hôm sau rồi mới sang vòng kế. Cứ 1 phút lịch thì 30 giây
  hiện bảng xếp hạng, có mũi tên lên/xuống so với trước vòng gần nhất. Đội yêu thích (mặc định Man United)
  được làm nổi bật ở cả hai.
- **Màn hình chờ** sau 5 phút để yên ở Home: 6 ảnh kèm sẵn hoặc ảnh của bạn (đổi mỗi 15 phút), giờ, ngày dương, ngày âm lịch;
  góc phải xoay vòng đếm lùi tới Tết (từ tháng 9 âm lịch) và giá BTC/ETH. Chữ dời nhẹ mỗi 3 phút để tránh
  lưu ảnh trên màn hình.
- **Cài đặt** (nút bánh răng góc trên trái): Ảnh nền Home, Màn hình chờ, Widget (bật/tắt ô giá, lịch bóng đá,
  chọn đội yêu thích) và Cài đặt hệ thống. Bấm giữ nút bánh răng để mở thẳng Cài đặt của Google TV.
- **Gửi ảnh từ điện thoại**: trong Ảnh nền Home hoặc Màn hình chờ, chọn "Thêm ảnh từ điện thoại", quét mã QR
  trên tivi rồi chọn ảnh. Điện thoại phải cùng mạng Wi-Fi với tivi; trang gửi ảnh chỉ mở khi hộp đó đang hiện.
- **Ô Apps** cuối dock: bảng tất cả ứng dụng (có Cửa hàng Play). App cài sau nằm ở đây, không tự lên dock. Chọn ô Apps
  thì hàng bên dưới hiện các app mở gần nhất. Phím Menu cũng mở bảng này.
- **Game giả lập trong bảng Apps** (nhóm Trò chơi): mỗi game là một cặp file trong
  `Android/data/vn.glass.home/files/games/` gồm `<tên>.json` (`title`, `pkg` app giả lập, `activity`, `uri` file game,
  `bg` ảnh nền, `thumbs`) và `<tên>.png` (ảnh ô 464x290). Bấm ô là app giả lập (vd PPSSPP) mở thẳng game.
  Với `"pkg": "com.retroarch"` (RetroArch bản Cửa hàng Play), core chọn theo đuôi file (`.sfc`/`.smc` Snes9x,
  `.nes`, `.gba`, `.gb`, `.md`); file game phải nằm trong `Android/media/com.retroarch/`.

## Tải về

Hướng dẫn cài từng bước cho người không rành kỹ thuật: [docs/huong-dan-cai-dat.pdf](docs/huong-dan-cai-dat.pdf).

Bản cài sẵn nằm trong thư mục [`release/`](release):
- [`glass-home.apk`](release/glass-home.apk): launcher Glass Home.
- [`doi-anh-nen.apk`](release/doi-anh-nen.apk): app phụ "Đổi ảnh nền" (tuỳ chọn, để gán nút APPS bằng Button Mapper).

Cài bằng `adb install -r glass-home.apk` rồi làm tiếp phần "Cài lên box" bên dưới. Không cần build nếu dùng bản này.

## Build

Cần Windows, JDK 17 (Microsoft OpenJDK, cài ở `C:\Program Files\Microsoft\jdk-17*`) và Android SDK
có build-tools 34 + platforms android-34 ở `%LOCALAPPDATA%\Android\Sdk`. Ảnh ô app làm bằng Python 3 + Pillow.

```powershell
python tools/make_cards.py     # ảnh ô app từ tools/logos
python tools/make_extras.py    # ô Cài đặt + banner
./build.ps1                    # -> build/glass-home.apk
```

Lần build đầu `build.ps1` tự tạo khoá ký `glass-home.keystore`. Giữ file này lại: cập nhật đè lên bản đã
cài cần đúng khoá cũ (khoá không đưa lên git).

Đổi danh sách app trên dock: `src/vn/glass/home/Apps.java`, ảnh ô app ở `assets/cards` (tạo từ `tools/make_dock.py`).

## Cài lên box

Bật Gỡ lỗi qua mạng trên box (Tuỳ chọn cho nhà phát triển), rồi từ máy tính:

```powershell
adb connect <ip-box>:5555
adb install -r build/glass-home.apk
adb shell pm grant vn.glass.home android.permission.READ_TV_LISTINGS
adb shell cmd package set-home-activity vn.glass.home/.MainActivity
```

Nếu nút Home vẫn về launcher cũ, tắt dịch vụ trợ năng của launcher đó (ví dụ Projectivy) và tắt launcher gốc
của Google TV. Cài xong nhớ tắt lại Gỡ lỗi qua mạng.

## Ảnh nền và ảnh màn hình chờ

Cách dễ nhất là gửi từ điện thoại trong Cài đặt (xem trên). Ai quen dùng máy tính có thể đồng bộ cả thư mục
bằng script; ảnh gửi từ điện thoại (tên bắt đầu `dt-`) không bị script xoá. Script cắt giữa về 16:9, thu về 1920x1080, bỏ ảnh nhỏ hơn Full HD
hoặc trùng nhau, chỉ đẩy ảnh mới và xoá trên box ảnh đã xoá khỏi thư mục máy tính.

```powershell
python tools/sync_wallpapers.py "D:\Wallpaper"    <ip-box>:5555 chung   # ảnh nền Home
python tools/sync_wallpapers.py "D:\lock screen" <ip-box>:5555 khoa    # ảnh màn hình chờ
```

Tên ảnh muốn bỏ qua (dù vẫn nằm trong thư mục) ghi vào `tools/wallpaper-skip.txt`, mỗi dòng một phần tên.
Ảnh nằm trên box ở `/sdcard/Android/data/vn.glass.home/files/bg/chung` và `.../bg/khoa`.

## Nút trên remote

Google TV giữ riêng nhiều nút (APPS, YouTube, Netflix...), launcher không bắt được. Dùng app
[Button Mapper](https://play.google.com/store/apps/details?id=flar2.homebutton) để gán:

- **APPS** → app phụ "Đổi ảnh nền" (thư mục `helper/`): sang ảnh nền kế tiếp.
- Các nút app khác → mở thẳng app bạn muốn.

```powershell
./helper/build.ps1
adb install -r helper/build/allapps.apk
```

## Lệnh tiện thử

```powershell
adb shell am start -a vn.glass.home.SAVER       # hiện màn hình chờ ngay
adb shell am start -a vn.glass.home.NEXT_BG     # sang ảnh nền kế tiếp
adb shell am start -a vn.glass.home.EPL_DEMO    # xem thử thẻ bóng đá lúc đang đá / đá xong (60 giây)
adb logcat -s GlassShelf GlassImg               # soi nội dung các app đăng lên và lỗi tải ảnh
```

## Lưu ý

- Dữ liệu lấy từ API công khai không chính thức (Fantasy Premier League, Binance, PNJ, BTMC); bên đó đổi
  hoặc chặn thì ô tương ứng có thể ngừng cập nhật, phần còn lại vẫn chạy.
- Bố cục tính cho màn 1920x1080 và danh sách app Việt Nam; máy khác có thể cần chỉnh.
- Logo và tên các app thuộc về chủ sở hữu tương ứng, chỉ dùng để đánh dấu ô app trên dock.

## Giấy phép

Mã nguồn theo giấy phép [MIT](LICENSE). Font Inter theo SIL Open Font License (`assets/fonts/OFL.txt`).
