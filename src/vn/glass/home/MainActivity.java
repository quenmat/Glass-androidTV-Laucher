package vn.glass.home;

import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;
import android.widget.FrameLayout;
import android.widget.GridLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.File;
import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Executors;

// Màn hình chính kiểu Apple TV:
// - ảnh nền tràn màn hình, đổi theo app đang chọn (ảnh nội dung app đẩy lên, ảnh riêng, hoặc màu)
// - khay kính đứng yên, ô app nổi lên + quầng sáng theo màu logo khi chọn
// - bấm lên: kệ nội dung của app (tối đa 5 mục), bấm xuống quay lại dock
public class MainActivity extends Activity {

    // bố cục thiết kế cho 1920x1080, nhân hệ số k nếu màn hình khác
    private float k;
    private int px(float v) { return Math.round(v * k); }

    // 6 ô: khay vẫn rộng 1842 như bản 8 ô, mỗi ô to ra (6 x 272 + 5 x 30 + 60)
    private static final int CARD_W = 272, CARD_H = 170, CARD_GAP = 30, TRAY_PAD_X = 30, TRAY_PAD_Y = 26;
    private static final float CARD_R = 20;
    // Ô đang chọn phóng tới đúng 300x188 điểm ảnh (~110%; ngang/dọc lệch nhau 0,3%, mắt không thấy): ảnh logo dựng
    // sẵn đúng cỡ này hiển thị 1:1 với điểm ảnh màn hình nên sắc nét, mép ô và lỗ khoét của quầng nằm trên toạ độ
    // nguyên nên khít nhau từng điểm ảnh, kể cả góc bo (cùng độ bo 12% chiều cao như ảnh ô).
    private static final int SEL_W = 300, SEL_H = 188;
    private static final float SCALE_X = SEL_W / (float) CARD_W, SCALE_Y = SEL_H / (float) CARD_H;
    private static final float SEL_R = SEL_H * 0.12f;
    // dock + hàng nội dung dồn xuống dưới để ảnh nền hở rộng
    private static final int TRAY_TOP = 614;
    // bố cục (đồng hồ, ô giá, hàng nội dung) theo khay 6 ô; cài thêm app thì khay dài ra về bên phải, tràn khỏi màn hình
    private static final int BASE_N = 6;
    private static int trayW(int n) { return n * CARD_W + (n - 1) * CARD_GAP + TRAY_PAD_X * 2; }
    private static final int TRAY_LEFT = (1920 - trayW(BASE_N)) / 2;
    // hàng nội dung: 5 ô 16:9, bề ngang tính theo dock (xem buildShelf)
    private static final int SHELF_N = 5, THUMB_GAP = 30, ROW_TOP = 872;   // đáy hàng ~1059: sát mép dưới theo ý chủ nhà, lấn nhẹ vùng an toàn 5%
    private final int[] thumbSize = new int[2];
    private static final float ROW_IDLE_ALPHA = 0.82f, THUMB_R = 12;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private Images images;
    private Typeface regular, semibold;
    private List<Apps.App> apps;
    private Map<String, List<Shelf.Item>> shelf = Collections.emptyMap();

    private FrameLayout root;
    private ImageView bgFront, bgBack;
    private String bgShown;
    private View scrim;
    private TextView clock, rowHead;
    private List<Shelf.Item> rowItems = Collections.emptyList();
    private String rowKey;
    private int shelfVersion;
    private LinearLayout tray, shelfRow;
    private final List<FrameLayout> cards = new ArrayList<>();
    private final List<FrameLayout> thumbs = new ArrayList<>();
    private FrameLayout allApps;
    private int focused;
    private boolean inShelf;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        k = getResources().getDisplayMetrics().widthPixels / 1920f;
        images = new Images(this);
        // font cho tiêu đề hàng (2 phần cùng cỡ 30): đúng opsz của cỡ đó
        regular = fontFor(400, 30); semibold = fontFor(600, 30);
        apps = Apps.dock(this);
        focused = prefs().getInt("focused", 0);
        if (focused >= apps.size()) focused = 0;
        buildUi();
        setContentView(root);
        watchTvProvider(true);
        watchNetwork(true);
        // mở bằng nút APPS khi launcher chưa chạy: dựng xong giao diện rồi sang ảnh kế tiếp
        String act = getIntent() == null ? null : getIntent().getAction();
        if (isAppsButton(act)) root.post(this::nextBackground);
        hideSystemBars();
    }

    // một luồng nền dùng chung (đọc nội dung, làm nhoè kính); không tạo luồng mới mỗi lần
    private final java.util.concurrent.ExecutorService bg = Executors.newSingleThreadExecutor();

    /**
     * Rời màn hình chính (đang xem phim...): nhả ảnh tải sẵn và các nền màu vẽ sẵn để app đang chạy đủ RAM.
     * Box chỉ 2GB, khi thiếu RAM hệ thống phải đẩy ra swap liên tục và video dễ khựng. Quay về thì tự tải lại.
     */
    @Override
    public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        if (level >= TRIM_MEMORY_UI_HIDDEN) {
            images.trim();
            colorBgs.evictAll();
        }
    }

    @Override
    protected void onDestroy() {
        watchTvProvider(false);
        watchNetwork(false);
        bg.shutdownNow();
        net.shutdownNow();
        super.onDestroy();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // vừa cài/gỡ app (ví dụ từ Play Store quay về): dựng lại dock để ô mới hiện ngay trước Play Store
        List<Apps.App> now = Apps.dock(this);
        boolean same = now.size() == apps.size();
        for (int i = 0; same && i < now.size(); i++) same = now.get(i).pkg.equals(apps.get(i).pkg);
        if (!same) { recreate(); return; }
        hideSystemBars();
        // xin quyền đọc nội dung một lần; bị từ chối thì thôi, không hỏi lại mỗi lần quay về
        if (checkSelfPermission("android.permission.READ_TV_LISTINGS") != PackageManager.PERMISSION_GRANTED
                && !prefs().getBoolean("asked_tv", false)) {
            prefs().edit().putBoolean("asked_tv", true).apply();
            requestPermissions(new String[]{"android.permission.READ_TV_LISTINGS"}, 1);
        }
        // quay về từ nội dung đang xem: về dock gọn gàng (hàng có thể đã đổi)
        if (inShelf) exitShelf();
        refreshShelf();
        if (allApps == null) {
            FrameLayout c = cards.get(focused);
            if (c.hasFocus()) { if (glowCur >= 0) startBreathing(glowViews[glowCur]); }
            else c.requestFocus();
        }
        ui.removeCallbacks(clockTick);
        ui.post(clockTick);
        // giá chỉ cập nhật khi màn hình chính đang hiện
        applyWidgets();
        ui.removeCallbacks(eplCycle);
        if (eplView.mode() != EplView.COMPACT)
            ui.postDelayed(eplCycle, eplView.mode() == EplView.TABLE ? EPL_TABLE_MS : EPL_FIXTURES_MS);
        ui.removeCallbacks(bgRotate); ui.postDelayed(bgRotate, bgRotateMs());
        armSaver();   // đứng yên ở màn hình chính 5 phút thì hiện màn hình chờ
        ui.removeCallbacks(showHomePrompt);
        if (needsHomePrompt()) ui.postDelayed(showHomePrompt, 1200);
    }

    @Override
    protected void onPause() {
        super.onPause();
        // không chạy hiệu ứng lặp và đồng hồ khi đang xem app khác
        if (breathe != null) { breathe.cancel(); breathe = null; }
        ui.removeCallbacks(clockTick);
        ui.removeCallbacks(pendingBg);
        ui.removeCallbacks(btcTick);   // rời launcher: ngừng gọi mạng lấy giá
        ui.removeCallbacks(goldTick);
        ui.removeCallbacks(eplTick);
        ui.removeCallbacks(eplCycle);
        ui.removeCallbacks(bgRotate);
        ui.removeCallbacks(showSaverLater);
        hideSaver(false);
        if (settings != null) settings.close(false);   // rời màn hình chính: đóng bảng, tắt máy chủ nhận ảnh
        ui.removeCallbacks(showHomePrompt);
        if (homePrompt != null) { root.removeView(homePrompt); homePrompt = null;
            tray.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
            shelfRow.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
            gear.setFocusable(true); }
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] res) { refreshShelf(); }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        // nút Home khi đang ở launcher: đóng mọi lớp phủ, về dock
        String act = intent.getAction();
        // xem trước màn hình chờ từ máy tính (am start -a vn.glass.home.SAVER -n vn.glass.home/.MainActivity)
        if ("vn.glass.home.SAVER".equals(act)) { ui.post(this::showSaver); return; }
        // xem thử lịch thi đấu khi có trận đang đá / đã xong (am start -a vn.glass.home.EPL_DEMO ...), 1 phút rồi về thật
        if ("vn.glass.home.EPL_DEMO".equals(act)) { eplDemo(); return; }
        hideSaver(false);   // nút Home, APPS...: luôn đánh thức khỏi màn hình chờ
        armSaver();
        // nút APPS trên điều khiển (qua app phụ "Tất cả ứng dụng" hoặc Button Mapper): sang ảnh nền kế tiếp
        // lệnh từ máy tính: nhảy tới đúng một ảnh theo tên (am start -a vn.glass.home.NEXT_BG --es file <tên>)
        if ("vn.glass.home.NEXT_BG".equals(act) && intent.getStringExtra("file") != null) { showWallpaper(intent.getStringExtra("file")); return; }
        if (isAppsButton(act) || "vn.glass.home.NEXT_BG".equals(act)) { nextBackground(); return; }
        closeAllApps();
        if (settings != null) settings.close(true);
        if (inShelf) exitShelf();
    }

    // ---------- giao diện ----------

    private void buildUi() {
        root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        root.setClipChildren(false);

        bgBack = fullImage(); bgFront = fullImage();
        root.addView(bgBack); root.addView(bgFront);
        tint = new View(this);
        tint.setBackgroundColor(tintColor);
        root.addView(tint, new FrameLayout.LayoutParams(-1, -1));

        // làm tối dưới đáy và bên trái để dock, chữ luôn đọc được, phần trên giữ trọn ảnh
        scrim = new View(this);
        // vẽ sẵn có rải nhiễu: lớp tối dần vẽ 8-bit trơn sẽ lộ từng bậc trên ảnh tối
        scrim.setBackground(new android.graphics.drawable.BitmapDrawable(getResources(),
                Dither.fadeUp(getResources().getDisplayMetrics().widthPixels, px(620), new int[]{0xE6, 0x99, 0x00})));
        root.addView(scrim, new FrameLayout.LayoutParams(-1, px(620), Gravity.BOTTOM));
        // tối dần ở hai mép trái phải: ảnh nền gom về giữa, mép màn hình không chói
        // hai dải mép trái/phải (không phủ cả màn hình: phần giữa trong suốt mà vẫn tốn công vẽ mỗi khung)
        int dsw = getResources().getDisplayMetrics().widthPixels, dsh = getResources().getDisplayMetrics().heightPixels;
        Bitmap sideBmp = Dither.fadeSides(dsw, dsh, 0x99, 0.22f);
        int band = Math.round(dsw * 0.22f) + 2;
        for (int side = 0; side < 2; side++) {
            View v = new View(this);
            Bitmap part = Bitmap.createBitmap(sideBmp, side == 0 ? 0 : dsw - band, 0, band, dsh);
            v.setBackground(new android.graphics.drawable.BitmapDrawable(getResources(), part));
            root.addView(v, new FrameLayout.LayoutParams(band, -1, side == 0 ? Gravity.START : Gravity.END));
        }
        sideBmp.recycle();

        clock = text(600, 34, 0xD1FFFFFF);
        clock.setShadowLayer(px(4), 0, px(1), 0x40000000);   // đồng hồ nằm trên ảnh sáng: bóng rất mỏng, rất nhạt
        FrameLayout.LayoutParams lc = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.END);
        // đồng hồ thẳng mép phải khay dock
        int trayWDesign = trayW(BASE_N);
        lc.setMargins(0, px(46), px((1920 - trayWDesign) / 2f), 0);
        root.addView(clock, lc);
        buildGear((1920 - trayWDesign) / 2f);

        buildTicker();       // cột giá BTC, ETH, vàng dưới đồng hồ, cùng chất kính với dock
        buildEpl();          // thẻ lịch Ngoại hạng Anh bên trái, kính đậm
        buildShelf();
        buildFrost();        // kính mờ: ảnh nền nhoè, cắt theo khay
        buildTray();
        buildGlowLayer();    // quầng sáng nằm trên dock (đã khoét hình ô): toả đều 4 phía, không bị ô bên cạnh che
        setLift(px(DOCK_DROP), false);   // mặc định đứng ở dock: cả cụm hạ thấp
    }

    // ---------- nút Cài đặt: bánh răng tròn ở góc trên trái, thẳng mép trái khay ----------

    private static final int GEAR = 60;
    private FrameLayout gear;

    private void buildGear(float left) {
        gear = new FrameLayout(this);
        gear.setFocusable(true);
        gear.setFocusableInTouchMode(true);
        GradientDrawable dot = new GradientDrawable();
        dot.setShape(GradientDrawable.OVAL);
        dot.setColor(0x47000000);   // cùng độ tối với kính ô giá
        gear.setBackground(dot);
        ImageView ic = new ImageView(this);
        ic.setImageBitmap(scaleHQ(asset("icons/gear.png"), px(GEAR * 0.56f * 1.15f), px(GEAR * 0.56f * 1.15f)));
        ic.setColorFilter(0xF0FFFFFF);
        FrameLayout.LayoutParams li = new FrameLayout.LayoutParams(px(GEAR * 0.56f), px(GEAR * 0.56f), Gravity.CENTER);
        gear.addView(ic, li);
        // chọn: như nút Apple TV, nền trắng đặc, biểu tượng tối, nổi nhẹ
        gear.setOnFocusChangeListener((v, has) -> {
            dimGlow(has);
            ValueAnimator c = ValueAnimator.ofArgb(has ? 0x47000000 : 0xF2FFFFFF, has ? 0xF2FFFFFF : 0x47000000);
            c.setDuration(220);
            c.addUpdateListener(an -> dot.setColor((int) an.getAnimatedValue()));
            c.start();
            ic.setColorFilter(has ? 0xFF1C1C1E : 0xF0FFFFFF);
            gear.animate().scaleX(has ? 1.15f : 1f).scaleY(has ? 1.15f : 1f).setInterpolator(EASE).setDuration(260).start();
        });
        gear.setOnClickListener(v -> openSettings());
        gear.setOnLongClickListener(v -> { openSystemSettings(); return true; });
        FrameLayout.LayoutParams lg = new FrameLayout.LayoutParams(px(GEAR), px(GEAR), Gravity.TOP | Gravity.START);
        lg.setMargins(px(left), px(36), 0, 0);   // giữa theo chiều dọc với đồng hồ
        root.addView(gear, lg);
    }

    private void buildTray() {
        int n = apps.size();
        int trayW = trayW(n);
        int trayH = CARD_H + TRAY_PAD_Y * 2;

        tray = new LinearLayout(this);
        tray.setOrientation(LinearLayout.HORIZONTAL);
        tray.setGravity(Gravity.CENTER_VERTICAL);
        tray.setPadding(px(TRAY_PAD_X), 0, px(TRAY_PAD_X), 0);
        tray.setClipChildren(false); tray.setClipToPadding(false);
        // lớp kính phía trên lớp nhoè: chỉ một viền rất mảnh và nhạt
        // kính, viền và bo góc do FrostView vẽ (khử răng cưa); khay chỉ còn xếp các ô
        // neo mép trái (không căn giữa): thêm app thì khay chỉ dài ra về bên phải
        FrameLayout.LayoutParams lt = new FrameLayout.LayoutParams(px(trayW), px(trayH), Gravity.TOP | Gravity.START);
        lt.topMargin = px(TRAY_TOP);
        lt.leftMargin = px(TRAY_LEFT);
        root.addView(tray, lt);

        for (int i = 0; i < n; i++) {
            Apps.App a = apps.get(i);
            FrameLayout cell = card(a, i);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(px(CARD_W), px(CARD_H));
            if (i > 0) lp.leftMargin = px(CARD_GAP);
            tray.addView(cell, lp);
            cards.add(cell);
        }
    }

    private FrameLayout card(Apps.App a, int index) {
        FrameLayout cell = new FrameLayout(this);
        cell.setClipChildren(false);
        cell.setFocusable(true);
        cell.setFocusableInTouchMode(true);

        // thu về đúng cỡ ô lúc được chọn để khi phóng to, ảnh khớp 1:1 với điểm ảnh màn hình
        Bitmap art = a.key != null
                ? scaleHQ(asset("cards/" + a.key + ".png"), px(SEL_W), px(SEL_H))
                : appCard(a.pkg, px(SEL_W), px(SEL_H));
        int glowColor = a.isSettings() ? Color.WHITE : Glow.dominant(art);
        // quầng theo đúng ô lúc đang chọn (300x188), kể cả độ bo góc
        glowBitmaps.add(Glow.make(px(SEL_W), px(SEL_H), px(SEL_R), px(GLOW_SPREAD), glowColor));

        // góc bo đã vẽ mịn sẵn trong ảnh ô. Không đổ bóng: ô nổi lên đã có quầng sáng, còn bóng thì
        // trên kính dock sáng lộ thành vệt xám đen ở hai góc dưới. translationZ vẫn giữ để ô chọn nằm trên ô bên.
        ImageView img = new ImageView(this);
        img.setImageBitmap(art);
        img.setScaleType(ImageView.ScaleType.FIT_XY);
        img.setOutlineProvider(null);
        cell.addView(img, new FrameLayout.LayoutParams(-1, -1));

        // ánh sáng nhẹ trên mặt ô khi chọn (GradientDrawable bo góc tự khử răng cưa)
        View sheen = new View(this);
        GradientDrawable sh = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{0x40FFFFFF, 0x0DFFFFFF, 0x00FFFFFF});
        sh.setCornerRadius(px(CARD_R));
        sheen.setBackground(sh);
        sheen.setOutlineProvider(null);
        sheen.setAlpha(0f);
        cell.addView(sheen, new FrameLayout.LayoutParams(-1, -1));

        cell.setOnFocusChangeListener((v, has) -> {
            lift(img, sheen, has);
            if (has) { shelfState(false); onAppFocused(index); shiftDock(index); moveGlow(index); }
        });
        // nhấn OK: lún xuống ngay (chưa cần thả tay) để biết lệnh đã nhận
        cell.setOnKeyListener((v, kc, ev) -> {
            if (isOk(kc) && ev.getAction() == KeyEvent.ACTION_DOWN && ev.getRepeatCount() == 0) press(img, sheen);
            return false;
        });
        // thả tay: bật lại + quầng loé, mở app ngay, cửa sổ app phóng ra từ chính ô này.
        // âm thanh bấm/di chuyển: Android tự phát âm hệ thống (như menu Google TV), không đóng gói file nào
        cell.setOnClickListener(v -> { release(img, sheen); flashGlow(); launch(a, cell); });
        return cell;
    }

    private static boolean isOk(int kc) {
        return kc == KeyEvent.KEYCODE_DPAD_CENTER || kc == KeyEvent.KEYCODE_ENTER || kc == KeyEvent.KEYCODE_NUMPAD_ENTER;
    }

    /** Lún xuống như phím thật: thu từ 116% về ~107%, bóng ngắn lại. */
    private void press(View img, View sheen) {
        for (View v : new View[]{img, sheen}) {
            v.animate().cancel();
            v.animate().scaleX(1.07f).scaleY(1.07f).translationZ(px(10)).setInterpolator(EASE).setDuration(110).start();
        }
    }

    /** Bật nảy trở lại cỡ đang chọn. */
    private void release(View img, View sheen) {
        for (View v : new View[]{img, sheen}) {
            v.animate().cancel();
            v.animate().scaleX(SCALE_X).scaleY(SCALE_Y).translationZ(px(30))
                    .setInterpolator(new OvershootInterpolator(2.2f)).setDuration(260).start();
        }
    }

    /** Quầng sáng loé lên một nhịp rồi trở lại "thở". */
    private void flashGlow() {
        if (glowCur < 0) return;
        if (breathe != null) { breathe.cancel(); breathe = null; }
        ImageView g = glowViews[glowCur];
        g.animate().cancel();
        // loé sáng bằng độ sáng (không phình to: lỗ khoét phải khít mép ô)
        g.animate().alpha(1f).setDuration(140).setInterpolator(EASE)
                .withEndAction(() -> g.animate().alpha(GLOW_MOVING).setDuration(420).setInterpolator(EASE)
                        .withEndAction(() -> startBreathing(g)).start()).start();
    }

    /** Cửa sổ app phóng ra từ vị trí ô (Android hiện ngay màn hình chờ của app trong lúc app khởi động). */
    private android.os.Bundle zoomFrom(View src) {
        return src == null ? null : android.app.ActivityOptions
                .makeScaleUpAnimation(src, 0, 0, src.getWidth(), src.getHeight()).toBundle();
    }

    // đường cong "chậm dần mềm" dùng chung cho mọi chuyển động để cả bộ đồng nhất
    private static final android.view.animation.Interpolator EASE =
            new android.view.animation.PathInterpolator(0.2f, 0f, 0f, 1f);

    /** Chọn: ô nổi lên đều từ tâm, nảy rất nhẹ, bóng sâu dần. Bỏ chọn: chìm dần về khay, không nảy. */
    private void lift(View img, View sheen, boolean on) {
        float sx = on ? SCALE_X : 1f, sy = on ? SCALE_Y : 1f;
        img.animate().cancel(); sheen.animate().cancel();
        android.view.animation.Interpolator in = on ? new OvershootInterpolator(0.8f) : EASE;
        img.animate().scaleX(sx).scaleY(sy).translationZ(on ? px(30) : 0)
                .setInterpolator(in).setDuration(on ? 360 : 420).start();
        sheen.animate().scaleX(sx).scaleY(sy).translationZ(on ? px(31) : 0).alpha(on ? 1f : 0f)
                .setInterpolator(in).setDuration(on ? 360 : 380).start();
    }

    // ---------- kính mờ: ảnh nền nhoè mạnh, cắt đúng hình khay, bo góc khử răng cưa ----------

    private static final float TRAY_RADIUS = CARD_R + TRAY_PAD_Y * 0.9f;
    private FrostView frost;

    private void buildFrost() {
        int trayW = px(trayW(apps.size())), trayH = px(CARD_H + TRAY_PAD_Y * 2);
        int sw = getResources().getDisplayMetrics().widthPixels, sh = getResources().getDisplayMetrics().heightPixels;
        // cùng chất kính tối với ô giá và thẻ lịch (đen mờ, đậm dần trên nền sáng), không trắng sữa
        frost = new FrostView(this, px(TRAY_RADIUS), sw, sh, px(TRAY_LEFT), px(TRAY_TOP), Math.max(1, px(1))).dark();
        FrameLayout.LayoutParams lf = new FrameLayout.LayoutParams(trayW, trayH, Gravity.TOP | Gravity.START);
        lf.topMargin = px(TRAY_TOP);
        lf.leftMargin = px(TRAY_LEFT);
        root.addView(frost, lf);
    }

    /** Kính mờ đổi theo ảnh nền (làm nhoè ở luồng nền), hoà dần cùng lúc với nền. */
    private void updateFrost(Bitmap bmp, int color) {
        if (bmp == null) {
            Bitmap c = Bitmap.createBitmap(16, 9, Bitmap.Config.ARGB_8888);
            c.eraseColor(color);
            lastBlur = c;
            frost.setImage(c);
            for (FrostView f : tickFrost) if (f != null) f.setImage(c);
            if (eplFrost != null) eplFrost.setImage(c);
            return;
        }
        bg.execute(() -> {
            Bitmap b = FrostView.blur(bmp);
            ui.post(() -> {
                lastBlur = b;
                frost.setImage(b);
                for (FrostView f : tickFrost) if (f != null) f.setImage(b);
                if (eplFrost != null) eplFrost.setImage(b);
            });
        });
    }

    // ---------- ô giá BTC + vàng (chỉ để theo dõi) ----------

    private static final int TICK_W = 236, TICK_H = 96, TICK_GAP = 18, TICK_PAD = 20, TICK_TOP = 116;
    // bản sáng của xanh/đỏ hệ thống, đủ tương phản trên kính tối
    // dịu mắt: xanh/đỏ ngả pastel, không đặc 100%; trắng ~78% (xanh/đỏ/trắng bão hoà nhìn lâu bị chói)
    private static final int GREEN = 0xD97FD99C, RED = 0xD9F59A90, WHITE = 0xC7FFFFFF;
    private static final long BTC_EVERY = 3000, GOLD_EVERY = 10 * 60 * 1000;
    // cột giá từ trên xuống: BTC, ETH (cùng thứ tự Prices.COINS), rồi Vàng
    private static final int GOLD = 2, TICKS = 3;
    private final FrostView[] tickFrost = new FrostView[TICKS];
    private final View[] tickTile = new View[TICKS];
    private final TextView[] tickExtra = new TextView[TICKS], tickPrice = new TextView[TICKS];
    private long btcOkAt, goldOkAt;
    private final double[] lastCoin = {-1, -1};

    // ---------- lịch Ngoại hạng Anh: thẻ kính đậm bên trái, dưới nút Cài đặt ----------

    private static final int EPL_X = 40, EPL_TOP = 116;
    private FrameLayout eplBox;
    private FrostView eplFrost;
    private EplView eplView;
    private final java.util.concurrent.atomic.AtomicBoolean eplBusy = new java.util.concurrent.atomic.AtomicBoolean();
    private long eplFailMs;   // lỗi mạng liên tiếp: giãn nhịp gọi (1 -> 2 -> 4... tối đa 15 phút)

    private void buildEpl() {
        int sw = getResources().getDisplayMetrics().widthPixels, sh = getResources().getDisplayMetrics().heightPixels;
        eplBox = new FrameLayout(this);
        eplBox.setFocusable(false);
        eplBox.setVisibility(View.GONE);   // hiện khi có dữ liệu
        eplFrost = new FrostView(this, px(20), sw, sh, px(EPL_X), px(EPL_TOP), Math.max(1, px(1))).deep();
        eplBox.addView(eplFrost, new FrameLayout.LayoutParams(-1, -1));
        Epl.badgePx = px(28);
        eplView = new EplView(this, px(1000) / 1000f, fontFor(600, 17), fontFor(500, 20));
        eplView.setFavorite(Wallpapers.favTeam(this));
        // lượt đầy đủ chỉ chạy một lần mỗi lần box khởi động; về sau (kể cả khi hệ thống dọn launcher để nhường RAM
        // cho app đang xem rồi mở lại) thì vào thẳng thẻ gọn
        long boot = (System.currentTimeMillis() - android.os.SystemClock.elapsedRealtime()) / 60_000L;
        if (Math.abs(prefs().getLong("epl_full_boot", -10) - boot) <= 1) eplView.setMode(EplView.COMPACT);
        else prefs().edit().putLong("epl_full_boot", boot).apply();
        eplBox.addView(eplView, new FrameLayout.LayoutParams(-2, -2));
        View ring = new View(this);
        eplRing = ring;
        GradientDrawable rb = new GradientDrawable();
        rb.setCornerRadius(px(20));
        rb.setStroke(Math.max(2, px(3)), 0xD9FFFFFF);
        ring.setBackground(rb);
        ring.setAlpha(0f);
        eplBox.addView(ring, new FrameLayout.LayoutParams(-1, -1));
        // chỉ cho chọn khi đi từ nút bánh răng xuống (xem dispatchKeyEvent): bảng Cài đặt, hộp nhắc không lỡ trỏ vào
        eplBox.setOnFocusChangeListener((v, has) -> {
            if (!has) {
                ring.animate().alpha(0f).setDuration(200).setInterpolator(EASE).start();
                eplView.setSelected(EplView.SEL_NONE);
                eplBox.setFocusable(false);
            }
            dimGlow(has);
        });
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.START);
        lp.leftMargin = px(EPL_X); lp.topMargin = px(EPL_TOP);
        root.addView(eplBox, lp);
    }

    /**
     * Lấy lịch/tỉ số. Nhịp: có trận đang đá (hoặc vừa tới giờ đá) -> mỗi phút; không thì chờ tới đúng giờ bóng lăn
     * của trận kế tiếp, tối đa 3 tiếng. Chỉ chạy khi đang ở màn hình chính (dừng ở onPause). Lỗi mạng thì giãn nhịp.
     */
    private final Runnable eplTick = new Runnable() {
        @Override public void run() {
            long demoLeft = eplDemoUntil - android.os.SystemClock.uptimeMillis();
            if (demoLeft > 1000) { ui.removeCallbacks(this); ui.postDelayed(this, demoLeft); return; }
            if (!eplBusy.compareAndSet(false, true)) return;
            net.execute(() -> {
                Epl.Data d = Epl.load(MainActivity.this);
                eplBusy.set(false);
                ui.post(() -> {
                    long now = System.currentTimeMillis(), wait;
                    if (d == null || d.matches.isEmpty()) {
                        eplFailMs = Math.min(15 * 60_000L, eplFailMs == 0 ? 60_000L : eplFailMs * 2);
                        wait = eplFailMs;
                    } else {
                        eplFailMs = 0;
                        eplView.setData(d);
                        // khung kính đúng cỡ thẻ lịch (kính tự đo thì chiếm cả màn hình)
                        ViewGroup.LayoutParams lb = eplBox.getLayoutParams();
                        lb.width = px(eplView.designWidth());
                        lb.height = px(eplView.designHeight());
                        eplBox.setLayoutParams(lb);
                        if (eplBox.getVisibility() != View.VISIBLE && Wallpapers.widget(MainActivity.this, "epl")) {
                            eplBox.setAlpha(0f);
                            eplBox.setVisibility(View.VISIBLE);
                            eplBox.animate().alpha(inShelf ? 0f : 1f).setDuration(400).start();
                        }
                        long nk = d.nextKick(now);
                        wait = d.inPlayWindow(now) ? 60_000L
                                : Math.max(60_000L, Math.min(3 * 3600_000L, nk > 0 ? nk - now + 60_000L : 3 * 3600_000L));
                        if (!eplView.hasTable()) wait = 60_000L;
                    }
                    ui.removeCallbacks(eplTick);
                    ui.postDelayed(eplTick, wait);
                });
            });
        }
    };

    /** Giả lập: 3 trận đầu đã xong, 2 trận kế đang đá (phút 67 và 23), còn lại chưa đá. Sau 1 phút lấy lại dữ liệu thật. */
    private void eplDemo() {
        Epl.Data real = eplView.data();
        if (real == null) return;
        Epl.Data d = new Epl.Data();
        d.round = real.round;
        d.teams = real.teams;
        d.table = real.table;
        int[][] score = {{2, 1}, {0, 0}, {1, 3}, {1, 0}, {0, 2}};
        int[] minute = {0, 0, 0, 67, 23};
        for (int i = 0; i < real.matches.size(); i++) {
            Epl.Match o = real.matches.get(i), m = new Epl.Match();
            m.kick = o.kick; m.home = o.home; m.away = o.away;
            if (i < 5) {
                m.started = true; m.hs = score[i][0]; m.as = score[i][1];
                m.finished = i < 3; m.minutes = minute[i];
            }
            d.matches.add(m);
        }
        eplView.setData(d);
        eplDemoUntil = android.os.SystemClock.uptimeMillis() + 60_000L;
        ui.removeCallbacks(eplTick);
        ui.postDelayed(eplTick, 60_000L);
    }

    private long eplDemoUntil;   // đang xem thử: lượt lấy dữ liệu thật chen ngang (onResume) không được ghi đè

    // Thẻ bóng đá: một lượt đầy đủ (lịch 1 phút -> bảng xếp hạng 30 giây) rồi thu gọn lại và ở yên đó.
    // Muốn xem lại: từ nút bánh răng bấm xuống chọn thẻ, bấm OK là chạy lại đúng một lượt đầy đủ.
    private static final long EPL_FIXTURES_MS = 60_000L, EPL_TABLE_MS = 30_000L;
    private ValueAnimator eplResize;
    private View eplRing;   // viền sáng cả thẻ khi chọn thẻ đầy đủ

    private final Runnable eplCycle = new Runnable() {
        @Override public void run() {
            if (eplBox.getVisibility() != View.VISIBLE || inShelf || settings != null) {
                ui.postDelayed(this, EPL_FIXTURES_MS);   // đang bị che: chưa đi tiếp, hẹn lần sau
                return;
            }
            int m = eplView.mode();
            if (m == EplView.FIXTURES && eplView.hasTable()) {
                swapEpl(EplView.TABLE);
                ui.postDelayed(this, EPL_TABLE_MS);
            } else if (m != EplView.COMPACT) {
                swapEpl(EplView.COMPACT);
            }
        }
    };

    private final Runnable eplCollapse = new Runnable() {
        @Override public void run() {
            if (inShelf || settings != null) { ui.postDelayed(this, EPL_FIXTURES_MS); return; }   // đang che: để sau
            swapEpl(EplView.COMPACT);
        }
    };

    /**
     * Bấm OK trên thẻ. Thẻ gọn: khu lịch đang chọn thì mở lịch đầy đủ (1 phút), khu bảng xếp hạng thì mở bảng
     * đầy đủ (30 giây), xong tự thu gọn lại. Thẻ đang đầy đủ: thu gọn ngay.
     */
    private void stepEpl() {
        ui.removeCallbacks(eplCycle);
        ui.removeCallbacks(eplCollapse);
        if (eplView.mode() == EplView.COMPACT) {
            boolean table = eplView.selected() == EplView.SEL_TABLE;
            swapEpl(table ? EplView.TABLE : EplView.FIXTURES);
            ui.postDelayed(eplCollapse, table ? EPL_TABLE_MS : EPL_FIXTURES_MS);
        } else {
            swapEpl(EplView.COMPACT);
        }
    }

    /** Thẻ gọn có khu nào để chọn: từ dưới lên ưu tiên bảng xếp hạng, từ trên xuống ưu tiên lịch. */
    private int eplPart(boolean fromBelow) {
        boolean m = eplView.hasMatchPart(), t = eplView.hasTablePart();
        if (fromBelow) return t ? EplView.SEL_TABLE : m ? EplView.SEL_MATCH : EplView.SEL_NONE;
        return m ? EplView.SEL_MATCH : t ? EplView.SEL_TABLE : EplView.SEL_NONE;
    }

    /** Chọn thẻ bằng điều khiển: thẻ gọn thì chọn đúng một khu, thẻ đầy đủ thì viền sáng cả thẻ. */
    private void focusEpl(boolean fromBelow) {
        eplBox.setFocusable(true);
        eplBox.requestFocus();
        if (eplView.mode() == EplView.COMPACT) eplView.setSelected(eplPart(fromBelow));
        eplRing.animate().alpha(eplView.mode() == EplView.COMPACT ? 0f : 1f).setDuration(200).setInterpolator(EASE).start();
    }

    /** Đổi chế độ thẻ: chữ mờ đi, khung kính co giãn cả ngang lẫn dọc tới cỡ mới, chữ mới hiện dần. */
    private void swapEpl(int to) {
        if (eplView.mode() == to) return;
        eplView.animate().cancel();
        eplView.animate().alpha(0f).setDuration(240).setInterpolator(EASE).withEndAction(() -> {
            ViewGroup.LayoutParams cur = eplBox.getLayoutParams();
            int w0 = cur.width > 0 ? cur.width : eplBox.getWidth(), h0 = cur.height > 0 ? cur.height : eplBox.getHeight();
            eplView.setMode(to);
            if (eplBox.hasFocus()) {
                eplRing.animate().alpha(to == EplView.COMPACT ? 0f : 1f).setDuration(200).start();
                eplView.setSelected(to == EplView.COMPACT ? eplPart(false) : EplView.SEL_NONE);
            }
            int w1 = px(eplView.designWidth()), h1 = px(eplView.designHeight());
            if (eplResize != null) eplResize.cancel();
            eplResize = ValueAnimator.ofFloat(0f, 1f);
            eplResize.setDuration(440);
            eplResize.setInterpolator(EASE);
            eplResize.addUpdateListener(a -> {
                float f = (float) a.getAnimatedValue();
                ViewGroup.LayoutParams lb = eplBox.getLayoutParams();
                lb.width = Math.round(w0 + (w1 - w0) * f);
                lb.height = Math.round(h0 + (h1 - h0) * f);
                eplBox.setLayoutParams(lb);
            });
            eplResize.start();
            eplView.animate().alpha(1f).setStartDelay(180).setDuration(360).setInterpolator(EASE).start();
        }).start();
    }

    /** Chọn được thẻ bằng điều khiển khi đang hiện trên màn hình chính. */
    private boolean eplSelectable() {
        return eplBox != null && eplBox.getVisibility() == View.VISIBLE && eplBox.getAlpha() > 0.5f
                && !inShelf && settings == null && allApps == null && homePrompt == null;
    }

    private void buildTicker() {
        int sw = getResources().getDisplayMetrics().widthPixels, sh = getResources().getDisplayMetrics().heightPixels;
        int trayW = trayW(BASE_N);
        int right = (1920 + trayW) / 2;                     // thẳng mép phải khay dock
        float r = TICK_H * 0.206f;                           // bo góc cùng tỉ lệ với khay dock
        String[] names = {"BTC", "ETH", "Vàng"};
        // xếp dọc dưới đồng hồ: các ô cùng cỡ, cùng thẳng mép phải dock
        for (int i = 0; i < TICKS; i++) {
            int x0 = right - TICK_W;
            int y0 = TICK_TOP + i * (TICK_H + TICK_GAP);
            FrameLayout tile = new FrameLayout(this);
            tile.setFocusable(false);
            tile.setClipChildren(false);   // ô dời lên lấp chỗ ô bị ẩn: nội dung ra ngoài khung gốc
            tickFrost[i] = new FrostView(this, px(r), sw, sh, px(x0), px(y0), Math.max(1, px(1))).dark();
            tile.addView(tickFrost[i], new FrameLayout.LayoutParams(-1, -1));
            TextView name = text(500, 18, 0x80FFFFFF);
            name.setText(names[i]);
            FrameLayout.LayoutParams ln = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.START);
            ln.setMargins(px(TICK_PAD), px(TICK_PAD - 2), 0, 0);
            tile.addView(name, ln);
            tickExtra[i] = text(500, 18, 0x73FFFFFF);
            tickExtra[i].setText("");
            FrameLayout.LayoutParams le = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.END);
            le.setMargins(0, px(TICK_PAD - 2), px(TICK_PAD), 0);
            tile.addView(tickExtra[i], le);
            tickPrice[i] = text(600, 30, WHITE);
            tickPrice[i].setFontFeatureSettings("tnum");     // số đều chiều rộng, nhảy số không xô chữ
            tickPrice[i].setText("···");
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.START);
            lp.setMargins(px(TICK_PAD), px(TICK_PAD + 24), 0, 0);
            tile.addView(tickPrice[i], lp);
            FrameLayout.LayoutParams lt = new FrameLayout.LayoutParams(px(TICK_W), px(TICK_H), Gravity.TOP | Gravity.START);
            lt.leftMargin = px(x0); lt.topMargin = px(y0);
            root.addView(tile, lt);
            tickTile[i] = tile;
        }
    }

    private static java.text.DecimalFormat viFormat(String pattern) {
        java.text.DecimalFormatSymbols s = new java.text.DecimalFormatSymbols(new Locale("vi", "VN"));
        s.setGroupingSeparator('.'); s.setDecimalSeparator(',');
        return new java.text.DecimalFormat(pattern, s);
    }

    // luồng riêng cho mạng (không làm chậm việc làm nhoè kính/đọc nội dung); lần gọi trước chưa xong thì bỏ qua
    private final java.util.concurrent.ExecutorService net = Executors.newFixedThreadPool(2);
    private final java.util.concurrent.atomic.AtomicBoolean btcBusy = new java.util.concurrent.atomic.AtomicBoolean(),
            goldBusy = new java.util.concurrent.atomic.AtomicBoolean();

    private final Runnable btcTick = new Runnable() {
        @Override public void run() {
            if (btcBusy.compareAndSet(false, true)) {
                net.execute(() -> {
                    Prices.Btc[] cs = Prices.crypto();
                    btcBusy.set(false);
                    ui.post(() -> {
                        if (cs != null) for (int i = 0; i < cs.length; i++) if (cs[i] != null) showCoin(i, cs[i]);
                        markStale();
                    });
                });
            }
            ui.postDelayed(this, BTC_EVERY);
        }
    };

    private final Runnable goldTick = new Runnable() {
        @Override public void run() {
            if (goldBusy.compareAndSet(false, true)) {
                net.execute(() -> {
                    Prices.Gold g = Prices.gold();
                    goldBusy.set(false);
                    ui.post(() -> { if (g != null) showGold(g); markStale(); });
                });
            }
            ui.postDelayed(this, GOLD_EVERY);
        }
    };

    /** BTC/ETH (ô i): xanh khi tăng, đỏ khi giảm so với 24 giờ trước, trắng khi đứng; nhảy giá thì chớp nhẹ. */
    private void showCoin(int i, Prices.Btc b) {
        btcOkAt = System.currentTimeMillis();
        int col = b.changePct > 0.005 ? GREEN : b.changePct < -0.005 ? RED : WHITE;
        String arrow = col == GREEN ? "▲ " : col == RED ? "▼ " : "";
        tickExtra[i].setText(arrow + viFormat("0.00").format(Math.abs(b.changePct)) + "%");
        tickExtra[i].setTextColor(col == WHITE ? 0x73FFFFFF : col);
        tickPrice[i].setText(viFormat("#,##0.00").format(b.price));
        tickPrice[i].setTextColor(col);
        if (lastCoin[i] >= 0 && b.price != lastCoin[i]) {
            tickPrice[i].animate().cancel();
            tickPrice[i].setAlpha(0.45f);
            tickPrice[i].animate().alpha(1f).setDuration(420).setInterpolator(EASE).start();
        }
        lastCoin[i] = b.price;
        lastChg[i] = b.changePct;
    }

    /** Vàng: so với mức giá trước đó (tự nhớ): xanh nếu tăng, đỏ nếu giảm, trắng nếu chưa đổi. */
    private void showGold(Prices.Gold g) {
        goldOkAt = System.currentTimeMillis();
        long last = prefs().getLong("gold_last", 0), prev = prefs().getLong("gold_prev", 0);
        if (g.sell != last) {
            prev = last;
            prefs().edit().putLong("gold_prev", prev).putLong("gold_last", g.sell).apply();
        }
        int col = prev <= 0 || g.sell == prev ? WHITE : g.sell > prev ? GREEN : RED;
        tickPrice[GOLD].setText(viFormat("#,##0").format(g.sell));
        tickPrice[GOLD].setTextColor(col);
    }

    /** Mất mạng/nguồn lỗi: giữ giá cuối và làm mờ để biết là giá cũ. */
    private void markStale() {
        long now = System.currentTimeMillis();
        float coin = btcOkAt > 0 && now - btcOkAt < 30_000 ? 1f : 0.5f;
        for (int i = 0; i < GOLD; i++) tickTile[i].animate().alpha(coin).setDuration(300).start();
        tickTile[GOLD].animate().alpha(goldOkAt > 0 && now - goldOkAt < 2 * 3600_000 ? 1f : 0.5f).setDuration(300).start();
    }

    // ---------- quầng sáng: một lớp riêng nằm dưới khay kính, trượt theo ô đang chọn ----------

    private static final int GLOW_SPREAD = 145;   // lề ảnh quầng sáng; độ toả thật ~60px quanh ô + phần nhoè
    private final List<Bitmap> glowBitmaps = new ArrayList<>();
    private final ImageView[] glowViews = new ImageView[2];
    private int glowCur = -1;
    private AnimatorSet breathe;

    private void buildGlowLayer() {
        int w = px(SEL_W) + px(GLOW_SPREAD) * 2, h = px(SEL_H) + px(GLOW_SPREAD) * 2;
        for (int i = 0; i < 2; i++) {
            ImageView g = new ImageView(this);
            g.setAlpha(0f);
            root.addView(g, new FrameLayout.LayoutParams(w, h));   // thêm sau khay -> nằm trên khay
            glowViews[i] = g;
        }
    }

    // ---------- dock dài hơn màn hình: trượt ngang để ô đang chọn luôn nằm trong khung ----------

    // ---------- dock hạ thấp khi đứng ở dock (hàng nội dung ló nửa dưới mép), nâng lên khi vào hàng ----------

    // vào hàng nội dung: cả cụm nâng thêm, đáy ô phim đang chọn cách mép màn hình ~60px cho thoáng
    private static final int SHELF_RISE = 50;
    private static final int DOCK_DROP = 171;   // hạ bao nhiêu khi ở dock: hàng nội dung chỉ ló ~1/5 (≈37px) ở đáy
    private float dockLift;
    private ValueAnimator liftAnim;

    /** Đặt độ hạ của cả cụm dưới (khay, kính mờ, hàng nội dung); quầng sáng tự theo ở lần moveGlow kế tiếp. */
    private void setLift(float target, boolean animate) {
        if (liftAnim != null) liftAnim.cancel();
        dockLift = target;
        if (!animate) { applyLift(target); return; }
        liftAnim = ValueAnimator.ofFloat(tray.getTranslationY(), target);
        liftAnim.setDuration(420);
        liftAnim.setInterpolator(EASE);
        liftAnim.addUpdateListener(an -> applyLift((float) an.getAnimatedValue()));
        liftAnim.start();
    }

    private void applyLift(float y) {
        tray.setTranslationY(y);
        frost.setShiftY(y);
        shelfRow.setTranslationY(y);
        rowHead.setTranslationY(y);
    }

    private float dockShift;
    private ValueAnimator dockAnim;

    /** Ô đang chọn lọt ra ngoài khung 6 ô thì cả khay (ô + kính mờ) trượt vừa đủ để thấy trọn ô đó. */
    private void shiftDock(int i) {
        float left = px(TRAY_LEFT + TRAY_PAD_X + i * (CARD_W + CARD_GAP)), right = left + px(CARD_W);
        float min = px(TRAY_LEFT + TRAY_PAD_X), max = px(1920 - TRAY_LEFT - TRAY_PAD_X);
        float target = dockShift;
        if (right + target > max) target = max - right;
        if (left + target < min) target = min - left;
        target = Math.min(0f, target);
        if (target == dockShift) return;
        if (dockAnim != null) dockAnim.cancel();
        dockAnim = ValueAnimator.ofFloat(tray.getTranslationX(), target);
        dockAnim.setDuration(380);
        dockAnim.setInterpolator(EASE);
        dockAnim.addUpdateListener(an -> {
            float s = (float) an.getAnimatedValue();
            tray.setTranslationX(s);
            frost.setShift(s);
        });
        dockAnim.start();
        dockShift = target;
    }

    /** Quầng cũ trượt sang ô mới và mờ đi, quầng mới (màu logo mới) trượt theo và hiện lên. */
    private void moveGlow(int i) {
        FrameLayout cell = cards.get(i);
        cell.post(() -> {
            // tính theo vị trí đích của khay (đang trượt thì quầng tới thẳng chỗ ô sẽ dừng)
            // ô phóng đều từ tâm: quầng (cỡ ô đã phóng) đặt sao cho tâm trùng tâm ô
            // toạ độ nguyên: lỗ khoét trùng mép ô đang chọn từng điểm ảnh (không lệch nửa điểm ảnh gây mép gai)
            float x = Math.round(tray.getLeft() + dockShift + cell.getLeft() - (px(SEL_W) - cell.getWidth()) / 2f) - px(GLOW_SPREAD);
            float y = Math.round(tray.getTop() + dockLift + cell.getTop() - (px(SEL_H) - cell.getHeight()) / 2f) - px(GLOW_SPREAD);
            if (breathe != null) { breathe.cancel(); breathe = null; }
            int nextIdx = glowCur < 0 ? 0 : 1 - glowCur;
            ImageView next = glowViews[nextIdx];
            ImageView cur = glowCur < 0 ? null : glowViews[glowCur];
            next.animate().cancel();
            next.setImageBitmap(glowBitmaps.get(i));
            if (cur != null && cur.getAlpha() > 0.05f) { next.setX(cur.getX()); next.setY(cur.getY()); }
            else { next.setX(x); next.setY(y); }
            next.setScaleX(1f); next.setScaleY(1f);   // không co giãn: lỗ khoét luôn khít mép ô
            next.animate().x(x).y(y).alpha(GLOW_MOVING).setDuration(380)
                    .setInterpolator(EASE)
                    .withEndAction(() -> startBreathing(next)).start();
            if (cur != null) {
                cur.animate().cancel();
                cur.animate().x(x).y(y).alpha(0f).setDuration(380).setInterpolator(EASE).start();
            }
            glowCur = nextIdx;
        });
    }

    // đang lướt / vừa sang ô: quầng dịu; đứng yên ~1 s mới sáng lên tối đa rồi thở
    private static final float GLOW_MOVING = 0.6f;

    /**
     * Đứng yên ở một ô: chờ ~1 s, quầng sáng dần từ mức dịu lên tối đa (0,8 s), rồi "thở": dịu còn ~55%
     * và sáng lại đầy, mỗi nhịp ~3,2 s. Di chuyển sang ô khác thì quầng về mức dịu (moveGlow).
     * Một bộ đếm thời gian duy nhất tự tính độ sáng theo thời gian (bộ hiệu ứng lồng nhau không chạy ổn trên box).
     * Chỉ đổi độ sáng, không phình to: quầng có lỗ khoét đúng hình ô, phình ra là hở khe giữa ô và quầng.
     */
    private void startBreathing(View g) {
        if (breathe != null) breathe.cancel();
        final float from = g.getAlpha();
        ValueAnimator clock = ValueAnimator.ofFloat(0f, 1f);
        clock.setDuration(60_000);
        clock.setRepeatCount(ValueAnimator.INFINITE);
        clock.setInterpolator(new android.view.animation.LinearInterpolator());
        final long t0 = android.os.SystemClock.uptimeMillis();
        clock.addUpdateListener(an -> {
            long t = android.os.SystemClock.uptimeMillis() - t0 - 1000;   // chờ 1 s đứng yên
            if (t < 0) { g.setAlpha(from); return; }
            if (t < 800) { g.setAlpha(from + (1f - from) * EASE.getInterpolation(t / 800f)); return; }
            double ph = (t - 800) / 3200.0 * 2 * Math.PI;                  // nhịp 3,2 s: 1 -> 0,55 -> 1
            g.setAlpha((float) (0.775 + 0.225 * Math.cos(ph)));
        });
        breathe = new AnimatorSet();
        breathe.play(clock);
        breathe.start();
    }

    /** Rời dock (vào hàng nội dung / mở bảng ứng dụng): quầng sáng dịu đi. */
    private void dimGlow(boolean dim) {
        if (glowCur < 0) return;
        if (breathe != null) { breathe.cancel(); breathe = null; }
        ImageView g = glowViews[glowCur];
        g.animate().cancel();
        g.animate().alpha(dim ? 0f : GLOW_MOVING).scaleX(1f).scaleY(1f).setDuration(240)
                .withEndAction(() -> { if (!dim) startBreathing(g); }).start();
    }

    // Khu nội dung dưới dock: luôn hiện nội dung của app đang chọn (đang xem dở trước, rồi gợi ý).
    private void buildShelf() {
        rowHead = text(600, 30, 0xFFFFFFFF);
        FrameLayout.LayoutParams lh = new FrameLayout.LayoutParams(-2, -2);
        root.addView(rowHead, lh);
        rowHead.setVisibility(View.GONE);   // không hiện tiêu đề hàng: ảnh nội dung tự nói lên nó là gì

        // lưới chung kiểu Apple: tiêu đề + hàng ảnh thẳng mép với ô app đầu và cuối trong dock
        int trayW = trayW(BASE_N);
        int innerW = trayW - TRAY_PAD_X * 2;
        int left = (1920 - trayW) / 2 + TRAY_PAD_X;
        int thumbW = (innerW - (SHELF_N - 1) * THUMB_GAP) / SHELF_N;
        int thumbH = Math.round(thumbW * 9f / 16f);
        thumbSize[0] = thumbW; thumbSize[1] = thumbH;

        shelfRow = new LinearLayout(this);
        shelfRow.setOrientation(LinearLayout.HORIZONTAL);
        shelfRow.setClipChildren(false); shelfRow.setClipToPadding(false);
        FrameLayout.LayoutParams lr = new FrameLayout.LayoutParams(px(innerW), px(thumbH), Gravity.TOP | Gravity.START);
        lr.topMargin = px(ROW_TOP);
        lr.leftMargin = px(left);
        root.addView(shelfRow, lr);
        for (int i = 0; i < SHELF_N; i++) {
            FrameLayout t = thumb(i);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(px(thumbW), px(thumbH));
            if (i > 0) lp.leftMargin = px(THUMB_GAP);
            shelfRow.addView(t, lp);
            thumbs.add(t);
        }
        rowHead.setX(px(left));
        rowHead.setY(px(ROW_TOP - 54));
        shelfRow.setAlpha(ROW_IDLE_ALPHA);
    }

    // ô phim đang chọn to thêm đúng 48x28 điểm ảnh (~14,5%; 24 mỗi bên ngang, vẫn trong khe 30 tới ô bên): mép rơi
    // đúng điểm ảnh
    private float thumbScaleX() { return (px(thumbSize[0]) + px(48)) / (float) px(thumbSize[0]); }
    private float thumbScaleY() { return (px(thumbSize[1]) + px(28)) / (float) px(thumbSize[1]); }

    private static final float THUMB_DIM = 0.55f;   // ô phim không được chọn khi đang ở hàng nội dung

    private FrameLayout thumb(int i) {
        FrameLayout t = new FrameLayout(this);
        t.setClipChildren(false);
        t.setFocusable(true);
        // mọi phần bo góc đều vẽ bằng drawable khử răng cưa, không cắt theo outline
        float tr = px(THUMB_R);
        FrameLayout face = new FrameLayout(this);
        GradientDrawable ph = new GradientDrawable();
        ph.setColor(0x26FFFFFF);
        ph.setCornerRadius(tr);
        face.setBackground(ph);
        shadowShape(face, tr);
        ImageView img = new ImageView(this);
        img.setScaleType(ImageView.ScaleType.FIT_XY);   // RoundImage tự cắt kiểu center-crop
        face.addView(img, new FrameLayout.LayoutParams(-1, -1));
        // dải tối mờ ở đáy để tên nội dung đọc được mà không cần khối chữ riêng
        View shade = new View(this);
        GradientDrawable sd = new GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP,
                new int[]{0xD9000000, 0x66000000, 0x00000000});
        sd.setCornerRadii(new float[]{0, 0, 0, 0, tr, tr, tr, tr});
        shade.setBackground(sd);
        face.addView(shade, new FrameLayout.LayoutParams(-1, px(thumbSize[1] * 0.55f), Gravity.BOTTOM));
        TextView title = text(600, 21, 0xFFFFFFFF);
        title.setSingleLine(true);
        title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        FrameLayout.LayoutParams lt = new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM);
        lt.setMargins(px(14), 0, px(14), px(16));
        face.addView(title, lt);
        // thanh tiến độ dạng viên thuốc, nằm gọn trong ảnh (không chạm góc bo)
        View bar = new View(this);
        GradientDrawable bd = new GradientDrawable();
        bd.setColor(0xF2FFFFFF);
        bd.setCornerRadius(px(2));
        bar.setBackground(bd);
        FrameLayout.LayoutParams lbar = new FrameLayout.LayoutParams(0, px(4), Gravity.BOTTOM | Gravity.START);
        lbar.setMargins(px(12), 0, 0, px(8));
        face.addView(bar, lbar);
        // logo app nhỏ ở góc trên trái, chỉ hiện khi hàng trộn nội dung nhiều app
        ImageView badge = new ImageView(this);
        badge.setScaleType(ImageView.ScaleType.FIT_XY);
        badge.setElevation(px(4));
        shadowShape(badge, px(BADGE_H * 0.12f));
        FrameLayout.LayoutParams lbd = new FrameLayout.LayoutParams(px(BADGE_W), px(BADGE_H), Gravity.TOP | Gravity.START);
        lbd.setMargins(px(10), px(10), 0, 0);
        face.addView(badge, lbd);
        t.addView(face, new FrameLayout.LayoutParams(-1, -1));
        // phóng đều từ tâm ra 4 phía tới đúng cỡ nguyên (thêm 32x18 điểm ảnh, cùng một tỉ lệ ~109,6%):
        // không nảy quá đà, không bóng đổ (bóng chỉ đổ xuống dưới làm ô trông phình lệch, và tốn sức vẽ mỗi khung)
        face.setOutlineProvider(null);
        t.setOnFocusChangeListener((v, has) -> {
            if (has) shelfState(true);   // ô phim nhận lựa chọn bằng đường nào thì cũng là đang ở hàng nội dung
            // ô chọn: to hẳn + sáng đầy; ô khác (khi đang ở hàng): tối bớt để ô chọn nổi lên
            v.setPivotX(v.getWidth() / 2f); v.setPivotY(v.getHeight() / 2f);
            v.animate().cancel();
            // vừa từ dock xuống: cả hàng đang trượt lên; phóng cùng lúc thì mép dưới bị kéo lên, trông như ô chỉ nở
            // lên trên. Đợi hàng trượt gần xong rồi mới phóng, để mắt thấy ô nở đều 4 phía.
            long wait = has && liftAnim != null && liftAnim.isRunning()
                    ? Math.max(0, liftAnim.getDuration() - liftAnim.getCurrentPlayTime() - 80) : 0;
            v.animate().scaleX(has ? thumbScaleX() : 1f).scaleY(has ? thumbScaleY() : 1f)
                    .alpha(has || !inShelf ? 1f : THUMB_DIM)
                    .translationZ(has ? px(2) : 0)   // chỉ để ô chọn vẽ đè lên ô bên cạnh, không đổ bóng
                    .setStartDelay(has ? wait : 0)
                    .setInterpolator(EASE).setDuration(has ? 300 : 260).start();
        });
        // nhấn: lún nhẹ ngay; thả: phồng lại đúng cỡ chọn rồi mở nội dung, cửa sổ phóng ra từ ô này
        t.setOnKeyListener((v, kc, ev) -> {
            if (isOk(kc) && ev.getAction() == KeyEvent.ACTION_DOWN && ev.getRepeatCount() == 0) {
                v.animate().cancel();
                v.animate().scaleX(1.03f).scaleY(1.03f).setInterpolator(EASE).setDuration(110).start();
            }
            return false;
        });
        t.setOnClickListener(v -> {
            v.animate().cancel();
            v.animate().scaleX(thumbScaleX()).scaleY(thumbScaleY()).setInterpolator(EASE).setDuration(200).start();
            openItem(i, v);
        });
        return t;
    }

    private static ImageView thumbImage(FrameLayout t) { return (ImageView) ((FrameLayout) t.getChildAt(0)).getChildAt(0); }
    private static TextView thumbTitle(FrameLayout t) { return (TextView) ((FrameLayout) t.getChildAt(0)).getChildAt(2); }
    private static View thumbBar(FrameLayout t) { return ((FrameLayout) t.getChildAt(0)).getChildAt(3); }
    private static ImageView thumbBadge(FrameLayout t) { return (ImageView) ((FrameLayout) t.getChildAt(0)).getChildAt(4); }

    private static final int BADGE_W = 64, BADGE_H = 40;
    private final Map<String, Bitmap> badgeCache = new java.util.HashMap<>();

    /**
     * Ô cho app cài thêm (không có ảnh vẽ sẵn): banner TV của app phủ kín ô; app không có banner thì
     * biểu tượng đặt giữa nền màu chủ đạo của chính nó. Bo góc như ô vẽ sẵn (12% chiều cao), khử răng cưa.
     */
    private Bitmap appCard(String pkg, int w, int h) {
        Bitmap face = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(face);
        try {
            PackageManager pm = getPackageManager();
            Intent li = pm.getLeanbackLaunchIntentForPackage(pkg);
            Drawable banner = li != null ? pm.getActivityBanner(li) : null;
            if (banner == null) banner = pm.getApplicationBanner(pkg);
            if (banner != null) {
                // banner 16:9 -> ô 16:10: phủ kín, cắt bớt hai bên
                int bw = Math.round(h * 16f / 9f);
                c.drawBitmap(toBitmap(banner, bw, h), (w - bw) / 2f, 0, new Paint(Paint.FILTER_BITMAP_FLAG));
            } else {
                Bitmap icon = toBitmap(pm.getApplicationIcon(pkg), Math.round(h * 0.56f), Math.round(h * 0.56f));
                float[] hsv = new float[3];
                Color.colorToHSV(Glow.dominant(icon), hsv);
                hsv[2] = Math.min(hsv[2], 0.35f);
                c.drawColor(Color.HSVToColor(hsv));
                c.drawBitmap(icon, (w - icon.getWidth()) / 2f, (h - icon.getHeight()) / 2f, new Paint(Paint.FILTER_BITMAP_FLAG));
            }
        } catch (Exception e) {
            c.drawColor(0xFF2C2C30);
        }
        Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        p.setShader(new android.graphics.BitmapShader(face, android.graphics.Shader.TileMode.CLAMP, android.graphics.Shader.TileMode.CLAMP));
        new Canvas(out).drawRoundRect(0, 0, w, h, h * 0.12f, h * 0.12f, p);
        return out;
    }

    /** Logo nhỏ của app (ảnh ô trên dock nếu có, không thì banner/biểu tượng của app). */
    // Logo nhỏ không khung cho ảnh phim (kiểu chữ N của Netflix): chỉ logo trong suốt, cao ~26px, bóng mềm phía sau
    // để không chìm trên ảnh sáng. App có trong đây dùng logo này thay cho ô dock thu nhỏ (to và nặng).
    private static final Map<String, String> MARK = new java.util.HashMap<>();
    static { MARK.put("com.iqiyi.i18n.tv", "cards/iqiyi_badge.png"); }
    private static final int MARK_H = 26, MARK_PAD = 4;
    private final Map<String, Bitmap> markCache = new java.util.HashMap<>();

    private Bitmap markFor(String pkg) {
        String path = MARK.get(pkg);
        if (path == null) return null;
        Bitmap m = markCache.get(pkg);
        if (m != null) return m;
        Bitmap src = asset(path);
        int h = px(MARK_H), w = Math.round(h * src.getWidth() / (float) src.getHeight());
        Bitmap logo = scaleHQ(src, w, h);
        int pad = px(MARK_PAD);
        m = Bitmap.createBitmap(w + 2 * pad, h + 2 * pad, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(m);
        Paint sh = new Paint(Paint.ANTI_ALIAS_FLAG);
        sh.setMaskFilter(new android.graphics.BlurMaskFilter(px(3), android.graphics.BlurMaskFilter.Blur.NORMAL));
        sh.setColor(0x8C000000);
        c.drawBitmap(logo.extractAlpha(), pad, pad + px(1), sh);
        c.drawBitmap(logo, pad, pad, new Paint(Paint.FILTER_BITMAP_FLAG));
        markCache.put(pkg, m);
        return m;
    }

    private Bitmap badgeFor(String pkg) {
        Bitmap b = badgeCache.get(pkg);
        if (b != null) return b;
        int w = px(BADGE_W) * 2, h = px(BADGE_H) * 2;
        for (Apps.App a : apps) {
            if (pkg.equals(a.pkg) && a.key != null) { b = scaleHQ(asset("cards/" + a.key + ".png"), w, h); break; }
        }
        if (b == null) {
            try {
                PackageManager pm = getPackageManager();
                Drawable d = pm.getApplicationBanner(pkg);
                if (d == null) d = pm.getApplicationIcon(pkg);
                b = toBitmap(d, w, h);
            } catch (Exception e) { return null; }
        }
        badgeCache.put(pkg, b);
        return b;
    }

    /**
     * Tiêu đề hàng kiểu Apple TV: phần chính trắng semibold, phần phụ cùng dòng xám nhạt nét thường.
     * Thứ bậc bằng độ đậm và độ sáng, không dùng màu.
     */
    private CharSequence heading(String main, String sub) {
        android.text.SpannableStringBuilder sb = new android.text.SpannableStringBuilder(main);
        sb.setSpan(new android.text.style.TypefaceSpan(semibold), 0, main.length(), 0);
        sb.setSpan(new android.text.style.ForegroundColorSpan(0xFFFFFFFF), 0, main.length(), 0);
        if (sub != null) {
            int s = sb.length();
            sb.append("  –  ").append(sub);
            sb.setSpan(new android.text.style.TypefaceSpan(regular), s, sb.length(), 0);
            sb.setSpan(new android.text.style.ForegroundColorSpan(0x8CFFFFFF), s, sb.length(), 0);
        }
        return sb;
    }

    /**
     * Đổ nội dung vào hàng dưới dock: nội dung của app đang chọn đứng đầu; còn chỗ thì lấp bằng
     * nội dung đang xem dở của app khác (mới xem nhất trước, không trùng). Hàng trộn nhiều app thì
     * tiêu đề là "Xem tiếp" và mỗi ô có logo app nhỏ ở góc.
     */
    private void fillRow(Apps.App a) {
        String key = a.key + "|" + shelfVersion;
        if (key.equals(rowKey)) return;
        rowKey = key;

        List<Shelf.Item> own = itemsOf(a);
        List<Shelf.Item> items = new ArrayList<>();
        for (Shelf.Item it : own) { if (items.size() < SHELF_N) items.add(it); }
        // hàng chỉ có nội dung của chính app đang chọn; app không có nội dung thì hàng để trống (không mượn app khác)
        boolean mixed = false;
        for (Shelf.Item it : items) if (!it.pkg.equals(items.get(0).pkg)) { mixed = true; break; }
        CharSequence head;
        if (items.isEmpty()) head = "";
        else if (mixed || own.isEmpty()) head = heading("Xem tiếp", null);
        else head = heading(own.get(0).continueWatching ? "Đang xem dở" : "Gợi ý", a.label);
        rowItems = items;
        rowHead.animate().cancel();
        rowHead.setAlpha(0f);
        rowHead.setText(head);
        rowHead.animate().alpha(1f).setDuration(300).start();
        for (int i = 0; i < SHELF_N; i++) {
            FrameLayout t = thumbs.get(i);
            t.animate().cancel();
            boolean has = i < items.size();
            t.setVisibility(has ? View.VISIBLE : View.INVISIBLE);
            t.setFocusable(has);
            if (!has) continue;
            Shelf.Item it = items.get(i);
            images.into(thumbImage(t), it.art, px(thumbSize[0]) * 2, px(THUMB_R));
            thumbTitle(t).setText(it.title == null ? "" : it.title);
            View bar = thumbBar(t);
            ViewGroup.LayoutParams lb = bar.getLayoutParams();
            lb.width = Math.round((px(thumbSize[0]) - px(24)) * it.progress);
            bar.setLayoutParams(lb);
            ImageView badge = thumbBadge(t);
            // ảnh của Netflix đã in sẵn chữ N đỏ ở góc: không dán logo app đè lên, giữ chữ N gốc
            boolean branded = "com.netflix.ninja".equals(it.pkg);
            // iQIYI: ảnh không có logo của app, nên luôn gắn logo nhỏ ở góc trên trái (như chữ N trên ảnh Netflix)
            Bitmap mark = markFor(it.pkg);   // logo nhỏ không khung (iQIYI): luôn gắn, kể cả hàng của chính app
            Bitmap bb = mark != null ? mark : (mixed || own.isEmpty()) && !branded ? badgeFor(it.pkg) : null;
            badge.setVisibility(bb == null ? View.GONE : View.VISIBLE);
            if (bb != null) {
                badge.setImageBitmap(bb);
                FrameLayout.LayoutParams lbd = (FrameLayout.LayoutParams) badge.getLayoutParams();
                if (mark != null) {
                    // đúng cỡ ảnh, không khung, không bóng hộp; lề bù phần đệm bóng để logo cách mép ~10px
                    lbd.width = mark.getWidth(); lbd.height = mark.getHeight();
                    lbd.setMargins(px(10 - MARK_PAD), px(10 - MARK_PAD), 0, 0);
                    badge.setElevation(0);
                    badge.setOutlineProvider(null);
                } else {
                    lbd.width = px(BADGE_W); lbd.height = px(BADGE_H);
                    lbd.setMargins(px(10), px(10), 0, 0);
                    badge.setElevation(px(4));
                    shadowShape(badge, px(BADGE_H * 0.12f));
                }
                badge.setLayoutParams(lbd);
            }
            // đổi app: các ô mờ ra rồi trượt nhẹ vào lần lượt
            t.setAlpha(0f);
            t.setTranslationX(px(24));
            t.animate().alpha(1f).translationX(0).setStartDelay(i * 40L).setDuration(300)
                    .setInterpolator(new DecelerateInterpolator(1.8f)).start();
        }
    }

    // ---------- trạng thái chọn ----------

    private void onAppFocused(int i) {
        focused = i;
        prefs().edit().putInt("focused", i).apply();
        // không hiện tên app: nhìn logo là biết. ô phản hồi ngay; nền chỉ đổi khi người dùng dừng lại, lướt nhanh không tải ảnh thừa
        ui.removeCallbacks(pendingBg);
        ui.postDelayed(pendingBg, BG_SETTLE_MS);
    }

    private static final long BG_SETTLE_MS = 180;
    // đổi app chỉ đổi hàng nội dung; nền giữ nguyên, tự sang ảnh khác theo nhịp chọn trong Cài đặt
    private final Runnable pendingBg = () -> fillRow(apps.get(focused));

    private static final int BG_FALLBACK = 0xFF1E2A44;   // chưa có ảnh nào: nền màu xanh đêm

    /** Nền: ảnh tới lượt trong bộ ảnh đang chọn ở Cài đặt; cùng ảnh thì không tải lại. */
    private void showBackground() {
        String uri = sharedBackground();
        String key = "wall:" + uri;
        if (key.equals(bgShown)) return;
        bgShown = key;
        if (uri == null) { crossfade(null, BG_FALLBACK); return; }
        images.load(uri, bgWidth(), bmp -> {
            if (!key.equals(bgShown)) return;
            crossfade(bmp, BG_FALLBACK);
        });
    }

    /**
     * Bộ ảnh nền theo Cài đặt (ảnh kèm sẵn, ảnh của bạn hoặc trộn, xem Wallpapers); lần lượt từng ảnh,
     * đổi theo nhịp đã chọn (mặc định 10 phút).
     */
    private String sharedBackground() {
        List<String> fs = Wallpapers.active(this, Wallpapers.HOME);
        if (fs.isEmpty()) return null;
        long slot = System.currentTimeMillis() / bgRotateMs() + prefs().getInt("bg_skip", 0);
        return fs.get((int) (slot % fs.size()));
    }

    /** Hiện ngay ảnh có tên bắt đầu bằng `name`; vòng xoay đi tiếp từ ảnh đó. */
    private void showWallpaper(String name) {
        List<String> fs = Wallpapers.active(this, Wallpapers.HOME);
        for (int i = 0; i < fs.size(); i++) {
            String n = fs.get(i).substring(fs.get(i).lastIndexOf('/') + 1);
            if (!n.startsWith(name)) continue;
            long slot = System.currentTimeMillis() / bgRotateMs();
            int skip = (int) (((i - slot) % fs.size() + fs.size()) % fs.size());
            prefs().edit().putInt("bg_skip", skip).apply();
            if (inShelf) exitShelf();
            showBackground();
            return;
        }
    }

    private static boolean inside(View v, ViewGroup g) {
        for (android.view.ViewParent p = v == null ? null : v.getParent(); p != null; p = p.getParent()) if (p == g) return true;
        return false;
    }

    private static boolean isAppsButton(String act) {
        return "android.intent.action.ALL_APPS".equals(act) || "vn.glass.home.ALL_APPS".equals(act);
    }

    /** Sang ngay ảnh kế tiếp trong vòng (am start -a vn.glass.home.NEXT_BG -n vn.glass.home/.MainActivity). */
    private void nextBackground() {
        prefs().edit().putInt("bg_skip", prefs().getInt("bg_skip", 0) + 1).apply();
        if (inShelf) exitShelf();
        showBackground();
    }

    private long bgRotateMs() { return Wallpapers.everyMin(this, Wallpapers.HOME) * 60_000L; }
    // tới lượt ảnh chung kế tiếp: chỉ đổi khi đang đứng ở dock (đang soi hàng nội dung thì giữ nền của ô)
    private final Runnable bgRotate = new Runnable() {
        @Override public void run() {
            if (!inShelf && allApps == null) showBackground();
            ui.postDelayed(this, bgRotateMs());
        }
    };

    private int bgWidth() { return Math.min(1600, getResources().getDisplayMetrics().widthPixels); }

    /** Bấm xuống từ dock: vào hàng nội dung (hàng sáng lên, dock lùi nhẹ). */
    private void enterShelf() {
        // đang lướt nhanh mà hàng chưa kịp đổi: đổ ngay theo app đang chọn
        ui.removeCallbacks(pendingBg);
        pendingBg.run();
        if (rowItems.isEmpty()) return;
        shelfState(true);
        thumbs.get(0).requestFocus();
    }

    /** Bấm lên từ hàng nội dung: về đúng ô app. */
    private void exitShelf() {
        shelfState(false);
        cards.get(focused).requestFocus();
    }

    /**
     * Trạng thái dock / hàng nội dung. Gọi cả từ sự kiện chọn ô (ô dock hay ô phim nhận lựa chọn bằng
     * đường nào cũng vậy) nên trạng thái luôn khớp với ô đang chọn, không bao giờ lệch.
     * Ảnh nền đứng yên khi chuyển qua lại (chỉ đổi theo nhịp hoặc nút APPS).
     */
    private void shelfState(boolean on) {
        if (inShelf == on) return;
        inShelf = on;
        dimGlow(on);
        setLift(on ? -px(SHELF_RISE) : px(DOCK_DROP), true);   // vào hàng: cả cụm trượt lên; về dock: hạ xuống lại
        shelfRow.animate().alpha(on ? 1f : ROW_IDLE_ALPHA).setDuration(220).start();
        // vào hàng: ô chưa chọn tối bớt (ô chọn tự sáng lại trong sự kiện chọn); về dock: cả hàng sáng đều
        for (FrameLayout t : thumbs) if (!t.hasFocus()) t.animate().alpha(on ? THUMB_DIM : 1f).setDuration(220).start();
        tray.animate().alpha(on ? 0.6f : 1f).setDuration(220).start();
        // vào hàng nội dung thì cả cụm nâng lên, chạm thẻ lịch: thẻ mờ đi, về dock thì hiện lại
        if (eplBox != null) eplBox.animate().alpha(on ? 0f : 1f).setDuration(220).start();
    }

    // ---------- nền ----------

    private AnimatorSet bgAnim;

    /** Ảnh mới mờ dần vào và thu nhẹ 106% -> 100% (Ken Burns); ảnh cũ tối dần phía sau. */
    private void crossfade(Bitmap bmp, int color) {
        if (bgAnim != null) bgAnim.cancel();
        bgBack.setImageDrawable(bgFront.getDrawable());
        bgBack.setScaleX(bgFront.getScaleX()); bgBack.setScaleY(bgFront.getScaleY());
        bgBack.setAlpha(bgFront.getAlpha());
        bgBack.setVisibility(View.VISIBLE);
        if (bmp != null) bgFront.setImageBitmap(bmp);
        else bgFront.setImageDrawable(colorBackground(color));
        updateFrost(bmp, color);
        bgFront.setAlpha(0f);
        bgFront.setScaleX(1.06f); bgFront.setScaleY(1.06f);

        ObjectAnimator fadeIn = ObjectAnimator.ofFloat(bgFront, View.ALPHA, 0f, 1f);
        fadeIn.setDuration(550);
        fadeIn.setInterpolator(new DecelerateInterpolator(1.6f));
        ObjectAnimator sx = ObjectAnimator.ofFloat(bgFront, View.SCALE_X, 1.06f, 1f);
        ObjectAnimator sy = ObjectAnimator.ofFloat(bgFront, View.SCALE_Y, 1.06f, 1f);
        sx.setDuration(1200); sy.setDuration(1200);
        sx.setInterpolator(new DecelerateInterpolator(2f)); sy.setInterpolator(new DecelerateInterpolator(2f));
        ObjectAnimator dim = ObjectAnimator.ofFloat(bgBack, View.ALPHA, bgBack.getAlpha(), 0.35f);
        dim.setDuration(550);

        // lớp phủ mỏng theo màu chủ đạo của ảnh mới, chuyển màu cùng lúc với ảnh
        int target = overlayFor(bmp, color);
        ValueAnimator tc = ValueAnimator.ofArgb(tintColor, target);
        tc.setDuration(700);
        tc.addUpdateListener(an -> { tintColor = (int) an.getAnimatedValue(); tint.setBackgroundColor(tintColor); });

        bgAnim = new AnimatorSet();
        bgAnim.playTogether(fadeIn, sx, sy, dim, tc);
        // ảnh mới đã phủ kín: ẩn ảnh cũ phía sau, đỡ vẽ thêm một lớp toàn màn hình ở mọi khung
        fadeIn.addListener(new android.animation.AnimatorListenerAdapter() {
            boolean canceled;
            @Override public void onAnimationCancel(android.animation.Animator a) { canceled = true; }
            @Override public void onAnimationEnd(android.animation.Animator a) {
                if (!canceled) { bgBack.setVisibility(View.INVISIBLE); bgBack.setImageDrawable(null); }
            }
        });
        bgAnim.start();
    }

    // nền màu đã rải nhiễu, giữ vài cái gần nhất (mỗi cái ~8MB ở 1080p)
    private final android.util.LruCache<Integer, Bitmap> colorBgs = new android.util.LruCache<>(3);

    private Drawable colorBackground(int c) {
        Bitmap b = colorBgs.get(c);
        if (b == null) {
            int w = getResources().getDisplayMetrics().widthPixels, h = getResources().getDisplayMetrics().heightPixels;
            b = Dither.radial(w, h, w * 0.7f, h * 0.3f, px(1300), c, 0xFF050507);
            colorBgs.put(c, b);
        }
        return new android.graphics.drawable.BitmapDrawable(getResources(), b);
    }


    // ---------- lớp phủ nền: mỏng, theo màu chủ đạo của ảnh ----------

    private View tint;
    private int tintColor = 0x2E000000;
    private static final int TINT_ALPHA = 0x38;   // ~22%, phủ nhẹ

    /**
     * Màu chủ đạo của ảnh (trung bình có trọng số độ bão hoà), làm sẫm lại rồi phủ lên.
     * Độ phủ theo độ sáng của ảnh: ảnh tối phủ nhẹ (~22%), ảnh càng sáng phủ càng đậm (tới ~55%),
     * để ảnh nào nhìn cũng tối ngang nhau, chữ và dock luôn nổi.
     */
    private static int overlayFor(Bitmap b, int fallback) {
        int base = fallback;
        float lum = 0f;
        if (b != null) {
            int w = b.getWidth(), h = b.getHeight(), step = Math.max(1, Math.min(w, h) / 48);
            double r = 0, g = 0, bl = 0, wsum = 0, l = 0;
            int n = 0;
            float[] hsv = new float[3];
            for (int y = 0; y < h; y += step) {
                for (int x = 0; x < w; x += step) {
                    int c = b.getPixel(x, y);
                    Color.colorToHSV(c, hsv);
                    double wt = 0.15 + hsv[1] * hsv[2];      // ưu tiên vùng có màu, bớt trọng số vùng xám
                    r += Color.red(c) * wt; g += Color.green(c) * wt; bl += Color.blue(c) * wt; wsum += wt;
                    l += FrostView.glare(c);   // độ chói cảm nhận: ảnh đỏ/xanh rực cũng được phủ đủ
                    n++;
                }
            }
            if (wsum > 0) base = Color.rgb((int) (r / wsum), (int) (g / wsum), (int) (bl / wsum));
            if (n > 0) lum = (float) (l / n / 255.0);
        }
        float[] hsv = new float[3];
        Color.colorToHSV(base, hsv);
        hsv[1] = Math.min(1f, hsv[1] * 1.15f);
        hsv[2] = Math.min(hsv[2], 0.32f);                  // sẫm lại để không làm nhạt ảnh
        // phủ vừa đủ kéo độ sáng trung bình về mức mục tiêu: sau phủ ~ lum * (1 - a) = BG_TARGET
        float a = lum <= 0f ? 0f : 1f - BG_TARGET / lum;
        a = Math.max(TINT_ALPHA / 255f, Math.min(TINT_MAX / 255f, a));
        return (Math.round(a * 255) << 24) | (Color.HSVToColor(hsv) & 0xFFFFFF);
    }

    private static final float BG_TARGET = 0.20f;   // độ sáng trung bình mong muốn của nền sau khi phủ
    private static final int TINT_MAX = 0xA3;        // ~64%, trần cho ảnh rất sáng

    // ---------- dữ liệu ----------

    // App (Netflix...) cập nhật hàng "Xem tiếp" vài giây SAU khi người xem thoát phim, lúc launcher đã đọc xong.
    // Nghe thay đổi của TvProvider suốt vòng đời launcher (kể cả lúc đang ở app khác) để không sót phim nào
    // và kịp ghi nhớ trước khi app tự đẩy phim cũ ra. Gom các thay đổi dồn dập lại, đọc một lần sau 1,5 s.
    private final Runnable shelfChanged = this::refreshShelf;
    private final android.database.ContentObserver tvObserver = new android.database.ContentObserver(ui) {
        @Override public void onChange(boolean self) {
            ui.removeCallbacks(shelfChanged);
            ui.postDelayed(shelfChanged, 1500);
        }
    };

    private void watchTvProvider(boolean on) {
        try {
            if (on) {
                getContentResolver().registerContentObserver(
                        android.net.Uri.parse("content://android.media.tv/watch_next_program"), true, tvObserver);
            } else {
                getContentResolver().unregisterContentObserver(tvObserver);
                ui.removeCallbacks(shelfChanged);
            }
        } catch (Exception ignored) {}
    }

    private void refreshShelf() {
        bg.execute(() -> {
            Map<String, List<Shelf.Item>> m = Shelf.load(this);
            ui.post(() -> {
                shelf = m;
                shelfVersion++;
                fillRow(apps.get(focused));
                // nền chỉ đổi nếu tới lượt ảnh khác, không nháy khi quay về
                if (!inShelf) showBackground();
            });
        });
    }

    private List<Shelf.Item> itemsOf(Apps.App a) {
        if (a.isSettings()) return Collections.emptyList();
        List<Shelf.Item> l = shelf.get(a.pkg);
        return l == null ? Collections.emptyList() : l;
    }

    // ---------- mở app ----------

    private void launch(Apps.App a, View src) {
        try {
            if (a.isSettings()) { startActivity(new Intent(Settings.ACTION_SETTINGS), zoomFrom(src)); return; }
            launchPkg(a.pkg, src);
        } catch (Exception ignored) {}
    }

    private void launchPkg(String pkg, View src) {
        PackageManager pm = getPackageManager();
        Intent i = pm.getLeanbackLaunchIntentForPackage(pkg);
        if (i == null) i = pm.getLaunchIntentForPackage(pkg);
        if (i != null) startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), zoomFrom(src));
    }

    /** Mở đúng nội dung; không mở được thì mở app của nội dung đó (hàng có thể trộn nhiều app). */
    private void openItem(int i, View src) {
        List<Shelf.Item> items = rowItems;
        if (i >= items.size()) return;
        Shelf.Item it = items.get(i);
        try {
            if (it.intent != null) {
                startActivity(Intent.parseUri(it.intent, Intent.URI_INTENT_SCHEME)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), zoomFrom(src));
                return;
            }
        } catch (Exception ignored) {}
        launchPkg(it.pkg, src);
    }

    // ---------- có mạng trở lại ----------
    // Lúc box vừa khởi động, launcher chạy trước khi có mạng: lần tải đầu hỏng, rồi phải đợi lượt sau (giãn dần,
    // có khi rất lâu). Hệ thống báo có mạng thì tải lại ngay những gì còn thiếu.

    private android.net.ConnectivityManager.NetworkCallback netCb;

    private void watchNetwork(boolean on) {
        android.net.ConnectivityManager cm = getSystemService(android.net.ConnectivityManager.class);
        if (cm == null) return;
        try {
            if (on && netCb == null) {
                netCb = new android.net.ConnectivityManager.NetworkCallback() {
                    @Override public void onAvailable(android.net.Network n) { ui.postDelayed(MainActivity.this::onNetworkBack, 1500); }
                };
                cm.registerDefaultNetworkCallback(netCb);
            } else if (!on && netCb != null) {
                cm.unregisterNetworkCallback(netCb);
                netCb = null;
            }
        } catch (Exception ignored) {}
    }

    private void onNetworkBack() {
        if (isFinishing() || !shown()) return;   // đang xem app khác: lúc quay về màn hình chính sẽ tự tải
        if (Wallpapers.widget(this, "epl") && (eplView.data() == null || !eplView.hasTable() || eplFailMs > 0)) {
            eplFailMs = 0;
            ui.removeCallbacks(eplTick);
            ui.post(eplTick);
        }
        if (Wallpapers.widget(this, "gold") && goldOkAt == 0) { ui.removeCallbacks(goldTick); ui.post(goldTick); }
    }

    // ---------- nhắc đặt Glass Home làm màn hình chính ----------
    // Google TV không có chỗ chọn launcher. Máy chưa đặt bằng máy tính (adb) thì nhắc bật dịch vụ HomeGuard trong
    // Hỗ trợ tiếp cận: bật tivi và bấm Home sẽ vào Glass Home. "Để sau" thì thôi nhắc tới lần khởi động lại app.

    private FrameLayout homePrompt;
    private static boolean homePromptLater;
    private final Runnable showHomePrompt = this::showHomePrompt;

    private boolean needsHomePrompt() {
        return !homePromptLater && homePrompt == null && settings == null && allApps == null
                && !HomeGuard.isSystemHome(this) && !HomeGuard.enabled(this);
    }

    private void showHomePrompt() {
        if (!needsHomePrompt() || saverOn || !shown()) return;
        int sw = getResources().getDisplayMetrics().widthPixels, sh = getResources().getDisplayMetrics().heightPixels;
        FrameLayout box = new FrameLayout(this);
        View shade = new View(this);
        shade.setBackgroundColor(0xA6000000);
        box.addView(shade, new FrameLayout.LayoutParams(-1, -1));
        int cw = 920, ch = 470, cx = (1920 - cw) / 2, cy = (1080 - ch) / 2;
        FrameLayout card = new FrameLayout(this);
        FrostView glass = new FrostView(this, px(36), sw, sh, px(cx), px(cy), Math.max(1, px(1))).deep();
        if (lastBlur != null) glass.setImage(lastBlur);
        card.addView(glass, new FrameLayout.LayoutParams(-1, -1));
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(px(64), px(56), px(64), px(48));
        TextView t = text(600, 38, 0xF0F2EEE6);
        t.setText("Đặt Glass Home làm màn hình chính");
        col.addView(t);
        TextView b = text(400, 24, 0xB3F2EEE6);
        b.setText("Bật một lần trong Cài đặt → Hỗ trợ tiếp cận → Glass Home: màn hình chính. "
                + "Từ đó bật tivi hay bấm nút Home đều vào thẳng Glass Home.");
        b.setLineSpacing(px(8), 1f);
        LinearLayout.LayoutParams lb = new LinearLayout.LayoutParams(-1, -2);
        lb.topMargin = px(22);
        col.addView(b, lb);
        TextView n = text(400, 20, 0x80F2EEE6);
        n.setText("Android sẽ cảnh báo dịch vụ có thể theo dõi thao tác. Glass Home chỉ nghe nút Home và biết màn hình gốc có đang hiện không.");
        n.setLineSpacing(px(6), 1f);
        LinearLayout.LayoutParams ln = new LinearLayout.LayoutParams(-1, -2);
        ln.topMargin = px(18);
        col.addView(n, ln);
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        TextView open = promptButton("Mở cài đặt", v -> {
            closeHomePrompt(false);
            try { startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)); } catch (Exception ignored) {}
        });
        TextView later = promptButton("Để sau", v -> closeHomePrompt(true));
        LinearLayout.LayoutParams lo = new LinearLayout.LayoutParams(px(300), px(64));
        row.addView(open, lo);
        LinearLayout.LayoutParams ll = new LinearLayout.LayoutParams(px(220), px(64));
        ll.leftMargin = px(20);
        row.addView(later, ll);
        LinearLayout.LayoutParams lr = new LinearLayout.LayoutParams(-2, -2);
        lr.topMargin = px(34);
        col.addView(row, lr);
        card.addView(col, new FrameLayout.LayoutParams(-1, -1));
        FrameLayout.LayoutParams lc = new FrameLayout.LayoutParams(px(cw), px(ch), Gravity.TOP | Gravity.START);
        lc.leftMargin = px(cx); lc.topMargin = px(cy);
        box.addView(card, lc);

        tray.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        shelfRow.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        gear.setFocusable(false);
        dimGlow(true);
        box.setAlpha(0f);
        card.setScaleX(0.96f); card.setScaleY(0.96f);
        root.addView(box, new FrameLayout.LayoutParams(-1, -1));
        homePrompt = box;
        box.animate().alpha(1f).setDuration(280).setInterpolator(EASE).start();
        card.animate().scaleX(1f).scaleY(1f).setDuration(340).setInterpolator(EASE).withLayer().start();
        open.requestFocus();
    }

    /** Nút trong hộp nhắc: nền kính mờ, được chọn thì nền trắng chữ tối như nút Apple TV. */
    private TextView promptButton(String s, View.OnClickListener click) {
        TextView b = text(500, 24, 0xE6F2EEE6);
        b.setText(s);
        b.setGravity(Gravity.CENTER);
        b.setFocusable(true);
        b.setClickable(true);
        b.setOnClickListener(click);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(px(16));
        bg.setColor(0x1FFFFFFF);
        b.setBackground(bg);
        b.setOnFocusChangeListener((v, has) -> {
            ValueAnimator a = ValueAnimator.ofArgb(has ? 0x1FFFFFFF : 0xEBF2EEE6, has ? 0xEBF2EEE6 : 0x1FFFFFFF);
            a.setDuration(220);
            a.setInterpolator(EASE);
            a.addUpdateListener(an -> bg.setColor((int) an.getAnimatedValue()));
            a.start();
            b.setTextColor(has ? 0xFF1C1C1E : 0xE6F2EEE6);
            b.animate().scaleX(has ? 1.04f : 1f).scaleY(has ? 1.04f : 1f).setDuration(220).setInterpolator(EASE).start();
        });
        return b;
    }

    private void closeHomePrompt(boolean later) {
        if (homePrompt == null) return;
        if (later) homePromptLater = true;
        View v = homePrompt;
        homePrompt = null;
        v.animate().alpha(0f).setDuration(220).setInterpolator(EASE).withEndAction(() -> root.removeView(v)).start();
        tray.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
        shelfRow.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
        gear.setFocusable(true);
        cards.get(focused).requestFocus();
    }

    // ---------- bảng Cài đặt ----------

    private SettingsPanel settings;
    private Bitmap lastBlur;   // ảnh nền đã làm nhoè: kính của bảng Cài đặt dùng chung

    private void openSettings() {
        if (settings != null) return;
        tray.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        shelfRow.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        gear.setFocusable(false);
        dimGlow(true);
        if (eplBox != null) eplBox.animate().alpha(0f).setDuration(220).start();   // bảng nằm đúng chỗ thẻ lịch
        settings = new SettingsPanel(this, new SettingsPanel.Host() {
            @Override public TextView text(int weight, float size, int color) { return MainActivity.this.text(weight, size, color); }
            @Override public int px(float v) { return MainActivity.this.px(v); }
            @Override public Images images() { return images; }
            @Override public Bitmap blur() { return lastBlur; }
            @Override public List<Epl.Team> teams() {
                Epl.Data d = eplView.data();
                if (d == null) return null;
                List<Epl.Team> ts = new ArrayList<>(d.teams.values());
                Collections.sort(ts, (x, y) -> x.name.compareToIgnoreCase(y.name));
                return ts;
            }
            @Override public void changed(String what) { settingsChanged(what); }
            @Override public void openSystemSettings() { MainActivity.this.openSystemSettings(); }
            @Override public void closed() { onSettingsClosed(); }
        });
        root.addView(settings, new FrameLayout.LayoutParams(-1, -1));
    }

    private void onSettingsClosed() {
        settings = null;
        tray.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
        shelfRow.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
        gear.setFocusable(true);
        if (eplBox != null && !inShelf) eplBox.animate().alpha(1f).setDuration(300).start();
        gear.requestFocus();   // đóng bảng: con trỏ về nút bánh răng như lúc mở
    }

    private void openSystemSettings() {
        if (settings != null) settings.close(false);
        startActivity(new Intent(Settings.ACTION_SETTINGS), zoomFrom(gear));
    }

    private void settingsChanged(String what) {
        switch (what) {
            case Wallpapers.HOME:
                bgShown = null;
                showBackground();
                ui.removeCallbacks(bgRotate); ui.postDelayed(bgRotate, bgRotateMs());
                break;
            case "saver": armSaver(); break;
            case "widgets": applyWidgets(); break;
            case "team": eplView.setFavorite(Wallpapers.favTeam(this)); break;
            default: break;
        }
    }

    private static final String[] TICK_KEYS = {"btc", "eth", "gold"};   // cùng thứ tự với cột giá
    private final float[] tickShift = new float[TICKS];
    private final ValueAnimator[] tickMove = new ValueAnimator[TICKS];

    /**
     * Ẩn/hiện từng ô giá và thẻ lịch theo Cài đặt; ô nào ẩn thì các ô dưới trượt lên lấp chỗ (giữ thứ tự
     * BTC, ETH, vàng). Không còn ô nào cần thì không gọi mạng.
     */
    private void applyWidgets() {
        boolean epl = Wallpapers.widget(this, "epl");
        int slot = 0;
        for (int i = 0; i < TICKS; i++) {
            boolean on = Wallpapers.widget(this, TICK_KEYS[i]);
            boolean wasHidden = tickTile[i].getVisibility() != View.VISIBLE;
            float dy = px((slot - i) * (TICK_H + TICK_GAP));
            if (on) slot++;
            // ô đang ẩn hiện ra: đặt sẵn ở chỗ mới rồi mới hiện dần, không trượt từ chỗ cũ
            if (on) moveTick(i, dy, !wasHidden);
            fadeVisible(tickTile[i], on, 1f);
        }
        boolean coins = Wallpapers.widget(this, "btc") || Wallpapers.widget(this, "eth");
        ui.removeCallbacks(btcTick); ui.removeCallbacks(goldTick);
        if (coins) ui.post(btcTick);
        if (Wallpapers.widget(this, "gold")) ui.post(goldTick);
        ui.removeCallbacks(eplTick);
        if (epl) {
            if (eplView.data() != null) fadeVisible(eplBox, true, inShelf || settings != null ? 0f : 1f);
            ui.post(eplTick);
        } else fadeVisible(eplBox, false, 0f);
    }

    /**
     * Dời ô giá i lên/xuống dy: chữ dời bằng translationY, kính dời bằng setShiftY để vùng nhoè vẫn khớp nền
     * phía sau (ô không di chuyển cả khối nên kính không bị lệch ảnh).
     */
    private void moveTick(int i, float dy, boolean animate) {
        if (tickMove[i] != null) tickMove[i].cancel();
        if (!animate || tickShift[i] == dy) { setTickShift(i, dy); return; }
        tickMove[i] = ValueAnimator.ofFloat(tickShift[i], dy);
        tickMove[i].setDuration(420);
        tickMove[i].setInterpolator(EASE);
        tickMove[i].addUpdateListener(a -> setTickShift(i, (float) a.getAnimatedValue()));
        tickMove[i].start();
    }

    private void setTickShift(int i, float dy) {
        tickShift[i] = dy;
        ViewGroup t = (ViewGroup) tickTile[i];
        for (int c = 0; c < t.getChildCount(); c++) {
            View v = t.getChildAt(c);
            if (v == tickFrost[i]) tickFrost[i].setShiftY(dy); else v.setTranslationY(dy);
        }
    }

    private static void fadeVisible(View v, boolean on, float alpha) {
        if (v == null) return;
        v.animate().cancel();
        if (on) {
            if (v.getVisibility() != View.VISIBLE) { v.setAlpha(0f); v.setVisibility(View.VISIBLE); }
            v.animate().alpha(alpha).setDuration(300).setInterpolator(EASE).start();
        } else if (v.getVisibility() == View.VISIBLE) {
            v.animate().alpha(0f).setDuration(260).setInterpolator(EASE).withEndAction(() -> v.setVisibility(View.GONE)).start();
        }
    }

    // ---------- tất cả ứng dụng ----------

    private void openAllApps() {
        if (allApps != null) return;
        allApps = new FrameLayout(this);
        allApps.setBackgroundColor(0xF0050507);
        allApps.setAlpha(0f);
        ScrollView sv = new ScrollView(this);
        sv.setClipChildren(false);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(px(110), px(70), px(110), px(70));
        col.setClipChildren(false);
        TextView h = text(600, 40, 0xFFFFFFFF);
        h.setText("Ứng dụng");
        col.addView(h);
        GridLayout grid = new GridLayout(this);
        grid.setColumnCount(5);
        grid.setClipChildren(false);
        LinearLayout.LayoutParams lgp = new LinearLayout.LayoutParams(-2, -2);
        lgp.topMargin = px(40);
        col.addView(grid, lgp);

        PackageManager pm = getPackageManager();
        List<ResolveInfo> ris = pm.queryIntentActivities(
                new Intent(Intent.ACTION_MAIN).addCategory("android.intent.category.LEANBACK_LAUNCHER"), 0);
        Collections.sort(ris, (x, y) -> String.valueOf(x.loadLabel(pm)).compareToIgnoreCase(String.valueOf(y.loadLabel(pm))));
        View first = null;
        for (ResolveInfo ri : ris) {
            String pkg = ri.activityInfo.packageName;
            if (pkg.equals(getPackageName()) || pkg.startsWith("vn.glass.")) continue;
            FrameLayout tile = appTile(pm, ri);
            GridLayout.LayoutParams gl = new GridLayout.LayoutParams();
            gl.width = px(300); gl.height = px(220);
            gl.setMargins(px(10), px(10), px(26), px(26));
            grid.addView(tile, gl);
            if (first == null) first = tile;
        }
        sv.addView(col);
        allApps.addView(sv, new FrameLayout.LayoutParams(-1, -1));
        root.addView(allApps, new FrameLayout.LayoutParams(-1, -1));
        // khoá dock + hàng nội dung phía sau để con trỏ không lọt ra ngoài bảng; tắt quầng "thở"
        tray.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        shelfRow.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        gear.setFocusable(false);
        dimGlow(true);
        allApps.animate().alpha(1f).setDuration(220).start();
        if (first != null) first.requestFocus();
    }

    private FrameLayout appTile(PackageManager pm, ResolveInfo ri) {
        FrameLayout t = new FrameLayout(this);
        t.setFocusable(true);
        t.setClipChildren(false);
        ImageView img = new ImageView(this);
        GradientDrawable ph = new GradientDrawable();
        ph.setColor(0x33FFFFFF);
        ph.setCornerRadius(px(14));
        img.setBackground(ph);
        shadowShape(img, px(14));
        Drawable d = ri.activityInfo.loadBanner(pm);
        if (d == null) d = ri.activityInfo.applicationInfo.loadBanner(pm);
        if (d == null) {
            d = ri.loadIcon(pm);
            img.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
            img.setPadding(px(40), px(30), px(40), px(30));
            img.setImageDrawable(d);
        } else {
            // banner vẽ thành ảnh rồi bo góc bằng bút khử răng cưa
            img.setScaleType(ImageView.ScaleType.FIT_XY);
            img.setImageDrawable(new RoundImage(toBitmap(d, px(300), px(169)), px(14)));
        }
        t.addView(img, new FrameLayout.LayoutParams(-1, px(169)));
        TextView name = text(500, 22, 0xD9FFFFFF);
        name.setText(ri.loadLabel(pm));
        name.setSingleLine(true);
        FrameLayout.LayoutParams ln = new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM);
        t.addView(name, ln);
        String pkg = ri.activityInfo.packageName;
        t.setOnFocusChangeListener((v, has) -> {
            float s = has ? 1.08f : 1f;
            img.animate().scaleX(s).scaleY(s).translationZ(has ? px(18) : 0).setDuration(180).start();
        });
        t.setOnClickListener(v -> { launchPkg(pkg, v); closeAllApps(); });
        return t;
    }

    private void closeAllApps() {
        if (allApps == null) return;
        View a = allApps;
        allApps = null;
        a.animate().alpha(0f).setDuration(160).withEndAction(() -> root.removeView(a)).start();
        tray.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
        shelfRow.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
        gear.setFocusable(true);
        cards.get(focused).requestFocus();   // focus về ô -> quầng sáng hiện lại và "thở"
    }

    // ---------- phím ----------

    // ---------- màn hình chờ: đứng yên ở màn hình chính 5 phút ----------
    // Nền là ảnh đang hiện, tối thêm, trôi rất chậm; giờ + ngày dương + âm lịch nằm giữa, trong 1/3 phía trên.
    // Chữ rõ mà không gắt: trắng ngà, bóng mỏng nhoè rộng, quầng tối mềm phía sau đậm theo độ sáng vùng ảnh.
    // Cụm chữ dịch nhẹ vài phút một lần để tấm nền không lưu ảnh. Bấm nút bất kỳ: mờ đi, nút đó không bị tính.

    private static final String[] THU = {"Chủ Nhật", "Thứ Hai", "Thứ Ba", "Thứ Tư", "Thứ Năm", "Thứ Sáu", "Thứ Bảy"};
    private FrameLayout saver;
    private View saverHalo;
    private LinearLayout saverText;
    private TextView saverTime, saverSolar, saverLunar, saverTet;
    private ObjectAnimator saverDrift;
    private ImageView saverBg;
    private long saverImageMs() { return Wallpapers.everyMin(this, Wallpapers.LOCK) * 60_000L; }   // mặc định 15 phút
    // Màn hình chờ của Google TV (Ambient/Backdrop) tự bật sau 10 phút không dùng và đè lên màn hình chờ này.
    // Khi màn hình chờ đang hiện thì giữ màn hình sáng để hệ thống không chen vào, nhưng chỉ tối đa 2 tiếng:
    // quá giờ thì trả lại cho hệ thống để box tự chờ rồi tắt như cài đặt sẵn, không bắt TV sáng mãi.
    private static final long SAVER_HOLD_MS = 2 * 60 * 60_000L;
    private final Runnable releaseScreen = () ->
            getWindow().clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

    /** Ảnh kế tiếp trong bộ ảnh màn hình chờ (xoay vòng theo tên); fade = hoà dần từ ảnh cũ. */
    private void nextSaverImage(boolean fade) {
        List<String> fs = Wallpapers.active(this, Wallpapers.LOCK);
        if (fs.isEmpty()) return;                           // không có ảnh nào: giữ ảnh nền đang hiện
        int i = (prefs().getInt("saver_img", -1) + 1) % fs.size();
        prefs().edit().putInt("saver_img", i).apply();
        ImageView target = saverBg;
        images.load(fs.get(i), bgWidth(), bmp -> {
            if (!saverOn || saverBg != target || bmp == null) return;
            if (!fade) { target.setImageBitmap(bmp); tuneHalo(target.getDrawable()); return; }
            target.animate().alpha(0f).setDuration(900).withEndAction(() -> {
                target.setImageBitmap(bmp);
                tuneHalo(target.getDrawable());
                target.animate().alpha(1f).setDuration(1200).start();
            }).start();
        });
    }

    private final Runnable saverImageTick = new Runnable() {
        @Override public void run() {
            if (!saverOn) return;
            nextSaverImage(true);
            ui.postDelayed(this, saverImageMs());
        }
    };

    /** Quầng sau cụm giờ đậm theo độ sáng vùng ảnh ở góc dưới trái: ảnh tối gần như không thấy, ảnh sáng đậm tới ~45%. */
    private void tuneHalo(Drawable d) {
        if (saverHalo == null) return;
        float a = 0.33f;
        if (d instanceof android.graphics.drawable.BitmapDrawable) {
            float lum = regionGlare(((android.graphics.drawable.BitmapDrawable) d).getBitmap(), 0.02f, 0.62f, 0.4f, 0.95f);
            a = 0.33f + 0.67f * Math.max(0f, Math.min(1f, (lum - 0.2f) / 0.45f));
        }
        saverHalo.animate().alpha(a).setDuration(600).start();
        if (d instanceof android.graphics.drawable.BitmapDrawable) glassFrom(((android.graphics.drawable.BitmapDrawable) d).getBitmap());
    }

    // ---------- chữ kính mờ của màn hình chờ ----------
    // Lòng chữ là chính ảnh nền làm nhoè, sáng lên và pha trắng sữa: thấy màu ảnh xuyên qua, hơi trong. Kính phẳng:
    // không viền sáng, không bóng lệch; chỉ quầng tối rất mềm đều quanh chữ. Ảnh nhoè chỉ dựng lại khi đổi ảnh,
    // vị trí chỉ tính lại khi cụm chữ dời chỗ (3 phút một lần), không vẽ lại từng khung hình.

    private Bitmap glassSrc;

    /** Ảnh nhoè đã pha màu kính: tối như nền màn hình chờ, sáng lên ~1,9 lần rồi phủ ~33% trắng ngà. */
    private void glassFrom(Bitmap bg) {
        if (bg == null) return;
        Bitmap b = FrostView.blur(bg);
        Bitmap t = Bitmap.createBitmap(b.getWidth(), b.getHeight(), Bitmap.Config.ARGB_8888);
        float k = 0.456f;   // 0,55 (lớp tối) x 0,65 (đáy tối dần) x 1,9 (làm sáng) x 0,67 (phần còn lại sau lớp trắng)
        android.graphics.ColorMatrix cm = new android.graphics.ColorMatrix(new float[]{
                k, 0, 0, 0, 107,
                0, k, 0, 0, 105,
                0, 0, k, 0, 103,
                0, 0, 0, 1, 0});
        Paint p = new Paint(Paint.FILTER_BITMAP_FLAG);
        p.setColorFilter(new android.graphics.ColorMatrixColorFilter(cm));
        new Canvas(t).drawBitmap(b, 0, 0, p);
        glassSrc = t;
        if (saverText != null) saverText.post(this::placeGlass);
    }

    /** Gắn ảnh kính vào nét chữ, khớp đúng chỗ ảnh nền phía sau (ảnh nền phóng 108%, cắt giữa như ImageView). */
    private void placeGlass() {
        if (!saverOn || glassSrc == null || saverBg == null) return;
        int sw = getResources().getDisplayMetrics().widthPixels, sh = getResources().getDisplayMetrics().heightPixels;
        float k = Math.max(sw / (float) glassSrc.getWidth(), sh / (float) glassSrc.getHeight()) * saverBg.getScaleX();
        float ox = (sw - glassSrc.getWidth() * k) / 2f + saverBg.getTranslationX(), oy = (sh - glassSrc.getHeight() * k) / 2f;
        int[] at = new int[2];
        for (TextView tv : new TextView[]{saverTime, saverSolar, saverLunar}) {
            if (tv == null) continue;
            tv.getLocationOnScreen(at);
            android.graphics.Matrix m = new android.graphics.Matrix();
            m.setScale(k, k);
            m.postTranslate(ox - at[0] - tv.getTotalPaddingLeft(), oy - at[1] - tv.getExtendedPaddingTop());
            android.graphics.BitmapShader shader = new android.graphics.BitmapShader(glassSrc,
                    android.graphics.Shader.TileMode.CLAMP, android.graphics.Shader.TileMode.CLAMP);
            shader.setLocalMatrix(m);
            tv.getPaint().setFilterBitmap(true);
            tv.getPaint().setShader(shader);
            tv.invalidate();
        }
    }
    private boolean saverOn, swallowUp;

    private final Runnable showSaverLater = this::showSaver;
    private final Runnable saverMove = new Runnable() {
        @Override public void run() {
            if (!saverOn) return;
            // dịch cụm chữ tới một chỗ ngẫu nhiên gần đó (vẫn ở góc dưới trái), thật chậm
            java.util.Random r = new java.util.Random();
            float dx = px(-40 + r.nextInt(81)), dy = px(-40 + r.nextInt(41));
            saverText.animate().translationX(dx).translationY(dy).setDuration(4000).setInterpolator(EASE)
                    .withEndAction(MainActivity.this::placeGlass).start();   // dời xong: kính khớp lại với nền
            saverHalo.animate().translationX(dx).translationY(dy).setDuration(4000).setInterpolator(EASE).start();
            // thông báo dịch cùng độ cao với cụm bên trái: chân chữ luôn thẳng hàng với dòng âm lịch
            saverTet.animate().translationX(-Math.abs(dx) / 3).translationY(noticeShift + dy).setDuration(4000).setInterpolator(EASE).start();
            ui.postDelayed(this, 3 * 60_000L);
        }
    };

    /** Đếm lại thời gian chờ (mặc định 5 phút) từ lần bấm cuối; Cài đặt chọn "Không bật" thì thôi. */
    private void armSaver() {
        ui.removeCallbacks(showSaverLater);
        int m = Wallpapers.saverAfterMin(this);
        if (m > 0) ui.postDelayed(showSaverLater, m * 60_000L);
    }

    private void showSaver() {
        if (saverOn || isFinishing()) return;
        saverOn = true;
        if (breathe != null) { breathe.cancel(); breathe = null; }   // dock bị che: khỏi vẽ nhịp thở
        ui.removeCallbacks(btcTick);                                  // giá cũng bị che: khỏi gọi mạng
        int sw = getResources().getDisplayMetrics().widthPixels, sh = getResources().getDisplayMetrics().heightPixels;
        saver = new FrameLayout(this);
        saver.setBackgroundColor(Color.BLACK);
        // nền: bộ ảnh riêng của màn hình chờ (bg/khoa, đồng bộ từ D:\lock screen), mỗi lần hiện lấy ảnh kế tiếp;
        // bộ ảnh trống thì dùng ảnh nền đang hiện. Phóng nhẹ và trôi qua lại rất chậm (90 s một chiều)
        ImageView bgv = fullImage();
        bgv.setImageDrawable(bgFront.getDrawable());
        bgv.setScaleX(1.08f); bgv.setScaleY(1.08f);
        saver.addView(bgv);
        saverBg = bgv;
        nextSaverImage(false);
        saverDrift = ObjectAnimator.ofFloat(bgv, View.TRANSLATION_X, -px(45), px(45));
        saverDrift.setDuration(90_000);
        saverDrift.setRepeatCount(ValueAnimator.INFINITE);
        saverDrift.setRepeatMode(ValueAnimator.REVERSE);
        saverDrift.setInterpolator(new android.view.animation.AccelerateDecelerateInterpolator());
        saverDrift.start();
        // tối hơn thường lệ ~45% để đỡ hại tấm nền, thêm loang tối hai bên như màn hình chính
        View dim = new View(this);
        dim.setBackgroundColor(0x73000000);
        saver.addView(dim, new FrameLayout.LayoutParams(-1, -1));
        View sides = new View(this);
        sides.setBackground(new android.graphics.drawable.BitmapDrawable(getResources(), Dither.fadeSides(sw, sh, 0x66, 0.25f)));
        saver.addView(sides, new FrameLayout.LayoutParams(-1, -1));
        // đáy tối dần: hai cụm chữ ở hai góc dưới luôn nằm trên nền đủ tối
        View floor = new View(this);
        floor.setBackground(new android.graphics.drawable.BitmapDrawable(getResources(), Dither.fadeUp(sw, px(480), new int[]{0x8C, 0x40, 0x00})));
        saver.addView(floor, new FrameLayout.LayoutParams(-1, px(480), Gravity.BOTTOM));
        // quầng tối mềm sau cụm giờ: dựng đậm tối đa (~45%), độ đậm thật chỉnh bằng alpha theo độ sáng vùng ảnh
        saverHalo = new View(this);
        saverHalo.setBackground(new android.graphics.drawable.BitmapDrawable(getResources(), Dither.halo(px(1200), px(620), 0x73)));
        tuneHalo(saverBg.getDrawable());
        FrameLayout.LayoutParams lh = new FrameLayout.LayoutParams(px(1200), px(620), Gravity.BOTTOM | Gravity.START);
        lh.leftMargin = px(-250); lh.bottomMargin = px(-150);
        saver.addView(saverHalo, lh);
        // cụm chữ: góc dưới trái, căn lề trái, không che nhân vật ở giữa ảnh
        saverText = new LinearLayout(this);
        saverText.setOrientation(LinearLayout.VERTICAL);
        saverText.setGravity(Gravity.START);
        // nét 320 (thanh nhưng không như sợi tóc): giao diện vẽ ở 1080p rồi TV phóng lên 4K, nét quá mảnh sẽ răng cưa
        // nhỏ và dịu: kem ấm (không trắng lạnh) ~60% (giờ) > ~55% (lịch dương) > ~44% (âm lịch);
        // trên nền tối, trắng lạnh và độ sáng cao trông gắt
        // chữ kính: màu lấy từ ảnh nền (placeGlass), ở đây chỉ còn độ trong; giờ nét 460 để lòng kính đủ rộng mà thấy
        saverTime = text(460, 140, 0xD7FFFFFF);
        saverTime.setLetterSpacing(0f);   // không giãn chữ âm: bề rộng đo được khớp nét vẽ, chữ số cuối không bị xén
        saverTime.setFontFeatureSettings("tnum");
        saverTime.setSingleLine(true);
        saverTime.setEllipsize(null);
        saverTime.setPadding(0, px(8), px(24), px(8));
        saverSolar = text(500, 36, 0xD2FFFFFF);
        // âm lịch cùng kiểu chữ với dòng thông báo góc phải (đếm ngược Tết / giá coin) và cùng đường chân chữ
        saverLunar = text(500, 26, 0xB4FFFFFF);
        saverLunar.setLetterSpacing(0.02f);
        saverLunar.setFontFeatureSettings("tnum");
        // ô thông báo góc dưới phải (đếm ngược Tết / giá coin, xoay vòng): chữ nhỏ, màu từng phần do span quyết định
        saverTet = text(500, 26, 0xD1FAF6EE);
        saverTet.setFontFeatureSettings("tnum");
        saverTet.setLetterSpacing(0.02f);
        saverTet.setShadowLayer(px(14), 0, px(2), 0x52000000);   // bóng mỏng nhoè rộng: tách chữ khỏi ảnh, không thành viền
        // chữ kính: quầng tối rất mềm, đều quanh chữ (không lệch xuống, không thành viền nổi khối)
        for (TextView t : new TextView[]{saverTime, saverSolar, saverLunar}) t.setShadowLayer(px(14), 0, 0, 0x2E000000);
        // bề rộng cố định theo chuỗi rộng nhất (+15% dư): khung tự đo của TextView hụt với Inter có độ đậm tuỳ biến
        // ở cỡ lớn, làm chữ số cuối bị xén một nửa
        int timeW = (int) Math.ceil(saverTime.getPaint().measureText("00:00") * 1.15f) + saverTime.getPaddingRight();
        saverText.addView(saverTime, new LinearLayout.LayoutParams(timeW, -2));
        LinearLayout.LayoutParams ls = new LinearLayout.LayoutParams(-2, -2);
        ls.topMargin = px(6);
        saverText.addView(saverSolar, ls);
        LinearLayout.LayoutParams ll = new LinearLayout.LayoutParams(-2, -2);
        ll.topMargin = px(12);
        saverText.addView(saverLunar, ll);
        FrameLayout.LayoutParams lt = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.START);
        lt.leftMargin = px(110); lt.bottomMargin = px(72);
        saver.addView(saverText, lt);
        saverTime.setTranslationX(-px(8));   // mép chữ số to có khoảng trắng riêng: kéo nhẹ cho thẳng lề với hai dòng dưới
        // đếm ngược Tết tách riêng ở góc dưới phải: cụm giữa đỡ nhiều chữ
        FrameLayout.LayoutParams lte = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.END);
        lte.rightMargin = px(96); lte.bottomMargin = px(72);
        saver.addView(saverTet, lte);
        updateSaverText();
        saver.setAlpha(0f);
        root.addView(saver, new FrameLayout.LayoutParams(-1, -1));
        saver.animate().alpha(1f).setDuration(1200).setInterpolator(EASE).start();
        ui.removeCallbacks(saverMove);
        ui.postDelayed(saverMove, 3 * 60_000L);
        ui.removeCallbacks(saverImageTick);
        ui.postDelayed(saverImageTick, saverImageMs());
        noticeIdx = 0;
        noticeShift = 0;
        showNotice(false);
        saver.post(this::alignNotice);
        saver.post(this::placeGlass);   // bố cục xong mới biết chữ nằm đâu để khớp kính với nền
        ui.removeCallbacks(noticeTick);
        ui.postDelayed(noticeTick, NOTICE_MS);
        getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        ui.removeCallbacks(releaseScreen);
        ui.postDelayed(releaseScreen, SAVER_HOLD_MS);
    }

    /** Thoát màn hình chờ: mờ đi rồi gỡ hẳn (nhả bộ nhớ các lớp vẽ sẵn), dock trở lại như cũ. */
    private void hideSaver(boolean animate) {
        if (!saverOn) return;
        saverOn = false;
        ui.removeCallbacks(saverMove);
        ui.removeCallbacks(saverImageTick);
        ui.removeCallbacks(releaseScreen);
        ui.removeCallbacks(noticeTick);
        releaseScreen.run();
        View v = saver;
        saver = null;
        saverBg = null;
        saverHalo = null;
        if (saverDrift != null) { saverDrift.cancel(); saverDrift = null; }
        Runnable gone = () -> root.removeView(v);
        if (animate) v.animate().alpha(0f).setDuration(350).setInterpolator(EASE).withEndAction(gone).start();
        else gone.run();
        if (shown()) {
            if (Wallpapers.widget(this, "btc") || Wallpapers.widget(this, "eth")) { ui.removeCallbacks(btcTick); ui.post(btcTick); }
            if (glowCur >= 0 && !inShelf && allApps == null) startBreathing(glowViews[glowCur]);
        }
    }

    private boolean shown() { return getWindow() != null && hasWindowFocus(); }

    private void updateSaverText() {
        if (!saverOn) return;
        java.util.Calendar c = java.util.Calendar.getInstance();
        int d = c.get(java.util.Calendar.DAY_OF_MONTH), m = c.get(java.util.Calendar.MONTH) + 1, y = c.get(java.util.Calendar.YEAR);
        saverTime.setText(new SimpleDateFormat("HH:mm", Locale.getDefault()).format(c.getTime()));
        saverSolar.setText(THU[c.get(java.util.Calendar.DAY_OF_WEEK) - 1] + ", " + d + " tháng " + m + ", " + y);
        saverLunar.setText(Lunar.describe(d, m, y));
    }

    // ---------- ô thông báo góc dưới phải của màn hình chờ: xoay vòng mỗi phút ----------
    // Đếm ngược Tết (chỉ trong mùa chờ Tết) -> BTCUSDT -> ETHUSDT -> lại từ đầu. Giá coin lấy mới đúng lúc tới lượt.

    private static final long NOTICE_MS = 60_000L;
    private int noticeIdx;
    private float noticeShift;   // độ dời để chân chữ thông báo trùng chân chữ dòng âm lịch (khác cỡ chữ)

    /** Đo sau khi bố cục xong: dời thông báo cho đường chân chữ trùng đúng dòng âm lịch bên trái. */
    private void alignNotice() {
        if (saverLunar == null || saverTet == null) return;
        int[] a = new int[2], b = new int[2];
        saverLunar.getLocationOnScreen(a);
        saverTet.getLocationOnScreen(b);
        float lunarBase = a[1] - saverLunar.getTranslationY() - saverText.getTranslationY() + saverLunar.getBaseline();
        float noticeBase = b[1] - saverTet.getTranslationY() + saverTet.getBaseline();
        noticeShift = Math.round(lunarBase - noticeBase);
        saverTet.setTranslationY(noticeShift + saverText.getTranslationY());
    }
    private final double[] lastChg = new double[2];
    private final Runnable noticeTick = new Runnable() {
        @Override public void run() {
            if (!saverOn) return;
            noticeIdx++;
            showNotice(true);
            ui.postDelayed(this, NOTICE_MS);
        }
    };

    private void showNotice(boolean fade) {
        String tet = Lunar.tetCountdown(java.util.Calendar.getInstance());
        List<Integer> kinds = new ArrayList<>();
        if (!tet.isEmpty()) kinds.add(-1);
        kinds.add(0); kinds.add(1);
        int k = kinds.get(noticeIdx % kinds.size());
        if (k < 0) {
            android.text.SpannableString s = new android.text.SpannableString(tet);
            s.setSpan(new android.text.style.ForegroundColorSpan(0xA6FFE2B0), 0, s.length(), 0);   // vàng ấm dịu
            setNotice(s, fade);
            return;
        }
        // tới lượt coin: lấy giá mới rồi mới hiện (lỗi mạng thì dùng giá cuối đã biết)
        net.execute(() -> {
            Prices.Btc[] cs = Prices.crypto();
            ui.post(() -> {
                if (cs != null) for (int i = 0; i < cs.length; i++) if (cs[i] != null) {
                    lastCoin[i] = cs[i].price; lastChg[i] = cs[i].changePct;
                }
                if (saverOn) setNotice(coinNotice(k), fade);
            });
        });
    }

    /** "BTCUSDT  80.790,10  ▲ 0,65%": tên nhạt, giá trắng ngà, % xanh/đỏ dịu. */
    private CharSequence coinNotice(int i) {
        android.text.SpannableStringBuilder sb = new android.text.SpannableStringBuilder();
        String name = i == 0 ? "BTCUSDT" : "ETHUSDT";
        sb.append(name);
        sb.setSpan(new android.text.style.ForegroundColorSpan(0x8CFAF6EE), 0, sb.length(), 0);
        if (lastCoin[i] < 0) return sb;
        int p0 = sb.length();
        sb.append("   ").append(viFormat("#,##0.00").format(lastCoin[i]));
        sb.setSpan(new android.text.style.ForegroundColorSpan(0xD1FAF6EE), p0, sb.length(), 0);
        double ch = lastChg[i];
        String arrow = ch > 0.005 ? "▲ " : ch < -0.005 ? "▼ " : "";
        int col = ch > 0.005 ? 0xC78FE0AE : ch < -0.005 ? 0xC7FF9F94 : 0x8CFAF6EE;
        int c0 = sb.length();
        sb.append("   ").append(arrow).append(viFormat("0.00").format(Math.abs(ch))).append("%");
        sb.setSpan(new android.text.style.ForegroundColorSpan(col), c0, sb.length(), 0);
        return sb;
    }

    /** Đổi thông báo: mờ đi, thay chữ, hiện lại (chậm, nhẹ). */
    private void setNotice(CharSequence s, boolean fade) {
        if (saverTet == null) return;
        if (!fade) { saverTet.setText(s); saverTet.setAlpha(1f); return; }
        saverTet.animate().cancel();
        saverTet.animate().alpha(0f).setDuration(500).setInterpolator(EASE).withEndAction(() -> {
            saverTet.setText(s);
            saverTet.animate().alpha(1f).setDuration(700).setInterpolator(EASE).start();
        }).start();
    }

    /** Độ chói cảm nhận trung bình (0..1) của một vùng ảnh, toạ độ tính theo tỉ lệ khung hình. */
    private static float regionGlare(Bitmap b, float x0, float y0, float x1, float y1) {
        int w = b.getWidth(), h = b.getHeight(), step = Math.max(1, Math.min(w, h) / 60);
        double sum = 0; int n = 0;
        for (int y = (int) (h * y0); y < (int) (h * y1); y += step) {
            for (int x = (int) (w * x0); x < (int) (w * x1); x += step) { sum += FrostView.glare(b.getPixel(x, y)); n++; }
        }
        return n == 0 ? 0f : (float) (sum / n / 255.0);
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        // màn hình chờ đang hiện: nút bấm đầu tiên chỉ để đánh thức, không tính là thao tác
        if (saverOn) {
            if (e.getAction() == KeyEvent.ACTION_DOWN) { hideSaver(true); swallowUp = true; armSaver(); }
            return true;
        }
        if (swallowUp) {
            if (e.getAction() == KeyEvent.ACTION_UP) swallowUp = false;
            return true;
        }
        armSaver();
        if (homePrompt != null) {
            // hộp nhắc đặt làm màn hình chính: Quay lại = Để sau; phím hướng chuyển giữa hai nút
            int kc = e.getKeyCode();
            if (kc == KeyEvent.KEYCODE_BACK || kc == KeyEvent.KEYCODE_ESCAPE) {
                if (e.getAction() == KeyEvent.ACTION_DOWN) closeHomePrompt(true);
                return true;
            }
            return super.dispatchKeyEvent(e);
        }
        if (settings != null) {
            // bảng Cài đặt: Quay lại đi lùi từng bước; các phím hướng để hệ thống tự chuyển trong bảng
            int kc = e.getKeyCode();
            if (kc == KeyEvent.KEYCODE_BACK || kc == KeyEvent.KEYCODE_ESCAPE) {
                if (e.getAction() == KeyEvent.ACTION_DOWN) settings.back();
                return true;
            }
            if (kc == KeyEvent.KEYCODE_ALL_APPS || kc == KeyEvent.KEYCODE_MENU) return true;
            return super.dispatchKeyEvent(e);
        }
        if (e.getAction() == KeyEvent.ACTION_DOWN) {
            int kc = e.getKeyCode();
            if (kc == KeyEvent.KEYCODE_ALL_APPS) {   // nút APPS: sang ảnh nền kế tiếp
                if (allApps != null) closeAllApps();
                nextBackground();
                return true;
            }
            if (kc == KeyEvent.KEYCODE_MENU) {        // bảng Tất cả ứng dụng vẫn mở được bằng phím Menu
                if (allApps == null) openAllApps(); else closeAllApps();
                return true;
            }
            if (kc == KeyEvent.KEYCODE_BACK || kc == KeyEvent.KEYCODE_ESCAPE) {
                if (allApps != null) closeAllApps();
                else if (inShelf) exitShelf();
                return true;   // launcher không thoát khi bấm Back
            }
            if (allApps == null && eplBox.hasFocus()) {
                // đang chọn thẻ bóng đá
                boolean compact = eplView.mode() == EplView.COMPACT;
                if (kc == KeyEvent.KEYCODE_DPAD_UP) {
                    root.playSoundEffect(android.view.SoundEffectConstants.NAVIGATION_UP);
                    if (compact && eplView.selected() == EplView.SEL_TABLE && eplView.hasMatchPart()) eplView.setSelected(EplView.SEL_MATCH);
                    else gear.requestFocus();
                    return true;
                }
                if (kc == KeyEvent.KEYCODE_DPAD_DOWN) {
                    root.playSoundEffect(android.view.SoundEffectConstants.NAVIGATION_DOWN);
                    if (compact && eplView.selected() == EplView.SEL_MATCH && eplView.hasTablePart()) eplView.setSelected(EplView.SEL_TABLE);
                    else cards.get(focused).requestFocus();
                    return true;
                }
                if (kc == KeyEvent.KEYCODE_DPAD_CENTER || kc == KeyEvent.KEYCODE_ENTER) {
                    root.playSoundEffect(android.view.SoundEffectConstants.CLICK);
                    stepEpl(); return true;
                }
                return true;   // trái/phải: đứng yên
            }
            if (allApps == null && gear.hasFocus()) {
                // ở nút Cài đặt: xuống thì về đúng ô app đang chọn, các hướng khác đứng yên
                if (kc == KeyEvent.KEYCODE_DPAD_DOWN) {
                    root.playSoundEffect(android.view.SoundEffectConstants.NAVIGATION_DOWN);
                    if (eplSelectable()) focusEpl(false);
                    else cards.get(focused).requestFocus();
                    return true;
                }
                if (kc == KeyEvent.KEYCODE_DPAD_UP || kc == KeyEvent.KEYCODE_DPAD_LEFT || kc == KeyEvent.KEYCODE_DPAD_RIGHT) return true;
            }
            if (allApps == null && (kc == KeyEvent.KEYCODE_DPAD_LEFT || kc == KeyEvent.KEYCODE_DPAD_RIGHT)) {
                // trái/phải chỉ đi trong cùng một hàng: tới mép thì đứng yên, không nhảy sang dock/hàng nội dung
                // hay nút Cài đặt (Cài đặt chỉ lên được bằng phím lên)
                View cur = getCurrentFocus();
                View next = cur == null ? null
                        : cur.focusSearch(kc == KeyEvent.KEYCODE_DPAD_LEFT ? View.FOCUS_LEFT : View.FOCUS_RIGHT);
                ViewGroup row = inside(cur, shelfRow) ? shelfRow : inside(cur, tray) ? tray : null;
                if (next == null || next == gear || (row != null && !inside(next, row))) return true;
            }
            if (allApps == null) {
                if (kc == KeyEvent.KEYCODE_DPAD_UP && !inShelf) {
                    root.playSoundEffect(android.view.SoundEffectConstants.NAVIGATION_UP);
                    if (eplSelectable() && (eplView.mode() != EplView.COMPACT || eplPart(true) != EplView.SEL_NONE)) focusEpl(true);
                    else gear.requestFocus();
                    return true;
                }
                // hai phím này launcher tự xử lý nên hệ thống không phát tiếng: gọi âm điều hướng hệ thống bằng tay
                if (kc == KeyEvent.KEYCODE_DPAD_DOWN && !inShelf) {
                    if (!rowItems.isEmpty()) root.playSoundEffect(android.view.SoundEffectConstants.NAVIGATION_DOWN);
                    enterShelf(); return true;
                }
                if (kc == KeyEvent.KEYCODE_DPAD_UP && inShelf) {
                    root.playSoundEffect(android.view.SoundEffectConstants.NAVIGATION_UP);
                    exitShelf(); return true;
                }
                if (kc == KeyEvent.KEYCODE_DPAD_DOWN) return true;
                if (kc == KeyEvent.KEYCODE_DPAD_UP) return true;
            }
        }
        return super.dispatchKeyEvent(e);
    }

    // ---------- tiện ích ----------

    private final Runnable clockTick = new Runnable() {
        @Override public void run() {
            clock.setText(new SimpleDateFormat("HH:mm", Locale.getDefault()).format(new Date()));
            updateSaverText();
            ui.postDelayed(this, 15000);
        }
    };

    private ImageView fullImage() {
        ImageView v = new ImageView(this);
        v.setScaleType(ImageView.ScaleType.CENTER_CROP);
        v.setLayoutParams(new FrameLayout.LayoutParams(-1, -1));
        return v;
    }

    /**
     * Chữ mịn kiểu Apple: font Inter đúng kích cỡ quang học (opsz) theo cỡ chữ, khử răng cưa + vị trí
     * dưới điểm ảnh, chữ to khít nhẹ; không bóng chữ (bóng nhoè làm viền chữ lem, "gai").
     */
    private TextView text(int weight, float sizePx, int color) {
        TextView t = new TextView(this);
        t.setTypeface(fontFor(weight, sizePx));
        t.setTextSize(TypedValue.COMPLEX_UNIT_PX, px(sizePx));
        t.setTextColor(color);
        t.setPaintFlags(t.getPaintFlags() | android.graphics.Paint.ANTI_ALIAS_FLAG
                | android.graphics.Paint.SUBPIXEL_TEXT_FLAG);
        t.setLetterSpacing(sizePx >= 28 ? -0.01f : 0f);
        t.setIncludeFontPadding(false);
        return t;
    }

    private final Map<String, Typeface> fonts = new java.util.HashMap<>();

    /** opsz của Inter chạy 14..32: chữ nhỏ nét thoáng dễ đọc, chữ to nét thanh gọn. */
    private Typeface fontFor(int weight, float sizePx) {
        int opsz = Math.max(14, Math.min(32, Math.round(sizePx)));
        String key = weight + "/" + opsz;
        Typeface tf = fonts.get(key);
        if (tf != null) return tf;
        try {
            tf = new Typeface.Builder(getAssets(), "fonts/Inter.ttf")
                    .setFontVariationSettings("'wght' " + weight + ", 'opsz' " + opsz).build();
        } catch (Exception e) {
            tf = Typeface.create("sans-serif", weight >= 600 ? Typeface.BOLD : Typeface.NORMAL);
        }
        fonts.put(key, tf);
        return tf;
    }

    private static Bitmap toBitmap(Drawable d, int w, int h) {
        Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        android.graphics.Canvas c = new android.graphics.Canvas(b);
        d.setBounds(0, 0, w, h);
        d.draw(c);
        return b;
    }

    /** Hình bo góc chỉ dùng cho bóng đổ, không cắt nội dung (cắt theo outline dễ răng cưa). */
    private void shadowShape(View v, float r) {
        v.setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View view, Outline o) {
                o.setRoundRect(0, 0, view.getWidth(), view.getHeight(), r);
            }
        });
        v.setClipToOutline(false);
    }

    private Bitmap asset(String path) {
        try (InputStream in = getAssets().open(path)) {
            return BitmapFactory.decodeStream(in);
        } catch (Exception e) {
            Bitmap b = Bitmap.createBitmap(480, 300, Bitmap.Config.ARGB_8888);
            b.eraseColor(0xFF2C2C30);
            return b;
        }
    }

    /** Thu nhỏ từng nửa (lọc song tuyến ở tỉ lệ 2:1 = trung bình 4 điểm, không răng cưa) rồi về đúng cỡ. */
    private static Bitmap scaleHQ(Bitmap b, int w, int h) {
        while (b.getWidth() / 2 >= w && b.getHeight() / 2 >= h) {
            b = Bitmap.createScaledBitmap(b, b.getWidth() / 2, b.getHeight() / 2, true);
        }
        if (b.getWidth() != w || b.getHeight() != h) b = Bitmap.createScaledBitmap(b, w, h, true);
        b.setHasMipMap(true);
        return b;
    }

    private SharedPreferences prefs() { return getSharedPreferences("glass", MODE_PRIVATE); }

    private void hideSystemBars() {
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
    }
}
